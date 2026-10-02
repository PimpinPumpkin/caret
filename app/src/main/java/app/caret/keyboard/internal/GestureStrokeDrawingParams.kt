/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.TypedArray

import app.caret.latin.R

/** This class holds parameters to control how a gesture stroke is sampled and drawn on the screen. */
class GestureStrokeDrawingParams(mainKeyboardViewAttr: TypedArray) {
    @JvmField val minSamplingDistance: Double // in pixel
    @JvmField val maxInterpolationAngularThreshold: Double // in radian
    @JvmField val maxInterpolationDistanceThreshold: Double // in pixel
    @JvmField val maxInterpolationSegments: Int

    init {
        minSamplingDistance = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_gestureTrailMinSamplingDistance,
                DEFAULT_MIN_SAMPLING_DISTANCE).toDouble()
        val interpolationAngularDegree = mainKeyboardViewAttr.getInteger(R.styleable
                .MainKeyboardView_gestureTrailMaxInterpolationAngularThreshold, 0)
        maxInterpolationAngularThreshold = if (interpolationAngularDegree <= 0)
                Math.toRadians(DEFAULT_MAX_INTERPOLATION_ANGULAR_THRESHOLD.toDouble())
                else Math.toRadians(interpolationAngularDegree.toDouble())
        maxInterpolationDistanceThreshold = mainKeyboardViewAttr.getDimension(R.styleable
                .MainKeyboardView_gestureTrailMaxInterpolationDistanceThreshold,
                DEFAULT_MAX_INTERPOLATION_DISTANCE_THRESHOLD).toDouble()
        maxInterpolationSegments = mainKeyboardViewAttr.getInteger(
                R.styleable.MainKeyboardView_gestureTrailMaxInterpolationSegments,
                DEFAULT_MAX_INTERPOLATION_SEGMENTS)
    }

    companion object {
        private const val DEFAULT_MIN_SAMPLING_DISTANCE = 0.0f // dp
        private const val DEFAULT_MAX_INTERPOLATION_ANGULAR_THRESHOLD = 15 // in degree
        private const val DEFAULT_MAX_INTERPOLATION_DISTANCE_THRESHOLD = 0.0f // dp
        private const val DEFAULT_MAX_INTERPOLATION_SEGMENTS = 4
    }
}
