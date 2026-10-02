/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import com.android.inputmethod.latin.utils.BinaryDictionaryUtils

import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.define.DebugFlags

object AutoCorrectionUtils {
    private val TAG = AutoCorrectionUtils::class.java.simpleName

    @JvmStatic
    fun suggestionExceedsThreshold(suggestion: SuggestedWordInfo?,
            consideredWord: String?, threshold: Float): Boolean {
        if (null != suggestion) {
            // Shortlist a whitelisted word
            if (suggestion.isKindOf(SuggestedWordInfo.KIND_WHITELIST)) {
                return true
            }
            if (!suggestion.isAppropriateForAutoCorrection()) {
                return false
            }
            val autoCorrectionSuggestionScore = suggestion.score
            // TODO: when the normalized score of the first suggestion is nearly equals to
            //       the normalized score of the second suggestion, behave less aggressive.
            val normalizedScore = BinaryDictionaryUtils.calcNormalizedScore(
                    consideredWord, suggestion.word, autoCorrectionSuggestionScore)
            if (DebugFlags.DEBUG_ENABLED) {
                Log.d(TAG, "Normalized " + consideredWord + "," + suggestion + ","
                        + autoCorrectionSuggestionScore + ", " + normalizedScore
                        + " (" + threshold + ")")
            }
            if (normalizedScore >= threshold) {
                if (DebugFlags.DEBUG_ENABLED) {
                    Log.d(TAG, "Exceeds threshold.")
                }
                return true
            }
        }
        return false
    }
}
