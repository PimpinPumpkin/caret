/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.TypedArray

import app.caret.latin.R
import app.caret.latin.utils.ResourceUtils

/**
 * This class holds parameters to control how a gesture stroke is sampled and recognized.
 * This class also has parameters to distinguish gesture input events from fast typing events.
 */
class GestureStrokeRecognitionParams {
    // Static threshold for gesture after fast typing
    @JvmField val staticTimeThresholdAfterFastTyping: Int // msec
    // Static threshold for starting gesture detection
    @JvmField val detectFastMoveSpeedThreshold: Float // keyWidth/sec
    // Dynamic threshold for gesture after fast typing
    @JvmField val dynamicThresholdDecayDuration: Int // msec
    // Time based threshold values
    @JvmField val dynamicTimeThresholdFrom: Int // msec
    @JvmField val dynamicTimeThresholdTo: Int // msec
    // Distance based threshold values
    @JvmField val dynamicDistanceThresholdFrom: Float // keyWidth
    @JvmField val dynamicDistanceThresholdTo: Float // keyWidth
    // Parameters for gesture sampling
    @JvmField val samplingMinimumDistance: Float // keyWidth
    // Parameters for gesture recognition
    @JvmField val recognitionMinimumTime: Int // msec
    @JvmField val recognitionSpeedThreshold: Float // keyWidth/sec

    private constructor() {
        // These parameter values are default and intended for testing.
        staticTimeThresholdAfterFastTyping = 500 // msec
        detectFastMoveSpeedThreshold = 1.5f // keyWidth/sec
        dynamicThresholdDecayDuration = 450 // msec
        dynamicTimeThresholdFrom = 300 // msec
        dynamicTimeThresholdTo = 20 // msec
        dynamicDistanceThresholdFrom = 6.0f // keyWidth
        dynamicDistanceThresholdTo = 0.35f // keyWidth
        // The following parameters' change will affect the result of regression test.
        samplingMinimumDistance = 1.0f / 6.0f // keyWidth
        recognitionMinimumTime = 100 // msec
        recognitionSpeedThreshold = 5.5f // keyWidth/sec
    }

    constructor(mainKeyboardViewAttr: TypedArray) {
        staticTimeThresholdAfterFastTyping = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureStaticTimeThresholdAfterFastTyping,
                DEFAULT.staticTimeThresholdAfterFastTyping)
        detectFastMoveSpeedThreshold = ResourceUtils.getFraction(mainKeyboardViewAttr,
                R.styleable.MainKeyboardView_gestureDetectFastMoveSpeedThreshold,
                DEFAULT.detectFastMoveSpeedThreshold)
        dynamicThresholdDecayDuration = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureDynamicThresholdDecayDuration,
                DEFAULT.dynamicThresholdDecayDuration)
        dynamicTimeThresholdFrom = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureDynamicTimeThresholdFrom,
                DEFAULT.dynamicTimeThresholdFrom)
        dynamicTimeThresholdTo = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureDynamicTimeThresholdTo,
                DEFAULT.dynamicTimeThresholdTo)
        dynamicDistanceThresholdFrom = ResourceUtils.getFraction(mainKeyboardViewAttr,
                R.styleable.MainKeyboardView_gestureDynamicDistanceThresholdFrom,
                DEFAULT.dynamicDistanceThresholdFrom)
        dynamicDistanceThresholdTo = ResourceUtils.getFraction(mainKeyboardViewAttr,
                R.styleable.MainKeyboardView_gestureDynamicDistanceThresholdTo,
                DEFAULT.dynamicDistanceThresholdTo)
        samplingMinimumDistance = ResourceUtils.getFraction(mainKeyboardViewAttr,
                R.styleable.MainKeyboardView_gestureSamplingMinimumDistance,
                DEFAULT.samplingMinimumDistance)
        recognitionMinimumTime = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureRecognitionMinimumTime,
                DEFAULT.recognitionMinimumTime)
        recognitionSpeedThreshold = ResourceUtils.getFraction(mainKeyboardViewAttr,
                R.styleable.MainKeyboardView_gestureRecognitionSpeedThreshold,
                DEFAULT.recognitionSpeedThreshold)
    }

    companion object {
        // Default GestureStrokeRecognitionPoints parameters.
        @JvmField
        val DEFAULT = GestureStrokeRecognitionParams()
    }
}
