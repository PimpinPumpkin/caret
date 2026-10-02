/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.Context
import android.graphics.Rect
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

import app.caret.keyboard.Key
import app.caret.keyboard.KeyboardTypeface
import app.caret.latin.R
import app.caret.latin.common.isEmoji
import app.caret.latin.settings.Settings

import java.util.HashSet

/** The pop up key preview view. */
// Android Studio complains about TextView, but we're not using tint or auto-size that should be the relevant differences
class KeyPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int = 0) :
        TextView(context, attrs, defStyleAttr) {

    private val backgroundPadding = Rect()

    init {
        setGravity(Gravity.CENTER)
    }

    fun setPreviewVisual(key: Key, iconsSet: KeyboardIconsSet, drawParams: KeyDrawParams) {
        // What we show as preview should match what we show on a key top in onDraw().
        if (key.iconName != null) {
            setCompoundDrawables(key.getPreviewIcon(iconsSet), null, null, null)
            setText(null as CharSequence?)
            return
        }

        setCompoundDrawables(null, null, null, null)
        setTextColor(drawParams.previewTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, key.selectPreviewTextSize(drawParams)
                * Settings.values.fontSizeMultiplier)
        KeyboardTypeface.applyToTextView(this, key.previewLabel, key.selectPreviewTypeface(drawParams))
        // TODO Should take care of temporaryShiftLabel here.
        setTextAndScaleX(key.previewLabel)
    }

    private fun setTextAndScaleX(text: String?) {
        setTextScaleX(1.0f)
        setText(text)
        if (noScaleXTextSet.contains(text)) {
            return
        }
        if (isEmoji(text!!)) {
            noScaleXTextSet.add(text)
            return
        }
        // TODO: Override {@link #setBackground(Drawable)} that is supported from API 16 and
        // calculate maximum text width.
        val background = getBackground()
        if (background == null) {
            return
        }
        background.getPadding(backgroundPadding)
        val maxWidth = background.intrinsicWidth - backgroundPadding.left -
                backgroundPadding.right
        val width = getTextWidth(text, getPaint())
        if (width <= maxWidth) {
            noScaleXTextSet.add(text)
            return
        }
        setTextScaleX(maxWidth / width)
    }

    fun setPreviewBackground(hasPopupKeys: Boolean, position: Int) {
        val background = getBackground()
        if (background == null) {
            return
        }
        val hasPopupKeysState = if (hasPopupKeys) STATE_HAS_POPUPKEYS else STATE_NORMAL
        background.setState(KEY_PREVIEW_BACKGROUND_STATE_TABLE[position][hasPopupKeysState])
    }

    companion object {
        const val POSITION_MIDDLE = 0
        const val POSITION_LEFT = 1
        const val POSITION_RIGHT = 2

        private val noScaleXTextSet = HashSet<String>()

        @JvmStatic
        fun clearTextCache() {
            noScaleXTextSet.clear()
        }

        private fun getTextWidth(text: String?, paint: TextPaint): Float {
            if (text.isNullOrEmpty()) {
                return 0.0f
            }
            val len = text.length
            val widths = FloatArray(len)
            val count = paint.getTextWidths(text, 0, len, widths)
            var width = 0f
            for (i in 0 until count) {
                width += widths[i]
            }
            return width
        }

        // Background state set
        private val KEY_PREVIEW_BACKGROUND_STATE_TABLE = arrayOf(
            arrayOf( // POSITION_MIDDLE
                intArrayOf(),
                intArrayOf(R.attr.state_has_popup_keys)
            ),
            arrayOf( // POSITION_LEFT
                intArrayOf(R.attr.state_left_edge),
                intArrayOf(R.attr.state_left_edge, R.attr.state_has_popup_keys)
            ),
            arrayOf( // POSITION_RIGHT
                intArrayOf(R.attr.state_right_edge),
                intArrayOf(R.attr.state_right_edge, R.attr.state_has_popup_keys)
            )
        )
        private const val STATE_NORMAL = 0
        private const val STATE_HAS_POPUPKEYS = 1
    }
}
