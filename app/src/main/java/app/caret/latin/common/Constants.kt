/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.common

import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.BuildConfig

object Constants {

    class Color {
        companion object {
            /**
             * The alpha value for fully opaque.
             */
            const val ALPHA_OPAQUE = 255
        }
    }

    object ImeOption {
        /**
         * The private IME option used to indicate that no microphone should be shown for a given
         * text field. For instance, this is specified by the search dialog when the dialog is
         * already showing a voice search button.
         */
        const val NO_MICROPHONE = "noMicrophoneKey"

        /**
         * The private IME option used to suppress the floating gesture preview for a given text
         * field. This overrides the corresponding keyboard settings preference.
         * {@link app.caret.latin.settings.SettingsValues#mGestureFloatingPreviewTextEnabled}
         */
        const val NO_FLOATING_GESTURE_PREVIEW = "noGestureFloatingPreview"

        // This utility class is not publicly instantiable.
    }

    object Subtype {
        /** The subtype mode used to indicate that the subtype is a keyboard. */
        const val KEYBOARD_MODE = "keyboard"

        // some extra values:
        //  TrySuppressingImeSwitcher: not documented, but used in Android source
        //  SupportTouchPositionCorrection: never read, never used outside AOSP keyboard -> can be removed?
        object ExtraValue {
            /** Indicates that this subtype is capable of entering ASCII characters (not used, but recommended for Android 9 and older). */
            const val ASCII_CAPABLE = "AsciiCapable"

            /** Indicates that this subtype is enabled when the default subtype is not marked as ascii capable (used where?). */
            const val ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE = "EnabledWhenDefaultIsNotAsciiCapable"

            /** Indicates that this subtype is capable of entering emoji characters (always set?). */
            const val EMOJI_CAPABLE = "EmojiCapable"

            /** Indicates that the subtype does not have a shift key */
            const val NO_SHIFT_KEY = "NoShiftKey"

            /** Indicates that for this subtype corrections should not be based on proximity of keys for when shifted */
            const val NO_SHIFT_PROXIMITY_CORRECTION = "NoShiftProximityCorrection"

            /**
             * The subtype extra value used to indicate that the display name of this subtype
             * contains a "%s" for printf-like replacement and it should be replaced by
             * this extra value.
             * This extra value is supported on JellyBean and later.
             */
            const val UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME = "UntranslatableReplacementStringInSubtypeName"

            /** Contains the layouts used by this subtype. This extra value is private to LatinIME.*/
            const val KEYBOARD_LAYOUT_SET = "KeyboardLayoutSet"

            /** Indicates that this subtype is an additional subtype that the user defined. This extra value is private to LatinIME. */
            const val IS_ADDITIONAL_SUBTYPE = "isAdditionalSubtype"

            /** The subtype extra value used to specify the combining rules. */
            const val COMBINING_RULES = "CombiningRules"

            /** Overrides the general popup order setting */
            const val POPUP_ORDER = "PopupOrder"

            /** Overrides the general hint order / priority setting */
            const val HINT_ORDER = "HintOrder"

            /** Language tags indicating enabled secondary locales */
            const val SECONDARY_LOCALES = "SecondaryLocales"

            /** Overrides the general "more popups" setting */
            const val MORE_POPUPS = "MorePopups"

            /** Overrides the general "localized number row" setting */
            const val LOCALIZED_NUMBER_ROW = "LocalizedNumberRow"

            // This utility class is not publicly instantiable.
        }

        // This utility class is not publicly instantiable.
    }

    /** Separators for use in extra values and preferences. Notably cannot be = and , as they are already used in extra values */
    class Separators {
        companion object {
            /** key-value separator (to be used in subtype extra values) */
            const val KV = ":"
            /** separator between entries that might be key-value pairs (to be used in subtype extra values) */
            const val ENTRY = "|"
            /** separator between sets of entries (to be used for storing data for additional subtypes) */
            const val SET = "§"
            /** separator for sets (to be used for storing multiple extra additional subtypes in prefs) */
            const val SETS = ";"
        }
    }

    object TextUtils {
        /**
         * Capitalization mode for {@link android.text.TextUtils#getCapsMode}: don't capitalize
         * characters.  This value may be used with
         * {@link android.text.TextUtils#CAP_MODE_CHARACTERS},
         * {@link android.text.TextUtils#CAP_MODE_WORDS}, and
         * {@link android.text.TextUtils#CAP_MODE_SENTENCES}.
         */
        // TODO: Straighten this out. It's bizarre to have to use android.text.TextUtils.CAP_MODE_*
        // except for OFF that is in Constants.TextUtils.
        const val CAP_MODE_OFF = 0

        // This utility class is not publicly instantiable.
    }

    const val NOT_A_CODE = -1
    const val NOT_A_CURSOR_POSITION = -1
    // TODO: replace the following constants with state in InputTransaction?
    const val NOT_A_COORDINATE = -1
    const val SUGGESTION_STRIP_COORDINATE = -2
    const val EXTERNAL_KEYBOARD_COORDINATE = -4

    // A hint on how many characters to cache from the TextView. A good value of this is given by
    // how many characters we need to be able to almost always find the caps mode.
    const val EDITOR_CONTENTS_CACHE_SIZE = 1024
    // How many characters we accept for the recapitalization functionality. This needs to be
    // large enough for all reasonable purposes, but avoid purposeful attacks. 100k sounds about
    // right for this.
    const val MAX_CHARACTERS_FOR_RECAPITALIZATION = 1024 * 100

    // Key events coming any faster than this are long-presses.
    const val LONG_PRESS_MILLISECONDS = 200
    // TODO: Set this value appropriately.
    @JvmField
    val GET_SUGGESTED_WORDS_TIMEOUT = if (BuildConfig.DEBUG) 500 else 200 // debug build is slow, and timeout is annoying for testing
    // How many continuous deletes at which to start deleting at a higher speed.
    const val DELETE_ACCELERATE_AT = 20
    // Caret: while backspace is held, whole words are deleted after this many repeats,
    // one word every DELETE_WORDS_EVERY repeats
    const val DELETE_WORDS_AT = 12
    const val DELETE_WORDS_EVERY = 3
    const val DELETE_WORDS_LOOKBACK = 96

    const val WORD_SEPARATOR = " "

    @JvmStatic
    fun isValidCoordinate(coordinate: Int): Boolean {
        // Detect {@link NOT_A_COORDINATE}, {@link SUGGESTION_STRIP_COORDINATE},
        // and {@link SPELL_CHECKER_COORDINATE}.
        return coordinate >= 0
    }

    /**
     * Some common keys code. Must be positive.
     */
    const val CODE_ENTER = '\n'.code
    const val CODE_TAB = '\t'.code
    const val CODE_SPACE = ' '.code
    const val CODE_PERIOD = '.'.code
    const val CODE_COMMA = ','.code
    const val CODE_DASH = '-'.code
    const val CODE_SINGLE_QUOTE = '\''.code
    const val CODE_DOUBLE_QUOTE = '"'.code
    const val CODE_SLASH = '/'.code
    const val CODE_BACKSLASH = '\\'.code
    const val CODE_VERTICAL_BAR = '|'.code
    const val CODE_COMMERCIAL_AT = '@'.code
    const val CODE_PLUS = '+'.code
    const val CODE_PERCENT = '%'.code
    const val CODE_CLOSING_PARENTHESIS = ')'.code
    const val CODE_CLOSING_SQUARE_BRACKET = ']'.code
    const val CODE_CLOSING_CURLY_BRACKET = '}'.code
    const val CODE_CLOSING_ANGLE_BRACKET = '>'.code
    const val CODE_INVERTED_QUESTION_MARK = '¿'.code
    const val CODE_INVERTED_EXCLAMATION_MARK = '¡'.code
    const val CODE_GRAVE_ACCENT = '`'.code
    const val CODE_CIRCUMFLEX_ACCENT = '^'.code
    const val CODE_TILDE = '~'.code
    const val RECENTS_TEMPLATE_KEY_CODE_0 = 0x30
    const val RECENTS_TEMPLATE_KEY_CODE_1 = 0x31

    const val REGEXP_PERIOD = "\\."
    const val STRING_SPACE = " "

    @JvmStatic
    fun isLetterCode(code: Int): Boolean {
        return code >= CODE_SPACE
    }

    @JvmStatic
    fun printableCode(code: Int): String {
        return when (code) {
            KeyCode.SHIFT -> "shift"
            KeyCode.CAPS_LOCK -> "capslock"
            KeyCode.SYMBOL_ALPHA -> "symbol_alpha"
            KeyCode.ALPHA -> "alpha"
            KeyCode.SYMBOL -> "symbol"
            KeyCode.MULTIPLE_CODE_POINTS -> "text"
            KeyCode.DELETE -> "delete"
            KeyCode.SETTINGS -> "settings"
            KeyCode.VOICE_INPUT -> "shortcut"
            KeyCode.ACTION_NEXT -> "actionNext"
            KeyCode.ACTION_PREVIOUS -> "actionPrevious"
            KeyCode.LANGUAGE_SWITCH -> "languageSwitch"
            KeyCode.EMOJI -> "emoji"
            KeyCode.CLIPBOARD -> "clipboard"
            KeyCode.SHIFT_ENTER -> "shiftEnter"
            KeyCode.NOT_SPECIFIED -> "unspec"
            CODE_TAB -> "tab"
            CODE_ENTER -> "enter"
            CODE_SPACE -> "space"
            KeyCode.TOGGLE_ONE_HANDED_MODE -> "toggleOneHandedMode"
            KeyCode.SWITCH_ONE_HANDED_MODE -> "switchOneHandedMode"
            KeyCode.SPLIT_LAYOUT -> "splitLayout"
            KeyCode.NUMPAD -> "numpad"
            KeyCode.DPAD -> "dpad"
            KeyCode.EMOJI_SEARCH -> "emojiSearch"
            else -> {
                if (code < CODE_SPACE) String.format("\\u%02X", code)
                else if (code < 0x100) String.format("%c", code)
                else if (code < 0x10000) String.format("\\u%04X", code)
                else String.format("\\U%05X", code)
            }
        }
    }

    /**
     * Screen metrics (a.k.a. Device form factor) constants of
     * {@link app.caret.latin.R.integer#config_screen_metrics}.
     */
    const val SCREEN_METRICS_SMALL_PHONE = 0
    const val SCREEN_METRICS_LARGE_PHONE = 1
    const val SCREEN_METRICS_LARGE_TABLET = 2
    const val SCREEN_METRICS_SMALL_TABLET = 3

    /**
     * Default capacity of gesture points container.
     * This constant is used by {@link app.caret.keyboard.internal.BatchInputArbiter}
     * and etc. to preallocate regions that contain gesture event points.
     */
    const val DEFAULT_GESTURE_POINTS_CAPACITY = 128

    const val MAX_IME_DECODER_RESULTS = 20
    const val DECODER_SCORE_SCALAR = 1000000
    const val DECODER_MAX_SCORE = 1000000000

    // This utility class is not publicly instantiable.
}

// The declarations below were in this Kotlin file before the Java class of the same name was converted.

object Links {
    const val DICTIONARY_URL = "https://codeberg.org/Helium314/aosp-dictionaries"
    const val DICTIONARY_DOWNLOAD_SUFFIX = "/raw/branch/main/"
    const val DICTIONARY_NORMAL_SUFFIX = "dictionaries/"
    const val DICTIONARY_EXPERIMENTAL_SUFFIX = "dictionaries_experimental/"
    const val DICTIONARY_EMOJI_CLDR_SUFFIX = "emoji_cldr_signal_dictionaries/"
    const val GITHUB = "https://github.com/PimpinPumpkin/caret"
    /** HeliBoard, which Caret is forked from. Its wiki and discussions still describe most of the app. */
    const val UPSTREAM = "https://github.com/HeliBorg/HeliBoard"
    const val LICENSE = "$GITHUB/blob/main/LICENSE"
    const val WIKI_URL = "$UPSTREAM/wiki"
    const val LAYOUT_WIKI_URL = "$WIKI_URL/2.-Layouts"
    const val CUSTOM_LAYOUTS = "$UPSTREAM/discussions/categories/custom-layout"
    const val CUSTOM_COLORS = "$UPSTREAM/discussions/categories/custom-colors"
    const val GESTURE_DATA_VIDEO_PEERTUBE = "https://makertube.net/w/cQECfDkuLGR9eUQquUEo4K"
    const val GESTURE_DATA_VIDEO_YOUTUBE = "https://youtu.be/CyjumVTWtJA"
    const val GESTURE_DATA_WIKI = "$WIKI_URL/Tutorial:-How-to-Contribute-Gesture-Data"
    const val BACKGROUND_GESTURE_DATA_VIDEO_PEERTUBE = "https://makertube.net/w/pPywMiF7kjumFfMrQKHzoU"
    const val BACKGROUND_GESTURE_DATA_VIDEO_YOUTUBE = "https://youtu.be/VJLzVEhY2PY"
    const val BACKGROUND_GESTURE_DATA_WIKI = "$WIKI_URL/Background-Gesture-Data-Gathering"
    const val SWIPE_O_SCOPE = "https://codeberg.org/eclexic/swipe-o-scope"
    const val COMMUNITY_LINKS = "$UPSTREAM#to-community"
}

val combiningRange = 0x300..0x35b
