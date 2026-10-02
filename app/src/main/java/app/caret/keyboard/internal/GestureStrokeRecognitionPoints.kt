/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.latin.settings.Settings
import app.caret.latin.utils.Log

import app.caret.latin.common.Constants
import app.caret.latin.common.InputPointers
import app.caret.latin.common.ResizableIntArray

import java.util.Locale

/**
 * This class holds event points to recognize a gesture stroke.
 * TODO: Should be package private class.
 */
// TODO: Make this package private
class GestureStrokeRecognitionPoints(private val pointerId: Int,
        private val recognitionParams: GestureStrokeRecognitionParams) {
    private val eventTimes = ResizableIntArray(
            Constants.DEFAULT_GESTURE_POINTS_CAPACITY)
    private val xCoordinates = ResizableIntArray(
            Constants.DEFAULT_GESTURE_POINTS_CAPACITY)
    private val yCoordinates = ResizableIntArray(
            Constants.DEFAULT_GESTURE_POINTS_CAPACITY)

    private var mKeyWidth = 0 // pixel
    private var minYCoordinate = 0 // pixel
    private var maxYCoordinate = 0 // pixel
    // Static threshold for starting gesture detection
    private var detectFastMoveSpeedThreshold = 0 // pixel /sec
    private var detectFastMoveTime = 0
    private var detectFastMoveX = 0
    private var detectFastMoveY = 0
    // Dynamic threshold for gesture after fast typing
    private var afterFastTyping = false
    private var gestureDynamicDistanceThresholdFrom = 0 // pixel
    private var gestureDynamicDistanceThresholdTo = 0 // pixel
    // Variables for gesture sampling
    private var gestureSamplingMinimumDistance = 0 // pixel
    private var lastMajorEventTime = 0L
    private var lastMajorEventX = 0
    private var lastMajorEventY = 0
    // Variables for gesture recognition
    private var gestureRecognitionSpeedThreshold = 0 // pixel / sec
    private var incrementalRecognitionSize = 0
    private var lastIncrementalBatchSize = 0

    // TODO: Make this package private
    fun setKeyboardGeometry(keyWidth: Int, keyboardHeight: Int) {
        mKeyWidth = keyWidth
        minYCoordinate = -(keyboardHeight * EXTRA_GESTURE_TRAIL_AREA_ABOVE_KEYBOARD_RATIO).toInt()
        maxYCoordinate = keyboardHeight
        // TODO: Find an appropriate base metric for these length. Maybe diagonal length of the key?
        detectFastMoveSpeedThreshold = (
                keyWidth * recognitionParams.mDetectFastMoveSpeedThreshold).toInt()
        gestureDynamicDistanceThresholdFrom = (
                keyWidth * recognitionParams.mDynamicDistanceThresholdFrom).toInt()
        gestureDynamicDistanceThresholdTo = (
                keyWidth * recognitionParams.mDynamicDistanceThresholdTo).toInt()
        gestureSamplingMinimumDistance = (
                keyWidth * recognitionParams.mSamplingMinimumDistance).toInt()
        gestureRecognitionSpeedThreshold = (
                keyWidth * recognitionParams.mRecognitionSpeedThreshold).toInt()
        if (DEBUG) {
            Log.d(TAG, String.format(Locale.US,
                    "[%d] setKeyboardGeometry: keyWidth=%3d tT=%3d >> %3d tD=%3d >> %3d",
                    pointerId, keyWidth,
                    recognitionParams.mDynamicTimeThresholdFrom,
                    recognitionParams.mDynamicTimeThresholdTo,
                    gestureDynamicDistanceThresholdFrom,
                    gestureDynamicDistanceThresholdTo))
        }
    }

    // TODO: Make this package private
    fun getLength(): Int {
        return eventTimes.getLength()
    }

    // TODO: Make this package private
    fun addDownEventPoint(x: Int, y: Int, elapsedTimeSinceFirstDown: Int,
            elapsedTimeSinceLastTyping: Int) {
        reset()
        if (elapsedTimeSinceLastTyping < Settings.getValues().mGestureFastTypingCooldown) {
            afterFastTyping = true
        }
        if (DEBUG) {
            Log.d(TAG, String.format(Locale.US, "[%d] onDownEvent: dT=%3d%s", pointerId,
                    elapsedTimeSinceLastTyping, if (afterFastTyping) " afterFastTyping" else ""))
        }
        // Call {@link #addEventPoint(int,int,int,boolean)} to record this down event point as a
        // major event point.
        addEventPoint(x, y, elapsedTimeSinceFirstDown, true /* isMajorEvent */)
    }

    private fun getGestureDynamicDistanceThreshold(deltaTime: Int): Int {
        if (!afterFastTyping || deltaTime >= recognitionParams.mDynamicThresholdDecayDuration) {
            return gestureDynamicDistanceThresholdTo
        }
        val decayedThreshold =
                (gestureDynamicDistanceThresholdFrom - gestureDynamicDistanceThresholdTo) *
                deltaTime / recognitionParams.mDynamicThresholdDecayDuration
        return gestureDynamicDistanceThresholdFrom - decayedThreshold
    }

    private fun getGestureDynamicTimeThreshold(deltaTime: Int): Int {
        if (!afterFastTyping || deltaTime >= recognitionParams.mDynamicThresholdDecayDuration) {
            return recognitionParams.mDynamicTimeThresholdTo
        }
        val decayedThreshold =
                (recognitionParams.mDynamicTimeThresholdFrom
                        - recognitionParams.mDynamicTimeThresholdTo) *
                deltaTime / recognitionParams.mDynamicThresholdDecayDuration
        return recognitionParams.mDynamicTimeThresholdFrom - decayedThreshold
    }

    // TODO: Make this package private
    fun isStartOfAGesture(): Boolean {
        if (!hasDetectedFastMove()) {
            return false
        }
        val size = getLength()
        if (size <= 0) {
            return false
        }
        val lastIndex = size - 1
        val deltaTime = eventTimes.get(lastIndex) - detectFastMoveTime
        if (deltaTime < 0) {
            return false
        }
        val deltaDistance = getDistance(
                xCoordinates.get(lastIndex), yCoordinates.get(lastIndex),
                detectFastMoveX, detectFastMoveY)
        val distanceThreshold = getGestureDynamicDistanceThreshold(deltaTime)
        val timeThreshold = getGestureDynamicTimeThreshold(deltaTime)
        val isStartOfAGesture = deltaTime >= timeThreshold
                && deltaDistance >= distanceThreshold
        if (DEBUG) {
            Log.d(TAG, String.format(Locale.US, "[%d] isStartOfAGesture: dT=%3d tT=%3d dD=%3d tD=%3d%s%s",
                    pointerId, deltaTime, timeThreshold,
                    deltaDistance, distanceThreshold,
                    if (afterFastTyping) " afterFastTyping" else "",
                    if (isStartOfAGesture) " startOfAGesture" else ""))
        }
        return isStartOfAGesture
    }

    // TODO: Make this package private
    fun duplicateLastPointWith(time: Int) {
        val lastIndex = getLength() - 1
        if (lastIndex >= 0) {
            val x = xCoordinates.get(lastIndex)
            val y = yCoordinates.get(lastIndex)
            if (DEBUG) {
                Log.d(TAG, String.format(Locale.US, "[%d] duplicateLastPointWith: %d,%d|%d", pointerId,
                        x, y, time))
            }
            // TODO: Have appendMajorPoint()
            appendPoint(x, y, time)
            updateIncrementalRecognitionSize(x, y, time)
        }
    }

    private fun reset() {
        incrementalRecognitionSize = 0
        lastIncrementalBatchSize = 0
        eventTimes.setLength(0)
        xCoordinates.setLength(0)
        yCoordinates.setLength(0)
        lastMajorEventTime = 0
        detectFastMoveTime = 0
        afterFastTyping = false
    }

    private fun appendPoint(x: Int, y: Int, time: Int) {
        val lastIndex = getLength() - 1
        // The point that is created by {@link duplicateLastPointWith(int)} may have later event
        // time than the next {@link MotionEvent}. To maintain the monotonicity of the event time,
        // drop the successive point here.
        if (lastIndex >= 0 && eventTimes.get(lastIndex) > time) {
            Log.w(TAG, String.format(Locale.US, "[%d] drop stale event: %d,%d|%d last: %d,%d|%d", pointerId,
                    x, y, time, xCoordinates.get(lastIndex), yCoordinates.get(lastIndex),
                    eventTimes.get(lastIndex)))
            return
        }
        eventTimes.add(time)
        xCoordinates.add(x)
        yCoordinates.add(y)
    }

    private fun updateMajorEvent(x: Int, y: Int, time: Int) {
        lastMajorEventTime = time.toLong()
        lastMajorEventX = x
        lastMajorEventY = y
    }

    private fun hasDetectedFastMove(): Boolean {
        return detectFastMoveTime > 0
    }

    private fun detectFastMove(x: Int, y: Int, time: Int): Int {
        val size = getLength()
        val lastIndex = size - 1
        val lastX = xCoordinates.get(lastIndex)
        val lastY = yCoordinates.get(lastIndex)
        val dist = getDistance(lastX, lastY, x, y)
        val msecs = time - eventTimes.get(lastIndex)
        if (msecs > 0) {
            val pixels = getDistance(lastX, lastY, x, y)
            val pixelsPerSec = pixels * MSEC_PER_SEC
            if (DEBUG_SPEED) {
                val speed = pixelsPerSec.toFloat() / msecs / mKeyWidth
                Log.d(TAG, String.format(Locale.US, "[%d] detectFastMove: speed=%5.2f", pointerId, speed))
            }
            // Equivalent to (pixels / msecs < mStartSpeedThreshold / MSEC_PER_SEC)
            if (!hasDetectedFastMove() && pixelsPerSec > detectFastMoveSpeedThreshold * msecs) {
                if (DEBUG) {
                    val speed = pixelsPerSec.toFloat() / msecs / mKeyWidth
                    Log.d(TAG, String.format(Locale.US,
                            "[%d] detectFastMove: speed=%5.2f T=%3d points=%3d fastMove",
                            pointerId, speed, time, size))
                }
                detectFastMoveTime = time
                detectFastMoveX = x
                detectFastMoveY = y
            }
        }
        return dist
    }

    /**
     * Add an event point to this gesture stroke recognition points. Returns true if the event
     * point is on the valid gesture area.
     * @param x the x-coordinate of the event point
     * @param y the y-coordinate of the event point
     * @param time the elapsed time in millisecond from the first gesture down
     * @param isMajorEvent false if this is a historical move event
     * @return true if the event point is on the valid gesture area
     */
    // TODO: Make this package private
    fun addEventPoint(x: Int, y: Int, time: Int,
            isMajorEvent: Boolean): Boolean {
        val size = getLength()
        if (size <= 0) {
            // The first event of this stroke (a.k.a. down event).
            appendPoint(x, y, time)
            updateMajorEvent(x, y, time)
        } else {
            val distance = detectFastMove(x, y, time)
            if (distance > gestureSamplingMinimumDistance) {
                appendPoint(x, y, time)
            }
        }
        if (isMajorEvent) {
            updateIncrementalRecognitionSize(x, y, time)
            updateMajorEvent(x, y, time)
        }
        return y >= minYCoordinate && y < maxYCoordinate
    }

    private fun updateIncrementalRecognitionSize(x: Int, y: Int, time: Int) {
        val msecs = (time - lastMajorEventTime).toInt()
        if (msecs <= 0) {
            return
        }
        val pixels = getDistance(lastMajorEventX, lastMajorEventY, x, y)
        val pixelsPerSec = pixels * MSEC_PER_SEC
        // Equivalent to (pixels / msecs < mGestureRecognitionThreshold / MSEC_PER_SEC)
        if (pixelsPerSec < gestureRecognitionSpeedThreshold * msecs) {
            incrementalRecognitionSize = getLength()
        }
    }

    // TODO: Make this package private
    fun hasRecognitionTimePast(
            currentTime: Long, lastRecognitionTime: Long): Boolean {
        return currentTime > lastRecognitionTime + recognitionParams.mRecognitionMinimumTime
    }

    // TODO: Make this package private
    fun appendAllBatchPoints(out: InputPointers) {
        appendBatchPoints(out, getLength())
    }

    // TODO: Make this package private
    fun appendIncrementalBatchPoints(out: InputPointers) {
        appendBatchPoints(out, incrementalRecognitionSize)
    }

    private fun appendBatchPoints(out: InputPointers, size: Int) {
        val length = size - lastIncrementalBatchSize
        if (length <= 0) {
            return
        }
        out.append(pointerId, eventTimes, xCoordinates, yCoordinates,
                lastIncrementalBatchSize, length)
        lastIncrementalBatchSize = size
    }

    companion object {
        private val TAG = GestureStrokeRecognitionPoints::class.java.simpleName
        private const val DEBUG = false
        private const val DEBUG_SPEED = false

        // The height of extra area above the keyboard to draw gesture trails.
        // Proportional to the keyboard height.
        const val EXTRA_GESTURE_TRAIL_AREA_ABOVE_KEYBOARD_RATIO = 0.25f

        private const val MSEC_PER_SEC = 1000

        private fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
            return Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toInt()
        }
    }
}
