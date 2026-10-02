/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.text.TextUtils
import android.util.SparseIntArray
import app.caret.keyboard.Key
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.common.CollectionUtils
import app.caret.latin.common.Constants
import app.caret.latin.common.StringUtils
import java.util.ArrayList
import java.util.HashSet
import java.util.Locale

/**
 * The popup key specification object. The popup keys are an array of {@link PopupKeySpec}.
 * <p>
 * The popup keys specification is comma separated "key specification" each of which represents one
 * "popup key".
 * The key specification might have label or string resource reference in it. These references are
 * expanded before parsing comma.
 * Special character, comma ',' backslash '\' can be escaped by '\' character.
 * Note that the '\' is also parsed by XML parser and {@link PopupKeySpec#splitKeySpecs(String)}
 * as well.
 */
// TODO: Should extend the key specification object.
class PopupKeySpec(popupKeySpec: String, needsToUpperCase: Boolean, locale: Locale) {
    @JvmField
    val mCode: Int
    @JvmField
    val mLabel: String?
    @JvmField
    val mOutputText: String?
    @JvmField
    val mIconName: String?

    init {
        if (popupKeySpec.isEmpty()) {
            throw KeySpecParser.KeySpecParserError("Empty popup key spec")
        }
        val label: String? = KeySpecParser.getLabel(popupKeySpec)
        mLabel = if (needsToUpperCase) StringUtils.toTitleCaseOfKeyLabel(label, locale) else label
        val codeInSpec = KeySpecParser.getCode(popupKeySpec)
        val code = if (needsToUpperCase) StringUtils.toTitleCaseOfKeyCode(codeInSpec, locale)
                else codeInSpec
        if (code == KeyCode.NOT_SPECIFIED) {
            // Some letter, for example German Eszett (U+00DF: "ß"), has multiple characters
            // upper case representation ("SS").
            mCode = KeyCode.MULTIPLE_CODE_POINTS
            mOutputText = mLabel
        } else {
            mCode = code
            val outputText: String? = KeySpecParser.getOutputText(popupKeySpec, code)
            mOutputText = if (needsToUpperCase)
                    StringUtils.toTitleCaseOfKeyLabel(outputText, locale) else outputText
        }
        mIconName = KeySpecParser.getIconName(popupKeySpec)
    }

    fun buildKey(x: Int, y: Int, labelFlags: Int, background: Int, params: KeyboardParams): Key {
        return Key(mLabel, mIconName, mCode, mOutputText, null, labelFlags, background, x, y,
                params.mDefaultAbsoluteKeyWidth, params.mDefaultAbsoluteRowHeight, params.mHorizontalGap, params.mVerticalGap)
    }

    override fun hashCode(): Int {
        var hashCode = 31 + mCode
        val iconName = mIconName
        hashCode = hashCode * 31 + (if (iconName == null) 0 else iconName.hashCode())
        val label = mLabel
        hashCode = hashCode * 31 + (if (label == null) 0 else label.hashCode())
        val outputText = mOutputText
        hashCode = hashCode * 31 + (if (outputText == null) 0 else outputText.hashCode())
        return hashCode
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other is PopupKeySpec) {
            return mCode == other.mCode
                    && TextUtils.equals(mIconName, other.mIconName)
                    && TextUtils.equals(mLabel, other.mLabel)
                    && TextUtils.equals(mOutputText, other.mOutputText)
        }
        return false
    }

    override fun toString(): String {
        val label = (if (mIconName == null) mLabel else KeyboardIconsSet.PREFIX_ICON + mIconName)
        val output = (if (mCode == KeyCode.MULTIPLE_CODE_POINTS) mOutputText else Constants.printableCode(mCode))
        if (StringUtils.codePointCount(label) == 1 && label!!.codePointAt(0) == mCode) {
            return output!!
        }
        return label + "|" + output
    }

    class LettersOnBaseLayout {
        private val codes = SparseIntArray()
        private val texts = HashSet<String?>()

        fun addLetter(key: Key.KeyParams) {
            val code = key.mCode
            if (code > 32) {
                codes.put(code, 0)
            } else if (code == KeyCode.MULTIPLE_CODE_POINTS) {
                texts.add(key.getOutputText())
            }
        }

        fun contains(popupKey: PopupKeySpec): Boolean {
            val code = popupKey.mCode
            if (codes.indexOfKey(code) >= 0) {
                return true
            } else return code == KeyCode.MULTIPLE_CODE_POINTS && texts.contains(popupKey.mOutputText)
        }
    }

    companion object {
        @JvmStatic
        fun removeRedundantPopupKeys(popupKeys: Array<PopupKeySpec>?,
                lettersOnBaseLayout: LettersOnBaseLayout): Array<PopupKeySpec>? {
            if (popupKeys == null) {
                return null
            }
            val filteredPopupKeys = ArrayList<PopupKeySpec>()
            for (popupKey in popupKeys) {
                if (!lettersOnBaseLayout.contains(popupKey)) {
                    filteredPopupKeys.add(popupKey)
                }
            }
            val size = filteredPopupKeys.size
            if (size == popupKeys.size) {
                return popupKeys
            }
            if (size == 0) {
                return null
            }
            return filteredPopupKeys.toTypedArray()
        }

        // Constants for parsing.
        private const val COMMA = Constants.CODE_COMMA.toChar()
        private const val BACKSLASH = Constants.CODE_BACKSLASH.toChar()
        private val ADDITIONAL_POPUP_KEY_MARKER: String =
                StringUtils.newSingleCodePointString(Constants.CODE_PERCENT)

        /**
         * Split the text containing multiple key specifications separated by commas into an array of
         * key specifications.
         * A key specification can contain a character escaped by the backslash character, including a
         * comma character.
         * Note that an empty key specification will be eliminated from the result array.
         *
         * @param text the text containing multiple key specifications.
         * @return an array of key specification text. Null if the specified <code>text</code> is empty
         * or has no key specifications.
         */
        @JvmStatic
        fun splitKeySpecs(text: String?): Array<String>? {
            if (TextUtils.isEmpty(text)) {
                return null
            }
            val size = text!!.length
            // Optimization for one-letter key specification.
            if (size == 1) {
                return if (text[0] == COMMA) null else arrayOf(text)
            }

            var list: ArrayList<String>? = null
            var start = 0
            // The characters in question in this loop are COMMA and BACKSLASH. These characters never
            // match any high or low surrogate character. So it is OK to iterate through with char
            // index.
            var pos = 0
            while (pos < size) {
                val c = text[pos]
                if (c == COMMA) {
                    // Skip empty entry.
                    if (pos - start > 0) {
                        if (list == null) {
                            list = ArrayList()
                        }
                        list.add(text.substring(start, pos))
                    }
                    // Skip comma
                    start = pos + 1
                } else if (c == BACKSLASH) {
                    // Skip escape character and escaped character.
                    pos++
                }
                pos++
            }
            val remain = if (size - start > 0) text.substring(start) else null
            if (list == null) {
                return if (remain != null) arrayOf(remain) else null
            }
            if (remain != null) {
                list.add(remain)
            }
            return list.toTypedArray()
        }

        private val EMPTY_STRING_ARRAY = arrayOf<String>()

        // Note: the given array may contain null entries (see getIntValue and getBooleanValue).
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun filterOutEmptyString(array: Array<out String?>?): Array<String> {
            if (array == null) {
                return EMPTY_STRING_ARRAY
            }
            @Suppress("UNCHECKED_CAST")
            val entries = array as Array<String?>
            var out: ArrayList<String>? = null
            for (i in entries.indices) {
                val entry = entries[i]
                if (TextUtils.isEmpty(entry)) {
                    if (out == null) {
                        out = CollectionUtils.arrayAsList(array, 0, i) as ArrayList<String> // entries before i are not empty
                    }
                } else if (out != null) {
                    out.add(entry!!)
                }
            }
            if (out == null) {
                return array as Array<String> // no empty entry was found
            }
            return out.toTypedArray()
        }

        @JvmStatic
        fun insertAdditionalPopupKeys(popupKeySpecs: Array<String>?,
                additionalPopupKeySpecs: Array<String>?): Array<String>? {
            val popupKeys = filterOutEmptyString(popupKeySpecs)
            val additionalPopupKeys = filterOutEmptyString(additionalPopupKeySpecs)
            val popupKeysCount = popupKeys.size
            val additionalCount = additionalPopupKeys.size
            var out: ArrayList<String>? = null
            var additionalIndex = 0
            for (popupKeyIndex in 0 until popupKeysCount) {
                val popupKeySpec = popupKeys[popupKeyIndex]
                if (popupKeySpec == ADDITIONAL_POPUP_KEY_MARKER) {
                    if (additionalIndex < additionalCount) {
                        // Replace '%' marker with additional popup key specification.
                        val additionalPopupKey = additionalPopupKeys[additionalIndex]
                        if (out != null) {
                            out.add(additionalPopupKey)
                        } else {
                            popupKeys[popupKeyIndex] = additionalPopupKey
                        }
                        additionalIndex++
                    } else {
                        // Filter out excessive '%' marker.
                        if (out == null) {
                            out = CollectionUtils.arrayAsList(popupKeys, 0, popupKeyIndex)
                        }
                    }
                } else {
                    if (out != null) {
                        out.add(popupKeySpec)
                    }
                }
            }
            if (additionalCount > 0 && additionalIndex == 0) {
                // No '%' marker is found in popup keys.
                // Insert all additional popup keys to the head of popup keys.
                out = CollectionUtils.arrayAsList(additionalPopupKeys, additionalIndex, additionalCount)
                for (i in 0 until popupKeysCount) {
                    out.add(popupKeys[i])
                }
            } else if (additionalIndex < additionalCount) {
                // The number of '%' markers are less than additional popup keys.
                // Append remained additional popup keys to the tail of popup keys.
                out = CollectionUtils.arrayAsList(popupKeys, 0, popupKeysCount)
                for (i in additionalIndex until additionalCount) {
                    out.add(additionalPopupKeys[i])
                }
            }
            if (out == null && popupKeysCount > 0) {
                return popupKeys
            } else if (out != null && out.size > 0) {
                return out.toTypedArray()
            } else {
                return null
            }
        }

        // Note: matching entries of the given array are set to null.
        @JvmStatic
        fun getIntValue(popupKeys: Array<out String?>?, key: String,
                defaultValue: Int): Int {
            if (popupKeys == null) {
                return defaultValue
            }
            @Suppress("UNCHECKED_CAST")
            val entries = popupKeys as Array<String?>
            val keyLen = key.length
            var foundValue = false
            var value = defaultValue
            for (i in entries.indices) {
                val popupKeySpec = entries[i]
                if (popupKeySpec == null || !popupKeySpec.startsWith(key)) {
                    continue
                }
                entries[i] = null
                try {
                    if (!foundValue) {
                        value = Integer.parseInt(popupKeySpec.substring(keyLen))
                        foundValue = true
                    }
                } catch (e: NumberFormatException) {
                    throw RuntimeException(
                            "integer should follow after " + key + ": " + popupKeySpec)
                }
            }
            return value
        }

        // Note: matching entries of the given array are set to null.
        @JvmStatic
        fun getBooleanValue(popupKeys: Array<out String?>?, key: String): Boolean {
            if (popupKeys == null) {
                return false
            }
            @Suppress("UNCHECKED_CAST")
            val entries = popupKeys as Array<String?>
            var value = false
            for (i in entries.indices) {
                val popupKeySpec = entries[i]
                if (popupKeySpec == null || popupKeySpec != key) {
                    continue
                }
                entries[i] = null
                value = true
            }
            return value
        }
    }
}
