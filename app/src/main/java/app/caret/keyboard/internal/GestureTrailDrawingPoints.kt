/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock

import app.caret.latin.common.ResizableIntArray

/**
 * This class holds drawing points to represent a gesture trail. The gesture trail may contain
 * multiple non-contiguous gesture strokes and will be animated asynchronously from gesture input.
 * <p>
 * On the other hand, {@link GestureStrokeDrawingPoints} class holds drawing points of each gesture
 * stroke. This class holds drawing points of those gesture strokes to draw as a gesture trail.
 * Drawing points in this class will be asynchronously removed when fading out animation goes.
 */
class GestureTrailDrawingPoints {
    // These three {@link ResizableIntArray}s should be synchronized by {@link #eventTimes}.
    private val xCoordinates = ResizableIntArray(DEFAULT_CAPACITY)
    private val yCoordinates = ResizableIntArray(DEFAULT_CAPACITY)
    private val eventTimes = ResizableIntArray(DEFAULT_CAPACITY)
    private val pointTypes = ResizableIntArray(
            if (DEBUG_SHOW_POINTS) DEFAULT_CAPACITY else 0)
    private var currentStrokeId = -1
    // The wall time of the zero value in {@link #eventTimes}
    private var currentTimeBase: Long = 0
    private var trailStartIndex = 0
    private var lastInterpolatedDrawIndex = 0

    fun addStroke(stroke: GestureStrokeDrawingPoints, downTime: Long) {
        synchronized(eventTimes) {
            addStrokeLocked(stroke, downTime)
        }
    }

    private fun addStrokeLocked(stroke: GestureStrokeDrawingPoints, downTime: Long) {
        val trailSize = eventTimes.getLength()
        stroke.appendPreviewStroke(eventTimes, xCoordinates, yCoordinates, pointTypes)
        if (eventTimes.getLength() == trailSize) {
            return
        }
        val times = eventTimes.getPrimitiveArray()
        val strokeId = stroke.gestureStrokeId
        // Because interpolation algorithm in {@link GestureStrokeDrawingPoints} can't determine
        // the interpolated points in the last segment of gesture stroke, it may need recalculation
        // of interpolation when new segments are added to the stroke.
        // {@link #lastInterpolatedDrawIndex} holds the start index of the last segment. It may
        // be updated by the interpolation
        // {@link GestureStrokeDrawingPoints#interpolatePreviewStroke}
        // or by animation {@link #drawGestureTrail(Canvas,Paint,Rect,GestureTrailDrawingParams)}
        // below.
        val lastInterpolatedIndex = if (strokeId == currentStrokeId)
                lastInterpolatedDrawIndex else trailSize
        lastInterpolatedDrawIndex = stroke.interpolateStrokeAndReturnStartIndexOfLastSegment(
                lastInterpolatedIndex, eventTimes, xCoordinates, yCoordinates, pointTypes)
        if (strokeId != currentStrokeId) {
            val elapsedTime = (downTime - currentTimeBase).toInt()
            for (i in trailStartIndex until trailSize) {
                // Decay the previous strokes' event times.
                times[i] -= elapsedTime
            }
            val xCoords = xCoordinates.getPrimitiveArray()
            xCoords[trailSize] = markAsDownEvent(xCoords[trailSize])
            currentTimeBase = downTime - times[trailSize]
            currentStrokeId = strokeId
        }
    }

    private val roundedLine = RoundedLine()
    private val roundedLineBounds = Rect()

    /**
     * Draw gesture trail
     * @param canvas The canvas to draw the gesture trail
     * @param paint The paint object to be used to draw the gesture trail
     * @param outBoundsRect the bounding box of this gesture trail drawing
     * @param params The drawing parameters of gesture trail
     * @return true if some gesture trails remain to be drawn
     */
    fun drawGestureTrail(canvas: Canvas, paint: Paint,
            outBoundsRect: Rect, params: GestureTrailDrawingParams): Boolean {
        synchronized(eventTimes) {
            return drawGestureTrailLocked(canvas, paint, outBoundsRect, params)
        }
    }

    private fun drawGestureTrailLocked(canvas: Canvas, paint: Paint,
            outBoundsRect: Rect, params: GestureTrailDrawingParams): Boolean {
        // Initialize bounds rectangle.
        outBoundsRect.setEmpty()
        val trailSize = eventTimes.getLength()
        if (trailSize == 0) {
            return false
        }

        val times = eventTimes.getPrimitiveArray()
        val xCoords = xCoordinates.getPrimitiveArray()
        val yCoords = yCoordinates.getPrimitiveArray()
        val types = pointTypes.getPrimitiveArray()
        val sinceDown = (SystemClock.uptimeMillis() - currentTimeBase).toInt()
        var startIndex = trailStartIndex
        while (startIndex < trailSize) {
            val elapsedTime = sinceDown - times[startIndex]
            // Skip too old trail points.
            if (elapsedTime < params.trailLingerDuration) {
                break
            }
            startIndex++
        }
        trailStartIndex = startIndex

        if (startIndex < trailSize) {
            paint.setColor(params.trailColor)
            paint.setStyle(Paint.Style.FILL)
            var p1x = getXCoordValue(xCoords[startIndex])
            var p1y = yCoords[startIndex]
            val lastTime = sinceDown - times[startIndex]
            var r1 = getWidth(lastTime, params) / 2.0f
            for (i in startIndex + 1 until trailSize) {
                val elapsedTime = sinceDown - times[i]
                val p2x = getXCoordValue(xCoords[i])
                val p2y = yCoords[i]
                val r2 = getWidth(elapsedTime, params) / 2.0f
                // Draw trail line only when the current point isn't a down point.
                if (!isDownEventXCoord(xCoords[i])) {
                    val body1 = r1 * params.trailBodyRatio
                    val body2 = r2 * params.trailBodyRatio
                    val path = roundedLine.makePath(p1x.toFloat(), p1y.toFloat(), body1,
                            p2x.toFloat(), p2y.toFloat(), body2)
                    if (!path.isEmpty()) {
                        roundedLine.getBounds(roundedLineBounds)
                        if (params.trailShadowEnabled) {
                            val shadow2 = r2 * params.trailShadowRatio
                            paint.setShadowLayer(shadow2, 0.0f, 0.0f, params.trailColor)
                            val shadowInset = -Math.ceil(shadow2.toDouble()).toInt()
                            roundedLineBounds.inset(shadowInset, shadowInset)
                        }
                        // Take union for the bounds.
                        outBoundsRect.union(roundedLineBounds)
                        val alpha = getAlpha(elapsedTime, params)
                        paint.setAlpha(alpha)
                        canvas.drawPath(path, paint)
                    }
                }
                p1x = p2x
                p1y = p2y
                r1 = r2
            }
            if (DEBUG_SHOW_POINTS) {
                debugDrawPoints(canvas, startIndex, trailSize, paint)
            }
        }

        val newSize = trailSize - startIndex
        if (newSize < startIndex) {
            trailStartIndex = 0
            if (newSize > 0) {
                System.arraycopy(times, startIndex, times, 0, newSize)
                System.arraycopy(xCoords, startIndex, xCoords, 0, newSize)
                System.arraycopy(yCoords, startIndex, yCoords, 0, newSize)
                if (DEBUG_SHOW_POINTS) {
                    System.arraycopy(types, startIndex, types, 0, newSize)
                }
            }
            eventTimes.setLength(newSize)
            xCoordinates.setLength(newSize)
            yCoordinates.setLength(newSize)
            if (DEBUG_SHOW_POINTS) {
                pointTypes.setLength(newSize)
            }
            // The start index of the last segment of the stroke
            // {@link lastInterpolatedDrawIndex} should also be updated because all array
            // elements have just been shifted for compaction or been zeroed.
            lastInterpolatedDrawIndex = Math.max(lastInterpolatedDrawIndex - startIndex, 0)
        }
        return newSize > 0
    }

    private fun debugDrawPoints(canvas: Canvas, startIndex: Int, endIndex: Int,
            paint: Paint) {
        val xCoords = xCoordinates.getPrimitiveArray()
        val yCoords = yCoordinates.getPrimitiveArray()
        val types = pointTypes.getPrimitiveArray()
        // {@link Paint} that is zero width stroke and anti alias off draws exactly 1 pixel.
        paint.setAntiAlias(false)
        paint.setStrokeWidth(0f)
        for (i in startIndex until endIndex) {
            val pointType = types[i]
            if (pointType == POINT_TYPE_INTERPOLATED) {
                paint.setColor(Color.RED)
            } else if (pointType == POINT_TYPE_SAMPLED) {
                paint.setColor(0xFFA000FF.toInt())
            } else {
                paint.setColor(Color.GREEN)
            }
            canvas.drawPoint(getXCoordValue(xCoords[i]).toFloat(), yCoords[i].toFloat(), paint)
        }
        paint.setAntiAlias(true)
    }

    companion object {
        const val DEBUG_SHOW_POINTS = false
        const val POINT_TYPE_SAMPLED = 1
        const val POINT_TYPE_INTERPOLATED = 2

        private const val DEFAULT_CAPACITY = GestureStrokeDrawingPoints.PREVIEW_CAPACITY

        // Use this value as imaginary zero because x-coordinates may be zero.
        private const val DOWN_EVENT_MARKER = -128

        private fun markAsDownEvent(xCoord: Int): Int {
            return DOWN_EVENT_MARKER - xCoord
        }

        private fun isDownEventXCoord(xCoordOrMark: Int): Boolean {
            return xCoordOrMark <= DOWN_EVENT_MARKER
        }

        private fun getXCoordValue(xCoordOrMark: Int): Int {
            return if (isDownEventXCoord(xCoordOrMark))
                    DOWN_EVENT_MARKER - xCoordOrMark else xCoordOrMark
        }

        /**
         * Calculate the alpha of a gesture trail.
         * A gesture trail starts from fully opaque. After mFadeStartDelay has been passed, the alpha
         * of a trail reduces in proportion to the elapsed time. Then after mFadeDuration has been
         * passed, a trail becomes fully transparent.
         *
         * @param elapsedTime the elapsed time since a trail has been made.
         * @param params gesture trail display parameters
         * @return the width of a gesture trail
         */
        private fun getAlpha(elapsedTime: Int, params: GestureTrailDrawingParams): Int {
            val fullAlpha = Color.alpha(params.trailColor)
            if (elapsedTime < params.fadeoutStartDelay) {
                return fullAlpha
            }
            val decreasingAlpha = (fullAlpha
                    * (elapsedTime - params.fadeoutStartDelay)
                    / params.fadeoutDuration)
            return fullAlpha - decreasingAlpha
        }

        /**
         * Calculate the width of a gesture trail.
         * A gesture trail starts from the width of mTrailStartWidth and reduces its width in proportion
         * to the elapsed time. After mTrailEndWidth has been passed, the width becomes mTraiLEndWidth.
         *
         * @param elapsedTime the elapsed time since a trail has been made.
         * @param params gesture trail display parameters
         * @return the width of a gesture trail
         */
        private fun getWidth(elapsedTime: Int, params: GestureTrailDrawingParams): Float {
            val deltaWidth = params.trailStartWidth - params.trailEndWidth
            return params.trailStartWidth - (deltaWidth * elapsedTime) / params.trailLingerDuration
        }
    }
}
