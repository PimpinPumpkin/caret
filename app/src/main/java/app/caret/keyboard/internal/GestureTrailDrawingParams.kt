/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.TypedArray

import app.caret.latin.R
import app.caret.latin.common.ColorType
import app.caret.latin.settings.Settings

/**
 * This class holds parameters to control how a gesture trail is drawn and animated on the screen.
 * <p>
 * On the other hand, {@link GestureStrokeDrawingParams} class controls how each gesture stroke is
 * sampled and interpolated. This class controls how those gesture strokes are displayed as a
 * gesture trail and animated on the screen.
 */
class GestureTrailDrawingParams(mainKeyboardViewAttr: TypedArray) {
    @JvmField val trailColor: Int
    @JvmField val trailStartWidth: Float
    @JvmField val trailEndWidth: Float
    @JvmField val trailBodyRatio: Float
    @JvmField val trailShadowEnabled: Boolean
    @JvmField val trailShadowRatio: Float
    @JvmField val fadeoutStartDelay: Int
    @JvmField val fadeoutDuration: Int
    @JvmField val updateInterval: Int

    @JvmField val trailLingerDuration: Int

    init {
        trailColor = Settings.getValues().mColors.get(ColorType.GESTURE_TRAIL)
        trailStartWidth = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_gestureTrailStartWidth, 0.0f)
        trailEndWidth = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_gestureTrailEndWidth, 0.0f)
        val PERCENTAGE_INT = 100
        trailBodyRatio = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureTrailBodyRatio, PERCENTAGE_INT).toFloat() /
                PERCENTAGE_INT.toFloat()
        val trailShadowRatioInt = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureTrailShadowRatio, 0)
        trailShadowEnabled = (trailShadowRatioInt > 0)
        trailShadowRatio = trailShadowRatioInt.toFloat() / PERCENTAGE_INT.toFloat()
        fadeoutStartDelay = if (GestureTrailDrawingPoints.DEBUG_SHOW_POINTS)
            FADEOUT_START_DELAY_FOR_DEBUG
        else
            mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_gestureTrailFadeoutStartDelay, 0)
        fadeoutDuration = if (GestureTrailDrawingPoints.DEBUG_SHOW_POINTS)
            FADEOUT_DURATION_FOR_DEBUG
        else
            Settings.getValues().gestureTrailFadeoutDuration
        trailLingerDuration = fadeoutStartDelay + fadeoutDuration
        updateInterval = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureTrailUpdateInterval, 0)
    }

    companion object {
        private const val FADEOUT_START_DELAY_FOR_DEBUG = 2000 // millisecond
        private const val FADEOUT_DURATION_FOR_DEBUG = 200 // millisecond
    }
}
