/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

class TypingTimeRecorder(staticTimeThresholdAfterFastTyping: Int,
        suppressKeyPreviewAfterBatchInputDuration: Int) {
    private val mStaticTimeThresholdAfterFastTyping: Int // msec
    private val mSuppressKeyPreviewAfterBatchInputDuration: Int
    private var lastTypingTime: Long = 0
    private var mLastLetterTypingTime: Long = 0
    private var lastBatchInputTime: Long = 0

    init {
        mStaticTimeThresholdAfterFastTyping = staticTimeThresholdAfterFastTyping
        mSuppressKeyPreviewAfterBatchInputDuration = suppressKeyPreviewAfterBatchInputDuration
    }

    fun isInFastTyping(eventTime: Long): Boolean {
        val elapsedTimeSinceLastLetterTyping = eventTime - mLastLetterTypingTime
        return elapsedTimeSinceLastLetterTyping < mStaticTimeThresholdAfterFastTyping
    }

    private fun wasLastInputTyping(): Boolean {
        return lastTypingTime >= lastBatchInputTime
    }

    fun onCodeInput(code: Int, eventTime: Long) {
        // Record the letter typing time when
        // 1. Letter keys are typed successively without any batch input in between.
        // 2. A letter key is typed within the threshold time since the last any key typing.
        // 3. A non-letter key is typed within the threshold time since the last letter key typing.
        if (Character.isLetter(code)) {
            if (wasLastInputTyping()
                    || eventTime - lastTypingTime < mStaticTimeThresholdAfterFastTyping) {
                mLastLetterTypingTime = eventTime
            }
        } else {
            if (eventTime - mLastLetterTypingTime < mStaticTimeThresholdAfterFastTyping) {
                // This non-letter typing should be treated as a part of fast typing.
                mLastLetterTypingTime = eventTime
            }
        }
        lastTypingTime = eventTime
    }

    fun onEndBatchInput(eventTime: Long) {
        lastBatchInputTime = eventTime
    }

    fun getLastLetterTypingTime(): Long {
        return mLastLetterTypingTime
    }

    fun needsToSuppressKeyPreviewPopup(eventTime: Long): Boolean {
        return !wasLastInputTyping()
                && eventTime - lastBatchInputTime < mSuppressKeyPreviewAfterBatchInputDuration
    }
}
