/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.spellcheck

import android.content.res.Resources
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import app.caret.latin.common.Constants
import app.caret.latin.settings.SpacingAndPunctuations
import app.caret.latin.utils.runInLocale
import java.util.ArrayList
import java.util.Locale

/**
 * This code is mostly lifted directly from android.service.textservice.SpellCheckerService in
 * the framework; maybe that should be protected instead, so that implementers don't have to
 * rewrite everything for any small change.
 */
class SentenceLevelAdapter(res: Resources, locale: Locale) {
    private class EmptySentenceSuggestionsInfosInitializationHolder {
        companion object {
            @JvmField
            val EMPTY_SENTENCE_SUGGESTIONS_INFOS: Array<SentenceSuggestionsInfo?> =
                    arrayOfNulls<SentenceSuggestionsInfo>(0)
        }
    }

    /**
     * Container for split TextInfo parameters
     */
    class SentenceWordItem(ti: TextInfo, start: Int, end: Int) {
        @JvmField
        val mTextInfo: TextInfo
        @JvmField
        val mStart: Int
        @JvmField
        val mLength: Int

        init {
            mTextInfo = ti
            mStart = start
            mLength = end - start
        }
    }

    /**
     * Container for originally queried TextInfo and parameters
     */
    class SentenceTextInfoParams(ti: TextInfo, items: ArrayList<SentenceWordItem>) {
        @JvmField
        val mOriginalTextInfo: TextInfo
        @JvmField
        val mItems: ArrayList<SentenceWordItem>
        @JvmField
        val mSize: Int

        init {
            mOriginalTextInfo = ti
            mItems = items
            mSize = items.size
        }
    }

    private class WordIterator(res: Resources, locale: Locale) {
        private val mSpacingAndPunctuations: SpacingAndPunctuations

        init {
            mSpacingAndPunctuations = runInLocale(res, locale) { r -> SpacingAndPunctuations(r, false) }
        }

        fun getEndOfWord(sequence: CharSequence, fromIndex: Int): Int {
            val length = sequence.length
            var index = if (fromIndex < 0) 0 else Character.offsetByCodePoints(sequence, fromIndex, 1)
            while (index < length) {
                val codePoint = Character.codePointAt(sequence, index)
                if (mSpacingAndPunctuations.isWordSeparator(codePoint)) {
                    // If it's a period, we want to stop here only if it's followed by another
                    // word separator. In all other cases we stop here.
                    if (Constants.CODE_PERIOD == codePoint) {
                        val indexOfNextCodePoint =
                                index + Character.charCount(Constants.CODE_PERIOD)
                        if (indexOfNextCodePoint < length
                                && mSpacingAndPunctuations.isWordSeparator(
                                        Character.codePointAt(sequence, indexOfNextCodePoint))) {
                            return index
                        }
                    } else {
                        return index
                    }
                }
                index += Character.charCount(codePoint)
            }
            return index
        }

        fun getBeginningOfNextWord(sequence: CharSequence, fromIndex: Int): Int {
            val length = sequence.length
            if (fromIndex >= length) {
                return -1
            }
            var index = if (fromIndex < 0) 0 else Character.offsetByCodePoints(sequence, fromIndex, 1)
            while (index < length) {
                val codePoint = Character.codePointAt(sequence, index)
                if (!mSpacingAndPunctuations.isWordSeparator(codePoint)) {
                    return index
                }
                index += Character.charCount(codePoint)
            }
            return -1
        }
    }

    private val mWordIterator: WordIterator

    init {
        mWordIterator = WordIterator(res, locale)
    }

    fun getSplitWords(originalTextInfo: TextInfo?): SentenceTextInfoParams {
        val wordIterator = mWordIterator
        val originalText = originalTextInfo!!.charSequence
        val cookie = originalTextInfo.cookie
        val start = -1
        val end = originalText.length
        val wordItems = ArrayList<SentenceWordItem>()
        var wordStart = wordIterator.getBeginningOfNextWord(originalText, start)
        var wordEnd = wordIterator.getEndOfWord(originalText, wordStart)
        while (wordStart <= end && wordEnd != -1 && wordStart != -1) {
            if (wordEnd >= start && wordEnd > wordStart) {
                val ti = TextInfo(originalText, wordStart,
                        wordEnd, cookie, originalText.subSequence(wordStart, wordEnd).hashCode())
                wordItems.add(SentenceWordItem(ti, wordStart, wordEnd))
            }
            wordStart = wordIterator.getBeginningOfNextWord(originalText, wordEnd)
            if (wordStart == -1) {
                break
            }
            wordEnd = wordIterator.getEndOfWord(originalText, wordStart)
        }
        return SentenceTextInfoParams(originalTextInfo, wordItems)
    }

    companion object {
        private val EMPTY_SUGGESTIONS_INFO = SuggestionsInfo(0, null)

        @JvmStatic
        fun getEmptySentenceSuggestionsInfo(): Array<SentenceSuggestionsInfo?> {
            return EmptySentenceSuggestionsInfosInitializationHolder.EMPTY_SENTENCE_SUGGESTIONS_INFOS
        }

        @JvmStatic
        fun reconstructSuggestions(
                originalTextInfoParams: SentenceTextInfoParams?, results: Array<SuggestionsInfo?>?): SentenceSuggestionsInfo? {
            if (results == null || results.size == 0) {
                return null
            }
            if (originalTextInfoParams == null) {
                return null
            }
            val originalCookie = originalTextInfoParams.mOriginalTextInfo.cookie
            val originalSequence =
                    originalTextInfoParams.mOriginalTextInfo.sequence

            val querySize = originalTextInfoParams.mSize
            val offsets = IntArray(querySize)
            val lengths = IntArray(querySize)
            val reconstructedSuggestions = arrayOfNulls<SuggestionsInfo>(querySize)
            for (i in 0 until querySize) {
                val item = originalTextInfoParams.mItems[i]
                var result: SuggestionsInfo? = null
                for (cur in results) {
                    if (cur != null && cur.sequence == item.mTextInfo.sequence) {
                        result = cur
                        result.setCookieAndSequence(originalCookie, originalSequence)
                        break
                    }
                }
                offsets[i] = item.mStart
                lengths[i] = item.mLength
                reconstructedSuggestions[i] = if (result != null) result else EMPTY_SUGGESTIONS_INFO
            }
            return SentenceSuggestionsInfo(reconstructedSuggestions, offsets, lengths)
        }
    }
}
