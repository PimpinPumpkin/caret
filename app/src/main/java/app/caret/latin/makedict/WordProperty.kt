/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.makedict

import com.android.inputmethod.latin.BinaryDictionary
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.NgramContext
import app.caret.latin.NgramContext.WordInfo
import app.caret.latin.common.StringUtils
import app.caret.latin.utils.CombinedFormatUtils

import java.util.ArrayList
import java.util.Arrays

/**
 * Utility class for a word with a probability.
 * <p>
 * This is chiefly used to iterate a dictionary.
 */
class WordProperty : Comparable<WordProperty> {
    @JvmField val word: String
    @JvmField val probabilityInfo: ProbabilityInfo
    @JvmField val shortcutTargets: ArrayList<WeightedString>
    @JvmField val ngrams: ArrayList<NgramProperty>?
    // TODO: Support isBeginningOfSentence.
    @JvmField val isBeginningOfSentence: Boolean
    @JvmField val isNotAWord: Boolean
    @JvmField val isPossiblyOffensive: Boolean
    @JvmField val hasShortcuts: Boolean
    @JvmField val hasNgrams: Boolean

    private var cachedHashCode = 0

    // TODO: Support n-gram.
    constructor(word: String, probabilityInfo: ProbabilityInfo,
            shortcutTargets: ArrayList<WeightedString>?,
            bigrams: ArrayList<WeightedString>?,
            isNotAWord: Boolean, isPossiblyOffensive: Boolean) {
        this.word = word
        this.probabilityInfo = probabilityInfo
        this.shortcutTargets = shortcutTargets!!
        if (null == bigrams) {
            this.ngrams = null
        } else {
            val ngrams = ArrayList<NgramProperty>()
            this.ngrams = ngrams
            val ngramContext = NgramContext(WordInfo(word))
            for (bigramTarget in bigrams) {
                ngrams.add(NgramProperty(bigramTarget, ngramContext))
            }
        }
        isBeginningOfSentence = false
        this.isNotAWord = isNotAWord
        this.isPossiblyOffensive = isPossiblyOffensive
        hasNgrams = bigrams != null && !bigrams.isEmpty()
        hasShortcuts = shortcutTargets != null && !shortcutTargets.isEmpty()
    }

    // Construct word property using information from native code.
    // This represents invalid word when the probability is BinaryDictionary.NOT_A_PROBABILITY.
    constructor(codePoints: IntArray, isNotAWord: Boolean,
            isPossiblyOffensive: Boolean, hasBigram: Boolean, hasShortcuts: Boolean,
            isBeginningOfSentence: Boolean, probabilityInfo: IntArray,
            ngramPrevWordsArray: ArrayList<Array<IntArray>>,
            ngramPrevWordIsBeginningOfSentenceArray: ArrayList<BooleanArray>,
            ngramTargets: ArrayList<IntArray>, ngramProbabilityInfo: ArrayList<IntArray>,
            shortcutTargets: ArrayList<IntArray>,
            shortcutProbabilities: ArrayList<Int>) {
        word = StringUtils.getStringFromNullTerminatedCodePointArray(codePoints)
        this.probabilityInfo = createProbabilityInfoFromArray(probabilityInfo)
        this.shortcutTargets = ArrayList()
        val ngrams = ArrayList<NgramProperty>()
        this.isBeginningOfSentence = isBeginningOfSentence
        this.isNotAWord = isNotAWord
        this.isPossiblyOffensive = isPossiblyOffensive
        this.hasShortcuts = hasShortcuts
        hasNgrams = hasBigram

        val relatedNgramCount = ngramTargets.size
        for (i in 0 until relatedNgramCount) {
            val ngramTargetString =
                    StringUtils.getStringFromNullTerminatedCodePointArray(ngramTargets[i])
            val ngramTarget = WeightedString(ngramTargetString,
                    createProbabilityInfoFromArray(ngramProbabilityInfo[i]))
            val prevWords = ngramPrevWordsArray[i]
            val isBeginningOfSentenceArray =
                    ngramPrevWordIsBeginningOfSentenceArray[i]
            val wordInfoArray = arrayOfNulls<WordInfo>(prevWords.size)
            for (j in prevWords.indices) {
                wordInfoArray[j] = if (isBeginningOfSentenceArray[j])
                    WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO
                else
                    WordInfo(StringUtils.getStringFromNullTerminatedCodePointArray(
                            prevWords[j]))
            }
            val ngramContext = NgramContext(*wordInfoArray)
            ngrams.add(NgramProperty(ngramTarget, ngramContext))
        }
        this.ngrams = if (ngrams.isEmpty()) null else ngrams

        val shortcutTargetCount = shortcutTargets.size
        for (i in 0 until shortcutTargetCount) {
            val shortcutTargetString =
                    StringUtils.getStringFromNullTerminatedCodePointArray(shortcutTargets[i])
            this.shortcutTargets.add(
                    WeightedString(shortcutTargetString, shortcutProbabilities[i]))
        }
    }

    // TODO: Remove
    fun getBigrams(): ArrayList<WeightedString>? {
        if (null == ngrams) {
            return null
        }
        val bigrams = ArrayList<WeightedString>()
        for (ngram in ngrams) {
            if (ngram.ngramContext.prevWordCount == 1) {
                bigrams.add(ngram.targetWord)
            }
        }
        return bigrams
    }

    val probability: Int
        get() = probabilityInfo.probability

    /**
     * Three-way comparison.
     * <p>
     * A Word x is greater than a word y if x has a higher frequency. If they have the same
     * frequency, they are sorted in lexicographic order.
     */
    override fun compareTo(other: WordProperty): Int {
        if (probability < other.probability) return 1
        if (probability > other.probability) return -1
        return word.compareTo(other.word)
    }

    /**
     * Equality test.
     * <p>
     * Words are equal if they have the same frequency, the same spellings, and the same
     * attributes.
     */
    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is WordProperty) return false
        val w = other
        return probabilityInfo == w.probabilityInfo && word == w.word
                && shortcutTargets == w.shortcutTargets && equals(ngrams, w.ngrams)
                && isNotAWord == w.isNotAWord && isPossiblyOffensive == w.isPossiblyOffensive
                && hasNgrams == w.hasNgrams && hasShortcuts && w.hasNgrams
    }

    override fun hashCode(): Int {
        if (cachedHashCode == 0) {
            cachedHashCode = computeHashCode(this)
        }
        return cachedHashCode
    }

    val isValid: Boolean
        get() = probability != Dictionary.NOT_A_PROBABILITY

    override fun toString(): String {
        return CombinedFormatUtils.formatWordProperty(this)
    }

    companion object {
        private fun createProbabilityInfoFromArray(probabilityInfo: IntArray): ProbabilityInfo {
            return ProbabilityInfo(
                    probabilityInfo[BinaryDictionary.FORMAT_WORD_PROPERTY_PROBABILITY_INDEX],
                    probabilityInfo[BinaryDictionary.FORMAT_WORD_PROPERTY_TIMESTAMP_INDEX],
                    probabilityInfo[BinaryDictionary.FORMAT_WORD_PROPERTY_LEVEL_INDEX],
                    probabilityInfo[BinaryDictionary.FORMAT_WORD_PROPERTY_COUNT_INDEX])
        }

        private fun computeHashCode(word: WordProperty): Int {
            return Arrays.hashCode(arrayOf<Any?>(
                    word.word,
                    word.probabilityInfo,
                    word.shortcutTargets,
                    word.ngrams,
                    word.isNotAWord,
                    word.isPossiblyOffensive
            ))
        }

        // TDOO: Have a utility method like java.util.Objects.equals.
        private fun <T> equals(a: ArrayList<T>?, b: ArrayList<T>?): Boolean {
            if (null == a) {
                return null == b
            }
            return a == b
        }
    }
}
