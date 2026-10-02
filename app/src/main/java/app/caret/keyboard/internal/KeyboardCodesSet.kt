/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode.checkAndConvertCode
import app.caret.latin.common.Constants

import java.util.HashMap

object KeyboardCodesSet {
    const val PREFIX_CODE = "!code/"

    private val nameToIdMap = HashMap<String?, Int>()

    @JvmStatic
    fun getCode(name: String?): Int {
        val id = nameToIdMap[name]
        if (id == null) {
            try {
                return Integer.parseInt(name).checkAndConvertCode(true) // need to take care about longPress check in some other place
            } catch (e: Exception) {
                throw RuntimeException("Unknown key code: " + name)
            }
        }
        return DEFAULT[id]
    }

    private val ID_TO_NAME = arrayOf(
        "key_tab",
        "key_enter",
        "key_space",
        "key_shift",
        "key_capslock",
        "key_switch_alpha_symbol",
        "key_switch_alpha",
        "key_switch_symbol",
        "key_output_text",
        "key_delete",
        "key_settings",
        "key_voice_input",
        "key_action_next",
        "key_action_previous",
        "key_shift_enter",
        "key_language_switch",
        "key_emoji",
        "key_unspecified",
        "key_clipboard",
        "key_toggle_onehanded",
        "key_start_onehanded", // keep name to avoid breaking custom layouts
        "key_stop_onehanded", // keep name to avoid breaking custom layouts
        "key_switch_onehanded",
        "key_emoji_search",
    )

    private val DEFAULT = intArrayOf(
        Constants.CODE_TAB,
        Constants.CODE_ENTER,
        Constants.CODE_SPACE,
        KeyCode.SHIFT,
        KeyCode.CAPS_LOCK,
        KeyCode.SYMBOL_ALPHA,
        KeyCode.ALPHA,
        KeyCode.SYMBOL,
        KeyCode.MULTIPLE_CODE_POINTS,
        KeyCode.DELETE,
        KeyCode.SETTINGS,
        KeyCode.VOICE_INPUT,
        KeyCode.ACTION_NEXT,
        KeyCode.ACTION_PREVIOUS,
        KeyCode.SHIFT_ENTER,
        KeyCode.LANGUAGE_SWITCH,
        KeyCode.EMOJI,
        KeyCode.NOT_SPECIFIED,
        KeyCode.CLIPBOARD,
        KeyCode.TOGGLE_ONE_HANDED_MODE,
        KeyCode.TOGGLE_ONE_HANDED_MODE,
        KeyCode.TOGGLE_ONE_HANDED_MODE,
        KeyCode.SWITCH_ONE_HANDED_MODE,
        KeyCode.EMOJI_SEARCH,
    )

    init {
        for (i in 0 until ID_TO_NAME.size) {
            nameToIdMap.put(ID_TO_NAME[i], i)
        }
    }
}
