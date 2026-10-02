/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.settings

import android.content.res.Resources
import app.caret.compat.locale
import app.caret.latin.R
import app.caret.latin.common.Constants
import app.caret.latin.common.StringUtils
import app.caret.latin.common.toSortedCodepointArrays
import java.util.Arrays
import java.util.Locale

class SpacingAndPunctuations {
    private val sortedSymbolsPrecededBySpace: IntArray
    private val sortedSymbolsFollowedBySpace: IntArray
    private val sortedSymbolsClusteringTogether: IntArray
    private val sortedWordConnectors: IntArray
    private val sortedSometimesWordConnectors: IntArray // maybe rename... they are some sort of glue for words containing separators
    @JvmField
    val mSortedWordSeparators: IntArray
    @JvmField
    val mPairSymbols: List<IntArray>
    private val sentenceSeparator: Int
    private val abbreviationMarker: Int
    private val sortedSentenceTerminators: IntArray
    @JvmField
    val mSentenceSeparatorAndSpace: String
    @JvmField
    val mCurrentLanguageHasSpaces: Boolean
    @JvmField
    val mUsesAmericanTypography: Boolean
    @JvmField
    val mUsesGermanRules: Boolean

    constructor(res: Resources, urlDetection: Boolean) {
        // To be able to binary search the code point. See {@link #isUsuallyPrecededBySpace(int)}.
        sortedSymbolsPrecededBySpace = StringUtils.toSortedCodePointArray(res.getString(R.string.symbols_preceded_by_space))
        // To be able to binary search the code point. See {@link #isUsuallyFollowedBySpace(int)}.
        sortedSymbolsFollowedBySpace = StringUtils.toSortedCodePointArray(res.getString(R.string.symbols_followed_by_space))
        sortedSymbolsClusteringTogether = StringUtils.toSortedCodePointArray(res.getString(R.string.symbols_clustering_together))
        // To be able to binary search the code point. See {@link #isWordConnector(int)}.
        sortedWordConnectors = StringUtils.toSortedCodePointArray(res.getString(R.string.symbols_word_connectors))
        mSortedWordSeparators = StringUtils.toSortedCodePointArray(res.getString(R.string.symbols_word_separators))
        mPairSymbols = toSortedCodepointArrays(res.getString(R.string.pair_symbols))
        sortedSentenceTerminators = StringUtils.toSortedCodePointArray(res.getString(R.string.symbols_sentence_terminators))
        sentenceSeparator = res.getInteger(R.integer.sentence_separator)
        abbreviationMarker = res.getInteger(R.integer.abbreviation_marker)
        mSentenceSeparatorAndSpace = String(intArrayOf(
                sentenceSeparator, Constants.CODE_SPACE), 0, 2)
        mCurrentLanguageHasSpaces = res.getBoolean(R.bool.current_language_has_spaces)
        // make it empty if language doesn't have spaces, to avoid weird glitches
        sortedSometimesWordConnectors = if (urlDetection && mCurrentLanguageHasSpaces) StringUtils.toSortedCodePointArray(res.getString(R.string.symbols_sometimes_word_connectors)) else IntArray(0)
        val locale = res.configuration.locale()
        // Heuristic: we use American Typography rules because it's the most common rules for all
        // English variants. German rules (not "German typography") also have small gotchas.
        mUsesAmericanTypography = Locale.ENGLISH.language == locale.language
        mUsesGermanRules = Locale.GERMAN.language == locale.language
    }

    fun isWordSeparator(code: Int): Boolean {
        return Arrays.binarySearch(mSortedWordSeparators, code) >= 0
    }

    fun getSecondInSymbolPair(code: Int): Int {
        return app.caret.latin.common.getSecondInSymbolPair(mPairSymbols, code)
    }

    fun isWordConnector(code: Int): Boolean {
        return Arrays.binarySearch(sortedWordConnectors, code) >= 0
    }

    fun isSometimesWordConnector(code: Int): Boolean {
        return Arrays.binarySearch(sortedSometimesWordConnectors, code) >= 0
    }

    fun containsSometimesWordConnector(word: CharSequence): Boolean {
        val s = if (word is String) word else word.toString()
        val length = s.length
        var offset = 0
        while (offset < length) {
            val cp = s.codePointAt(offset)
            if (isSometimesWordConnector(cp)) return true
            offset += Character.charCount(cp)
        }
        return false
    }

    fun isWordCodePoint(code: Int): Boolean {
        return Character.isLetter(code) || isWordConnector(code) || Character.COMBINING_SPACING_MARK.toInt() == Character.getType(code)
    }

    fun isUsuallyPrecededBySpace(code: Int): Boolean {
        return Arrays.binarySearch(sortedSymbolsPrecededBySpace, code) >= 0
    }

    fun isUsuallyFollowedBySpace(code: Int): Boolean {
        return Arrays.binarySearch(sortedSymbolsFollowedBySpace, code) >= 0
    }

    fun isClusteringSymbol(code: Int): Boolean {
        return Arrays.binarySearch(sortedSymbolsClusteringTogether, code) >= 0
    }

    fun isSentenceTerminator(code: Int): Boolean {
        return Arrays.binarySearch(sortedSentenceTerminators, code) >= 0
    }

    fun isAbbreviationMarker(code: Int): Boolean {
        return code == abbreviationMarker
    }

    fun isSentenceSeparator(code: Int): Boolean {
        return code == sentenceSeparator
    }

    fun dump(): String {
        return "mSortedSymbolsPrecededBySpace = " +
                "" + Arrays.toString(sortedSymbolsPrecededBySpace) +
                "\n   mSortedSymbolsFollowedBySpace = " +
                "" + Arrays.toString(sortedSymbolsFollowedBySpace) +
                "\n   mSortedWordConnectors = " +
                "" + Arrays.toString(sortedWordConnectors) +
                "\n   mSortedWordSeparators = " +
                "" + Arrays.toString(mSortedWordSeparators) +
                "\n   mSentenceSeparator = " +
                "" + sentenceSeparator +
                "\n   mSentenceSeparatorAndSpace = " +
                "" + mSentenceSeparatorAndSpace +
                "\n   mCurrentLanguageHasSpaces = " +
                "" + mCurrentLanguageHasSpaces +
                "\n   mUsesAmericanTypography = " +
                "" + mUsesAmericanTypography +
                "\n   mUsesGermanRules = " +
                "" + mUsesGermanRules
    }
}
