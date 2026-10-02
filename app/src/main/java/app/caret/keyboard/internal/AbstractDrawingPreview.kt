/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.graphics.Canvas
import android.view.View

import app.caret.keyboard.MainKeyboardView
import app.caret.keyboard.PointerTracker

/**
 * Abstract base class for previews that are drawn on DrawingPreviewPlacerView, e.g.,
 * GestureFloatingTextDrawingPreview, GestureTrailsDrawingPreview, and
 * SlidingKeyInputDrawingPreview.
 */
abstract class AbstractDrawingPreview {
    private var drawingView: View? = null
    private var previewEnabled = false
    private var hasValidGeometry = false

    fun setDrawingView(drawingView: DrawingPreviewPlacerView) {
        this.drawingView = drawingView
        drawingView.addPreview(this)
    }

    protected fun invalidateDrawingView() {
        drawingView?.invalidate()
    }

    protected val isPreviewEnabled: Boolean
        get() = previewEnabled && hasValidGeometry

    fun setPreviewEnabled(enabled: Boolean) {
        previewEnabled = enabled
    }

    /**
     * Set {@link MainKeyboardView} geometry and position in the window of input method.
     * The class that is overriding this method must call this super implementation.
     *
     * @param originCoords the top-left coordinates of the {@link MainKeyboardView} in
     *        the input method window coordinate-system. This is unused but has a point in an
     *        extended class, such as {@link GestureTrailsDrawingPreview}.
     * @param width the width of {@link MainKeyboardView}.
     * @param height the height of {@link MainKeyboardView}.
     */
    open fun setKeyboardViewGeometry(originCoords: IntArray, width: Int, height: Int) {
        hasValidGeometry = (width > 0 && height > 0)
    }

    abstract fun onDeallocateMemory()

    /**
     * Draws the preview
     * @param canvas The canvas where the preview is drawn.
     */
    abstract fun drawPreview(canvas: Canvas)

    /**
     * Set the position of the preview.
     * @param tracker The new location of the preview is based on the points in PointerTracker.
     */
    abstract fun setPreviewPosition(tracker: PointerTracker)
}
