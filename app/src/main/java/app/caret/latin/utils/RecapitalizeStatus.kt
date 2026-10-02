/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import java.util.Locale

/**
 * The status of the current recapitalize process.
 */
class RecapitalizeStatus {
    // We store the location of the cursor and the string that was there before the recapitalize
    // action was done, and the location of the cursor and the string that was there after.
    private lateinit var mText: String
    private lateinit var mTextPlacement: TextPlacement
    private lateinit var mCurrentMode: RecapitalizeMode
    private var mSkipOriginalMixedCaseMode = false
    private lateinit var mLocale: Locale
    private lateinit var mSortedSeparators: IntArray
    private var mIsStarted = false
    private var mIsEnabled = true

    init {
        // By default, initialize with dummy values that won't match any real recapitalize.
        start("", -1, Locale.getDefault(), IntArray(0))
        stop()
    }

    fun start(text: String, cursorStart: Int, locale: Locale, sortedSeparators: IntArray) {
        if (!mIsEnabled) {
            return
        }
        mText = text
        mTextPlacement = TextPlacement(text, cursorStart)
        mCurrentMode = RecapitalizeMode.of(mText, sortedSeparators)
        mSkipOriginalMixedCaseMode = RecapitalizeMode.ORIGINAL_MIXED_CASE != mCurrentMode
        mLocale = locale
        mSortedSeparators = sortedSeparators
        mIsStarted = true
    }

    fun stop() {
        mIsStarted = false
    }

    fun isStarted(): Boolean {
        return mIsStarted
    }

    fun enable() {
        mIsEnabled = true
    }

    fun disable() {
        mIsEnabled = false
    }

    fun isEnabled(): Boolean {
        return mIsEnabled
    }

    fun isSetAt(cursorStart: Int, cursorEnd: Int): Boolean {
        return cursorStart == mTextPlacement.startPosition && cursorEnd == mTextPlacement.endPosition()
    }

    /**
     * Rotate through the different possible capitalization modes.
     */
    fun rotate() {
        val modeCount = RecapitalizeMode.count()
        var replacement: String
        var i = 0 // Protection against infinite loop.
        do {
            mCurrentMode = mCurrentMode.rotate(mSkipOriginalMixedCaseMode)
            replacement = mCurrentMode.apply(mText, mSortedSeparators, mLocale)
            ++i
        } while (replacement == mTextPlacement.text && i <= modeCount)
        mTextPlacement.text = replacement
    }

    fun textReplacement(): TextPlacement {
        return mTextPlacement
    }

    fun getCurrentMode(): RecapitalizeMode {
        return mCurrentMode
    }
}
