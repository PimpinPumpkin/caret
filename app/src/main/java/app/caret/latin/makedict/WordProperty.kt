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
    @JvmField val mWord: String
    @JvmField val mProbabilityInfo: ProbabilityInfo
    @JvmField val mShortcutTargets: ArrayList<WeightedString>
    @JvmField val mNgrams: ArrayList<NgramProperty>?
    // TODO: Support mIsBeginningOfSentence.
    @JvmField val mIsBeginningOfSentence: Boolean
    @JvmField val mIsNotAWord: Boolean
    @JvmField val mIsPossiblyOffensive: Boolean
    @JvmField val mHasShortcuts: Boolean
    @JvmField val hasNgrams: Boolean

    private var mHashCode = 0

    // TODO: Support n-gram.
    constructor(word: String, probabilityInfo: ProbabilityInfo,
            shortcutTargets: ArrayList<WeightedString>?,
            bigrams: ArrayList<WeightedString>?,
            isNotAWord: Boolean, isPossiblyOffensive: Boolean) {
        mWord = word
        mProbabilityInfo = probabilityInfo
        mShortcutTargets = shortcutTargets!!
        if (null == bigrams) {
            mNgrams = null
        } else {
            val ngrams = ArrayList<NgramProperty>()
            mNgrams = ngrams
            val ngramContext = NgramContext(WordInfo(mWord))
            for (bigramTarget in bigrams) {
                ngrams.add(NgramProperty(bigramTarget, ngramContext))
            }
        }
        mIsBeginningOfSentence = false
        mIsNotAWord = isNotAWord
        mIsPossiblyOffensive = isPossiblyOffensive
        hasNgrams = bigrams != null && !bigrams.isEmpty()
        mHasShortcuts = shortcutTargets != null && !shortcutTargets.isEmpty()
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
        mWord = StringUtils.getStringFromNullTerminatedCodePointArray(codePoints)
        mProbabilityInfo = createProbabilityInfoFromArray(probabilityInfo)
        mShortcutTargets = ArrayList()
        val ngrams = ArrayList<NgramProperty>()
        mIsBeginningOfSentence = isBeginningOfSentence
        mIsNotAWord = isNotAWord
        mIsPossiblyOffensive = isPossiblyOffensive
        mHasShortcuts = hasShortcuts
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
        mNgrams = if (ngrams.isEmpty()) null else ngrams

        val shortcutTargetCount = shortcutTargets.size
        for (i in 0 until shortcutTargetCount) {
            val shortcutTargetString =
                    StringUtils.getStringFromNullTerminatedCodePointArray(shortcutTargets[i])
            mShortcutTargets.add(
                    WeightedString(shortcutTargetString, shortcutProbabilities[i]))
        }
    }

    // TODO: Remove
    fun getBigrams(): ArrayList<WeightedString>? {
        if (null == mNgrams) {
            return null
        }
        val bigrams = ArrayList<WeightedString>()
        for (ngram in mNgrams) {
            if (ngram.mNgramContext.prevWordCount == 1) {
                bigrams.add(ngram.mTargetWord)
            }
        }
        return bigrams
    }

    fun getProbability(): Int {
        return mProbabilityInfo.mProbability
    }

    /**
     * Three-way comparison.
     * <p>
     * A Word x is greater than a word y if x has a higher frequency. If they have the same
     * frequency, they are sorted in lexicographic order.
     */
    override fun compareTo(other: WordProperty): Int {
        if (getProbability() < other.getProbability()) return 1
        if (getProbability() > other.getProbability()) return -1
        return mWord.compareTo(other.mWord)
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
        return mProbabilityInfo == w.mProbabilityInfo && mWord == w.mWord
                && mShortcutTargets == w.mShortcutTargets && equals(mNgrams, w.mNgrams)
                && mIsNotAWord == w.mIsNotAWord && mIsPossiblyOffensive == w.mIsPossiblyOffensive
                && hasNgrams == w.hasNgrams && mHasShortcuts && w.hasNgrams
    }

    override fun hashCode(): Int {
        if (mHashCode == 0) {
            mHashCode = computeHashCode(this)
        }
        return mHashCode
    }

    fun isValid(): Boolean {
        return getProbability() != Dictionary.NOT_A_PROBABILITY
    }

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
                    word.mWord,
                    word.mProbabilityInfo,
                    word.mShortcutTargets,
                    word.mNgrams,
                    word.mIsNotAWord,
                    word.mIsPossiblyOffensive
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
