/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.Resources
import android.util.DisplayMetrics
import app.caret.latin.utils.Log

import app.caret.latin.R
import app.caret.latin.common.Constants
import app.caret.latin.define.DebugFlags

// This hack is applied to certain classes of tablets.
class BogusMoveEventDetector {
    private var accumulatedDistanceThreshold = 0
    private var radiusThreshold = 0

    // Accumulated distance from actual and artificial down keys.
    /* package */ var accumulatedDistanceFromDownKey = 0
        private set
    private var actualDownX = 0
    private var actualDownY = 0

    fun setKeyboardGeometry(keyWidth: Int, keyHeight: Int) {
        val keyDiagonal = Math.hypot(keyWidth.toDouble(), keyHeight.toDouble()).toFloat()
        accumulatedDistanceThreshold = (
                keyDiagonal * BOGUS_MOVE_ACCUMULATED_DISTANCE_THRESHOLD).toInt()
        radiusThreshold = (keyDiagonal * BOGUS_MOVE_RADIUS_THRESHOLD).toInt()
    }

    fun onActualDownEvent(x: Int, y: Int) {
        actualDownX = x
        actualDownY = y
    }

    fun onDownKey() {
        accumulatedDistanceFromDownKey = 0
    }

    fun onMoveKey(distance: Int) {
        accumulatedDistanceFromDownKey += distance
    }

    fun hasTraveledLongDistance(x: Int, y: Int): Boolean {
        if (!needsProximateBogusDownMoveUpEventHack) {
            return false
        }
        val dx = Math.abs(x - actualDownX)
        val dy = Math.abs(y - actualDownY)
        // A bogus move event should be a horizontal movement. A vertical movement might be
        // a sloppy typing and should be ignored.
        return dx >= dy && accumulatedDistanceFromDownKey >= accumulatedDistanceThreshold
    }

    fun getDistanceFromDownEvent(x: Int, y: Int): Int {
        return getDistance(x, y, actualDownX, actualDownY)
    }

    fun isCloseToActualDownEvent(x: Int, y: Int): Boolean {
        return needsProximateBogusDownMoveUpEventHack
                && getDistanceFromDownEvent(x, y) < radiusThreshold
    }

    companion object {
        private val TAG = BogusMoveEventDetector::class.java.simpleName

        // Move these thresholds to resource.
        // These thresholds' unit is a diagonal length of a key.
        private const val BOGUS_MOVE_ACCUMULATED_DISTANCE_THRESHOLD = 0.53f
        private const val BOGUS_MOVE_RADIUS_THRESHOLD = 1.14f

        private var needsProximateBogusDownMoveUpEventHack = false

        @JvmStatic
        fun init(res: Resources) {
            // The proximate bogus down move up event hack is needed for a device such like,
            // 1) is large tablet, or 2) is small tablet and the screen density is less than hdpi.
            // Though it seems odd to use screen density as criteria of the quality of the touch
            // screen, the small table that has a less density screen than hdpi most likely has been
            // made with the touch screen that needs the hack.
            val screenMetrics = res.getInteger(R.integer.config_screen_metrics)
            val isLargeTablet = (screenMetrics == Constants.SCREEN_METRICS_LARGE_TABLET)
            val isSmallTablet = (screenMetrics == Constants.SCREEN_METRICS_SMALL_TABLET)
            val densityDpi = res.getDisplayMetrics().densityDpi
            val hasLowDensityScreen = (densityDpi < DisplayMetrics.DENSITY_HIGH)
            val needsTheHack = isLargeTablet || (isSmallTablet && hasLowDensityScreen)
            if (DebugFlags.DEBUG_ENABLED) {
                val sw = res.getConfiguration().smallestScreenWidthDp
                Log.d(TAG, "needsProximateBogusDownMoveUpEventHack=" + needsTheHack
                        + " smallestScreenWidthDp=" + sw + " densityDpi=" + densityDpi
                        + " screenMetrics=" + screenMetrics)
            }
            needsProximateBogusDownMoveUpEventHack = needsTheHack
        }

        private fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
            return Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toInt()
        }
    }
}
