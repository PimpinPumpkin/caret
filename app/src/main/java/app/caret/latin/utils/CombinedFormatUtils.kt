/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import app.caret.latin.makedict.DictionaryHeader
import app.caret.latin.makedict.ProbabilityInfo
import app.caret.latin.makedict.WordProperty

import java.util.HashMap

class CombinedFormatUtils {
    companion object {
        const val DICTIONARY_TAG = "dictionary"
        const val BIGRAM_TAG = "bigram"
        const val NGRAM_TAG = "ngram"
        const val NGRAM_PREV_WORD_TAG = "prev_word"
        const val SHORTCUT_TAG = "shortcut"
        const val PROBABILITY_TAG = "f"
        const val HISTORICAL_INFO_TAG = "historicalInfo"
        const val HISTORICAL_INFO_SEPARATOR = ":"
        const val WORD_TAG = "word"
        const val BEGINNING_OF_SENTENCE_TAG = "beginning_of_sentence"
        const val NOT_A_WORD_TAG = "not_a_word"
        const val POSSIBLY_OFFENSIVE_TAG = "possibly_offensive"
        const val TRUE_VALUE = "true"

        @JvmStatic
        fun formatAttributeMap(attributeMap: HashMap<String, String>): String {
            val builder = StringBuilder()
            builder.append(DICTIONARY_TAG + "=")
            if (attributeMap.containsKey(DictionaryHeader.DICTIONARY_ID_KEY)) {
                builder.append(attributeMap.get(DictionaryHeader.DICTIONARY_ID_KEY))
            }
            for (key in attributeMap.keys) {
                if (key == DictionaryHeader.DICTIONARY_ID_KEY) {
                    continue
                }
                val value = attributeMap.get(key)
                builder.append("," + key + "=" + value)
            }
            builder.append("\n")
            return builder.toString()
        }

        @JvmStatic
        fun formatWordProperty(wordProperty: WordProperty): String {
            val builder = StringBuilder()
            builder.append(" " + WORD_TAG + "=" + wordProperty.mWord)
            builder.append(",")
            builder.append(formatProbabilityInfo(wordProperty.mProbabilityInfo))
            if (wordProperty.mIsBeginningOfSentence) {
                builder.append("," + BEGINNING_OF_SENTENCE_TAG + "=" + TRUE_VALUE)
            }
            if (wordProperty.mIsNotAWord) {
                builder.append("," + NOT_A_WORD_TAG + "=" + TRUE_VALUE)
            }
            if (wordProperty.mIsPossiblyOffensive) {
                builder.append("," + POSSIBLY_OFFENSIVE_TAG + "=" + TRUE_VALUE)
            }
            builder.append("\n")
            if (wordProperty.mHasShortcuts) {
                for (shortcutTarget in wordProperty.mShortcutTargets) {
                    builder.append("  " + SHORTCUT_TAG + "=" + shortcutTarget.mWord)
                    builder.append(",")
                    builder.append(formatProbabilityInfo(shortcutTarget.mProbabilityInfo))
                    builder.append("\n")
                }
            }
            if (wordProperty.hasNgrams) {
                for (ngramProperty in wordProperty.mNgrams!!) {
                    builder.append(" " + NGRAM_TAG + "=" + ngramProperty.mTargetWord.mWord)
                    builder.append(",")
                    builder.append(formatProbabilityInfo(ngramProperty.mTargetWord.mProbabilityInfo))
                    builder.append("\n")
                    var i = 0
                    while (i < ngramProperty.mNgramContext.getPrevWordCount()) {
                        builder.append("  " + NGRAM_PREV_WORD_TAG + "[" + i + "]="
                                + ngramProperty.mNgramContext.getNthPrevWord(i + 1))
                        if (ngramProperty.mNgramContext.isNthPrevWordBeginningOfSentence(i + 1)) {
                            builder.append("," + BEGINNING_OF_SENTENCE_TAG + "=true")
                        }
                        builder.append("\n")
                        i++
                    }
                }
            }
            return builder.toString()
        }

        @JvmStatic
        fun formatProbabilityInfo(probabilityInfo: ProbabilityInfo): String {
            val builder = StringBuilder()
            builder.append(PROBABILITY_TAG + "=" + probabilityInfo.mProbability)
            if (probabilityInfo.hasHistoricalInfo()) {
                builder.append(",")
                builder.append(HISTORICAL_INFO_TAG + "=")
                builder.append(probabilityInfo.mTimestamp)
                builder.append(HISTORICAL_INFO_SEPARATOR)
                builder.append(probabilityInfo.mLevel)
                builder.append(HISTORICAL_INFO_SEPARATOR)
                builder.append(probabilityInfo.mCount)
            }
            return builder.toString()
        }

        @JvmStatic
        fun isLiteralTrue(value: String?): Boolean {
            return TRUE_VALUE.equals(value, ignoreCase = true)
        }
    }
}
