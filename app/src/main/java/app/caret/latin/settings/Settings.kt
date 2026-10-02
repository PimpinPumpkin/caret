/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import app.caret.compat.locale
import app.caret.keyboard.KeyboardActionListener
import app.caret.keyboard.internal.PopupKeySpec
import app.caret.latin.AudioAndHapticFeedbackManager
import app.caret.latin.InputAttributes
import app.caret.latin.PunctuationSuggestions
import app.caret.latin.R
import app.caret.latin.RichInputMethodManager
import app.caret.latin.RichInputMethodSubtype
import app.caret.latin.common.StringUtils
import app.caret.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import app.caret.latin.utils.DeviceProtectedUtils
import app.caret.latin.utils.FoldableUtils
import app.caret.latin.utils.LayoutType
import app.caret.latin.utils.Log
import app.caret.latin.utils.ResourceUtils
import app.caret.latin.utils.StatsUtils
import app.caret.latin.utils.SubtypeSettings
import app.caret.latin.utils.ToolbarKey
import app.caret.latin.utils.ToolbarMode
import app.caret.latin.utils.clearCustomToolbarKeyCodes
import app.caret.latin.utils.getCustomKeyCode
import app.caret.latin.utils.getCustomLongpressKeyCode
import app.caret.latin.utils.prefs
import app.caret.latin.utils.runInLocale
import java.io.File
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import android.provider.Settings.Global
import androidx.core.content.edit
import kotlinx.serialization.json.Json

class Settings : SharedPreferences.OnSharedPreferenceChangeListener {
    private var context: Context? = null
    private lateinit var prefs: SharedPreferences
    private var settingsValues: SettingsValues? = null
    private val settingsValuesLock = ReentrantLock()

    private constructor() {
        // Intentional empty constructor for singleton.
    }

    private fun onCreate(context: Context) {
        this.context = context
        prefs = context.prefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key != null && !reloadOnChanged(key))
            return
        settingsValuesLock.lock()
        try {
            val oldValues = settingsValues
            if (oldValues == null) {
                // TODO: Introduce a static function to register this class and ensure that
                // loadSettings must be called before "onSharedPreferenceChanged" is called.
                Log.w(TAG, "onSharedPreferenceChanged called before loadSettings.")
                return
            }
            clearCustomToolbarKeyCodes()
            loadSettings(context!!, oldValues.locale, oldValues.inputAttributes)
            StatsUtils.onLoadSettings(settingsValues)
        } finally {
            settingsValuesLock.unlock()
        }
        if (PREF_ADDITIONAL_SUBTYPES == key) {
            SubtypeSettings.reloadEnabledSubtypes(context!!)
        }
    }

    /** convenience function for the rare situations where we need to load settings but may not have a keyboard */
    fun loadSettings(context: Context) {
        if (settingsValues != null) return
        val locale = context.resources.configuration.locale()
        val inputAttributes = InputAttributes(EditorInfo(), false, context.packageName)
        loadSettings(context, locale, inputAttributes)
    }

    fun loadSettings(context: Context, locale: Locale,
                     inputAttributes: InputAttributes) {
        settingsValuesLock.lock()
        this.context = context
        try {
            val currentPrefs = prefs
            Log.i(TAG, "loadSettings")
            settingsValues = runInLocale(context, locale) { ctx ->
                SettingsValues(ctx, currentPrefs, ctx.resources, inputAttributes)
            }
        } finally {
            settingsValuesLock.unlock()
        }
    }

    fun stopListener() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    fun startListener() {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    // TODO: Remove this method and add proxy method to SettingsValues.
    // The return type is deliberately left to inference from Class.cast, which gives the same platform type
    // the Java method had: null before loadSettings (some callers check for that), no null check forced on
    // the many callers that do not.
    val current get() = SettingsValues::class.java.cast(settingsValues)

    fun toggleAutoCorrect() {
        val oldValue = prefs.getBoolean(PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION)
        prefs.edit().putBoolean(PREF_AUTO_CORRECTION, !oldValue).apply()
    }

    fun toggleAlwaysIncognitoMode() {
        val oldValue = prefs.getBoolean(PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE)
        prefs.edit().putBoolean(PREF_ALWAYS_INCOGNITO_MODE, !oldValue).apply()
    }

    fun writeOneHandedModeEnabled(enabled: Boolean) {
        val sv = settingsValues!!
        val landscape = sv.displayOrientation == Configuration.ORIENTATION_LANDSCAPE
        val index = findIndexOfDefaultSetting(landscape, sv.isSplitKeyboardEnabled, FoldableUtils.isFolded)
        val key = createPrefKeyForBooleanSettings(PREF_ONE_HANDED_MODE_PREFIX, index, 3)
        prefs.edit().putBoolean(key, enabled).apply()
    }

    fun writeOneHandedModeScale(scale: Float) {
        val sv = settingsValues!!
        val landscape = sv.displayOrientation == Configuration.ORIENTATION_LANDSCAPE
        val index = findIndexOfDefaultSetting(landscape, sv.isSplitKeyboardEnabled, FoldableUtils.isFolded)
        val key = createPrefKeyForBooleanSettings(PREF_ONE_HANDED_SCALE_PREFIX, index, 3)
        prefs.edit().putFloat(key, scale).apply()
    }

    fun writeOneHandedModeGravity(gravity: Int) {
        val sv = settingsValues!!
        val landscape = sv.displayOrientation == Configuration.ORIENTATION_LANDSCAPE
        val index = findIndexOfDefaultSetting(landscape, sv.isSplitKeyboardEnabled, FoldableUtils.isFolded)
        val key = createPrefKeyForBooleanSettings(PREF_ONE_HANDED_GRAVITY_PREFIX, index, 3)
        prefs.edit().putInt(key, gravity).apply()
    }

    fun writeSplitKeyboardEnabled(enabled: Boolean, isLandscape: Boolean, isFolded: Boolean) {
        val pref = if (isLandscape)
                (if (isFolded) PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE else PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE)
            else
                (if (isFolded) PREF_ENABLE_SPLIT_KEYBOARD_FOLDED else PREF_ENABLE_SPLIT_KEYBOARD)
        prefs.edit().putBoolean(pref, enabled).apply()
    }

    fun readShowToolbarOnly(): Boolean {
        return settingsValues!!.hasHardwareKeyboard
            && prefs.getBoolean(PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD, Defaults.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD)
    }

    fun isTablet(): Boolean {
        return context!!.resources.getInteger(R.integer.config_screen_metrics) >= 3
    }

    @SuppressLint("DiscouragedApi")
    fun getStringResIdByName(name: String?): Int {
        val ctx = context!!
        return ctx.resources.getIdentifier(name, "string", ctx.packageName)
    }

    fun getInLocale(@StringRes resId: Int, locale: Locale): String {
        return runInLocale(context!!, locale) { ctx -> ctx.getString(resId) }
    }

    fun readCustomCurrencyKey(): String {
        return prefs.getString(PREF_CUSTOM_CURRENCY_KEY, Defaults.PREF_CUSTOM_CURRENCY_KEY)!!
    }

    fun getCustomToolbarKeyCode(key: ToolbarKey): Int? {
        return getCustomKeyCode(key, prefs)
    }

    fun getCustomToolbarLongpressCode(key: ToolbarKey): Int? {
        return getCustomLongpressKeyCode(key, prefs)
    }

    fun saveSubtypeForApp(subtype: RichInputMethodSubtype, packageName: String?) {
        if (isSubtypePerApp() && !StringUtils.isEmpty(packageName)) {
            prefs.edit().putString(PREF_SAVED_APP_SUBTYPE_PREFIX + packageName,
                                    subtype.rawSubtype.toSettingsSubtype().toPref()).apply()
        }
    }

    fun getSubtypeForApp(packageName: String?): RichInputMethodSubtype? {
        if (!isSubtypePerApp() || StringUtils.isEmpty(packageName)) return null
        val subtypePref = prefs.getString(PREF_SAVED_APP_SUBTYPE_PREFIX + packageName, null)
        if (subtypePref == null) return null
        val settingsSubtype = subtypePref.toSettingsSubtype()
        var subtype = settingsSubtype.toEnabledSubtype()
        if (subtype == null) subtype = RichInputMethodManager.getInstance().findSubtypeForHintLocale(settingsSubtype.locale)
        return if (subtype != null) RichInputMethodSubtype.get(subtype) else null
    }

    private fun isSubtypePerApp(): Boolean {
        return prefs.getBoolean(PREF_SAVE_SUBTYPE_PER_APP, Defaults.PREF_SAVE_SUBTYPE_PER_APP)
    }

    companion object {
        private val TAG = Settings::class.java.simpleName

        // theme-related stuff
        const val PREF_THEME_STYLE = "theme_style"
        const val PREF_ICON_STYLE = "icon_style"
        const val PREF_THEME_COLORS = "theme_colors"
        const val PREF_THEME_COLORS_NIGHT = "theme_colors_night"
        const val PREF_THEME_KEY_BORDERS = "theme_key_borders"
        const val PREF_THEME_DAY_NIGHT = "theme_auto_day_night"
        const val PREF_USER_COLORS_PREFIX = "user_colors_"
        const val PREF_USER_ALL_COLORS_PREFIX = "user_all_colors_"
        const val PREF_USER_MORE_COLORS_PREFIX = "user_more_colors_"

        const val PREF_CUSTOM_ICON_NAMES = "custom_icon_names"
        const val PREF_TOOLBAR_CUSTOM_KEY_CODES = "toolbar_custom_key_codes"
        const val PREF_LAYOUT_PREFIX = "layout_"

        const val PREF_AUTO_CAP = "auto_cap"
        const val PREF_VIBRATE_ON = "vibrate_on"
        const val PREF_VIBRATE_IN_DND_MODE = "vibrate_in_dnd_mode"
        const val PREF_SOUND_ON = "sound_on"
        const val PREF_SUGGEST_EMOJIS = "suggest_emojis"
        const val PREF_INLINE_EMOJI_SEARCH = "inline_emoji_search"
        const val PREF_SHOW_EMOJI_DESCRIPTIONS = "show_emoji_descriptions"
        const val PREF_POPUP_ON = "popup_on"
        const val PREF_AUTO_CORRECTION = "auto_correction"
        const val PREF_MORE_AUTO_CORRECTION = "more_auto_correction"
        const val PREF_AUTO_CORRECT_CONFIDENCE = "auto_correct_confidence"
        const val PREF_AUTOCORRECT_SHORTCUTS = "autocorrect_shortcuts"
        const val PREF_BACKSPACE_REVERTS_AUTOCORRECT = "backspace_reverts_autocorrect"
        const val PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION = "autocorrect_capitalized_suggestion"
        const val PREF_CENTER_SUGGESTION_TEXT_TO_ENTER = "center_suggestion_text_to_enter"
        const val PREF_SHOW_SUGGESTIONS = "show_suggestions"
        const val PREF_ALWAYS_SHOW_SUGGESTIONS = "always_show_suggestions"
        const val PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT = "always_show_suggestions_except_web_text"
        const val PREF_KEY_USE_PERSONALIZED_DICTS = "use_personalized_dicts"
        const val PREF_KEY_USE_DOUBLE_SPACE_PERIOD = "use_double_space_period"
        const val PREF_BLOCK_POTENTIALLY_OFFENSIVE = "block_potentially_offensive"
        const val PREF_SHOW_LANGUAGE_SWITCH_KEY = "show_language_switch_key"
        const val PREF_LANGUAGE_SWITCH_KEY = "language_switch_key"
        const val PREF_SHOW_EMOJI_KEY = "show_emoji_key"
        const val PREF_SHOW_DPAD_KEY = "show_dpad_key"
        const val PREF_VARIABLE_TOOLBAR_DIRECTION = "var_toolbar_direction"
        const val PREF_ADDITIONAL_SUBTYPES = "additional_subtypes"
        const val PREF_ENABLE_SPLIT_KEYBOARD = "split_keyboard"
        const val PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE = "split_keyboard_landscape"
        const val PREF_ENABLE_SPLIT_KEYBOARD_FOLDED = "split_keyboard_folded"
        const val PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE = "split_keyboard_folded_landscape"
        const val PREF_SPLIT_SPACER_SCALE_PREFIX = "split_spacer_scale"
        const val PREF_KEYBOARD_HEIGHT_SCALE_PREFIX = "keyboard_height_scale"
        const val PREF_BOTTOM_ROW_SCALE_PREFIX = "bottom_row_scale"
        const val PREF_BOTTOM_PADDING_SCALE_PREFIX = "bottom_padding_scale"
        const val PREF_SIDE_PADDING_SCALE_PREFIX = "side_padding_scale"
        const val PREF_KEY_GAP_SCALE_PREFIX = "key_gap_scale"
        const val PREF_FLOATING_HEIGHT_PREFIX = "floating_height"
        const val PREF_FLOATING_WIDTH_PREFIX = "floating_width"
        const val PREF_FLOATING_ENABLED_PREFIX = "floating_enabled"
        const val PREF_FLOATING_POS_X_PREFIX = "floating_pos_x"
        const val PREF_FLOATING_POS_Y_PREFIX = "floating_pos_y"
        const val PREF_FONT_SCALE = "font_scale"
        const val PREF_HINT_FONT_SCALE = "hint_font_scale"
        const val PREF_EMOJI_FONT_SCALE = "emoji_font_scale"
        const val PREF_EMOJI_KEY_FIT = "emoji_key_fit"
        const val PREF_EMOJI_SKIN_TONE = "emoji_skin_tone"
        const val PREF_SPACE_HORIZONTAL_SWIPE = "horizontal_space_swipe"
        const val PREF_SPACE_VERTICAL_SWIPE = "vertical_space_swipe"
        const val PREF_DELETE_SWIPE = "delete_swipe"
        const val PREF_SWIPE_DELETE_BY_WORD = "swipe_delete_by_word"
        const val PREF_HOLD_DELETE_BY_WORD = "hold_delete_by_word"
        const val PREF_AUTOSPACE_AFTER_PUNCTUATION = "autospace_after_punctuation"
        const val PREF_AUTOSPACE_AFTER_SUGGESTION = "autospace_after_suggestion"
        const val PREF_AUTOSPACE_AFTER_GESTURE_TYPING = "autospace_after_gesture_typing"
        const val PREF_AUTOSPACE_BEFORE_GESTURE_TYPING = "autospace_before_gesture_typing"
        const val PREF_SHIFT_REMOVES_AUTOSPACE = "shift_removes_autospace"
        const val PREF_ALWAYS_INCOGNITO_MODE = "always_incognito_mode"
        const val PREF_BIGRAM_PREDICTIONS = "next_word_prediction"
        // Caret: rank next-word suggestions with the swipe models' language model
        const val PREF_LM_NEXT_WORD = "lm_next_word"
        // Caret: the microphone key dictates inside the keyboard instead of switching keyboards
        const val PREF_INLINE_DICTATION = "inline_dictation"
        const val PREF_DICTATION_SOUND = "dictation_sound"
        // Caret: hide copied card numbers, tokens, keys and seed phrases where the clipboard is shown
        const val PREF_MASK_CLIPBOARD_SECRETS = "mask_clipboard_secrets"
        // Caret: arrow keys of a remote move between keys; on by default on a TV
        const val PREF_REMOTE_NAVIGATION = "remote_navigation"
        const val PREF_SUGGEST_PUNCTUATION = "suggest_punctuation"
        const val PREF_PUNCTUATION_SUGGESTIONS = "punctuation_suggestions"
        const val PREF_SUGGEST_CLIPBOARD_CONTENT = "suggest_clipboard_content"
        const val PREF_GESTURE_INPUT = "gesture_input"
        const val PREF_VIBRATION_DURATION_SETTINGS = "vibration_duration_settings"
        const val PREF_KEYPRESS_SOUND_VOLUME = "keypress_sound_volume"
        const val PREF_KEY_LONGPRESS_TIMEOUT = "key_longpress_timeout"
        const val PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY = "enable_emoji_alt_physical_key"
        const val PREF_GESTURE_PREVIEW_TRAIL = "gesture_preview_trail"
        const val PREF_GESTURE_FLOATING_PREVIEW_TEXT = "gesture_floating_preview_text"
        const val PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC = "gesture_floating_preview_dynamic"
        const val PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM = "gesture_dynamic_preview_follow_system"
        const val PREF_GESTURE_SPACE_AWARE = "gesture_space_aware"
        const val PREF_GESTURE_FAST_TYPING_COOLDOWN = "gesture_fast_typing_cooldown"
        const val PREF_GESTURE_TRAIL_FADEOUT_DURATION = "gesture_trail_fadeout_duration"
        const val PREF_SHOW_SETUP_WIZARD_ICON = "show_setup_wizard_icon"
        const val PREF_USE_CONTACTS = "use_contacts"
        const val PREF_USE_APPS = "use_apps"
        const val PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD = "long_press_symbols_for_numpad"

        const val PREF_ONE_HANDED_MODE_PREFIX = "one_handed_mode_enabled"
        const val PREF_ONE_HANDED_GRAVITY_PREFIX = "one_handed_mode_gravity"
        const val PREF_ONE_HANDED_SCALE_PREFIX = "one_handed_mode_scale"

        const val PREF_SHOW_NUMBER_ROW = "show_number_row"
        const val PREF_SHOW_NUMBER_ROW_IN_SYMBOLS = "show_number_row_in_symbols"
        const val PREF_LOCALIZED_NUMBER_ROW = "localized_number_row"
        const val PREF_SHOW_NUMBER_ROW_HINTS = "show_number_row_hints"
        const val PREF_CUSTOM_CURRENCY_KEY = "custom_currency_key"

        const val PREF_SHOW_HINTS = "show_hints"
        const val PREF_POPUP_KEYS_ORDER = "popup_keys_order"
        const val PREF_POPUP_KEYS_HINT_ORDER = "popup_keys_labels_order"
        const val PREF_SHOW_POPUP_HINTS = "show_popup_hints"
        const val PREF_MORE_POPUP_KEYS = "more_popup_keys"
        const val PREF_SHOW_TLD_POPUP_KEYS = "show_tld_popup_keys"
        const val PREF_URL_EMAIL_LAYOUT = "url_email_layout"

        const val PREF_SPACE_TO_CHANGE_LANG = "prefs_long_press_keyboard_to_change_lang"
        const val PREF_LANGUAGE_SWIPE_DISTANCE = "language_swipe_distance"
        const val PREF_TOUCHPAD_SENSITIVITY = "touchpad_sensitivity"
        const val PREF_TOUCHPAD_EDGE_SCROLL = "touchpad_edge_scroll"

        const val PREF_ENABLE_CLIPBOARD_HISTORY = "enable_clipboard_history"
        const val PREF_CLIPBOARD_HISTORY_RETENTION_TIME = "clipboard_history_retention_time"
        const val PREF_CLIPBOARD_HISTORY_PINNED_FIRST = "clipboard_history_pinned_first"
        const val PREF_CLIPBOARD_USE_FILES = "clipboard_histor_usey_files"
        const val PREF_CLIPBOARD_FILES_SIZE_LIMIT = "clipboard_history_files_size_limit"

        const val PREF_ADD_TO_PERSONAL_DICTIONARY = "add_to_personal_dictionary"
        const val PREF_NAVBAR_COLOR = "navbar_color"
        const val PREF_ENABLED_SUBTYPES = "enabled_subtypes"
        const val PREF_SELECTED_SUBTYPE = "selected_subtype"
        const val PREF_URL_DETECTION = "url_detection"
        const val PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG = "dont_show_missing_dict_dialog"
        const val PREF_QUICK_PIN_TOOLBAR_KEYS = "quick_pin_toolbar_keys"
        const val PREF_TOOLBAR_ABOVE_SUGGESTIONS = "toolbar_above_suggestions"
        const val PREF_PINNED_TOOLBAR_KEYS = "pinned_toolbar_keys"
        const val PREF_TOOLBAR_KEYS = "toolbar_keys"
        const val PREF_AUTO_SHOW_TOOLBAR = "auto_show_toolbar"
        const val PREF_AUTO_HIDE_TOOLBAR = "auto_hide_toolbar"
        const val PREF_CLIPBOARD_TOOLBAR_KEYS = "clipboard_toolbar_keys"
        const val PREF_ABC_AFTER_EMOJI = "abc_after_emoji"
        const val PREF_ABC_AFTER_CLIP = "abc_after_clip"
        const val PREF_ABC_AFTER_SYMBOL_SPACE = "abc_after_symbol_space"
        const val PREF_ABC_AFTER_NUMPAD_SPACE = "abc_after_numpad_space"
        const val PREF_REMOVE_REDUNDANT_POPUPS = "remove_redundant_popups"
        const val PREF_SPACE_BAR_TEXT = "space_bar_text"
        const val PREF_TIMESTAMP_FORMAT = "timestamp_format"
        const val PREF_TOOLBAR_MODE = "toolbar_mode"
        const val PREF_TOOLBAR_HIDING_GLOBAL = "toolbar_hiding_global"
        const val PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE = "toolbar_swipe_down_to_hide"
        const val PREF_SPELLCHECK_SUGGEST = "spellcheck_suggest"
        const val PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD = "only_toolbar_with_hw_keyboard"

        // Emoji
        const val PREF_EMOJI_MAX_SDK = "emoji_max_sdk"
        const val PREF_RECENT_EMOJIS = "recent_emojis"
        const val PREF_LAST_SHOWN_EMOJI_CATEGORY_ID = "last_shown_emoji_category_id"
        const val PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID = "last_shown_emoji_category_page_id"

        const val PREF_VERSION_CODE = "version_code"
        const val PREF_LIBRARY_CHECKSUM = "lib_checksum"
        const val PREF_SAVE_SUBTYPE_PER_APP = "save_subtype_per_app"
        const val PREF_SAVED_APP_SUBTYPE_PREFIX = "saved_app_subtype_"

        // static cache for background images to avoid potentially slow reload on every settings reload
        private val cachedBackgroundImages = arrayOfNulls<Drawable>(4)

        @JvmStatic
        val instance = Settings()

        // preferences that are not used in SettingsValues and thus should not trigger reload when changed
        private fun reloadOnChanged(key: String): Boolean {
            return when (key) {
                PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID, PREF_LAST_SHOWN_EMOJI_CATEGORY_ID, PREF_RECENT_EMOJIS,
                PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG, PREF_SELECTED_SUBTYPE -> false
                else -> !key.startsWith(PREF_SAVED_APP_SUBTYPE_PREFIX) && !key.startsWith("floating_pos")
            }
        }

        // Return type inferred from Class.cast on purpose (platform type, may be null before loadSettings),
        // see current.
        @JvmStatic
        val values get() = SettingsValues::class.java.cast(instance.settingsValues)

        // Return type inferred from Class.cast on purpose (platform type, null before init), see current.
        @JvmStatic
        val currentContext get() = Context::class.java.cast(instance.context)

        @JvmStatic
        fun init(context: Context) {
            instance.onCreate(context)
        }

        @JvmStatic
        fun readScreenMetrics(res: Resources): Int {
            return res.getInteger(R.integer.config_screen_metrics)
        }

        @JvmStatic
        fun readVibrationEnabled(prefs: SharedPreferences): Boolean {
            return prefs.getBoolean(PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON)
                    && AudioAndHapticFeedbackManager.getInstance().hasVibrator()
        }

        @JvmStatic
        fun readGestureDynamicPreviewEnabled(prefs: SharedPreferences): Boolean {
            val followSystem = prefs.getBoolean(PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM, Defaults.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM)
            val defValue = Defaults.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM
            val curValue = prefs.getBoolean(PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC, defValue)
            return if (followSystem) defValue else curValue
        }

        @JvmStatic
        fun readGestureDynamicPreviewDefault(context: Context): Boolean {
            // if transitions are disabled for the system (reduced motion), moving preview should be disabled
            return getTransitionAnimationScale(context) != 0.0f
        }

        @JvmStatic
        fun readDefaultGestureFastTypingCooldown(res: Resources): Int {
            return res.getInteger(R.integer.config_gesture_static_time_threshold_after_fast_typing)
        }

        @JvmStatic
        fun readToolbarMode(prefs: SharedPreferences): ToolbarMode {
            return ToolbarMode.valueOf(prefs.getString(PREF_TOOLBAR_MODE, Defaults.PREF_TOOLBAR_MODE)!!)
        }

        @JvmStatic
        fun readHorizontalSpaceSwipe(prefs: SharedPreferences): KeyboardActionListener.SwipeAction {
            try {
                val value = prefs.getString(PREF_SPACE_HORIZONTAL_SWIPE, Defaults.PREF_SPACE_HORIZONTAL_SWIPE)
                return KeyboardActionListener.SwipeAction.valueOf(value!!)
            } catch (e: IllegalArgumentException) {
                return KeyboardActionListener.SwipeAction.NONE
            }
        }

        @JvmStatic
        fun readVerticalSpaceSwipe(prefs: SharedPreferences): KeyboardActionListener.SwipeAction {
            try {
                val value = prefs.getString(PREF_SPACE_VERTICAL_SWIPE, Defaults.PREF_SPACE_VERTICAL_SWIPE)
                return KeyboardActionListener.SwipeAction.valueOf(value!!)
            } catch (e: IllegalArgumentException) {
                return KeyboardActionListener.SwipeAction.NONE
            }
        }

        @JvmStatic
        fun readFullscreenModeAllowed(res: Resources): Boolean {
            return res.getBoolean(R.bool.config_fullscreen_mode_allowed)
        }

        @JvmStatic
        fun readShowSetupWizardIcon(prefs: SharedPreferences,
                                    context: Context): Boolean {
            if (!prefs.contains(PREF_SHOW_SETUP_WIZARD_ICON)) {
                val appInfo = context.applicationInfo
                val isApplicationInSystemImage =
                        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // Default value
                return !isApplicationInSystemImage
            }
            return prefs.getBoolean(PREF_SHOW_SETUP_WIZARD_ICON, Defaults.PREF_SHOW_SETUP_WIZARD_ICON)
        }

        /** True on a TV, where a remote is the usual way to type. */
        @JvmStatic
        fun isTelevision(context: Context): Boolean {
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            return uiMode?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        }

        @JvmStatic
        fun readOneHandedModeEnabled(prefs: SharedPreferences, landscape: Boolean, split: Boolean, folded: Boolean): Boolean {
            val index = findIndexOfDefaultSetting(landscape, split, folded)
            val key = createPrefKeyForBooleanSettings(PREF_ONE_HANDED_MODE_PREFIX, index, 3)
            return prefs.getBoolean(key, Defaults.PREF_ONE_HANDED_MODE)
        }

        @JvmStatic
        fun readOneHandedModeScale(prefs: SharedPreferences, landscape: Boolean, split: Boolean, folded: Boolean): Float {
            val index = findIndexOfDefaultSetting(landscape, split, folded)
            val key = createPrefKeyForBooleanSettings(PREF_ONE_HANDED_SCALE_PREFIX, index, 3)
            return prefs.getFloat(key, Defaults.PREF_ONE_HANDED_SCALE)
        }

        @JvmStatic
        fun readOneHandedModeGravity(prefs: SharedPreferences, landscape: Boolean, split: Boolean, folded: Boolean): Int {
            val index = findIndexOfDefaultSetting(landscape, split, folded)
            val key = createPrefKeyForBooleanSettings(PREF_ONE_HANDED_GRAVITY_PREFIX, index, 3)
            return prefs.getInt(key, Defaults.PREF_ONE_HANDED_GRAVITY)
        }

        @JvmStatic
        fun readSplitKeyboardEnabled(prefs: SharedPreferences, isLandscape: Boolean, isFolded: Boolean): Boolean {
            val pref = if (isLandscape)
                    (if (isFolded) PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE else PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE)
                else
                    (if (isFolded) PREF_ENABLE_SPLIT_KEYBOARD_FOLDED else PREF_ENABLE_SPLIT_KEYBOARD)
            return prefs.getBoolean(pref, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
        }

        @JvmStatic
        fun readSplitSpacerScale(prefs: SharedPreferences, landscape: Boolean, folded: Boolean): Float {
            val index = findIndexOfDefaultSetting(landscape, folded)
            val defaults = Defaults.PREF_SPLIT_SPACER_SCALE
            val defaultValue = defaults[index]
            return prefs.getFloat(createPrefKeyForBooleanSettings(PREF_SPLIT_SPACER_SCALE_PREFIX, index, 2), defaultValue)
        }

        @JvmStatic
        fun readBottomPaddingScale(prefs: SharedPreferences, landscape: Boolean, folded: Boolean): Float {
            val index = findIndexOfDefaultSetting(landscape, folded)
            val defaults = Defaults.PREF_BOTTOM_PADDING_SCALE
            val defaultValue = defaults[index]
            return prefs.getFloat(createPrefKeyForBooleanSettings(PREF_BOTTOM_PADDING_SCALE_PREFIX, index, 2), defaultValue)
        }

        @JvmStatic
        fun readSidePaddingScale(prefs: SharedPreferences, landscape: Boolean, split: Boolean, folded: Boolean): Float {
            val index = findIndexOfDefaultSetting(landscape, split, folded)
            val defaults = Defaults.PREF_SIDE_PADDING_SCALE
            val defaultValue = defaults[index]
            return prefs.getFloat(createPrefKeyForBooleanSettings(PREF_SIDE_PADDING_SCALE_PREFIX, index, 3), defaultValue)
        }

        @JvmStatic
        fun readHeightScale(prefs: SharedPreferences, landscape: Boolean, folded: Boolean): Float {
            val index = findIndexOfDefaultSetting(landscape, folded)
            val defaults = Defaults.PREF_KEYBOARD_HEIGHT_SCALE
            val defaultValue = defaults[index]
            return prefs.getFloat(createPrefKeyForBooleanSettings(PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, index, 2), defaultValue)
        }

        @JvmStatic
        fun readBottomRowScale(prefs: SharedPreferences, landscape: Boolean, folded: Boolean): Float {
            val index = findIndexOfDefaultSetting(landscape, folded)
            val defaults = Defaults.PREF_BOTTOM_ROW_SCALE
            val defaultValue = defaults[index]
            return prefs.getFloat(createPrefKeyForBooleanSettings(PREF_BOTTOM_ROW_SCALE_PREFIX, index, 2), defaultValue)
        }

        @JvmStatic
        fun readKeyGapScale(prefs: SharedPreferences, landscape: Boolean, folded: Boolean): Float {
            val index = findIndexOfDefaultSetting(landscape, folded)
            val defaultValue = Defaults.PREF_KEY_GAP_SCALE[index]
            return prefs.getFloat(createPrefKeyForBooleanSettings(PREF_KEY_GAP_SCALE_PREFIX, index, 2), defaultValue)
        }

        @JvmStatic
        fun readHasHardwareKeyboard(conf: Configuration): Boolean {
            // The standard way of finding out whether we have a hardware keyboard. This code is taken
            // from InputMethodService#onEvaluateInputShown, which canonically determines this.
            // In a nutshell, we have a keyboard if the configuration says the type of hardware keyboard
            // is NOKEYS and if it's not hidden (e.g. folded inside the device).
            return conf.keyboard != Configuration.KEYBOARD_NOKEYS
                    && conf.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        }

        @JvmStatic
        fun readUserBackgroundImage(context: Context, night: Boolean): Drawable? {
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val index = (if (night) 1 else 0) + (if (landscape) 2 else 0)
            if (cachedBackgroundImages[index] != null) return cachedBackgroundImages[index]

            var image = getCustomBackgroundFile(context, night, landscape)
            if (!image.isFile() && landscape)
                image = getCustomBackgroundFile(context, night, false) // fall back to portrait image for historic reasons
            if (!image.isFile()) return null
            try {
                cachedBackgroundImages[index] = BitmapDrawable(context.resources, BitmapFactory.decodeFile(image.absolutePath))
                return cachedBackgroundImages[index]
            } catch (e: Exception) {
                return null
            }
        }

        @JvmStatic
        fun getCustomBackgroundFile(context: Context, night: Boolean, landscape: Boolean): File {
            return File(DeviceProtectedUtils.getFilesDir(context), "custom_background_image" + (if (landscape) "_landscape" else "") + (if (night) "_night" else ""))
        }

        @JvmStatic
        fun clearCachedBackgroundImages() {
            Arrays.fill(cachedBackgroundImages, null)
        }

        @JvmStatic
        fun getDayNightContext(context: Context, wantNight: Boolean): Context {
            val isNight = ResourceUtils.isNight(context.resources)
            if (isNight == wantNight)
                return context
            val config = Configuration(context.resources.configuration)
            val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val uiModeWithNightBitsZero = config.uiMode - night
            config.uiMode = uiModeWithNightBitsZero + (if (wantNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            val wrapper = ContextThemeWrapper(context, R.style.platformActivityTheme)
            wrapper.applyOverrideConfiguration(config)
            return wrapper
        }

        @JvmStatic
        fun getCustomFontFile(context: Context): File {
            return File(DeviceProtectedUtils.getFilesDir(context), "custom_font")
        }

        @JvmStatic
        fun getCustomEmojiFontFile(context: Context): File {
            return File(DeviceProtectedUtils.getFilesDir(context), "custom_emoji_font")
        }

        // "default" layout as in this is used if nothing else is specified in the subtype
        @JvmStatic
        fun readDefaultLayoutName(type: LayoutType, prefs: SharedPreferences): String {
            return prefs.getString(PREF_LAYOUT_PREFIX + type.name, with(Defaults) { type.default })!!
        }

        @JvmStatic
        fun writeDefaultLayoutName(name: String?, type: LayoutType, prefs: SharedPreferences) {
            if (name == null) prefs.edit().remove(PREF_LAYOUT_PREFIX + type.name).apply()
            else prefs.edit().putString(PREF_LAYOUT_PREFIX + type.name, name).apply()
        }

        @JvmStatic
        fun readPunctuationSuggestions(context: Context): PunctuationSuggestions {
            val prefs = context.prefs()
            // Pattern.split is what Java's String.split does (drops trailing empty strings)
            val suggestPuncsSpec = if (prefs.contains(PREF_PUNCTUATION_SUGGESTIONS))
                    Pattern.compile("\\s+").split(prefs.getString(PREF_PUNCTUATION_SUGGESTIONS, "")!!)
                else
                    PopupKeySpec.splitKeySpecs(context.getString(R.string.suggested_punctuations))
            return PunctuationSuggestions.newPunctuationSuggestions(suggestPuncsSpec)
        }
    }
}

// The declarations below were in this Kotlin file before the Java class of the same name was converted.

fun customIconNames(prefs: SharedPreferences) = runCatching {
    Json.decodeFromString<Map<String, String>>(prefs.getString(Settings.PREF_CUSTOM_ICON_NAMES, Defaults.PREF_CUSTOM_ICON_NAMES)!!)
}.getOrElse { emptyMap() }

@SuppressLint("DiscouragedApi")
fun customIconIds(context: Context, prefs: SharedPreferences) = customIconNames(prefs)
    .mapNotNull { entry ->
        val id = runCatching { context.resources.getIdentifier(entry.value, "drawable", context.packageName) }.getOrNull()
        id?.let { entry.key to it }
    }

/** Derive an index from a number of boolean [settingValues], used to access the matching default value in a defaults arraY */
fun findIndexOfDefaultSetting(vararg settingValues: Boolean): Int {
    var i = -1
    return settingValues.sumOf { i++; if (it) 1.shl(i) else 0 }
}

/** Create pref key that is derived from a [number] of boolean conditions. The [index] is as created by [findIndexOfDefaultSetting]. */
fun createPrefKeyForBooleanSettings(prefix: String, index: Int, number: Int): String =
    "${prefix}_${Array(number) { index.shr(it) % 2 == 1 }.joinToString("_")}"

fun getTransitionAnimationScale(context: Context) =
    Global.getFloat(context.contentResolver, Global.TRANSITION_ANIMATION_SCALE, 1f)

fun isFloatingKeyboardEnabled(context: Context) =
    context.prefs().getBoolean(Settings.PREF_FLOATING_ENABLED_PREFIX + context.resources.displayMetrics.widthPixels, false)

fun setFloatingKeyboardEnabled(context: Context, enabled: Boolean) =
    context.prefs().edit { putBoolean(Settings.PREF_FLOATING_ENABLED_PREFIX + context.resources.displayMetrics.widthPixels, enabled) }

fun readFloatingHeight(context: Context): Int {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val key = Settings.PREF_FLOATING_HEIGHT_PREFIX + screenWidth
    return context.prefs().getInt(key, context.resources.displayMetrics.heightPixels / 3)
}

fun readFloatingWidth(context: Context): Int {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val key = Settings.PREF_FLOATING_WIDTH_PREFIX + screenWidth
    return context.prefs().getInt(key, screenWidth / 2)
}

fun setFloatingSize(context: Context, width: Int, height: Int) {
    val screenWidth = context.resources.displayMetrics.widthPixels
    context.prefs().edit {
        putInt(Settings.PREF_FLOATING_WIDTH_PREFIX + screenWidth, width)
        putInt(Settings.PREF_FLOATING_HEIGHT_PREFIX + screenWidth, height)
    }
}
