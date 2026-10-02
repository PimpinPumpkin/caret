/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.makedict

import app.caret.latin.common.LocaleUtils.constructLocale
import app.caret.latin.makedict.FormatSpec.DictionaryOptions
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Class representing dictionary header.
 */
class DictionaryHeader(
    @JvmField val dictionaryOptions: DictionaryOptions,
) {
    val localeString = dictionaryOptions.mAttributes[DICTIONARY_LOCALE_KEY]
        ?: throw UnsupportedFormatException("Cannot create a FileHeader without a locale")
    @JvmField
    val versionString = dictionaryOptions.mAttributes[DICTIONARY_VERSION_KEY]
        ?: throw UnsupportedFormatException(
            "Cannot create a FileHeader without a version"
        )
    @JvmField
    val idString = dictionaryOptions.mAttributes[DICTIONARY_ID_KEY]
        ?: throw UnsupportedFormatException("Cannot create a FileHeader without an ID")
    private val mDate = dictionaryOptions.mAttributes[DICTIONARY_DATE_KEY]?.toIntOrNull()

    val description: String?
        // Helper method to get the description
        get() = dictionaryOptions.mAttributes[DICTIONARY_DESCRIPTION_KEY]

    fun info(locale: Locale): String {
        val date = if (mDate == null) ""
            else DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(mDate * 1000L)) + "\n"
        return idString + "\n" + localeString.constructLocale().getDisplayName(locale) +
                "\nv" + versionString + "\n" + date + description
    }

    companion object {
        // Note that these are corresponding definitions in native code in latinime::HeaderPolicy
        // and latinime::HeaderReadWriteUtils.
        const val DICTIONARY_VERSION_KEY = "version"
        const val DICTIONARY_LOCALE_KEY = "locale"
        const val DICTIONARY_ID_KEY = "dictionary"
        const val DICTIONARY_DESCRIPTION_KEY = "description"
        const val DICTIONARY_DATE_KEY = "date"
        const val HAS_HISTORICAL_INFO_KEY = "HAS_HISTORICAL_INFO"
        const val USES_FORGETTING_CURVE_KEY = "USES_FORGETTING_CURVE"
        const val FORGETTING_CURVE_PROBABILITY_VALUES_TABLE_ID_KEY =
            "FORGETTING_CURVE_PROBABILITY_VALUES_TABLE_ID"
        const val MAX_UNIGRAM_COUNT_KEY = "MAX_UNIGRAM_ENTRY_COUNT"
        const val MAX_BIGRAM_COUNT_KEY = "MAX_BIGRAM_ENTRY_COUNT"
        const val MAX_TRIGRAM_COUNT_KEY = "MAX_TRIGRAM_ENTRY_COUNT"
        const val ATTRIBUTE_VALUE_TRUE = "1"
        const val CODE_POINT_TABLE_KEY = "codePointTable"
    }
}
