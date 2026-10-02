/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.text.TextUtils

import app.caret.latin.common.StringUtils
import app.caret.latin.define.DecoderSpecificConstants

import java.util.ArrayList
import java.util.Arrays

/**
 * Class to represent information of previous words. This class is used to add n-gram entries
 * into binary dictionaries, to get predictions, and to get suggestions.
 */
class NgramContext {
    /**
     * Word information used to represent previous words information.
     */
    class WordInfo {
        // This is an empty char sequence when mIsBeginningOfSentence is true.
        @JvmField val mWord: CharSequence?
        // TODO: Have sentence separator.
        // Whether the current context is beginning of sentence or not. This is true when composing
        // at the beginning of an input field or composing a word after a sentence separator.
        @JvmField val mIsBeginningOfSentence: Boolean

        // Beginning of sentence.
        private constructor() {
            mWord = ""
            mIsBeginningOfSentence = true
        }

        constructor(word: CharSequence?) {
            mWord = word
            mIsBeginningOfSentence = false
        }

        fun isValid(): Boolean {
            return mWord != null
        }

        override fun hashCode(): Int {
            return Arrays.hashCode(arrayOf<Any?>(mWord, mIsBeginningOfSentence))
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) return true
            if (o !is WordInfo) return false
            val wordInfo: WordInfo = o
            if (mWord == null || wordInfo.mWord == null) {
                return mWord === wordInfo.mWord
                        && mIsBeginningOfSentence == wordInfo.mIsBeginningOfSentence
            }
            return TextUtils.equals(mWord, wordInfo.mWord)
                    && mIsBeginningOfSentence == wordInfo.mIsBeginningOfSentence
        }

        companion object {
            @JvmField
            val EMPTY_WORD_INFO = WordInfo(null)
            @JvmField
            val BEGINNING_OF_SENTENCE_WORD_INFO = WordInfo()
        }
    }

    // The words immediately before the considered word. EMPTY_WORD_INFO element means we don't
    // have any context for that previous word including the "beginning of sentence context" - we
    // just don't know what to predict using the information. An example of that is after a comma.
    // For simplicity of implementation, elements may also be EMPTY_WORD_INFO transiently after the
    // WordComposer was reset and before starting a new composing word, but we should never be
    // calling getSuggetions* in this situation.
    private val mPrevWordsInfo: Array<WordInfo?>
    private val prevWordsCount: Int

    private val mMaxPrevWordCount: Int

    // Construct from the previous word information.
    // (assigns the fields itself instead of delegating: spreading the array into the other
    // constructor would copy it, and the passed array must be used as is)
    @Suppress("UNCHECKED_CAST")
    constructor(vararg prevWordsInfo: WordInfo?) {
        mPrevWordsInfo = prevWordsInfo as Array<WordInfo?>
        prevWordsCount = prevWordsInfo.size
        mMaxPrevWordCount = DecoderSpecificConstants.MAX_PREV_WORD_COUNT_FOR_N_GRAM
    }

    @Suppress("UNCHECKED_CAST")
    constructor(maxPrevWordCount: Int, vararg prevWordsInfo: WordInfo?) {
        mPrevWordsInfo = prevWordsInfo as Array<WordInfo?>
        prevWordsCount = prevWordsInfo.size
        mMaxPrevWordCount = maxPrevWordCount
    }

    fun changeWordIfAfterBeginningOfSentence(from: String?, to: String?): Boolean {
        var beginning = false
        for (i in prevWordsCount - 1 downTo 0) {
            val info = mPrevWordsInfo[i]!!
            if (beginning && TextUtils.equals(info.mWord, from)) {
                mPrevWordsInfo[i] = WordInfo(to)
                return true
            }
            beginning = info.mIsBeginningOfSentence
        }
        return false
    }

    /**
     * Create next prevWordsInfo using current prevWordsInfo.
     */
    fun getNextNgramContext(wordInfo: WordInfo?): NgramContext {
        val nextPrevWordCount = Math.min(mMaxPrevWordCount, prevWordsCount + 1)
        val prevWordsInfo = arrayOfNulls<WordInfo>(nextPrevWordCount)
        prevWordsInfo[0] = wordInfo
        System.arraycopy(mPrevWordsInfo, 0, prevWordsInfo, 1, nextPrevWordCount - 1)
        return NgramContext(mMaxPrevWordCount, *prevWordsInfo)
    }


    /**
     * Extracts the previous words context.
     *
     * @return a String with the previous words separated by white space.
     */
    fun extractPrevWordsContext(): String {
        val terms = ArrayList<String>()
        for (i in mPrevWordsInfo.size - 1 downTo 0) {
            if (mPrevWordsInfo[i] != null && mPrevWordsInfo[i]!!.isValid()) {
                val wordInfo = mPrevWordsInfo[i]!!
                if (wordInfo.mIsBeginningOfSentence) {
                    terms.add(BEGINNING_OF_SENTENCE_TAG)
                } else {
                    val term = wordInfo.mWord!!.toString()
                    if (!term.isEmpty()) {
                        terms.add(term)
                    }
                }
            }
        }
        return TextUtils.join(CONTEXT_SEPARATOR, terms)
    }

    /**
     * Extracts the previous words context.
     *
     * @return a String array with the previous words.
     */
    fun extractPrevWordsContextArray(): Array<String> {
        val prevTermList = ArrayList<String>()
        for (i in mPrevWordsInfo.size - 1 downTo 0) {
            if (mPrevWordsInfo[i] != null && mPrevWordsInfo[i]!!.isValid()) {
                val wordInfo = mPrevWordsInfo[i]!!
                if (wordInfo.mIsBeginningOfSentence) {
                    prevTermList.add(BEGINNING_OF_SENTENCE_TAG)
                } else {
                    val term = wordInfo.mWord!!.toString()
                    if (!term.isEmpty()) {
                        prevTermList.add(term)
                    }
                }
            }
        }
        return prevTermList.toTypedArray()
    }

    fun isValid(): Boolean {
        return prevWordsCount > 0 && mPrevWordsInfo[0]!!.isValid()
    }

    fun isBeginningOfSentenceContext(): Boolean {
        return prevWordsCount > 0 && mPrevWordsInfo[0]!!.mIsBeginningOfSentence
    }

    // n is 1-indexed.
    // TODO: Remove
    fun getNthPrevWord(n: Int): CharSequence? {
        if (n <= 0 || n > prevWordsCount) {
            return null
        }
        return mPrevWordsInfo[n - 1]!!.mWord
    }

    // n is 1-indexed.
    fun isNthPrevWordBeginningOfSentence(n: Int): Boolean {
        if (n <= 0 || n > prevWordsCount) {
            return false
        }
        return mPrevWordsInfo[n - 1]!!.mIsBeginningOfSentence
    }

    fun outputToArray(codePointArrays: Array<IntArray?>,
            isBeginningOfSentenceArray: BooleanArray) {
        for (i in 0 until prevWordsCount) {
            val wordInfo = mPrevWordsInfo[i]
            if (wordInfo == null || !wordInfo.isValid()) {
                codePointArrays[i] = IntArray(0)
                isBeginningOfSentenceArray[i] = false
                continue
            }
            codePointArrays[i] = StringUtils.toCodePointArray(wordInfo.mWord!!)
            isBeginningOfSentenceArray[i] = wordInfo.mIsBeginningOfSentence
        }
    }

    fun getPrevWordCount(): Int {
        return prevWordsCount
    }

    override fun hashCode(): Int {
        var hashValue = 0
        for (wordInfo in mPrevWordsInfo) {
            if (WordInfo.EMPTY_WORD_INFO != wordInfo) {
                break
            }
            hashValue = hashValue xor wordInfo!!.hashCode()
        }
        return hashValue
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is NgramContext) return false
        val prevWordsInfo: NgramContext = o

        val minLength = Math.min(prevWordsCount, prevWordsInfo.prevWordsCount)
        for (i in 0 until minLength) {
            if (mPrevWordsInfo[i]!! != prevWordsInfo.mPrevWordsInfo[i]) {
                return false
            }
        }
        val longerWordsInfo: Array<WordInfo?>
        val longerWordsInfoCount: Int
        if (prevWordsCount > prevWordsInfo.prevWordsCount) {
            longerWordsInfo = mPrevWordsInfo
            longerWordsInfoCount = prevWordsCount
        } else {
            longerWordsInfo = prevWordsInfo.mPrevWordsInfo
            longerWordsInfoCount = prevWordsInfo.prevWordsCount
        }
        for (i in minLength until longerWordsInfoCount) {
            if (longerWordsInfo[i] != null
                    && WordInfo.EMPTY_WORD_INFO != longerWordsInfo[i]) {
                return false
            }
        }
        return true
    }

    override fun toString(): String {
        val builder = StringBuilder()
        for (i in 0 until prevWordsCount) {
            val wordInfo = mPrevWordsInfo[i]
            builder.append("PrevWord[")
            builder.append(i)
            builder.append("]: ")
            if (wordInfo == null) {
                builder.append("null. ")
                continue
            }
            if (!wordInfo.isValid()) {
                builder.append("Empty. ")
                continue
            }
            builder.append(wordInfo.mWord)
            builder.append(", isBeginningOfSentence: ")
            builder.append(wordInfo.mIsBeginningOfSentence)
            builder.append(". ")
        }
        return builder.toString()
    }

    companion object {
        @JvmField
        val EMPTY_PREV_WORDS_INFO =
                NgramContext(WordInfo.EMPTY_WORD_INFO)
        @JvmField
        val BEGINNING_OF_SENTENCE =
                NgramContext(WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO)

        const val BEGINNING_OF_SENTENCE_TAG = "<S>"

        const val CONTEXT_SEPARATOR = " "

        @JvmStatic
        fun getEmptyPrevWordsContext(maxPrevWordCount: Int): NgramContext {
            return NgramContext(maxPrevWordCount, WordInfo.EMPTY_WORD_INFO)
        }
    }
}
