/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.personalization

import android.content.Context

import com.android.inputmethod.latin.BinaryDictionary
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.dictionary.ExpandableBinaryDictionary
import app.caret.latin.NgramContext
import app.caret.latin.makedict.DictionaryHeader

import java.io.File
import java.util.Locale

/**
 * Locally gathers statistics about the words user types and various other signals like
 * auto-correction cancellation or manual picks. This allows the keyboard to adapt to the
 * typist over time.
 */
class UserHistoryDictionary
// TODO: Make this constructor private
constructor(context: Context, locale: Locale?) : ExpandableBinaryDictionary(
        context, getUserHistoryDictName(NAME, locale, null), locale!!, Dictionary.TYPE_USER_HISTORY, null) {

    init {
        if (mLocale != null && mLocale.toString().length > 1) {
            reloadDictionaryIfRequired()
        }
    }

    override fun getHeaderAttributeMap(): MutableMap<String, String> {
        val attributeMap = super.getHeaderAttributeMap()
        attributeMap.put(DictionaryHeader.USES_FORGETTING_CURVE_KEY,
                DictionaryHeader.ATTRIBUTE_VALUE_TRUE)
        attributeMap.put(DictionaryHeader.HAS_HISTORICAL_INFO_KEY,
                DictionaryHeader.ATTRIBUTE_VALUE_TRUE)
        return attributeMap
    }

    override fun loadInitialContentsLocked() {
        // No initial contents.
    }

    override fun isValidWord(word: String?): Boolean {
        // Strings out of this dictionary should not be considered existing words.
        return false
    }

    companion object {
        @JvmField val NAME: String = UserHistoryDictionary::class.java.simpleName

        /**
         * @returns the name of the {@link UserHistoryDictionary}.
         */
        @JvmStatic
        fun getUserHistoryDictName(name: String, locale: Locale?, dictFile: File?): String {
            return ExpandableBinaryDictionary.getDictName(name, locale!!, dictFile)
        }

        @JvmStatic
        fun getDictionary(context: Context, locale: Locale,
                dictFile: File?, dictNamePrefix: String?): UserHistoryDictionary {
            return PersonalizationHelper.getUserHistoryDictionary(context, locale)
        }

        /**
         * Add a word to the user history dictionary.
         *
         * @param userHistoryDictionary the user history dictionary
         * @param ngramContext the n-gram context
         * @param word the word the user inputted
         * @param isValid whether the word is valid or not
         * @param timestamp the timestamp when the word has been inputted
         */
        @JvmStatic
        fun addToDictionary(userHistoryDictionary: ExpandableBinaryDictionary,
                ngramContext: NgramContext, word: String, isValid: Boolean,
                timestamp: Int) {
            if (word.length > BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH) {
                return
            }
            userHistoryDictionary.updateEntriesForWord(ngramContext, word,
                    isValid, 1 /* count */, timestamp)
        }
    }
}
