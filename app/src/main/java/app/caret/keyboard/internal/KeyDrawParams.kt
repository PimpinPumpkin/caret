/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.graphics.Typeface

import app.caret.latin.utils.ResourceUtils

class KeyDrawParams {
    @JvmField
    var typeface: Typeface = Typeface.DEFAULT

    @JvmField var letterSize = 0
    @JvmField var labelSize = 0
    @JvmField var largeLetterSize = 0
    @JvmField var hintLetterSize = 0
    @JvmField var shiftedLetterHintSize = 0
    @JvmField var hintLabelSize = 0
    @JvmField var previewTextSize = 0

    @JvmField var textColor = 0
    @JvmField var textInactivatedColor = 0
    @JvmField var textShadowColor = 0
    @JvmField var functionalTextColor = 0
    @JvmField var hintLetterColor = 0
    @JvmField var hintLabelColor = 0
    @JvmField var shiftedLetterHintInactivatedColor = 0
    @JvmField var shiftedLetterHintActivatedColor = 0
    @JvmField var previewTextColor = 0

    @JvmField var hintLabelVerticalAdjustment = 0f
    @JvmField var labelOffCenterRatio = 0f
    @JvmField var hintLabelOffCenterRatio = 0f

    @JvmField var animAlpha = 0

    constructor()

    private constructor(copyFrom: KeyDrawParams) {
        typeface = copyFrom.typeface

        letterSize = copyFrom.letterSize
        labelSize = copyFrom.labelSize
        largeLetterSize = copyFrom.largeLetterSize
        hintLetterSize = copyFrom.hintLetterSize
        shiftedLetterHintSize = copyFrom.shiftedLetterHintSize
        hintLabelSize = copyFrom.hintLabelSize
        previewTextSize = copyFrom.previewTextSize

        textColor = copyFrom.textColor
        textInactivatedColor = copyFrom.textInactivatedColor
        textShadowColor = copyFrom.textShadowColor
        functionalTextColor = copyFrom.functionalTextColor
        hintLetterColor = copyFrom.hintLetterColor
        hintLabelColor = copyFrom.hintLabelColor
        shiftedLetterHintInactivatedColor = copyFrom.shiftedLetterHintInactivatedColor
        shiftedLetterHintActivatedColor = copyFrom.shiftedLetterHintActivatedColor
        previewTextColor = copyFrom.previewTextColor

        hintLabelVerticalAdjustment = copyFrom.hintLabelVerticalAdjustment
        labelOffCenterRatio = copyFrom.labelOffCenterRatio
        hintLabelOffCenterRatio = copyFrom.hintLabelOffCenterRatio

        animAlpha = copyFrom.animAlpha
    }

    fun updateParams(keySize: Int, attr: KeyVisualAttributes?) {
        if (attr == null) {
            return
        }

        val attrTypeface = attr.typeface
        if (attrTypeface != null) {
            typeface = attrTypeface
        }

        letterSize = selectTextSizeFromDimensionOrRatio(keySize,
                attr.letterSize, attr.letterRatio, letterSize)
        labelSize = selectTextSizeFromDimensionOrRatio(keySize,
                attr.labelSize, attr.labelRatio, labelSize)
        largeLetterSize = selectTextSize(keySize, attr.largeLetterRatio, largeLetterSize)
        hintLetterSize = selectTextSize(keySize, attr.hintLetterRatio, hintLetterSize)
        shiftedLetterHintSize = selectTextSize(keySize,
                attr.shiftedLetterHintRatio, shiftedLetterHintSize)
        hintLabelSize = selectTextSize(keySize, attr.hintLabelRatio, hintLabelSize)
        previewTextSize = selectTextSize(keySize, attr.previewTextRatio, previewTextSize)

        textColor = selectColor(attr.textColor, textColor)
        textInactivatedColor = selectColor(attr.textInactivatedColor, textInactivatedColor)
        textShadowColor = selectColor(attr.textShadowColor, textShadowColor)
        functionalTextColor = selectColor(attr.functionalTextColor, functionalTextColor)
        hintLetterColor = selectColor(attr.hintLetterColor, hintLetterColor)
        hintLabelColor = selectColor(attr.hintLabelColor, hintLabelColor)
        shiftedLetterHintInactivatedColor = selectColor(
                attr.shiftedLetterHintInactivatedColor, shiftedLetterHintInactivatedColor)
        shiftedLetterHintActivatedColor = selectColor(
                attr.shiftedLetterHintActivatedColor, shiftedLetterHintActivatedColor)
        previewTextColor = selectColor(attr.previewTextColor, previewTextColor)

        hintLabelVerticalAdjustment = selectFloatIfNonZero(
                attr.hintLabelVerticalAdjustment, hintLabelVerticalAdjustment)
        labelOffCenterRatio = selectFloatIfNonZero(
                attr.labelOffCenterRatio, labelOffCenterRatio)
        hintLabelOffCenterRatio = selectFloatIfNonZero(
                attr.hintLabelOffCenterRatio, hintLabelOffCenterRatio)
    }

    fun mayCloneAndUpdateParams(keySize: Int, attr: KeyVisualAttributes?): KeyDrawParams {
        if (attr == null) {
            return this
        }
        val newParams = KeyDrawParams(this)
        newParams.updateParams(keySize, attr)
        return newParams
    }

    companion object {
        private fun selectTextSizeFromDimensionOrRatio(keySize: Int, dimens: Int, ratio: Float, defaultDimens: Int): Int {
            if (ResourceUtils.isValidDimensionPixelSize(dimens)) {
                return dimens
            }
            if (ResourceUtils.isValidFraction(ratio)) {
                return (keySize * ratio).toInt()
            }
            return defaultDimens
        }

        private fun selectTextSize(keySize: Int, ratio: Float, defaultSize: Int): Int {
            if (ResourceUtils.isValidFraction(ratio)) {
                return (keySize * ratio).toInt()
            }
            return defaultSize
        }

        private fun selectColor(attrColor: Int, defaultColor: Int): Int {
            if (attrColor != 0) {
                return attrColor
            }
            return defaultColor
        }

        private fun selectFloatIfNonZero(attrFloat: Float, defaultFloat: Float): Float {
            if (attrFloat != 0f) {
                return attrFloat
            }
            return defaultFloat
        }
    }
}
