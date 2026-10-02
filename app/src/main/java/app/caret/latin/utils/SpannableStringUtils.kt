/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.os.Build
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.TextUtils
import android.text.style.SuggestionSpan
import android.text.style.URLSpan

import java.util.ArrayList
import java.util.regex.Pattern

class SpannableStringUtils {
    companion object {
        /**
         * Copies the spans from the region <code>start...end</code> in
         * <code>source</code> to the region
         * <code>destoff...destoff+end-start</code> in <code>dest</code>.
         * Spans in <code>source</code> that begin before <code>start</code>
         * or end after <code>end</code> but overlap this range are trimmed
         * as if they began at <code>start</code> or ended at <code>end</code>.
         * Only SuggestionSpans that don't have the SPAN_PARAGRAPH span are copied.
         * <p>
         * This code is almost entirely taken from {@link TextUtils#copySpansFrom}, except for the
         * kind of span that is copied.
         *
         * @throws IndexOutOfBoundsException if any of the copied spans
         * are out of range in <code>dest</code>.
         */
        @JvmStatic
        fun copyNonParagraphSuggestionSpansFrom(source: Spanned, start: Int, end: Int,
                dest: Spannable, destoff: Int) {
            val spans = source.getSpans(start, end, SuggestionSpan::class.java)

            for (span in spans) {
                var fl = source.getSpanFlags(span)
                // We don't care about the PARAGRAPH flag in LatinIME code. However, if this flag
                // is set, Spannable#setSpan will throw an exception unless the span is on the edge
                // of a word. But the spans have been split into two by the getText{Before,After}Cursor
                // methods, so after concatenation they may end in the middle of a word.
                // Since we don't use them, we can just remove them and avoid crashing.
                fl = fl and Spanned.SPAN_PARAGRAPH.inv()

                var st = source.getSpanStart(span)
                var en = source.getSpanEnd(span)

                if (st < start)
                    st = start
                if (en > end)
                    en = end

                dest.setSpan(span, st - start + destoff, en - start + destoff, fl)
            }
        }

        /**
         * Returns a CharSequence concatenating the specified CharSequences, retaining their
         * SuggestionSpans that don't have the PARAGRAPH flag, but not other spans.
         * <p>
         * This code is almost entirely taken from {@link TextUtils#concat(CharSequence...)}, except
         * it calls copyNonParagraphSuggestionSpansFrom instead of {@link TextUtils#copySpansFrom}.
         */
        @JvmStatic
        fun concatWithNonParagraphSuggestionSpansOnly(vararg text: CharSequence): CharSequence {
            if (text.size == 0) {
                return ""
            }

            if (text.size == 1) {
                return text[0]
            }

            var spanned = false
            for (value in text) {
                if (value is Spanned) {
                    spanned = true
                    break
                }
            }

            val sb = StringBuilder()
            for (sequence in text) {
                sb.append(sequence)
            }

            if (!spanned) {
                return sb.toString()
            }

            val ss = SpannableString(sb)
            var off = 0
            for (charSequence in text) {
                val len = charSequence.length

                if (charSequence is Spanned) {
                    copyNonParagraphSuggestionSpansFrom(charSequence, 0, len, ss, off)
                }

                off += len
            }

            return SpannedString(ss)
        }

        @Suppress("deprecation")
        @JvmStatic
        fun fromHtml(text: String): Spanned {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
            return Html.fromHtml(text)
        }

        @JvmStatic
        fun hasUrlSpans(text: CharSequence?,
                startIndex: Int, endIndex: Int): Boolean {
            if (text !is Spanned) {
                return false // Not spanned, so no link
            }
            val spanned: Spanned = text
            // getSpans(x, y) does not return spans that start on x or end on y. x-1, y+1 does the
            // trick, and works in all cases even if startIndex <= 0 or endIndex >= text.length().
            val spans = spanned.getSpans(startIndex - 1, endIndex + 1, URLSpan::class.java)
            return null != spans && spans.size > 0
        }

        /**
         * Splits the given {@code charSequence} with at occurrences of the given {@code regex}.
         * <p>
         * This is equivalent to
         * {@code charSequence.toString().split(regex, preserveTrailingEmptySegments ? -1 : 0)}
         * except that the spans are preserved in the result array.
         * </p>
         * @param charSequence the character sequence to be split.
         * @param regex the regex pattern to be used as the separator.
         * @param preserveTrailingEmptySegments {@code true} to preserve the trailing empty
         * segments. Otherwise, trailing empty segments will be removed before being returned.
         * @return the array which contains the result. All the spans in the <code>charSequence</code>
         * is preserved.
         */
        @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        @JvmStatic
        fun split(charSequence: CharSequence, regex: String,
                preserveTrailingEmptySegments: Boolean): Array<CharSequence> {
            // A short-cut for non-spanned strings.
            if (charSequence !is Spanned) {
                // -1 means that trailing empty segments will be preserved.
                return (charSequence.toString() as java.lang.String)
                        .split(regex, if (preserveTrailingEmptySegments) -1 else 0) as Array<CharSequence>
            }

            // Hereafter, emulate String.split for CharSequence.
            val sequences = ArrayList<CharSequence>()
            val matcher = Pattern.compile(regex).matcher(charSequence)
            var nextStart = 0
            var matched = false
            while (matcher.find()) {
                sequences.add(charSequence.subSequence(nextStart, matcher.start()))
                nextStart = matcher.end()
                matched = true
            }
            if (!matched) {
                // never matched. preserveTrailingEmptySegments is ignored in this case.
                return arrayOf(charSequence)
            }
            sequences.add(charSequence.subSequence(nextStart, charSequence.length))
            if (!preserveTrailingEmptySegments) {
                var i = sequences.size - 1
                while (i >= 0) {
                    if (!TextUtils.isEmpty(sequences.get(i))) {
                        break
                    }
                    sequences.removeAt(i)
                    --i
                }
            }
            return sequences.toArray(arrayOfNulls<CharSequence>(0)) as Array<CharSequence>
        }
    }
}
