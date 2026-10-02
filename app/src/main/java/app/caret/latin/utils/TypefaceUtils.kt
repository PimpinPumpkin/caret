/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.SparseArray

object TypefaceUtils {
    private val KEY_LABEL_REFERENCE_CHAR = charArrayOf('M')
    private val KEY_NUMERIC_HINT_LABEL_REFERENCE_CHAR = charArrayOf('8')

    // This utility class is not publicly instantiable.

    // This sparse array caches key label text height in pixel indexed by key label text size.
    private val textHeightCache = SparseArray<Float>()
    // Working variable for the following method.
    private val textHeightBounds = Rect()

    private fun getCharHeight(referenceChar: CharArray, paint: Paint): Float {
        val key = getCharGeometryCacheKey(referenceChar[0], paint)
        synchronized(textHeightCache) {
            val cachedValue: Float? = textHeightCache.get(key)
            if (cachedValue != null) {
                return cachedValue
            }

            paint.getTextBounds(referenceChar, 0, 1, textHeightBounds)
            val height = textHeightBounds.height().toFloat()
            textHeightCache.put(key, height)
            return height
        }
    }

    // This sparse array caches key label text width in pixel indexed by key label text size.
    private val textWidthCache = SparseArray<Float>()
    // Working variable for the following method.
    private val textWidthBounds = Rect()

    private fun getCharWidth(referenceChar: CharArray, paint: Paint): Float {
        val key = getCharGeometryCacheKey(referenceChar[0], paint)
        synchronized(textWidthCache) {
            val cachedValue: Float? = textWidthCache.get(key)
            if (cachedValue != null) {
                return cachedValue
            }

            paint.getTextBounds(referenceChar, 0, 1, textWidthBounds)
            val width = textWidthBounds.width().toFloat()
            textWidthCache.put(key, width)
            return width
        }
    }

    private fun getCharGeometryCacheKey(referenceChar: Char, paint: Paint): Int {
        val labelSize = paint.getTextSize().toInt()
        val face = paint.getTypeface()
        val codePointOffset = referenceChar.code shl 15
        if (face === Typeface.DEFAULT) {
            return codePointOffset + labelSize
        } else if (face === Typeface.DEFAULT_BOLD) {
            return codePointOffset + labelSize + 0x1000
        } else if (face === Typeface.MONOSPACE) {
            return codePointOffset + labelSize + 0x2000
        } else {
            return codePointOffset + labelSize
        }
    }

    @JvmStatic
    fun getReferenceCharHeight(paint: Paint): Float {
        return getCharHeight(KEY_LABEL_REFERENCE_CHAR, paint)
    }

    @JvmStatic
    fun getReferenceCharWidth(paint: Paint): Float {
        return getCharWidth(KEY_LABEL_REFERENCE_CHAR, paint)
    }

    @JvmStatic
    fun getReferenceDigitWidth(paint: Paint): Float {
        return getCharWidth(KEY_NUMERIC_HINT_LABEL_REFERENCE_CHAR, paint)
    }

    // Working variable for the following method.
    private val stringWidthBounds = Rect()

    @JvmStatic
    fun getStringWidth(string: String, paint: Paint): Float {
        synchronized(stringWidthBounds) {
            paint.getTextBounds(string, 0, string.length, stringWidthBounds)
            return stringWidthBounds.width().toFloat()
        }
    }
}
