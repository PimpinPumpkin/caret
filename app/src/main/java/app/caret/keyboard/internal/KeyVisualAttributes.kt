/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.TypedArray
import android.graphics.Typeface
import android.util.SparseIntArray

import app.caret.latin.R
import app.caret.latin.common.ColorType
import app.caret.latin.settings.Settings
import app.caret.latin.utils.ResourceUtils

class KeyVisualAttributes private constructor(keyAttr: TypedArray) {
    @JvmField val typeface: Typeface?

    @JvmField val letterRatio: Float
    @JvmField val letterSize: Int
    @JvmField val labelRatio: Float
    @JvmField val labelSize: Int
    @JvmField val largeLetterRatio: Float
    @JvmField val hintLetterRatio: Float
    @JvmField val shiftedLetterHintRatio: Float
    @JvmField val hintLabelRatio: Float
    @JvmField val previewTextRatio: Float

    @JvmField val textColor: Int
    @JvmField val textInactivatedColor: Int
    @JvmField val textShadowColor: Int
    @JvmField val functionalTextColor: Int
    @JvmField val hintLetterColor: Int
    @JvmField val hintLabelColor: Int
    @JvmField val shiftedLetterHintInactivatedColor: Int
    @JvmField val shiftedLetterHintActivatedColor: Int
    @JvmField val previewTextColor: Int

    @JvmField val hintLabelVerticalAdjustment: Float
    @JvmField val labelOffCenterRatio: Float
    @JvmField val hintLabelOffCenterRatio: Float

    init {
        if (keyAttr.hasValue(R.styleable.Keyboard_Key_keyTypeface)) {
            typeface = Typeface.defaultFromStyle(
                    keyAttr.getInt(R.styleable.Keyboard_Key_keyTypeface, Typeface.NORMAL))
        } else {
            typeface = null
        }

        letterRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyLetterSize)
        letterSize = ResourceUtils.getDimensionPixelSize(keyAttr,
                R.styleable.Keyboard_Key_keyLetterSize)
        labelRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyLabelSize)
        labelSize = ResourceUtils.getDimensionPixelSize(keyAttr,
                R.styleable.Keyboard_Key_keyLabelSize)
        largeLetterRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyLargeLetterRatio)
        hintLetterRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyHintLetterRatio)
        shiftedLetterHintRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyShiftedLetterHintRatio)
        hintLabelRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyHintLabelRatio)
        previewTextRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyPreviewTextRatio)

        // todo: check what colors do, and if irrelevant and no plan to use -> remove here and from attr
        val colors = Settings.getValues().mColors
        textColor = colors.get(ColorType.KEY_TEXT)
        // when? -> isShiftedLetterActivated, which is a label flag
        textInactivatedColor = keyAttr.getColor(R.styleable.Keyboard_Key_keyTextInactivatedColor, 0)
        // when? -> mKeyTextShadowRadius > 0, but it's always set to -1 (in theme) -> maybe play with this?
        textShadowColor = keyAttr.getColor(R.styleable.Keyboard_Key_keyTextShadowColor, 0)
        functionalTextColor = colors.get(ColorType.FUNCTIONAL_KEY_TEXT)
        hintLetterColor = colors.get(ColorType.KEY_HINT_TEXT)
        hintLabelColor = colors.get(ColorType.KEY_TEXT)
        // when? -> hasShiftedLetterHint and not isShiftedLetterActivated -> both are label flags
        shiftedLetterHintInactivatedColor = keyAttr.getColor(
                R.styleable.Keyboard_Key_keyShiftedLetterHintInactivatedColor, 0)
        // when? -> hasShiftedLetterHint and isShiftedLetterActivated -> both are label flags
        shiftedLetterHintActivatedColor = keyAttr.getColor(
                R.styleable.Keyboard_Key_keyShiftedLetterHintActivatedColor, 0)
        previewTextColor = colors.get(ColorType.KEY_PREVIEW_TEXT)

        hintLabelVerticalAdjustment = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyHintLabelVerticalAdjustment, 0.0f)
        labelOffCenterRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyLabelOffCenterRatio, 0.0f)
        hintLabelOffCenterRatio = ResourceUtils.getFraction(keyAttr,
                R.styleable.Keyboard_Key_keyHintLabelOffCenterRatio, 0.0f)
    }

    companion object {
        private val VISUAL_ATTRIBUTE_IDS = intArrayOf(
            R.styleable.Keyboard_Key_keyTypeface,
            R.styleable.Keyboard_Key_keyLetterSize,
            R.styleable.Keyboard_Key_keyLabelSize,
            R.styleable.Keyboard_Key_keyLargeLetterRatio,
            R.styleable.Keyboard_Key_keyHintLetterRatio,
            R.styleable.Keyboard_Key_keyShiftedLetterHintRatio,
            R.styleable.Keyboard_Key_keyHintLabelRatio,
            R.styleable.Keyboard_Key_keyPreviewTextRatio,
            R.styleable.Keyboard_Key_keyTextInactivatedColor,
            R.styleable.Keyboard_Key_keyTextShadowColor,
            R.styleable.Keyboard_Key_keyShiftedLetterHintInactivatedColor,
            R.styleable.Keyboard_Key_keyShiftedLetterHintActivatedColor,
            R.styleable.Keyboard_Key_keyHintLabelVerticalAdjustment,
            R.styleable.Keyboard_Key_keyLabelOffCenterRatio,
            R.styleable.Keyboard_Key_keyHintLabelOffCenterRatio
        )
        private val visualAttributeIds = SparseIntArray()
        private const val ATTR_DEFINED = 1
        private const val ATTR_NOT_FOUND = 0
        init {
            for (attrId in VISUAL_ATTRIBUTE_IDS) {
                visualAttributeIds.put(attrId, ATTR_DEFINED)
            }
        }

        @JvmStatic
        fun newInstance(keyAttr: TypedArray): KeyVisualAttributes? {
            val indexCount = keyAttr.indexCount
            for (i in 0 until indexCount) {
                val attrId = keyAttr.getIndex(i)
                if (visualAttributeIds.get(attrId, ATTR_NOT_FOUND) == ATTR_NOT_FOUND) {
                    continue
                }
                return KeyVisualAttributes(keyAttr)
            }
            return null
        }
    }
}
