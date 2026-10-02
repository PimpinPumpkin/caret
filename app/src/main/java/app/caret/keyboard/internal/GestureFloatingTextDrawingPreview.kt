/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextUtils

import app.caret.keyboard.KeyboardTypeface
import app.caret.keyboard.PointerTracker
import app.caret.latin.R
import app.caret.latin.SuggestedWords
import app.caret.latin.common.ColorType
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.settings.Settings

/**
 * The class for single gesture preview text. The class for multiple gesture preview text will be
 * derived from it.
 */
class GestureFloatingTextDrawingPreview(mainKeyboardViewAttr: TypedArray) : AbstractDrawingPreview() {
    protected class GesturePreviewTextParams(mainKeyboardViewAttr: TypedArray) {
        @JvmField val mGesturePreviewDynamic: Boolean
        @JvmField val mGesturePreviewTextOffset: Int
        @JvmField val mGesturePreviewTextHeight: Int
        @JvmField val mGesturePreviewHorizontalPadding: Float
        @JvmField val mGesturePreviewVerticalPadding: Float
        @JvmField val mGesturePreviewRoundRadius: Float
        @JvmField val mDisplayWidth: Int

        private val gesturePreviewTextSize: Int
        private val gesturePreviewTextColor: Int
        private val gesturePreviewColor: Int
        private val paint = Paint()

        init {
            val colors = Settings.getValues().mColors
            mGesturePreviewDynamic = Settings.getValues().mGestureFloatingPreviewDynamicEnabled
            gesturePreviewTextSize = mainKeyboardViewAttr.getDimensionPixelSize(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewTextSize, 0)
            gesturePreviewTextColor = colors.get(ColorType.KEY_TEXT)
            mGesturePreviewTextOffset = mainKeyboardViewAttr.getDimensionPixelOffset(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewTextOffset, 0)
            gesturePreviewColor = colors.get(ColorType.GESTURE_PREVIEW)
            mGesturePreviewHorizontalPadding = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewHorizontalPadding, 0.0f)
            mGesturePreviewVerticalPadding = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewVerticalPadding, 0.0f)
            mGesturePreviewRoundRadius = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewRoundRadius, 0.0f)
            mDisplayWidth = mainKeyboardViewAttr.getResources().getDisplayMetrics().widthPixels

            val textPaint = getTextPaint()
            val textRect = Rect()
            textPaint.getTextBounds(TEXT_HEIGHT_REFERENCE_CHAR, 0, 1, textRect)
            mGesturePreviewTextHeight = textRect.height()
        }

        fun getTextPaint(): Paint {
            paint.setAntiAlias(true)
            paint.setTextAlign(Align.CENTER)
            paint.setTextSize(gesturePreviewTextSize.toFloat())
            paint.setTypeface(KeyboardTypeface.customTypeface())
            paint.setColor(gesturePreviewTextColor)
            return paint
        }

        fun getBackgroundPaint(): Paint {
            paint.setColor(gesturePreviewColor)
            return paint
        }

        companion object {
            private val TEXT_HEIGHT_REFERENCE_CHAR = charArrayOf('M')
        }
    }

    private val params: GesturePreviewTextParams
    private val gesturePreviewRectangle = RectF()
    private var previewTextX = 0
    private var previewTextY = 0
    private var mSuggestedWords: SuggestedWords = SuggestedWords.getEmptyInstance()
    private val lastPointerCoords = CoordinateUtils.newInstance()

    init {
        params = GesturePreviewTextParams(mainKeyboardViewAttr)
    }

    override fun onDeallocateMemory() {
        // Nothing to do here.
    }

    fun dismissGestureFloatingPreviewText() {
        setSuggestedWords(SuggestedWords.getEmptyInstance())
    }

    fun setSuggestedWords(suggestedWords: SuggestedWords) {
        if (!isPreviewEnabled()) {
            return
        }
        mSuggestedWords = suggestedWords
        updatePreviewPosition()
    }

    override fun setPreviewPosition(tracker: PointerTracker) {
        if (!isPreviewEnabled()) {
            return
        }
        tracker.getLastCoordinates(lastPointerCoords)
        updatePreviewPosition()
    }

    /**
     * Draws gesture preview text
     * @param canvas The canvas where preview text is drawn.
     */
    override fun drawPreview(canvas: Canvas) {
        if (!isPreviewEnabled() || mSuggestedWords.isEmpty()
                || TextUtils.isEmpty(mSuggestedWords.getWord(0))) {
            return
        }
        val round = params.mGesturePreviewRoundRadius
        canvas.drawRoundRect(
                gesturePreviewRectangle, round, round, params.getBackgroundPaint())
        val text = mSuggestedWords.getWord(0)
        canvas.drawText(text, previewTextX.toFloat(), previewTextY.toFloat(), params.getTextPaint())
    }

    /**
     * Updates gesture preview text position based on mLastPointerCoords.
     */
    protected fun updatePreviewPosition() {
        if (mSuggestedWords.isEmpty() || TextUtils.isEmpty(mSuggestedWords.getWord(0))) {
            invalidateDrawingView()
            return
        }
        val text = mSuggestedWords.getWord(0)

        val textHeight = params.mGesturePreviewTextHeight
        val textWidth = params.getTextPaint().measureText(text)
        val hPad = params.mGesturePreviewHorizontalPadding
        val vPad = params.mGesturePreviewVerticalPadding
        val rectWidth = textWidth + hPad * 2.0f
        val rectHeight = textHeight + vPad * 2.0f

        val rectX = if (params.mGesturePreviewDynamic) Math.min(
                Math.max(CoordinateUtils.x(lastPointerCoords) - rectWidth / 2.0f, 0.0f),
                params.mDisplayWidth - rectWidth)
            else (params.mDisplayWidth - rectWidth) / 2.0f
        val rectY = if (params.mGesturePreviewDynamic) CoordinateUtils.y(lastPointerCoords) -
                params.mGesturePreviewTextOffset - rectHeight
            else -params.mGesturePreviewTextOffset - rectHeight
        gesturePreviewRectangle.set(rectX, rectY, rectX + rectWidth, rectY + rectHeight)

        previewTextX = (rectX + hPad + textWidth / 2.0f).toInt()
        previewTextY = (rectY + vPad).toInt() + textHeight
        // TODO: Should narrow the invalidate region.
        invalidateDrawingView()
    }
}
