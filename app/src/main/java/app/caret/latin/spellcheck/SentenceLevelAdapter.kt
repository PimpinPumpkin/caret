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
        val textInfo: TextInfo
        @JvmField
        val start: Int
        @JvmField
        val length: Int

        init {
            textInfo = ti
            this.start = start
            length = end - start
        }
    }

    /**
     * Container for originally queried TextInfo and parameters
     */
    class SentenceTextInfoParams(ti: TextInfo, items: ArrayList<SentenceWordItem>) {
        @JvmField
        val originalTextInfo: TextInfo
        @JvmField
        val items: ArrayList<SentenceWordItem>
        @JvmField
        val size: Int

        init {
            originalTextInfo = ti
            this.items = items
            size = items.size
        }
    }

    private class WordIterator(res: Resources, locale: Locale) {
        private val spacingAndPunctuations: SpacingAndPunctuations

        init {
            spacingAndPunctuations = runInLocale(res, locale) { r -> SpacingAndPunctuations(r, false) }
        }

        fun getEndOfWord(sequence: CharSequence, fromIndex: Int): Int {
            val length = sequence.length
            var index = if (fromIndex < 0) 0 else Character.offsetByCodePoints(sequence, fromIndex, 1)
            while (index < length) {
                val codePoint = Character.codePointAt(sequence, index)
                if (spacingAndPunctuations.isWordSeparator(codePoint)) {
                    // If it's a period, we want to stop here only if it's followed by another
                    // word separator. In all other cases we stop here.
                    if (Constants.CODE_PERIOD == codePoint) {
                        val indexOfNextCodePoint =
                                index + Character.charCount(Constants.CODE_PERIOD)
                        if (indexOfNextCodePoint < length
                                && spacingAndPunctuations.isWordSeparator(
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
                if (!spacingAndPunctuations.isWordSeparator(codePoint)) {
                    return index
                }
                index += Character.charCount(codePoint)
            }
            return -1
        }
    }

    private val wordIterator: WordIterator

    init {
        wordIterator = WordIterator(res, locale)
    }

    fun getSplitWords(originalTextInfo: TextInfo?): SentenceTextInfoParams {
        val textInfo = originalTextInfo!!
        val originalText = textInfo.charSequence
        val cookie = textInfo.cookie
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
        return SentenceTextInfoParams(textInfo, wordItems)
    }

    companion object {
        private val EMPTY_SUGGESTIONS_INFO = SuggestionsInfo(0, null)

        @JvmStatic
        val emptySentenceSuggestionsInfo: Array<SentenceSuggestionsInfo?>
            get() = EmptySentenceSuggestionsInfosInitializationHolder.EMPTY_SENTENCE_SUGGESTIONS_INFOS

        @JvmStatic
        fun reconstructSuggestions(
                originalTextInfoParams: SentenceTextInfoParams?, results: Array<SuggestionsInfo?>?): SentenceSuggestionsInfo? {
            if (results == null || results.size == 0) {
                return null
            }
            if (originalTextInfoParams == null) {
                return null
            }
            val originalCookie = originalTextInfoParams.originalTextInfo.cookie
            val originalSequence =
                    originalTextInfoParams.originalTextInfo.sequence

            val querySize = originalTextInfoParams.size
            val offsets = IntArray(querySize)
            val lengths = IntArray(querySize)
            val reconstructedSuggestions = arrayOfNulls<SuggestionsInfo>(querySize)
            for (i in 0 until querySize) {
                val item = originalTextInfoParams.items[i]
                var result: SuggestionsInfo? = null
                for (cur in results) {
                    if (cur != null && cur.sequence == item.textInfo.sequence) {
                        result = cur
                        result.setCookieAndSequence(originalCookie, originalSequence)
                        break
                    }
                }
                offsets[i] = item.start
                lengths[i] = item.length
                reconstructedSuggestions[i] = if (result != null) result else EMPTY_SUGGESTIONS_INFO
            }
            return SentenceSuggestionsInfo(reconstructedSuggestions, offsets, lengths)
        }
    }
}
