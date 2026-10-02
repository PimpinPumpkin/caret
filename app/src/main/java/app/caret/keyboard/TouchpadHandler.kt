// SPDX-License-Identifier: GPL-3.0-only
package app.caret.keyboard

import android.os.SystemClock

import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.common.Constants
import app.caret.latin.settings.Settings

class TouchpadHandler {
    private var listener: KeyboardActionListener? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var inTouchpadMode = false
    private var hasVibrated = false
    private var isScrolling = false

    private var touchpadActivationTime: Long = 0
    private var touchpadLastX = 0
    private var touchpadLastY = 0
    private var currentScrollDelay: Long = 0

    // Accumulators for fractional movement
    private var touchpadAccX = 0
    private var touchpadAccY = 0

    private var currentScrollDirection = 0

    fun disableTouchpadMode() {
        if (!inTouchpadMode) return
        stopEdgeScrolling()
        stopHapticRunnable()
        inTouchpadMode = false
        touchpadModeActive = false
        listener!!.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_OFF)
        listener = null
    }

    fun enableTouchpadMove(x: Int, y: Int, listener: KeyboardActionListener?) {
        if (!touchpadModeActive) return

        // Initialize
        if (!inTouchpadMode) {
            this.listener = listener
            inTouchpadMode = true
            hasVibrated = false
            touchpadLastX = x
            touchpadLastY = y
            touchpadActivationTime = SystemClock.elapsedRealtime()
            listener!!.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_ON)
            val sv = Settings.getValues()
            handler.postDelayed(hapticRunnable, sv.keyLongpressTimeout.toLong())
            return
        }

        onMove(x, y)
    }

    private fun onMove(x: Int, y: Int) {
        val sv = Settings.getValues()

        // Debounce
        if (SystemClock.elapsedRealtime() - touchpadActivationTime < sv.keyLongpressTimeout) {
            touchpadLastX = x
            touchpadLastY = y
            return
        }

        // Edge Scrolling
        if (sv.touchpadEdgeScroll && handleEdgeScrolling(x, y)) {
            return
        }

        // In touchpad mode - track both horizontal and vertical movement for 2D cursor control
        val deltaX = x - touchpadLastX
        val deltaY = y - touchpadLastY

        touchpadLastX = x
        touchpadLastY = y

        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            // Horizontal move, X only
            val accFactorX = 1.0f + (Math.abs(deltaX) / TOUCHPAD_ACCELERATION_FACTOR)
            touchpadAccX += (deltaX * accFactorX).toInt()
            touchpadAccY = 0
        } else {
            // Vertical move, Y only
            val accFactorY = 1.0f + (Math.abs(deltaY) / TOUCHPAD_ACCELERATION_FACTOR)
            touchpadAccY += (deltaY * accFactorY).toInt()
            touchpadAccX = 0
        }

        // Calculate dynamic threshold based on sensitivity setting (0-100)
        // Higher sensitivity = Lower threshold (faster cursor)
        // 0 -> 70px (Very Slow)
        // 50 -> 40px (Default)
        // 100 -> 10px (Very Fast)
        val sensitivity = Settings.getInstance().getCurrent().touchpadSensitivity
        val moveThreshold = 70 - (sensitivity * 0.6f).toInt()

        // Handle horizontal movement with accumulator
        while (Math.abs(touchpadAccX) >= moveThreshold) {
            val positive = touchpadAccX > 0
            val direction = if (positive) KeyCode.ARROW_RIGHT else KeyCode.ARROW_LEFT
            listener!!.onCodeInput(direction, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            touchpadAccX -= (if (positive) moveThreshold else -moveThreshold)
        }

        // Handle vertical movement with accumulator
        while (Math.abs(touchpadAccY) >= moveThreshold) {
            val positive = touchpadAccY > 0
            val direction = if (positive) KeyCode.ARROW_DOWN else KeyCode.ARROW_UP
            listener!!.onCodeInput(direction, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            touchpadAccY -= (if (positive) moveThreshold else -moveThreshold)
        }
    }

    private val hapticRunnable = Runnable {
        if (!hasVibrated) {
            listener!!.onCustomRequest(KeyboardActionListener.CustomAction.PERFORM_HAPTIC)
            hasVibrated = true
        }
    }

    private fun stopHapticRunnable() {
        hasVibrated = false
        handler.removeCallbacks(hapticRunnable)
    }

    private val scrollRunnable: Runnable = object : Runnable {
        override fun run() {
            val actionListener = listener
            if (isScrolling && actionListener != null) {
                val keyCode = getKeyCodeForDirection(currentScrollDirection)

                actionListener.onCodeInput(keyCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)

                currentScrollDelay = Math.max(MIN_EDGE_ACCELERATION_DELAY.toLong(), (currentScrollDelay * EDGE_ACCELERATION_FACTOR).toLong())

                handler.postDelayed(this, currentScrollDelay)
            }
        }
    }

    private fun handleEdgeScrolling(x: Int, y: Int): Boolean {
        val currentKeyboard = KeyboardSwitcher.getInstance().keyboard
        if (currentKeyboard == null) return false

        val keyboardHeight = currentKeyboard.baseHeight
        val keyboardWidth = currentKeyboard.baseWidth
        val thresholdX = (keyboardWidth * EDGE_THRESHOLD_PERCENTAGE).toInt()
        val thresholdY = (keyboardHeight * EDGE_THRESHOLD_PERCENTAGE).toInt()

        if (y <= thresholdY) {
            currentScrollDirection = DIRECTION_UP
            startEdgeScrolling()
            return true
        } else if (y >= (keyboardHeight - thresholdY)) {
            currentScrollDirection = DIRECTION_DOWN
            startEdgeScrolling()
            return true
        } else if (x <= thresholdX) {
            currentScrollDirection = DIRECTION_LEFT
            startEdgeScrolling()
            return true
        } else if (x >= (keyboardWidth - thresholdX)) {
            currentScrollDirection = DIRECTION_RIGHT
            startEdgeScrolling()
            return true
        } else {
            stopEdgeScrolling()
            return false
        }
    }

    private fun startEdgeScrolling() {
        if (!isScrolling) {
            isScrolling = true
            currentScrollDelay = getBaseScrollDelay()
            handler.removeCallbacks(scrollRunnable)
            handler.post(scrollRunnable)
        }
    }

    private fun stopEdgeScrolling() {
        isScrolling = false
        handler.removeCallbacks(scrollRunnable)
    }

    private fun getBaseScrollDelay(): Long {
        val sensitivity = Settings.getInstance().getCurrent().touchpadSensitivity

        // Calculates the base scroll delay based on user sensitivity (range: 0-100).
        // Maps sensitivity 0 to 300ms (slowest) and 100 to 50ms (fastest).
        return 300 - (sensitivity * 2.5f).toLong()
    }

    private fun getKeyCodeForDirection(direction: Int): Int {
        return when (direction) {
            DIRECTION_UP -> KeyCode.ARROW_UP
            DIRECTION_DOWN -> KeyCode.ARROW_DOWN
            DIRECTION_LEFT -> KeyCode.ARROW_LEFT
            DIRECTION_RIGHT -> KeyCode.ARROW_RIGHT
            else -> KeyCode.UNSPECIFIED
        }
    }

    companion object {
        private var touchpadModeActive = false

        private const val EDGE_THRESHOLD_PERCENTAGE = 0.1f        // Screen edge threshold percentage
        private const val TOUCHPAD_ACCELERATION_FACTOR = 50.0f    // Lower = more acceleration
        private const val EDGE_ACCELERATION_FACTOR = 0.95f
        private const val MIN_EDGE_ACCELERATION_DELAY = 20

        private const val DIRECTION_UP = 1
        private const val DIRECTION_DOWN = 2
        private const val DIRECTION_LEFT = 3
        private const val DIRECTION_RIGHT = 4

        @JvmStatic
        fun setTouchpadModeActive(active: Boolean) {
            touchpadModeActive = active
        }
    }
}
