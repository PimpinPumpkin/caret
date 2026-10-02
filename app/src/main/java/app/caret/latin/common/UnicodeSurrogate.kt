/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.common

/**
 * Emojis are supplementary characters expressed as a low+high pair. For instance,
 * the emoji U+1F625 is encoded as "😥" in UTF-16, where '\uD83D' is in
 * the range of [0xd800, 0xdbff] and '\uDE25' is in the range of [0xdc00, 0xdfff].
 * {@see http://docs.oracle.com/javase/6/docs/api/java/lang/Character.html#unicode}
 */
class UnicodeSurrogate {
    companion object {
        private const val LOW_SURROGATE_MIN = '\uD800'
        private const val LOW_SURROGATE_MAX = '\uDBFF'
        private const val HIGH_SURROGATE_MIN = '\uDC00'
        private const val HIGH_SURROGATE_MAX = '\uDFFF'

        @JvmStatic
        fun isLowSurrogate(c: Char): Boolean {
            return c >= LOW_SURROGATE_MIN && c <= LOW_SURROGATE_MAX
        }

        @JvmStatic
        fun isHighSurrogate(c: Char): Boolean {
            return c >= HIGH_SURROGATE_MIN && c <= HIGH_SURROGATE_MAX
        }
    }
}
