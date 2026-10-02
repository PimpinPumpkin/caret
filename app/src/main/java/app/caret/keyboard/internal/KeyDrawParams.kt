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
    var mTypeface: Typeface = Typeface.DEFAULT

    @JvmField var mLetterSize = 0
    @JvmField var mLabelSize = 0
    @JvmField var mLargeLetterSize = 0
    @JvmField var mHintLetterSize = 0
    @JvmField var mShiftedLetterHintSize = 0
    @JvmField var mHintLabelSize = 0
    @JvmField var mPreviewTextSize = 0

    @JvmField var mTextColor = 0
    @JvmField var mTextInactivatedColor = 0
    @JvmField var mTextShadowColor = 0
    @JvmField var mFunctionalTextColor = 0
    @JvmField var mHintLetterColor = 0
    @JvmField var mHintLabelColor = 0
    @JvmField var mShiftedLetterHintInactivatedColor = 0
    @JvmField var mShiftedLetterHintActivatedColor = 0
    @JvmField var mPreviewTextColor = 0

    @JvmField var mHintLabelVerticalAdjustment = 0f
    @JvmField var mLabelOffCenterRatio = 0f
    @JvmField var mHintLabelOffCenterRatio = 0f

    @JvmField var mAnimAlpha = 0

    constructor()

    private constructor(copyFrom: KeyDrawParams) {
        mTypeface = copyFrom.mTypeface

        mLetterSize = copyFrom.mLetterSize
        mLabelSize = copyFrom.mLabelSize
        mLargeLetterSize = copyFrom.mLargeLetterSize
        mHintLetterSize = copyFrom.mHintLetterSize
        mShiftedLetterHintSize = copyFrom.mShiftedLetterHintSize
        mHintLabelSize = copyFrom.mHintLabelSize
        mPreviewTextSize = copyFrom.mPreviewTextSize

        mTextColor = copyFrom.mTextColor
        mTextInactivatedColor = copyFrom.mTextInactivatedColor
        mTextShadowColor = copyFrom.mTextShadowColor
        mFunctionalTextColor = copyFrom.mFunctionalTextColor
        mHintLetterColor = copyFrom.mHintLetterColor
        mHintLabelColor = copyFrom.mHintLabelColor
        mShiftedLetterHintInactivatedColor = copyFrom.mShiftedLetterHintInactivatedColor
        mShiftedLetterHintActivatedColor = copyFrom.mShiftedLetterHintActivatedColor
        mPreviewTextColor = copyFrom.mPreviewTextColor

        mHintLabelVerticalAdjustment = copyFrom.mHintLabelVerticalAdjustment
        mLabelOffCenterRatio = copyFrom.mLabelOffCenterRatio
        mHintLabelOffCenterRatio = copyFrom.mHintLabelOffCenterRatio

        mAnimAlpha = copyFrom.mAnimAlpha
    }

    fun updateParams(keySize: Int, attr: KeyVisualAttributes?) {
        if (attr == null) {
            return
        }

        val attrTypeface = attr.mTypeface
        if (attrTypeface != null) {
            mTypeface = attrTypeface
        }

        mLetterSize = selectTextSizeFromDimensionOrRatio(keySize,
                attr.mLetterSize, attr.mLetterRatio, mLetterSize)
        mLabelSize = selectTextSizeFromDimensionOrRatio(keySize,
                attr.mLabelSize, attr.mLabelRatio, mLabelSize)
        mLargeLetterSize = selectTextSize(keySize, attr.mLargeLetterRatio, mLargeLetterSize)
        mHintLetterSize = selectTextSize(keySize, attr.mHintLetterRatio, mHintLetterSize)
        mShiftedLetterHintSize = selectTextSize(keySize,
                attr.mShiftedLetterHintRatio, mShiftedLetterHintSize)
        mHintLabelSize = selectTextSize(keySize, attr.mHintLabelRatio, mHintLabelSize)
        mPreviewTextSize = selectTextSize(keySize, attr.mPreviewTextRatio, mPreviewTextSize)

        mTextColor = selectColor(attr.mTextColor, mTextColor)
        mTextInactivatedColor = selectColor(attr.mTextInactivatedColor, mTextInactivatedColor)
        mTextShadowColor = selectColor(attr.mTextShadowColor, mTextShadowColor)
        mFunctionalTextColor = selectColor(attr.mFunctionalTextColor, mFunctionalTextColor)
        mHintLetterColor = selectColor(attr.mHintLetterColor, mHintLetterColor)
        mHintLabelColor = selectColor(attr.mHintLabelColor, mHintLabelColor)
        mShiftedLetterHintInactivatedColor = selectColor(
                attr.mShiftedLetterHintInactivatedColor, mShiftedLetterHintInactivatedColor)
        mShiftedLetterHintActivatedColor = selectColor(
                attr.mShiftedLetterHintActivatedColor, mShiftedLetterHintActivatedColor)
        mPreviewTextColor = selectColor(attr.mPreviewTextColor, mPreviewTextColor)

        mHintLabelVerticalAdjustment = selectFloatIfNonZero(
                attr.mHintLabelVerticalAdjustment, mHintLabelVerticalAdjustment)
        mLabelOffCenterRatio = selectFloatIfNonZero(
                attr.mLabelOffCenterRatio, mLabelOffCenterRatio)
        mHintLabelOffCenterRatio = selectFloatIfNonZero(
                attr.mHintLabelOffCenterRatio, mHintLabelOffCenterRatio)
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
