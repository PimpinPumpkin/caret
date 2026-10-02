/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.common.Constants
import app.caret.latin.common.StringUtils
import app.caret.latin.define.DebugFlags

/**
 * The string parser of the key specification.
 * <p>
 * Each key specification is one of the following:
 * - Label optionally followed by keyOutputText (keyLabel|keyOutputText).
 * - Label optionally followed by code point (keyLabel|!code/code_name).
 * - Icon followed by keyOutputText (!icon/icon_name|keyOutputText).
 * - Icon followed by code point (!icon/icon_name|!code/code_name).
 * Label and keyOutputText are literal strings.
 * Icon is represented by (!icon/icon_name), see {@link KeyboardIconsSet}.
 * Code is one of the following:
 * - Code point presented by hexadecimal string prefixed with "0x"
 * - Code reference represented by (!code/code_name), see {@link KeyboardCodesSet}.
 * Special character, comma ',' backslash '\', and bar '|' can be escaped by '\' character.
 * Note that the '\' is also parsed by XML parser and {@link PopupKeySpec#splitKeySpecs(String)}
 * as well.
 */
// TODO: Rename to KeySpec and make this class to the key specification object.
object KeySpecParser {
    // Constants for parsing.
    private const val BACKSLASH = Constants.CODE_BACKSLASH.toChar()
    private const val VERTICAL_BAR = Constants.CODE_VERTICAL_BAR.toChar()
    private const val PREFIX_HEX = "0x"

    private fun hasIcon(keySpec: String): Boolean {
        return keySpec.startsWith(KeyboardIconsSet.PREFIX_ICON)
    }

    private fun hasCode(keySpec: String, labelEnd: Int): Boolean {
        if (labelEnd <= 0 || labelEnd + 1 >= keySpec.length) {
            return false
        }
        if (keySpec.startsWith(KeyboardCodesSet.PREFIX_CODE, labelEnd + 1)) {
            return true
        }
        // This is a workaround to have a key that has a supplementary code point. We can't put a
        // string in resource as a XML entity of a supplementary code point or a surrogate pair.
        return keySpec.startsWith(PREFIX_HEX, labelEnd + 1)
    }

    private fun parseEscape(text: String): String {
        if (text.indexOf(BACKSLASH) < 0) {
            return text
        }
        val length = text.length
        val sb = StringBuilder()
        var pos = 0
        while (pos < length) {
            val c = text[pos]
            if (c == BACKSLASH && pos + 1 < length) {
                // Skip escape char
                pos++
                sb.append(text[pos])
            } else {
                sb.append(c)
            }
            pos++
        }
        return sb.toString()
    }

    private fun indexOfLabelEnd(keySpec: String): Int {
        val length = keySpec.length
        if (keySpec.indexOf(BACKSLASH) < 0) {
            val labelEnd = keySpec.lastIndexOf(VERTICAL_BAR)
            if (labelEnd == 0) {
                if (length == 1) {
                    // Treat a sole vertical bar as a special case of key label.
                    return -1
                }
                if (DebugFlags.DEBUG_ENABLED)
                    throw KeySpecParserError("Empty label")
                else return -1
            }
            return labelEnd
        }
        var pos = length - 1
        while (pos >= 0) {
            val c = keySpec[pos]
            if (c != VERTICAL_BAR) {
                pos--
                continue
            }
            if (pos > 0 && keySpec[pos - 1] == BACKSLASH) {
                pos-- // Skip escape char
            } else {
                return pos
            }
            pos--
        }
        return -1
    }

    private fun getBeforeLabelEnd(keySpec: String, labelEnd: Int): String {
        return if (labelEnd < 0) keySpec else keySpec.substring(0, labelEnd)
    }

    private fun getAfterLabelEnd(keySpec: String, labelEnd: Int): String {
        return keySpec.substring(labelEnd + /* VERTICAL_BAR */1)
    }

    private fun checkDoubleLabelEnd(keySpec: String, labelEnd: Int) {
        if (indexOfLabelEnd(getAfterLabelEnd(keySpec, labelEnd)) < 0) {
            return
        }
        if (DebugFlags.DEBUG_ENABLED)
            throw KeySpecParserError("Multiple " + VERTICAL_BAR + ": " + keySpec)
    }

    @JvmStatic
    fun getLabel(keySpec: String?): String? {
        if (keySpec == null) {
            // TODO: Throw {@link KeySpecParserError} once Key.keyLabel attribute becomes mandatory.
            return null
        }
        if (hasIcon(keySpec)) {
            return null
        }
        val labelEnd = indexOfLabelEnd(keySpec)
        val label = parseEscape(getBeforeLabelEnd(keySpec, labelEnd))
        if (label.isEmpty() && DebugFlags.DEBUG_ENABLED) {
            throw KeySpecParserError("Empty label: " + keySpec)
        }
        return label
    }

    private fun getOutputTextInternal(keySpec: String, labelEnd: Int): String? {
        if (labelEnd <= 0) {
            return null
        }
        checkDoubleLabelEnd(keySpec, labelEnd)
        return parseEscape(getAfterLabelEnd(keySpec, labelEnd))
    }

    @JvmStatic
    fun getOutputText(keySpec: String?, code: Int): String? {
        if (keySpec == null) {
            // TODO: Throw {@link KeySpecParserError} once Key.keyLabel attribute becomes mandatory.
            return null
        }
        val labelEnd = indexOfLabelEnd(keySpec)
        if (hasCode(keySpec, labelEnd)) {
            return null
        }
        val outputText = getOutputTextInternal(keySpec, labelEnd)
        // don't set output text if there is an actual code, because the text will not be used in that case
        if (outputText != null && (code == KeyCode.UNSPECIFIED || code == KeyCode.MULTIPLE_CODE_POINTS)) {
            if (StringUtils.codePointCount(outputText) == 1) {
                // If output text is one code point, it should be treated as a code.
                // See {@link #getCode(Resources, String)}.
                return null
            }
            // also empty output texts are acceptable
            return outputText
        }
        val label = getLabel(keySpec)
        if (label == null) {
            if (keySpec.startsWith(KeyboardIconsSet.PREFIX_ICON) && code != KeyCode.UNSPECIFIED && code != KeyCode.MULTIPLE_CODE_POINTS)
                return null // allow empty label in case of icon & actual code
            throw KeySpecParserError("Empty label: " + keySpec)
        }
        // Code is automatically generated for one letter label. See {@link getCode()}.
        return if (StringUtils.codePointCount(label) == 1) null else label
    }

    @JvmStatic
    fun getCode(keySpec: String?): Int {
        if (keySpec == null) {
            // TODO: Throw {@link KeySpecParserError} once Key.keyLabel attribute becomes mandatory.
            return KeyCode.NOT_SPECIFIED
        }
        val labelEnd = indexOfLabelEnd(keySpec)
        if (hasCode(keySpec, labelEnd)) {
            checkDoubleLabelEnd(keySpec, labelEnd)
            return parseCode(getAfterLabelEnd(keySpec, labelEnd), KeyCode.NOT_SPECIFIED)
        }
        val outputText = getOutputTextInternal(keySpec, labelEnd)
        if (outputText != null) {
            // If output text is one code point, it should be treated as a code.
            // See {@link #getOutputText(String)}.
            if (StringUtils.codePointCount(outputText) == 1) {
                return outputText.codePointAt(0)
            }
            return KeyCode.MULTIPLE_CODE_POINTS
        }
        val label = getLabel(keySpec)
        if (label == null) {
            if (DebugFlags.DEBUG_ENABLED)
                throw KeySpecParserError("Empty label: " + keySpec)
            else return KeyCode.MULTIPLE_CODE_POINTS
        }
        // Code is automatically generated for one letter label.
        return if (StringUtils.codePointCount(label) == 1) label.codePointAt(0) else KeyCode.MULTIPLE_CODE_POINTS
    }

    @JvmStatic
    fun parseCode(text: String?, defaultCode: Int): Int {
        if (text == null) {
            return defaultCode
        }
        if (text.startsWith(KeyboardCodesSet.PREFIX_CODE)) {
            return KeyboardCodesSet.getCode(text.substring(KeyboardCodesSet.PREFIX_CODE.length))
        }
        // This is a workaround to have a key that has a supplementary code point. We can't put a
        // string in resource as a XML entity of a supplementary code point or a surrogate pair.
        if (text.startsWith(PREFIX_HEX)) {
            return Integer.parseInt(text.substring(PREFIX_HEX.length), 16)
        }
        return defaultCode
    }

    @JvmStatic
    fun getIconName(keySpec: String?): String? {
        if (keySpec == null) {
            // TODO: Throw {@link KeySpecParserError} once Key.keyLabel attribute becomes mandatory.
            return null
        }
        if (!hasIcon(keySpec)) {
            return null
        }
        val labelEnd = indexOfLabelEnd(keySpec)
        return getBeforeLabelEnd(keySpec, labelEnd).substring(KeyboardIconsSet.PREFIX_ICON.length).intern()
    }

    class KeySpecParserError(message: String?) : RuntimeException(message)
}
