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
        @JvmField val word: CharSequence?
        // TODO: Have sentence separator.
        // Whether the current context is beginning of sentence or not. This is true when composing
        // at the beginning of an input field or composing a word after a sentence separator.
        @JvmField val isBeginningOfSentence: Boolean

        // Beginning of sentence.
        private constructor() {
            word = ""
            isBeginningOfSentence = true
        }

        constructor(word: CharSequence?) {
            this.word = word
            isBeginningOfSentence = false
        }

        val isValid: Boolean
            get() = word != null

        override fun hashCode(): Int {
            return Arrays.hashCode(arrayOf<Any?>(word, isBeginningOfSentence))
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) return true
            if (o !is WordInfo) return false
            val wordInfo: WordInfo = o
            if (word == null || wordInfo.word == null) {
                return word === wordInfo.word
                        && isBeginningOfSentence == wordInfo.isBeginningOfSentence
            }
            return TextUtils.equals(word, wordInfo.word)
                    && isBeginningOfSentence == wordInfo.isBeginningOfSentence
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
    private val prevWordsInfo: Array<WordInfo?>
    private val prevWordsCount: Int

    private val maxPrevWordCount: Int

    // Construct from the previous word information.
    // (assigns the fields itself instead of delegating: spreading the array into the other
    // constructor would copy it, and the passed array must be used as is)
    @Suppress("UNCHECKED_CAST")
    constructor(vararg prevWordsInfo: WordInfo?) {
        this.prevWordsInfo = prevWordsInfo as Array<WordInfo?>
        prevWordsCount = prevWordsInfo.size
        maxPrevWordCount = DecoderSpecificConstants.MAX_PREV_WORD_COUNT_FOR_N_GRAM
    }

    @Suppress("UNCHECKED_CAST")
    constructor(maxPrevWordCount: Int, vararg prevWordsInfo: WordInfo?) {
        this.prevWordsInfo = prevWordsInfo as Array<WordInfo?>
        prevWordsCount = prevWordsInfo.size
        this.maxPrevWordCount = maxPrevWordCount
    }

    fun changeWordIfAfterBeginningOfSentence(from: String?, to: String?): Boolean {
        var beginning = false
        for (i in prevWordsCount - 1 downTo 0) {
            val info = prevWordsInfo[i]!!
            if (beginning && TextUtils.equals(info.word, from)) {
                prevWordsInfo[i] = WordInfo(to)
                return true
            }
            beginning = info.isBeginningOfSentence
        }
        return false
    }

    /**
     * Create next prevWordsInfo using current prevWordsInfo.
     */
    fun getNextNgramContext(wordInfo: WordInfo?): NgramContext {
        val nextPrevWordCount = Math.min(maxPrevWordCount, prevWordsCount + 1)
        val nextPrevWordsInfo = arrayOfNulls<WordInfo>(nextPrevWordCount)
        nextPrevWordsInfo[0] = wordInfo
        System.arraycopy(prevWordsInfo, 0, nextPrevWordsInfo, 1, nextPrevWordCount - 1)
        return NgramContext(maxPrevWordCount, *nextPrevWordsInfo)
    }


    /**
     * Extracts the previous words context.
     *
     * @return a String with the previous words separated by white space.
     */
    fun extractPrevWordsContext(): String {
        val terms = ArrayList<String>()
        for (i in prevWordsInfo.size - 1 downTo 0) {
            val wordInfo = prevWordsInfo[i]
            val word = wordInfo?.word
            if (wordInfo != null && word != null) {
                if (wordInfo.isBeginningOfSentence) {
                    terms.add(BEGINNING_OF_SENTENCE_TAG)
                } else {
                    val term = word.toString()
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
        for (i in prevWordsInfo.size - 1 downTo 0) {
            val wordInfo = prevWordsInfo[i]
            val word = wordInfo?.word
            if (wordInfo != null && word != null) {
                if (wordInfo.isBeginningOfSentence) {
                    prevTermList.add(BEGINNING_OF_SENTENCE_TAG)
                } else {
                    val term = word.toString()
                    if (!term.isEmpty()) {
                        prevTermList.add(term)
                    }
                }
            }
        }
        return prevTermList.toTypedArray()
    }

    val isValid: Boolean
        get() = prevWordsCount > 0 && prevWordsInfo[0]!!.isValid

    val isBeginningOfSentenceContext: Boolean
        get() = prevWordsCount > 0 && prevWordsInfo[0]!!.isBeginningOfSentence

    // n is 1-indexed.
    // TODO: Remove
    fun getNthPrevWord(n: Int): CharSequence? {
        if (n <= 0 || n > prevWordsCount) {
            return null
        }
        return prevWordsInfo[n - 1]!!.word
    }

    // n is 1-indexed.
    fun isNthPrevWordBeginningOfSentence(n: Int): Boolean {
        if (n <= 0 || n > prevWordsCount) {
            return false
        }
        return prevWordsInfo[n - 1]!!.isBeginningOfSentence
    }

    fun outputToArray(codePointArrays: Array<IntArray?>,
            isBeginningOfSentenceArray: BooleanArray) {
        for (i in 0 until prevWordsCount) {
            val wordInfo = prevWordsInfo[i]
            val word = wordInfo?.word
            if (wordInfo == null || word == null) {
                codePointArrays[i] = IntArray(0)
                isBeginningOfSentenceArray[i] = false
                continue
            }
            codePointArrays[i] = StringUtils.toCodePointArray(word)
            isBeginningOfSentenceArray[i] = wordInfo.isBeginningOfSentence
        }
    }

    val prevWordCount: Int
        get() = prevWordsCount

    override fun hashCode(): Int {
        var hashValue = 0
        for (wordInfo in prevWordsInfo) {
            if (wordInfo == null || WordInfo.EMPTY_WORD_INFO != wordInfo) {
                break
            }
            hashValue = hashValue xor wordInfo.hashCode()
        }
        return hashValue
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is NgramContext) return false
        val other: NgramContext = o

        val minLength = Math.min(prevWordsCount, other.prevWordsCount)
        for (i in 0 until minLength) {
            if (prevWordsInfo[i]!! != other.prevWordsInfo[i]) {
                return false
            }
        }
        val longerWordsInfo: Array<WordInfo?>
        val longerWordsInfoCount: Int
        if (prevWordsCount > other.prevWordsCount) {
            longerWordsInfo = prevWordsInfo
            longerWordsInfoCount = prevWordsCount
        } else {
            longerWordsInfo = other.prevWordsInfo
            longerWordsInfoCount = other.prevWordsCount
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
            val wordInfo = prevWordsInfo[i]
            builder.append("PrevWord[")
            builder.append(i)
            builder.append("]: ")
            if (wordInfo == null) {
                builder.append("null. ")
                continue
            }
            if (!wordInfo.isValid) {
                builder.append("Empty. ")
                continue
            }
            builder.append(wordInfo.word)
            builder.append(", isBeginningOfSentence: ")
            builder.append(wordInfo.isBeginningOfSentence)
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
