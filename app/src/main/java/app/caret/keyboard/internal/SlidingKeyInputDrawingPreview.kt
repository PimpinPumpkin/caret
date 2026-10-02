/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import app.caret.keyboard.PointerTracker
import app.caret.latin.R
import app.caret.latin.common.ColorType
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.settings.Settings

/** Draw rubber band preview graphics during sliding key input. */
class SlidingKeyInputDrawingPreview(mainKeyboardViewAttr: TypedArray) : AbstractDrawingPreview() {
    private val previewBodyRadius: Float

    private var showsSlidingKeyInputPreview = false
    private val previewFrom = CoordinateUtils.newInstance()
    private val previewTo = CoordinateUtils.newInstance()

    // TODO: Finalize the rubber band preview implementation.
    private val roundedLine = RoundedLine()
    private val paint = Paint()

    init {
        val previewColor = Settings.getValues().mColors.get(ColorType.GESTURE_TRAIL)
        val previewRadius = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_slidingKeyInputPreviewWidth, 0f) / 2.0f
        val PERCENTAGE_INT = 100
        val previewBodyRatio = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_slidingKeyInputPreviewBodyRatio, PERCENTAGE_INT).toFloat() /
                PERCENTAGE_INT.toFloat()
        previewBodyRadius = previewRadius * previewBodyRatio
        val previewShadowRatioInt = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_slidingKeyInputPreviewShadowRatio, 0)
        if (previewShadowRatioInt > 0) {
            val previewShadowRatio = previewShadowRatioInt.toFloat() / PERCENTAGE_INT.toFloat()
            val shadowRadius = previewRadius * previewShadowRatio
            paint.setShadowLayer(shadowRadius, 0.0f, 0.0f, previewColor)
        }
        paint.setColor(previewColor)
    }

    override fun onDeallocateMemory() {
        // Nothing to do here.
    }

    fun dismissSlidingKeyInputPreview() {
        showsSlidingKeyInputPreview = false
        invalidateDrawingView()
    }

    /**
     * Draws the preview
     * @param canvas The canvas where the preview is drawn.
     */
    override fun drawPreview(canvas: Canvas) {
        if (!isPreviewEnabled() || !showsSlidingKeyInputPreview) {
            return
        }

        // TODO: Finalize the rubber band preview implementation.
        val radius = previewBodyRadius
        val path = roundedLine.makePath(
                CoordinateUtils.x(previewFrom).toFloat(), CoordinateUtils.y(previewFrom).toFloat(), radius,
                CoordinateUtils.x(previewTo).toFloat(), CoordinateUtils.y(previewTo).toFloat(), radius)
        canvas.drawPath(path, paint)
    }

    /**
     * Set the position of the preview.
     * @param tracker The new location of the preview is based on the points in PointerTracker.
     */
    override fun setPreviewPosition(tracker: PointerTracker) {
        tracker.getDownCoordinates(previewFrom)
        tracker.getLastCoordinates(previewTo)
        showsSlidingKeyInputPreview = true
        invalidateDrawingView()
    }
}
