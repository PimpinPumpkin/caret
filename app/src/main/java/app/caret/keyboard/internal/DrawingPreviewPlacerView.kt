/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.widget.RelativeLayout

import app.caret.latin.common.CoordinateUtils

import java.util.ArrayList

class DrawingPreviewPlacerView(context: Context, attrs: AttributeSet?) : RelativeLayout(context, attrs) {
    private val keyboardViewOrigin = CoordinateUtils.newInstance()

    private val previews = ArrayList<AbstractDrawingPreview>()

    init {
        setWillNotDraw(false)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        val layerPaint = Paint()
        layerPaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_OVER))
        setLayerType(LAYER_TYPE_HARDWARE, layerPaint)
    }

    fun addPreview(preview: AbstractDrawingPreview) {
        if (!previews.contains(preview)) {
            previews.add(preview)
        }
    }

    fun setKeyboardViewGeometry(originCoords: IntArray, width: Int, height: Int) {
        CoordinateUtils.copy(keyboardViewOrigin, originCoords)
        val count = previews.size
        for (i in 0 until count) {
            previews.get(i).setKeyboardViewGeometry(originCoords, width, height)
        }
    }

    fun deallocateMemory() {
        val count = previews.size
        for (i in 0 until count) {
            previews.get(i).onDeallocateMemory()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        deallocateMemory()
    }

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val originX = CoordinateUtils.x(keyboardViewOrigin)
        val originY = CoordinateUtils.y(keyboardViewOrigin)
        canvas.translate(originX.toFloat(), originY.toFloat())
        val count = previews.size
        for (i in 0 until count) {
            previews.get(i).drawPreview(canvas)
        }
        canvas.translate((-originX).toFloat(), (-originY).toFloat())
    }
}
