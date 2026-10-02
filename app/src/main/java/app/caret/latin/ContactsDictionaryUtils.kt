/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import app.caret.latin.common.Constants
import app.caret.latin.utils.ScriptUtils
import app.caret.latin.utils.ScriptUtils.script

import java.util.Locale

/**
 * Utility methods related contacts dictionary.
 */
class ContactsDictionaryUtils {

    companion object {
        /**
         * Returns the index of the last letter in the word, starting from position startIndex.
         */
        @JvmStatic
        fun getWordEndPosition(string: String, len: Int,
                startIndex: Int): Int {
            var end: Int
            var cp: Int
            end = startIndex + 1
            while (end < len) {
                cp = string.codePointAt(end)
                if (cp != Constants.CODE_DASH && cp != Constants.CODE_SINGLE_QUOTE
                       && !Character.isLetter(cp)) {
                    break
                }
                end += Character.charCount(cp)
            }
            return end
        }

        /**
         * Returns true if the locale supports using first name and last name as bigrams.
         */
        @JvmStatic
        fun useFirstLastBigramsForLocale(locale: Locale): Boolean {
            // todo (later): incomplete, see https://en.wikipedia.org/wiki/Personal_name#Name_order
            return when (locale.script()) {
                ScriptUtils.SCRIPT_CYRILLIC -> true
                ScriptUtils.SCRIPT_LATIN -> when (locale.getLanguage()) {
                    "hu", "ro", "vi" -> false
                    else -> true
                }
                else -> false
            }
        }
    }
}
