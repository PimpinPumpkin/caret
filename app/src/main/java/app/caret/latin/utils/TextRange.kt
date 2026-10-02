/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.text.Spanned
import android.text.TextUtils
import android.text.style.SuggestionSpan

import java.util.Arrays
import java.util.Objects

/**
 * Represents a range of text, relative to the current cursor position.
 */
class TextRange {
    private val textAtCursor: CharSequence
    private val wordAtCursorStartIndex: Int
    private val wordAtCursorEndIndex: Int
    private val cursorIndex: Int

    @JvmField
    val word: CharSequence
    @JvmField
    val hasUrlSpans: Boolean

    val numberOfCharsInWordBeforeCursor: Int
        get() = cursorIndex - wordAtCursorStartIndex

    val numberOfCharsInWordAfterCursor: Int
        get() = wordAtCursorEndIndex - cursorIndex

    fun length(): Int {
        return word.length
    }

    /**
     * Gets the suggestion spans that are put squarely on the word, with the exact start
     * and end of the span matching the boundaries of the word.
     * @return the array of spans.
     */
    @Suppress("UNCHECKED_CAST")
    fun getSuggestionSpansAtWord(): Array<SuggestionSpan> {
        if (!(textAtCursor is Spanned && word is Spanned)) {
            return arrayOfNulls<SuggestionSpan>(0) as Array<SuggestionSpan>
        }
        val text: Spanned = textAtCursor
        // Note: it's fine to pass indices negative or greater than the length of the string
        // to the #getSpans() method. The reason we need to get from -1 to +1 is that, the
        // spans were cut at the cursor position, and #getSpans(start, end) does not return
        // spans that end at `start' or begin at `end'. Consider the following case:
        //              this| is          (The | symbolizes the cursor position
        //              ---- ---
        // In this case, the cursor is in position 4, so the 0~7 span has been split into
        // a 0~4 part and a 4~7 part.
        // If we called #getSpans(0, 4) in this case, we would only get the part from 0 to 4
        // of the span, and not the part from 4 to 7, so we would not realize the span actually
        // extends from 0 to 7. But if we call #getSpans(-1, 5) we'll get both the 0~4 and
        // the 4~7 spans and we can merge them accordingly.
        // Any span starting more than 1 char away from the word boundaries in any direction
        // does not touch the word, so we don't need to consider it. That's why requesting
        // -1 ~ +1 is enough.
        // Of course this is only relevant if the cursor is at one end of the word. If it's
        // in the middle, the -1 and +1 are not necessary, but they are harmless.
        val spans: Array<SuggestionSpan?> = text.getSpans(wordAtCursorStartIndex - 1,
                wordAtCursorEndIndex + 1, SuggestionSpan::class.java)
        var readIndex = 0
        var writeIndex = 0
        while (readIndex < spans.size) {
            val span = spans[readIndex]
            // The span may be null, as we null them when we find duplicates. Cf a few lines
            // down.
            if (null == span) {
                ++readIndex
                continue
            }
            // Tentative span start and end. This may be modified later if we realize the
            // same span is also applied to other parts of the string.
            var spanStart = text.getSpanStart(span)
            var spanEnd = text.getSpanEnd(span)
            for (i in readIndex + 1 until spans.size) {
                if (span == spans[i]) {
                    // We found the same span somewhere else. Read the new extent of this
                    // span, and adjust our values accordingly.
                    spanStart = Math.min(spanStart, text.getSpanStart(spans[i]))
                    spanEnd = Math.max(spanEnd, text.getSpanEnd(spans[i]))
                    // ...and mark the span as processed.
                    spans[i] = null
                }
            }
            if (spanStart == wordAtCursorStartIndex && spanEnd == wordAtCursorEndIndex) {
                // If the span does not start and stop here, ignore it. It probably extends
                // past the start or end of the word, as happens in missing space correction
                // or EasyEditSpans put by voice input.
                spans[writeIndex++] = spans[readIndex]
            }
            ++readIndex
        }
        return (if (writeIndex == readIndex) spans else Arrays.copyOfRange(spans, 0, writeIndex)) as Array<SuggestionSpan>
    }

    override fun equals(other: Any?): Boolean {
        if (other !is TextRange) return false
        val textRange: TextRange = other
        return wordAtCursorStartIndex == textRange.wordAtCursorStartIndex
            && wordAtCursorEndIndex == textRange.wordAtCursorEndIndex
            && cursorIndex == textRange.cursorIndex
            && hasUrlSpans == textRange.hasUrlSpans
            && TextUtils.equals(textAtCursor, textRange.textAtCursor)
            && TextUtils.equals(word, textRange.word)
    }

    override fun hashCode(): Int {
        return Objects.hash(textAtCursor, wordAtCursorStartIndex, wordAtCursorEndIndex, cursorIndex, word, hasUrlSpans)
    }

    override fun toString(): String {
        return textAtCursor.toString() + ", " + word + ", " + cursorIndex
    }

    constructor(textAtCursor: CharSequence, wordAtCursorStartIndex: Int,
            wordAtCursorEndIndex: Int, cursorIndex: Int, hasUrlSpans: Boolean) {
        if (wordAtCursorStartIndex < 0 || cursorIndex < wordAtCursorStartIndex
                || cursorIndex > wordAtCursorEndIndex
                || wordAtCursorEndIndex > textAtCursor.length) {
            throw IndexOutOfBoundsException()
        }
        this.textAtCursor = textAtCursor
        this.wordAtCursorStartIndex = wordAtCursorStartIndex
        this.wordAtCursorEndIndex = wordAtCursorEndIndex
        this.cursorIndex = cursorIndex
        this.hasUrlSpans = hasUrlSpans
        word = textAtCursor.subSequence(wordAtCursorStartIndex, wordAtCursorEndIndex)
    }
}
