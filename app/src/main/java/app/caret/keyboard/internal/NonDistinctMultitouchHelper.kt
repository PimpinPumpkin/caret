/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.latin.utils.Log
import android.view.MotionEvent

import app.caret.keyboard.Key
import app.caret.keyboard.KeyDetector
import app.caret.keyboard.PointerTracker
import app.caret.latin.common.CoordinateUtils

class NonDistinctMultitouchHelper {
    private var oldPointerCount = 1
    private var oldKey: Key? = null
    private val lastCoords = CoordinateUtils.newInstance()

    fun processMotionEvent(me: MotionEvent, keyDetector: KeyDetector) {
        val pointerCount = me.getPointerCount()
        val previousPointerCount = oldPointerCount
        oldPointerCount = pointerCount
        // Ignore continuous multi-touch events because we can't trust the coordinates
        // in multi-touch events.
        if (pointerCount > 1 && previousPointerCount > 1) {
            return
        }

        // Use only main pointer tracker.
        val mainTracker = PointerTracker.getPointerTracker(
                MAIN_POINTER_TRACKER_ID)
        val action = me.getActionMasked()
        val index = me.getActionIndex()
        val eventTime = me.getEventTime()
        val downTime = me.getDownTime()

        // In single-touch.
        if (previousPointerCount == 1 && pointerCount == 1) {
            if (me.getPointerId(index) == mainTracker.pointerId) {
                mainTracker.processMotionEvent(me, keyDetector)
                return
            }
            // Inject a copied event.
            injectMotionEvent(action, me.getX(index), me.getY(index), downTime, eventTime,
                    mainTracker, keyDetector)
            return
        }

        // Single-touch to multi-touch transition.
        if (previousPointerCount == 1 && pointerCount == 2) {
            // Send an up event for the last pointer, be cause we can't trust the coordinates of
            // this multi-touch event.
            mainTracker.getLastCoordinates(lastCoords)
            val x = CoordinateUtils.x(lastCoords)
            val y = CoordinateUtils.y(lastCoords)
            oldKey = mainTracker.getKeyOn(x, y)
            // Inject an artifact up event for the old key.
            injectMotionEvent(MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), downTime, eventTime,
                    mainTracker, keyDetector)
            return
        }

        // Multi-touch to single-touch transition.
        if (previousPointerCount == 2 && pointerCount == 1) {
            // Send a down event for the latest pointer if the key is different from the previous
            // key.
            val x = me.getX(index).toInt()
            val y = me.getY(index).toInt()
            val newKey = mainTracker.getKeyOn(x, y)
            if (oldKey !== newKey) {
                // Inject an artifact down event for the new key.
                // An artifact up event for the new key will usually be injected as a single-touch.
                injectMotionEvent(MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), downTime, eventTime,
                        mainTracker, keyDetector)
                if (action == MotionEvent.ACTION_UP) {
                    // Inject an artifact up event for the new key also.
                    injectMotionEvent(MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), downTime, eventTime,
                            mainTracker, keyDetector)
                }
            }
            return
        }

        Log.w(TAG, "Unknown touch panel behavior: pointer count is "
                + pointerCount + " (previously " + previousPointerCount + ")")
    }

    companion object {
        private val TAG = NonDistinctMultitouchHelper::class.java.getSimpleName()

        private const val MAIN_POINTER_TRACKER_ID = 0

        private fun injectMotionEvent(action: Int, x: Float, y: Float,
                downTime: Long, eventTime: Long, tracker: PointerTracker,
                keyDetector: KeyDetector) {
            val me = MotionEvent.obtain(
                    downTime, eventTime, action, x, y, 0 /* metaState */)
            try {
                tracker.processMotionEvent(me, keyDetector)
            } finally {
                me.recycle()
            }
        }
    }
}
