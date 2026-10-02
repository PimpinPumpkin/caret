/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.latin.common.ResizableIntArray

/**
 * This class holds drawing points to represent a gesture stroke on the screen.
 */
class GestureStrokeDrawingPoints(private val drawingParams: GestureStrokeDrawingParams) {
    private val previewEventTimes = ResizableIntArray(PREVIEW_CAPACITY)
    private val previewXCoordinates = ResizableIntArray(PREVIEW_CAPACITY)
    private val previewYCoordinates = ResizableIntArray(PREVIEW_CAPACITY)

    private var strokeId = 0
    private var lastPreviewSize = 0
    private val interpolator = HermiteInterpolator()
    private var lastInterpolatedPreviewIndex = 0

    private var lastX = 0
    private var lastY = 0
    private var distanceFromLastSample = 0.0

    private fun reset() {
        strokeId++
        lastPreviewSize = 0
        lastInterpolatedPreviewIndex = 0
        previewEventTimes.setLength(0)
        previewXCoordinates.setLength(0)
        previewYCoordinates.setLength(0)
    }

    val gestureStrokeId: Int
        get() = strokeId

    fun onDownEvent(x: Int, y: Int, elapsedTimeSinceFirstDown: Int) {
        reset()
        onMoveEvent(x, y, elapsedTimeSinceFirstDown)
    }

    private fun needsSampling(x: Int, y: Int): Boolean {
        distanceFromLastSample += Math.hypot((x - lastX).toDouble(), (y - lastY).toDouble())
        lastX = x
        lastY = y
        val isDownEvent = (previewEventTimes.length == 0)
        if (distanceFromLastSample >= drawingParams.minSamplingDistance || isDownEvent) {
            distanceFromLastSample = 0.0
            return true
        }
        return false
    }

    fun onMoveEvent(x: Int, y: Int, elapsedTimeSinceFirstDown: Int) {
        if (needsSampling(x, y)) {
            previewEventTimes.add(elapsedTimeSinceFirstDown)
            previewXCoordinates.add(x)
            previewYCoordinates.add(y)
        }
    }

    /**
     * Append sampled preview points.
     *
     * @param eventTimes the event time array of gesture trail to be drawn.
     * @param xCoords the x-coordinates array of gesture trail to be drawn.
     * @param yCoords the y-coordinates array of gesture trail to be drawn.
     * @param types the point types array of gesture trail. This is valid only when
     * {@link GestureTrailDrawingPoints#DEBUG_SHOW_POINTS} is true.
     */
    fun appendPreviewStroke(eventTimes: ResizableIntArray,
            xCoords: ResizableIntArray, yCoords: ResizableIntArray,
            types: ResizableIntArray) {
        val length = previewEventTimes.length - lastPreviewSize
        if (length <= 0) {
            return
        }
        eventTimes.append(previewEventTimes, lastPreviewSize, length)
        xCoords.append(previewXCoordinates, lastPreviewSize, length)
        yCoords.append(previewYCoordinates, lastPreviewSize, length)
        if (GestureTrailDrawingPoints.DEBUG_SHOW_POINTS) {
            types.fill(GestureTrailDrawingPoints.POINT_TYPE_SAMPLED, types.length, length)
        }
        lastPreviewSize = previewEventTimes.length
    }

    /**
     * Calculate interpolated points between the last interpolated point and the end of the trail.
     * And return the start index of the last interpolated segment of input arrays because it
     * may need to recalculate the interpolated points in the segment if further segments are
     * added to this stroke.
     *
     * @param lastInterpolatedIndex the start index of the last interpolated segment of
     *        <code>eventTimes</code>, <code>xCoords</code>, and <code>yCoords</code>.
     * @param eventTimes the event time array of gesture trail to be drawn.
     * @param xCoords the x-coordinates array of gesture trail to be drawn.
     * @param yCoords the y-coordinates array of gesture trail to be drawn.
     * @param types the point types array of gesture trail. This is valid only when
     * {@link GestureTrailDrawingPoints#DEBUG_SHOW_POINTS} is true.
     * @return the start index of the last interpolated segment of input arrays.
     */
    fun interpolateStrokeAndReturnStartIndexOfLastSegment(lastInterpolatedIndex: Int,
            eventTimes: ResizableIntArray, xCoords: ResizableIntArray,
            yCoords: ResizableIntArray, types: ResizableIntArray): Int {
        val size = previewEventTimes.length
        val pt = previewEventTimes.primitiveArray
        val px = previewXCoordinates.primitiveArray
        val py = previewYCoordinates.primitiveArray
        interpolator.reset(px, py, 0, size)
        // The last segment of gesture stroke needs to be interpolated again because the slope of
        // the tangent at the last point isn't determined.
        var lastInterpolatedDrawIndex = lastInterpolatedIndex
        var d1 = lastInterpolatedIndex
        var p2 = lastInterpolatedPreviewIndex + 1
        while (p2 < size) {
            val p1 = p2 - 1
            val p0 = p1 - 1
            val p3 = p2 + 1
            lastInterpolatedPreviewIndex = p1
            lastInterpolatedDrawIndex = d1
            interpolator.setInterval(p0, p1, p2, p3)
            val m1 = Math.atan2(interpolator.slope1Y.toDouble(), interpolator.slope1X.toDouble())
            val m2 = Math.atan2(interpolator.slope2Y.toDouble(), interpolator.slope2X.toDouble())
            val deltaAngle = Math.abs(angularDiff(m2, m1))
            val segmentsByAngle = Math.ceil(
                    deltaAngle / drawingParams.maxInterpolationAngularThreshold).toInt()
            val deltaDistance = Math.hypot((interpolator.p1X - interpolator.p2X).toDouble(),
                    (interpolator.p1Y - interpolator.p2Y).toDouble())
            val segmentsByDistance = Math.ceil(deltaDistance
                    / drawingParams.maxInterpolationDistanceThreshold).toInt()
            val segments = Math.min(drawingParams.maxInterpolationSegments,
                    Math.max(segmentsByAngle, segmentsByDistance))
            val t1 = eventTimes.get(d1)
            val dt = pt[p2] - pt[p1]
            d1++
            var i = 1
            while (i < segments) {
                val t = i / segments.toFloat()
                interpolator.interpolate(t)
                eventTimes.addAt(d1, (dt * t).toInt() + t1)
                xCoords.addAt(d1, interpolator.interpolatedX.toInt())
                yCoords.addAt(d1, interpolator.interpolatedY.toInt())
                if (GestureTrailDrawingPoints.DEBUG_SHOW_POINTS) {
                    types.addAt(d1, GestureTrailDrawingPoints.POINT_TYPE_INTERPOLATED)
                }
                d1++
                i++
            }
            eventTimes.addAt(d1, pt[p2])
            xCoords.addAt(d1, px[p2])
            yCoords.addAt(d1, py[p2])
            if (GestureTrailDrawingPoints.DEBUG_SHOW_POINTS) {
                types.addAt(d1, GestureTrailDrawingPoints.POINT_TYPE_SAMPLED)
            }
            p2++
        }
        return lastInterpolatedDrawIndex
    }

    companion object {
        const val PREVIEW_CAPACITY = 256

        private const val TWO_PI = Math.PI * 2.0

        /**
         * Calculate the angular of rotation from <code>a0</code> to <code>a1</code>.
         *
         * @param a1 the angular to which the rotation ends.
         * @param a0 the angular from which the rotation starts.
         * @return the angular rotation value from a0 to a1, normalized to [-PI, +PI].
         */
        private fun angularDiff(a1: Double, a0: Double): Double {
            var deltaAngle = a1 - a0
            while (deltaAngle > Math.PI) {
                deltaAngle -= TWO_PI
            }
            while (deltaAngle < -Math.PI) {
                deltaAngle += TWO_PI
            }
            return deltaAngle
        }
    }
}
