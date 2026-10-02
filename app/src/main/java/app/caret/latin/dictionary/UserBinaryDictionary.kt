/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.dictionary

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.UserDictionary.Words
import android.text.TextUtils

import com.android.inputmethod.latin.BinaryDictionary

import app.caret.latin.utils.Log
import app.caret.latin.utils.SubtypeLocaleUtils

import java.io.File
import java.util.Arrays
import java.util.Locale

/**
 * An expandable dictionary that stores the words in the user dictionary provider into a binary
 * dictionary file to use it from native code.
 */
class UserBinaryDictionary protected constructor(context: Context, locale: Locale?,
                               alsoUseMoreRestrictiveLocales: Boolean,
                               dictFile: File?, name: String) : ExpandableBinaryDictionary(
        context, ExpandableBinaryDictionary.getDictName(name, locale!!, dictFile), locale!!,
        Dictionary.TYPE_USER, dictFile) {

    private var mObserver: ContentObserver?
    // this really needs to be the locale string, as it interacts with system
    private val mLocaleString: String
    private val mAlsoUseMoreRestrictiveLocales: Boolean

    init {
        if (null == locale) throw NullPointerException() // Catch the error earlier
        val localeStr = locale.toString()
        if (SubtypeLocaleUtils.NO_LANGUAGE == localeStr) {
            // If we don't have a locale, insert into the "all locales" user dictionary.
            mLocaleString = USER_DICTIONARY_ALL_LANGUAGES
        } else {
            mLocaleString = localeStr
        }
        mAlsoUseMoreRestrictiveLocales = alsoUseMoreRestrictiveLocales

        mObserver = object : ContentObserver(null) {
            override fun onChange(self: Boolean, uri: Uri?) {
                setNeedsToRecreate()
            }
        }
        context.contentResolver.registerContentObserver(Words.CONTENT_URI, true, mObserver!!)
        reloadDictionaryIfRequired()
    }

    @Synchronized
    override fun close() {
        if (mObserver != null) {
            mContext.contentResolver.unregisterContentObserver(mObserver!!)
            mObserver = null
        }
        super.close()
    }

    public override fun loadInitialContentsLocked() {
        // Split the locale. For example "en" => ["en"], "de_DE" => ["de", "DE"],
        // "en_US_foo_bar_qux" => ["en", "US", "foo_bar_qux"] because of the limit of 3.
        // This is correct for locale processing. (well, and it sucks e.g. for sr-Latn, resp. sr__#Latn as string)
        // For this example, we'll look at the "en_US_POSIX" case.
        val localeElements: Array<String> =
                if (TextUtils.isEmpty(mLocaleString)) arrayOf() else mLocaleString.split("_", limit = 3).toTypedArray()
        val length = localeElements.size

        val request = StringBuilder("(locale is NULL)")
        var localeSoFar = ""
        // At start, localeElements = ["en", "US", "POSIX"] ; localeSoFar = "" ;
        // and request = "(locale is NULL)"
        for (i in 0 until length) {
            // i | localeSoFar    | localeElements
            // 0 | ""             | ["en", "US", "POSIX"]
            // 1 | "en_"          | ["en", "US", "POSIX"]
            // 2 | "en_US_"       | ["en", "en_US", "POSIX"]
            localeElements[i] = localeSoFar + localeElements[i]
            localeSoFar = localeElements[i] + "_"
            // i | request
            // 0 | "(locale is NULL)"
            // 1 | "(locale is NULL) or (locale=?)"
            // 2 | "(locale is NULL) or (locale=?) or (locale=?)"
            request.append(" or (locale=?)")
        }
        // At the end, localeElements = ["en", "en_US", "en_US_POSIX"]; localeSoFar = en_US_POSIX_"
        // and request = "(locale is NULL) or (locale=?) or (locale=?) or (locale=?)"

        val requestArguments: Array<String>
        // If length == 3, we already have all the arguments we need (common prefix is meaningless inside variants)
        if (mAlsoUseMoreRestrictiveLocales && length < 3) {
            request.append(" or (locale like ?)")
            // The following creates an array with one more (null) position
            val localeElementsWithMoreRestrictiveLocalesIncluded: Array<String> =
                    Arrays.copyOf(localeElements, length + 1)
            localeElementsWithMoreRestrictiveLocalesIncluded[length] =
                    localeElements[length - 1] + "_%"
            requestArguments = localeElementsWithMoreRestrictiveLocalesIncluded
            // If for example localeElements = ["en"]
            // then requestArguments = ["en", "en_%"]
            // and request = (locale is NULL) or (locale=?) or (locale like ?)
            // If localeElements = ["en", "en_US"]
            // then requestArguments = ["en", "en_US", "en_US_%"]
        } else {
            requestArguments = localeElements
        }
        val requestString = request.toString()
        try {
            addWordsFromProjectionLocked(PROJECTION_QUERY_WITH_SHORTCUT, requestString, requestArguments)
        } catch (e: IllegalArgumentException) {
            // This may happen on some non-compliant devices where the declared API is JB+ but
            // the SHORTCUT column is not present for some reason.
            addWordsFromProjectionLocked(PROJECTION_QUERY_WITHOUT_SHORTCUT, requestString, requestArguments)
        }
    }

    @Throws(IllegalArgumentException::class)
    private fun addWordsFromProjectionLocked(query: Array<String>, request: String, requestArguments: Array<String>) {
        var cursor: Cursor? = null
        try {
            cursor = mContext.contentResolver.query(
                    Words.CONTENT_URI, query, request, requestArguments, null)
            addWordsLocked(cursor)
        } catch (e: SQLiteException) {
            Log.e(TAG, "SQLiteException in the remote User dictionary process.", e)
        } finally {
            try {
                if (null != cursor) cursor.close()
            } catch (e: SQLiteException) {
                Log.e(TAG, "SQLiteException in the remote User dictionary process.", e)
            }
        }
    }

    private fun addWordsLocked(cursor: Cursor?) {
        val hasShortcutColumn = true
        if (cursor == null) return
        if (cursor.moveToFirst()) {
            val indexWord = cursor.getColumnIndex(Words.WORD)
            val indexShortcut = if (hasShortcutColumn) cursor.getColumnIndex(Words.SHORTCUT) else 0
            val indexFrequency = cursor.getColumnIndex(Words.FREQUENCY)
            while (!cursor.isAfterLast()) {
                val word = cursor.getString(indexWord)
                val shortcut: String? = if (hasShortcutColumn) cursor.getString(indexShortcut) else null
                val frequency = cursor.getInt(indexFrequency)
                val adjustedFrequency = scaleFrequencyFromDefaultToLatinIme(frequency)
                // Safeguard against adding really long words.
                if (word.length <= MAX_WORD_LENGTH) {
                    runGCIfRequiredLocked(true /* mindsBlockByGC */)
                    addUnigramLocked(word, adjustedFrequency, null /* shortcutTarget */,
                            0 /* shortcutFreq */, false /* isNotAWord */,
                            false /* isPossiblyOffensive */,
                            BinaryDictionary.NOT_A_VALID_TIMESTAMP)
                    if (null != shortcut && shortcut.length <= MAX_WORD_LENGTH) {
                        runGCIfRequiredLocked(true /* mindsBlockByGC */)
                        addUnigramLocked(shortcut, adjustedFrequency, word,
                                USER_DICT_SHORTCUT_FREQUENCY, true /* isNotAWord */,
                                false /* isPossiblyOffensive */,
                                BinaryDictionary.NOT_A_VALID_TIMESTAMP)
                    }
                }
                cursor.moveToNext()
            }
        }
    }

    companion object {
        private val TAG = ExpandableBinaryDictionary::class.java.simpleName

        // The user dictionary provider uses an empty string to mean "all languages".
        private const val USER_DICTIONARY_ALL_LANGUAGES = ""
        private const val HISTORICAL_DEFAULT_USER_DICTIONARY_FREQUENCY = 250
        private const val LATINIME_DEFAULT_USER_DICTIONARY_FREQUENCY = 160
        // Shortcut frequency is 0~15, with 15 = whitelist. We don't want user dictionary entries
        // to auto-correct, so we set this to the highest frequency that won't, i.e. 14.
        private const val USER_DICT_SHORTCUT_FREQUENCY = 14

        private val PROJECTION_QUERY_WITH_SHORTCUT = arrayOf(
                Words.WORD,
                Words.SHORTCUT,
                Words.FREQUENCY,
        )
        private val PROJECTION_QUERY_WITHOUT_SHORTCUT = arrayOf(
                Words.WORD,
                Words.FREQUENCY,
        )

        private const val NAME = "userunigram"

        @JvmStatic
        fun getDictionary(
                context: Context, locale: Locale, dictFile: File?, dictNamePrefix: String): UserBinaryDictionary {
            return UserBinaryDictionary(context, locale, false, dictFile, dictNamePrefix + NAME)
        }

        private fun scaleFrequencyFromDefaultToLatinIme(defaultFrequency: Int): Int {
            // The default frequency for the user dictionary is 250 for historical reasons.
            // Latin IME considers a good value for the default user dictionary frequency
            // is about 160 considering the scale we use. So we are scaling down the values.
            if (defaultFrequency > Int.MAX_VALUE / LATINIME_DEFAULT_USER_DICTIONARY_FREQUENCY) {
                return (defaultFrequency / HISTORICAL_DEFAULT_USER_DICTIONARY_FREQUENCY) *
                        LATINIME_DEFAULT_USER_DICTIONARY_FREQUENCY
            }
            return (defaultFrequency * LATINIME_DEFAULT_USER_DICTIONARY_FREQUENCY) /
                    HISTORICAL_DEFAULT_USER_DICTIONARY_FREQUENCY
        }
    }
}
