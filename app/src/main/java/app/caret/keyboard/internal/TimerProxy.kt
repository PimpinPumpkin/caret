/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.keyboard.Key
import app.caret.keyboard.PointerTracker

interface TimerProxy {
    /**
     * Start a timer to detect if a user is typing keys.
     * @param typedKey the key that is typed.
     */
    fun startTypingStateTimer(typedKey: Key)

    /**
     * Check if a user is key typing.
     * @return true if a user is in typing.
     */
    fun isTypingState(): Boolean

    /**
     * Start a timer to simulate repeated key presses while a user keep pressing a key.
     * @param tracker the {@link PointerTracker} that points the key to be repeated.
     * @param repeatCount the number of times that the key is repeating. Starting from 1.
     * @param delay the interval delay to the next key repeat, in millisecond.
     */
    fun startKeyRepeatTimerOf(tracker: PointerTracker, repeatCount: Int, delay: Int)

    /**
     * Start a timer to detect a long pressed key.
     * If a key pointed by <code>tracker</code> is a shift key, start another timer to detect
     * long pressed shift key.
     * @param tracker the {@link PointerTracker} that starts long pressing.
     * @param delay the delay to fire the long press timer, in millisecond.
     */
    fun startLongPressTimerOf(tracker: PointerTracker, delay: Int)

    /**
     * Cancel timers for detecting a long pressed key and a long press shift key.
     * @param tracker cancel long press timers of this {@link PointerTracker}.
     */
    fun cancelLongPressTimersOf(tracker: PointerTracker)

    /**
     * Cancel a timer for detecting a long pressed shift key.
     */
    fun cancelLongPressShiftKeyTimer()

    /**
     * Cancel a timer for detecting a long pressed alpha/symbol key.
     */
    fun cancelLongPressAlphaSymbolKeyTimer()

    /**
     * Cancel timers for detecting repeated key press, long pressed key, and long pressed shift key.
     * @param tracker the {@link PointerTracker} that starts timers to be canceled.
     */
    fun cancelKeyTimersOf(tracker: PointerTracker)

    /**
     * Start a timer to detect double tapped shift key.
     */
    fun startDoubleTapShiftKeyTimer()

    /**
     * Cancel a timer of detecting double tapped shift key.
     */
    fun cancelDoubleTapShiftKeyTimer()

    /**
     * Check if a timer of detecting double tapped shift key is running, then cancel it.
     * @return true if detecting double tapped shift key was ongoing.
     */
    fun popDoubleTapShiftKeyTimer(): Boolean

    /**
     * Start a timer to fire updating batch input while <code>tracker</code> is on hold.
     * @param tracker the {@link PointerTracker} that stops moving.
     */
    fun startUpdateBatchInputTimer(tracker: PointerTracker)

    /**
     * Cancel a timer of firing updating batch input.
     * @param tracker the {@link PointerTracker} that resumes moving or ends gesture input.
     */
    fun cancelUpdateBatchInputTimer(tracker: PointerTracker)

    /**
     * Cancel all timers of firing updating batch input.
     */
    fun cancelAllUpdateBatchInputTimers()

    class Adapter : TimerProxy {
        override fun startTypingStateTimer(typedKey: Key) {}
        override fun isTypingState(): Boolean { return false }
        override fun startKeyRepeatTimerOf(tracker: PointerTracker, repeatCount: Int,
                delay: Int) {}
        override fun startLongPressTimerOf(tracker: PointerTracker, delay: Int) {}
        override fun cancelLongPressTimersOf(tracker: PointerTracker) {}
        override fun cancelLongPressShiftKeyTimer() {}
        override fun cancelLongPressAlphaSymbolKeyTimer() {}
        override fun cancelKeyTimersOf(tracker: PointerTracker) {}
        override fun startDoubleTapShiftKeyTimer() {}
        override fun cancelDoubleTapShiftKeyTimer() {}
        override fun popDoubleTapShiftKeyTimer(): Boolean { return false }
        override fun startUpdateBatchInputTimer(tracker: PointerTracker) {}
        override fun cancelUpdateBatchInputTimer(tracker: PointerTracker) {}
        override fun cancelAllUpdateBatchInputTimers() {}
    }
}
