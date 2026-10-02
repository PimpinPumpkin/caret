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
    private lateinit var text: String
    private lateinit var textPlacement: TextPlacement
    lateinit var currentMode: RecapitalizeMode
        private set
    private var skipOriginalMixedCaseMode = false
    private lateinit var locale: Locale
    private lateinit var sortedSeparators: IntArray
    var isStarted = false
        private set
    var isEnabled = true
        private set

    init {
        // By default, initialize with dummy values that won't match any real recapitalize.
        start("", -1, Locale.getDefault(), IntArray(0))
        stop()
    }

    fun start(text: String, cursorStart: Int, locale: Locale, sortedSeparators: IntArray) {
        if (!isEnabled) {
            return
        }
        this.text = text
        textPlacement = TextPlacement(text, cursorStart)
        currentMode = RecapitalizeMode.of(text, sortedSeparators)
        skipOriginalMixedCaseMode = RecapitalizeMode.ORIGINAL_MIXED_CASE != currentMode
        this.locale = locale
        this.sortedSeparators = sortedSeparators
        isStarted = true
    }

    fun stop() {
        isStarted = false
    }

    fun enable() {
        isEnabled = true
    }

    fun disable() {
        isEnabled = false
    }

    fun isSetAt(cursorStart: Int, cursorEnd: Int): Boolean {
        return cursorStart == textPlacement.startPosition && cursorEnd == textPlacement.endPosition()
    }

    /**
     * Rotate through the different possible capitalization modes.
     */
    fun rotate() {
        val modeCount = RecapitalizeMode.count()
        var replacement: String
        var i = 0 // Protection against infinite loop.
        do {
            currentMode = currentMode.rotate(skipOriginalMixedCaseMode)
            replacement = currentMode.apply(text, sortedSeparators, locale)
            ++i
        } while (replacement == textPlacement.text && i <= modeCount)
        textPlacement.text = replacement
    }

    fun textReplacement(): TextPlacement {
        return textPlacement
    }
}
