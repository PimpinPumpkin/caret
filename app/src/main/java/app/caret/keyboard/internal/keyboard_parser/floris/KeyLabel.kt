package app.caret.keyboard.internal.keyboard_parser.floris

import android.view.inputmethod.EditorInfo
import app.caret.latin.utils.prefs
import app.caret.keyboard.KeyboardElement
import app.caret.keyboard.internal.KeyboardCodesSet
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.keyboard.internal.KeyboardParams
import app.caret.keyboard.internal.keyboard_parser.floris.KeyData.Companion.replaceIconWithLabelIfNoDrawable
import app.caret.latin.settings.Settings
import app.caret.latin.utils.InputTypeUtils
import app.caret.latin.utils.ToolbarKey
import app.caret.latin.utils.getCodeForToolbarKey
import app.caret.latin.utils.toolbarKeyStrings
import java.util.Locale

/** labels for functional / special keys */
object KeyLabel {
    const val COM = "com"
    const val LANGUAGE_SWITCH = "language_switch"
    const val ACTION = "action"
    const val DELETE = "delete"
    const val SHIFT = "shift"
    const val NUMPAD = "numpad"
    const val DPAD = "dpad"
    const val SYMBOL = "symbol"
    const val ALPHA = "alpha"
    const val SYMBOL_ALPHA = "symbol_alpha"
    const val PERIOD = "period"
    const val COMMA = "comma"
    const val SPACE = "space"
    const val ZWNJ = "zwnj"
    const val CURRENCY = "$$$"
    const val CURRENCY1 = "$$$1"
    const val CURRENCY2 = "$$$2"
    const val CURRENCY3 = "$$$3"
    const val CURRENCY4 = "$$$4"
    const val CURRENCY5 = "$$$5"
    const val CTRL = "ctrl"
    const val ALT = "alt"
    const val FN = "fn"
    const val META = "meta"
    const val TAB = "tab"
    const val ESCAPE = "esc"
    const val TIMESTAMP = "timestamp"
    const val EMOJI_SEARCH = "emoji_search"

    /** to make sure a FlorisBoard label works when reading a JSON layout */
    // resulting special labels should be names of FunctionalKey enum, case insensitive
    /** Caret: the key next to the spacebar. Which toolbar action it is comes from a preference. */
    const val ACTION_KEY = "caret_action"
    const val PREF_ACTION_KEY = "action_key"
    const val DEFAULT_ACTION_KEY = "emoji"

    private fun actionKeyLabel(): String {
        val chosen = Settings.getCurrentContext()?.prefs()?.getString(PREF_ACTION_KEY, DEFAULT_ACTION_KEY) ?: DEFAULT_ACTION_KEY
        return if (chosen in toolbarKeyStrings.values) chosen else DEFAULT_ACTION_KEY
    }

    fun String.convertFlorisLabel(): String = when (this) {
        ACTION_KEY -> actionKeyLabel()
        "view_characters" -> ALPHA
        "view_symbols" -> SYMBOL
        "view_numeric_advanced" -> NUMPAD
        "view_phone" -> ALPHA // phone keyboard is treated like alphabet, just with different layout
        "view_phone2" -> SYMBOL // phone symbols
        "ime_ui_mode_media" -> toolbarKeyStrings[ToolbarKey.EMOJI]!!
        "ime_ui_mode_clipboard" -> toolbarKeyStrings[ToolbarKey.CLIPBOARD]!!
        "ime_ui_mode_text" -> ALPHA
        "currency_slot_1" -> CURRENCY
        "currency_slot_2" -> CURRENCY1
        "currency_slot_3" -> CURRENCY2
        "currency_slot_4" -> CURRENCY3
        "currency_slot_5" -> CURRENCY4
        "currency_slot_6" -> CURRENCY5
        "enter" -> ACTION
        "half_space" -> ZWNJ
        else -> this
    }

    fun String.rtlLabel(params: KeyboardParams): String {
        if (!params.id.subtype.isRtlSubtype || params.id.element.isNumberLayout) return this
        return when (this) {
            "{" -> "{|}"
            "}" -> "}|{"
            "(" -> "(|)"
            ")" -> ")|("
            "[" -> "[|]"
            "]" -> "]|["
            "<" -> "<|>"
            ">" -> ">|<"
            "≤" -> "≤|≥"
            "≥" -> "≥|≤"
            "«" -> "«|»"
            "»" -> "»|«"
            "‹" -> "‹|›"
            "›" -> "›|‹"
            "﴾" -> "﴾|﴿"
            "﴿" -> "﴿|﴾"
            "⁽" -> "⁽|⁾"
            "⁾" -> "⁾|⁽"
            else -> this
        }
    }

    fun keyLabelToActualLabel(label: String, params: KeyboardParams): String {
        val newLabel = when (label) {
            SYMBOL_ALPHA -> if (params.id.element.isAlphabet) {
                params.localeKeyboardInfos.labelSymbol
            } else {
                params.localeKeyboardInfos.labelAlphabet
            }
            SYMBOL -> params.localeKeyboardInfos.labelSymbolInNumpad
            ALPHA -> params.localeKeyboardInfos.labelAlphabet
            COMMA -> params.localeKeyboardInfos.labelComma
            PERIOD -> getPeriodLabel(params)
            SPACE -> getSpaceLabel(params)
            ACTION -> "${getActionKeyLabel(params)}|${getActionKeyCode(params)}"
            DELETE -> "!icon/delete_key|!code/key_delete"
            SHIFT -> "${getShiftLabel(params)}|!code/key_shift"
            COM -> params.localeKeyboardInfos.tlds.first()
            LANGUAGE_SWITCH -> "!icon/language_switch_key|!code/key_language_switch"
            ZWNJ -> "!icon/zwnj_key|\u200C"
            CURRENCY -> params.localeKeyboardInfos.currencyKey.first
            CURRENCY1 -> params.localeKeyboardInfos.currencyKey.second[0]
            CURRENCY2 -> params.localeKeyboardInfos.currencyKey.second[1]
            CURRENCY3 -> params.localeKeyboardInfos.currencyKey.second[2]
            CURRENCY4 -> params.localeKeyboardInfos.currencyKey.second[3]
            CURRENCY5 -> params.localeKeyboardInfos.currencyKey.second[4]
            CTRL, ALT, FN, META, ESCAPE -> label.uppercase(Locale.US)
            TAB -> "!icon/tab_key|!code/${KeyCode.TAB}"
            TIMESTAMP -> "⌚"
            EMOJI_SEARCH -> "!icon/search_key|!code/key_emoji_search"
            else -> if (label in toolbarKeyStrings.values)
                "!icon/$label|!code/${getCodeForToolbarKey(ToolbarKey.valueOf(label.uppercase(Locale.US)))}"
            else label
        }
        val code = when (label) { // maybe a bit lazy to not assemble the entire string above
            SYMBOL_ALPHA -> KeyCode.SYMBOL_ALPHA
            SYMBOL       -> KeyCode.SYMBOL
            ALPHA        -> KeyCode.ALPHA
            CTRL         -> KeyCode.CTRL
            ALT          -> KeyCode.ALT
            FN           -> KeyCode.FN
            META         -> KeyCode.META
            ESCAPE       -> KeyCode.ESCAPE
            TIMESTAMP    -> KeyCode.TIMESTAMP
            else         -> null
        }
        return if (code == null) newLabel
        else "$newLabel|!code/$code"
    }

    private fun getShiftLabel(params: KeyboardParams) = when (params.id.element) {
        KeyboardElement.SYMBOLS_SHIFTED -> params.localeKeyboardInfos.labelSymbol
        KeyboardElement.SYMBOLS -> params.localeKeyboardInfos.getShiftSymbolLabel(
            Settings.getInstance().isTablet())
        KeyboardElement.ALPHABET_MANUAL_SHIFTED, KeyboardElement.ALPHABET_AUTOMATIC_SHIFTED -> "!icon/${KeyboardIconsSet.NAME_SHIFT_KEY_SHIFTED}"
        KeyboardElement.ALPHABET_SHIFT_LOCKED -> "!icon/${KeyboardIconsSet.NAME_SHIFT_KEY_LOCKED}"

        else -> "!icon/${KeyboardIconsSet.NAME_SHIFT_KEY}"
    }

    // todo (later): try avoiding this weirdness
    //  maybe just remove it and if users want it they can use custom functional layouts?
    //  but it has been like this "forever" and actually seems to make sense
    private fun getPeriodLabel(params: KeyboardParams): String {
        if (params.id.element.isNumberLayout) return "."
        if (params.id.element.isAlphabet || params.id.locale.language == "ar" || params.id.locale.language == "fa")
            return params.localeKeyboardInfos.labelPeriod
        return "."
    }

    private fun getSpaceLabel(params: KeyboardParams): String =
        if (params.id.element.takesFunctionalKeys || params.id.element.isBottomRow)
            "!icon/space_key|!code/key_space"
        else "!icon/space_key_for_number_layout|!code/key_space"

    // todo (later): should this be handled with metaState? but metaState shift would require LOTS of changes...
    private fun getActionKeyCode(params: KeyboardParams): String {
        params.id.internalAction?.let { return "${KeyboardCodesSet.PREFIX_CODE}${it.code}" }
        return if (params.id.isMultiLine && params.id.element == KeyboardElement.ALPHABET_MANUAL_SHIFTED)
            "!code/key_shift_enter"
        else "!code/key_enter"
    }

    private fun getActionKeyLabel(params: KeyboardParams): String {
        params.id.internalAction?.let { return it.label }
        if (params.id.isMultiLine && params.id.element == KeyboardElement.ALPHABET_MANUAL_SHIFTED)
            return "!icon/enter_key"
        val iconName = when (params.id.imeAction) {
            EditorInfo.IME_ACTION_GO               -> KeyboardIconsSet.NAME_GO_KEY
            EditorInfo.IME_ACTION_SEARCH           -> KeyboardIconsSet.NAME_SEARCH_KEY
            EditorInfo.IME_ACTION_SEND             -> KeyboardIconsSet.NAME_SEND_KEY
            EditorInfo.IME_ACTION_NEXT             -> KeyboardIconsSet.NAME_NEXT_KEY
            EditorInfo.IME_ACTION_DONE             -> KeyboardIconsSet.NAME_DONE_KEY
            EditorInfo.IME_ACTION_PREVIOUS         -> KeyboardIconsSet.NAME_PREVIOUS_KEY
            InputTypeUtils.IME_ACTION_CUSTOM_LABEL -> params.id.customActionLabel?.let { return it } ?: "!icon/enter_key"
            else                                   -> return "!icon/enter_key"
        }
        val replacement = iconName.replaceIconWithLabelIfNoDrawable(params)
        return if (iconName == replacement) // i.e. icon exists
            "!icon/$iconName"
        else
            replacement
    }
}
