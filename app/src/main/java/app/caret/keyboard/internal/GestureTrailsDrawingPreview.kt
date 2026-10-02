/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.util.SparseArray

import app.caret.keyboard.PointerTracker

/**
 * Draw preview graphics of multiple gesture trails during gesture input.
 */
class GestureTrailsDrawingPreview(mainKeyboardViewAttr: TypedArray) : AbstractDrawingPreview(), Runnable {
    private val gestureTrails = SparseArray<GestureTrailDrawingPoints>()
    private val drawingParams: GestureTrailDrawingParams
    private val mGesturePaint: Paint
    private var offscreenWidth = 0
    private var offscreenHeight = 0
    private var offscreenOffsetY = 0
    private var mOffscreenBuffer: Bitmap? = null
    private val mOffscreenCanvas = Canvas()
    private val offscreenSrcRect = Rect()
    private val mDirtyRect = Rect()
    private val gestureTrailBoundsRect = Rect() // per trail

    private val drawingHandler = Handler()

    init {
        drawingParams = GestureTrailDrawingParams(mainKeyboardViewAttr)
        val gesturePaint = Paint()
        gesturePaint.setAntiAlias(true)
        gesturePaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC))
        mGesturePaint = gesturePaint
    }

    override fun setKeyboardViewGeometry(originCoords: IntArray, width: Int,
            height: Int) {
        super.setKeyboardViewGeometry(originCoords, width, height)
        offscreenOffsetY = (height
                * GestureStrokeRecognitionPoints.EXTRA_GESTURE_TRAIL_AREA_ABOVE_KEYBOARD_RATIO).toInt()
        offscreenWidth = width
        offscreenHeight = offscreenOffsetY + height
    }

    override fun onDeallocateMemory() {
        freeOffscreenBuffer()
    }

    private fun freeOffscreenBuffer() {
        mOffscreenCanvas.setBitmap(null)
        mOffscreenCanvas.setMatrix(null)
        val offscreenBuffer = mOffscreenBuffer
        if (offscreenBuffer != null) {
            offscreenBuffer.recycle()
            mOffscreenBuffer = null
        }
    }

    private fun mayAllocateOffscreenBuffer() {
        val offscreenBuffer = mOffscreenBuffer
        if (offscreenBuffer != null && offscreenBuffer.getWidth() == offscreenWidth
                && offscreenBuffer.getHeight() == offscreenHeight) {
            return
        }
        freeOffscreenBuffer()
        mOffscreenBuffer = Bitmap.createBitmap(
                offscreenWidth, offscreenHeight, Bitmap.Config.ARGB_8888)
        mOffscreenCanvas.setBitmap(mOffscreenBuffer)
        mOffscreenCanvas.translate(0f, offscreenOffsetY.toFloat())
    }

    private fun drawGestureTrails(offscreenCanvas: Canvas, paint: Paint,
            dirtyRect: Rect): Boolean {
        // Clear previous dirty rectangle.
        if (!dirtyRect.isEmpty()) {
            paint.setColor(Color.TRANSPARENT)
            paint.setStyle(Paint.Style.FILL)
            offscreenCanvas.drawRect(dirtyRect, paint)
        }
        dirtyRect.setEmpty()
        var needsUpdatingGestureTrail = false
        // Draw gesture trails to offscreen buffer.
        synchronized(gestureTrails) {
            // Trails count == fingers count that have ever been active.
            val trailsCount = gestureTrails.size()
            for (index in 0 until trailsCount) {
                val trail = gestureTrails.valueAt(index)
                needsUpdatingGestureTrail = needsUpdatingGestureTrail or trail.drawGestureTrail(
                        offscreenCanvas, paint, gestureTrailBoundsRect, drawingParams)
                // {@link #gestureTrailBoundsRect} has bounding box of the trail.
                dirtyRect.union(gestureTrailBoundsRect)
            }
        }
        return needsUpdatingGestureTrail
    }

    override fun run() {
        // Update preview.
        invalidateDrawingView()
    }

    /**
     * Draws the preview
     * @param canvas The canvas where the preview is drawn.
     */
    override fun drawPreview(canvas: Canvas) {
        if (!isPreviewEnabled()) {
            return
        }
        mayAllocateOffscreenBuffer()
        // Draw gesture trails to offscreen buffer.
        val needsUpdatingGestureTrail = drawGestureTrails(
                mOffscreenCanvas, mGesturePaint, mDirtyRect)
        if (needsUpdatingGestureTrail) {
            drawingHandler.removeCallbacks(this)
            drawingHandler.postDelayed(this, drawingParams.updateInterval.toLong())
        }
        // Transfer offscreen buffer to screen.
        if (!mDirtyRect.isEmpty()) {
            offscreenSrcRect.set(mDirtyRect)
            offscreenSrcRect.offset(0, offscreenOffsetY)
            canvas.drawBitmap(mOffscreenBuffer!!, offscreenSrcRect, mDirtyRect, null)
            // Note: Defer clearing the dirty rectangle here because we will get cleared
            // rectangle on the canvas.
        }
    }

    /**
     * Set the position of the preview.
     * @param tracker The new location of the preview is based on the points in PointerTracker.
     */
    override fun setPreviewPosition(tracker: PointerTracker) {
        if (!isPreviewEnabled()) {
            return
        }
        val trail: GestureTrailDrawingPoints = synchronized(gestureTrails) {
            var t: GestureTrailDrawingPoints? = gestureTrails.get(tracker.pointerId)
            if (t == null) {
                t = GestureTrailDrawingPoints()
                gestureTrails.put(tracker.pointerId, t)
            }
            t
        }
        trail.addStroke(tracker.gestureStrokeDrawingPoints, tracker.downTime)

        // TODO: Should narrow the invalidate region.
        invalidateDrawingView()
    }
}
