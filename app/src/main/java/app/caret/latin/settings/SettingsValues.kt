/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.settings

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.util.TypedValueCompat
import app.caret.compat.isDeviceLocked
import app.caret.compat.locale
import app.caret.keyboard.KeyboardActionListener
import app.caret.keyboard.KeyboardTheme
import app.caret.keyboard.internal.KeyboardState
import app.caret.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import app.caret.latin.InputAttributes
import app.caret.latin.PunctuationSuggestions
import app.caret.latin.R
import app.caret.latin.RichInputMethodManager
import app.caret.latin.common.Colors
import app.caret.latin.permissions.PermissionsUtil
import app.caret.latin.utils.FoldableUtils
import app.caret.latin.utils.InputTypeUtils
import app.caret.latin.utils.JniUtils
import app.caret.latin.utils.ScriptUtils
import app.caret.latin.utils.SubtypeSettings
import app.caret.latin.utils.ToolbarMode
import app.caret.latin.utils.getHasLocalizedNumberRow
import app.caret.latin.utils.getMoreKeys
import app.caret.latin.utils.getPopupKeyHintOrder
import app.caret.latin.utils.getPopupKeyOrder
import app.caret.latin.utils.getSecondaryLocales
import java.util.EnumSet
import java.util.Locale

/**
 * When you call the constructor of this class, you may want to change the current system locale by
 * using {@link app.caret.latin.utils.RunInLocaleKt}.
 */
// Non-final for testing via mock library.
// creation of Colors and SpacingAndPunctuations are the slowest parts in here, but still ok
class SettingsValues(context: Context, prefs: SharedPreferences, res: Resources,
                     inputAttributes: InputAttributes) {
    // From resources:
    @JvmField val spacingAndPunctuations: SpacingAndPunctuations
    @JvmField val doubleSpacePeriodTimeout: Long
    // From configuration:
    @JvmField val locale: Locale
    @JvmField val hasHardwareKeyboard: Boolean
    @JvmField val displayOrientation: Int
    // From preferences
    @JvmField val autoCap: Boolean
    @JvmField val vibrateOn: Boolean
    @JvmField val vibrateInDndMode: Boolean
    @JvmField val soundOn: Boolean
    @JvmField val suggestEmojis: Boolean
    @JvmField val inlineEmojiSearch: Boolean
    @JvmField val showEmojiDescriptions: Boolean
    @JvmField val keyPreviewPopupOn: Boolean
    @JvmField val showsVoiceInputKey: Boolean
    @JvmField val languageSwitchKeyToOtherImes: Boolean
    @JvmField val languageSwitchKeyToOtherSubtypes: Boolean
    private val showsLanguageSwitchKey: Boolean
    @JvmField val showsNumberRow: Boolean
    @JvmField val showsNumberRowInSymbols: Boolean
    @JvmField val localizedNumberRow: Boolean
    @JvmField val showNumberRowHints: Boolean
    @JvmField val showsHints: Boolean
    @JvmField val showsPopupHints: Boolean
    @JvmField val showTldPopupKeys: Boolean
    @JvmField val urlEmailLayout: Boolean
    @JvmField val spaceForLangChange: Boolean
    @JvmField val showsEmojiKey: Boolean
    @JvmField val showsDpadKey: Boolean
    @JvmField val varToolbarDirection: Boolean
    @JvmField val usePersonalizedDicts: Boolean
    @JvmField val useDoubleSpacePeriod: Boolean
    @JvmField val blockPotentiallyOffensive: Boolean
    @JvmField val spaceSwipeHorizontal: KeyboardActionListener.SwipeAction
    @JvmField val spaceSwipeVertical: KeyboardActionListener.SwipeAction
    @JvmField val languageSwipeDistance: Int
    @JvmField val touchpadSensitivity: Int
    @JvmField val touchpadEdgeScroll: Boolean
    @JvmField val deleteSwipeEnabled: Boolean
    @JvmField val swipeDeleteByWord: Boolean
    @JvmField val holdDeleteByWord: Boolean
    @JvmField val autospaceAfterPunctuation: Boolean
    @JvmField val autospaceAfterSuggestion: Boolean
    @JvmField val autospaceAfterGestureTyping: Boolean
    @JvmField val autospaceBeforeGestureTyping: Boolean
    @JvmField val shiftRemovesAutospace: Boolean
    @JvmField val clipboardHistoryEnabled: Boolean
    @JvmField val clipboardHistoryRetentionTime: Long
    @JvmField val clipboardHistoryPinnedFirst: Boolean
    @JvmField val oneHandedModeEnabled: Boolean
    @JvmField val oneHandedModeGravity: Int
    @JvmField val oneHandedModeScale: Float
    @JvmField val keyGapScale: Float
    @JvmField val showMorePopupKeys: String
    @JvmField val popupKeyOrder: List<String>
    @JvmField val popupKeyHintOrder: List<String>
    @JvmField val secondaryLocales: List<Locale>
    @JvmField val bigramPredictionEnabled: Boolean // Use bigrams to predict the next word when there is no input for it yet
    @JvmField val lmNextWordEnabled: Boolean // Caret: let the language model of the swipe models rank those predictions
    @JvmField val suggestPunctuation: Boolean
    @JvmField val centerSuggestionTextToEnter: Boolean
    @JvmField val gestureInputEnabled: Boolean
    @JvmField val gestureTrailEnabled: Boolean
    @JvmField val gestureFloatingPreviewTextEnabled: Boolean
    @JvmField val gestureFloatingPreviewDynamicEnabled: Boolean
    @JvmField val gestureFastTypingCooldown: Int
    @JvmField val gestureTrailFadeoutDuration: Int
    @JvmField val slidingKeyInputPreviewEnabled: Boolean
    @JvmField val keyLongpressTimeout: Int
    @JvmField val enableEmojiAltPhysicalKey: Boolean
    @JvmField val isSplitKeyboardEnabled: Boolean
    @JvmField val splitKeyboardSpacerRelativeWidth: Float
    @JvmField val quickPinToolbarKeys: Boolean
    @JvmField val addToPersonalDictionary: Boolean
    @JvmField val useContactsDictionary: Boolean
    @JvmField val useAppsDictionary: Boolean
    @JvmField val customNavBarColor: Boolean
    @JvmField val keyboardHeightScale: Float
    @JvmField val bottomRowScale: Float
    @JvmField val urlDetectionEnabled: Boolean
    @JvmField val bottomPaddingScale: Float
    @JvmField val sidePaddingScale: Float
    @JvmField val toolbarMode: ToolbarMode
    @JvmField val toolbarSwipeDownToHide: Boolean
    @JvmField val toolbarHidingGlobal: Boolean
    @JvmField val autoShowToolbar: Boolean
    @JvmField val autoHideToolbar: Boolean
    @JvmField val alphaAfterEmojiInEmojiView: Boolean
    @JvmField val alphaAfterClipHistoryEntry: Boolean
    @JvmField val alphaAfterSpace: EnumSet<KeyboardState.Mode> = EnumSet.noneOf(KeyboardState.Mode::class.java)
    @JvmField val removeRedundantPopups: Boolean
    @JvmField val spaceBarText: String
    @JvmField val fontSizeMultiplier: Float
    @JvmField val hintFontSizeMultiplier: Float
    @JvmField val fontSizeMultiplierEmoji: Float
    @JvmField val emojiKeyFit: Boolean
    @JvmField val punctuationSuggestions: PunctuationSuggestions
    @JvmField val isFloatingKeyboard: Boolean
    @JvmField val floatingWidth: Int
    @JvmField val floatingHeight: Int
    @JvmField val keypressVibrationDuration: Int
    @JvmField val keypressSoundVolume: Float
    @JvmField val autoCorrectionEnabledPerUserSettings: Boolean
    @JvmField val autoCorrectCapitalizedSuggestion: Boolean
    @JvmField val backspaceRevertsAutocorrect: Boolean
    @JvmField val autoCorrectShortcuts: Boolean
    @JvmField val suggestionsEnabled: Boolean
    private val overrideShowingSuggestions: Boolean
    @JvmField val suggestClipboardContent: Boolean
    @JvmField val incognitoModeEnabled: Boolean
    @JvmField val longPressSymbolsForNumpad: Boolean

    // From the input box
    @JvmField val inputAttributes: InputAttributes

    // Deduced settings
    @JvmField val suggestionStripHiddenPerUserSettings: Boolean
    @JvmField val autoCorrectEnabled: Boolean
    @JvmField val autoCorrectionThreshold: Float
    @JvmField val scoreLimitForAutocorrect: Int
    @JvmField val settingsValuesForSuggestion: SettingsValuesForSuggestion
    @JvmField val isLocked: Boolean

    // User-defined colors
    @JvmField val colors: Colors

    init {
        this.locale = res.configuration.locale()
        displayOrientation = res.configuration.orientation
        val selectedSubtype = SubtypeSettings.getSelectedSubtype(prefs)

        // Store the input attributes
        this.inputAttributes = inputAttributes

        // Get the settings preferences
        isLocked = isDeviceLocked(context)
        // we want to hide the toolbar / suggestion strip entirely if device is locked
        toolbarMode = if (isLocked) ToolbarMode.HIDDEN else Settings.readToolbarMode(prefs)
        toolbarSwipeDownToHide = prefs.getBoolean(Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE, Defaults.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE)
        toolbarHidingGlobal = isLocked || prefs.getBoolean(Settings.PREF_TOOLBAR_HIDING_GLOBAL, Defaults.PREF_TOOLBAR_HIDING_GLOBAL)
        autoCap = prefs.getBoolean(Settings.PREF_AUTO_CAP, Defaults.PREF_AUTO_CAP) && ScriptUtils.scriptSupportsUppercase(locale)
        vibrateOn = Settings.readVibrationEnabled(prefs)
        vibrateInDndMode = prefs.getBoolean(Settings.PREF_VIBRATE_IN_DND_MODE, Defaults.PREF_VIBRATE_IN_DND_MODE)
        soundOn = prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)
        suggestEmojis = prefs.getBoolean(Settings.PREF_SUGGEST_EMOJIS, Defaults.PREF_SUGGEST_EMOJIS)
        inlineEmojiSearch = prefs.getBoolean(Settings.PREF_INLINE_EMOJI_SEARCH, Defaults.PREF_INLINE_EMOJI_SEARCH)
        showEmojiDescriptions = prefs.getBoolean(Settings.PREF_SHOW_EMOJI_DESCRIPTIONS, Defaults.PREF_SHOW_EMOJI_DESCRIPTIONS)
        keyPreviewPopupOn = prefs.getBoolean(Settings.PREF_POPUP_ON, Defaults.PREF_POPUP_ON)
        slidingKeyInputPreviewEnabled = prefs.getBoolean(
                DebugSettings.PREF_SLIDING_KEY_INPUT_PREVIEW, Defaults.PREF_SLIDING_KEY_INPUT_PREVIEW)
        showsVoiceInputKey = inputAttributes.shouldShowVoiceInputKey
        val languagePref = prefs.getString(Settings.PREF_LANGUAGE_SWITCH_KEY, Defaults.PREF_LANGUAGE_SWITCH_KEY)
        languageSwitchKeyToOtherImes = languagePref!! == "input_method" || languagePref == "both"
        languageSwitchKeyToOtherSubtypes = isLocked || languagePref == "internal" || languagePref == "both"
        showsLanguageSwitchKey = prefs.getBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY)
        showsNumberRow = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW)
        showsNumberRowInSymbols = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, Defaults.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS)
        localizedNumberRow = getHasLocalizedNumberRow(selectedSubtype, prefs)
        showNumberRowHints = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW_HINTS, Defaults.PREF_SHOW_NUMBER_ROW_HINTS)
        showsHints = prefs.getBoolean(Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS)
        showsPopupHints = prefs.getBoolean(Settings.PREF_SHOW_POPUP_HINTS, Defaults.PREF_SHOW_POPUP_HINTS)
        showTldPopupKeys = prefs.getBoolean(Settings.PREF_SHOW_TLD_POPUP_KEYS, Defaults.PREF_SHOW_TLD_POPUP_KEYS)
        urlEmailLayout = prefs.getBoolean(Settings.PREF_URL_EMAIL_LAYOUT, Defaults.PREF_URL_EMAIL_LAYOUT)
        spaceForLangChange = prefs.getBoolean(Settings.PREF_SPACE_TO_CHANGE_LANG, Defaults.PREF_SPACE_TO_CHANGE_LANG)
        showsEmojiKey = prefs.getBoolean(Settings.PREF_SHOW_EMOJI_KEY, Defaults.PREF_SHOW_EMOJI_KEY)
        showsDpadKey = prefs.getBoolean(Settings.PREF_SHOW_DPAD_KEY, Defaults.PREF_SHOW_DPAD_KEY)
        varToolbarDirection = toolbarMode != ToolbarMode.HIDDEN && prefs.getBoolean(Settings.PREF_VARIABLE_TOOLBAR_DIRECTION, Defaults.PREF_VARIABLE_TOOLBAR_DIRECTION)
        usePersonalizedDicts = prefs.getBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, Defaults.PREF_KEY_USE_PERSONALIZED_DICTS)
        useDoubleSpacePeriod = prefs.getBoolean(Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD)
                && inputAttributes.isGeneralTextInput
        blockPotentiallyOffensive = prefs.getBoolean(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Defaults.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
        urlDetectionEnabled = prefs.getBoolean(Settings.PREF_URL_DETECTION, Defaults.PREF_URL_DETECTION)
        autoCorrectionEnabledPerUserSettings = prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION)
        autoCorrectEnabled = autoCorrectionEnabledPerUserSettings
                && (inputAttributes.inputTypeShouldAutoCorrect || prefs.getBoolean(Settings.PREF_MORE_AUTO_CORRECTION, Defaults.PREF_MORE_AUTO_CORRECTION))
                && (urlDetectionEnabled || !InputTypeUtils.isUriOrEmailType(inputAttributes.inputType))
        centerSuggestionTextToEnter = prefs.getBoolean(Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER, Defaults.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER)
        val autoCorrectConfidence = prefs.getFloat(Settings.PREF_AUTO_CORRECT_CONFIDENCE, Defaults.PREF_AUTO_CORRECT_CONFIDENCE)
        // confidence -> threshold and score limit are just some formulas that give something similar to the old values, so that confidence can be in a nice 0-1 range
        autoCorrectionThreshold = if (autoCorrectEnabled)
                0.5f - 0.5f * Math.pow(autoCorrectConfidence.toDouble(), 0.33).toFloat()
            else Float.MAX_VALUE
        scoreLimitForAutocorrect = ((1.14 - 0.2 * Math.pow(autoCorrectConfidence + 0.47, 3.5)) * 900000).toInt()
        autoCorrectCapitalizedSuggestion = prefs.getBoolean(Settings.PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION, Defaults.PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION)
        autoCorrectShortcuts = prefs.getBoolean(Settings.PREF_AUTOCORRECT_SHORTCUTS, Defaults.PREF_AUTOCORRECT_SHORTCUTS)
        backspaceRevertsAutocorrect = prefs.getBoolean(Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT, Defaults.PREF_BACKSPACE_REVERTS_AUTOCORRECT)
        bigramPredictionEnabled = prefs.getBoolean(Settings.PREF_BIGRAM_PREDICTIONS, Defaults.PREF_BIGRAM_PREDICTIONS)
        lmNextWordEnabled = prefs.getBoolean(Settings.PREF_LM_NEXT_WORD, Defaults.PREF_LM_NEXT_WORD)
        suggestPunctuation = prefs.getBoolean(Settings.PREF_SUGGEST_PUNCTUATION, Defaults.PREF_SUGGEST_PUNCTUATION)
        suggestClipboardContent = prefs.getBoolean(Settings.PREF_SUGGEST_CLIPBOARD_CONTENT, Defaults.PREF_SUGGEST_CLIPBOARD_CONTENT)
        doubleSpacePeriodTimeout = 1100 // ms
        hasHardwareKeyboard = Settings.readHasHardwareKeyboard(res.configuration)
        val isLandscape = displayOrientation == Configuration.ORIENTATION_LANDSCAPE
        val displayWidthDp = TypedValueCompat.pxToDp(res.displayMetrics.widthPixels.toFloat(), res.displayMetrics)
        val isFolded = FoldableUtils.isFolded
        isSplitKeyboardEnabled = Settings.readSplitKeyboardEnabled(prefs, isLandscape, isFolded)
        // determine spacerWidth from display width and scale setting
        splitKeyboardSpacerRelativeWidth = if (isSplitKeyboardEnabled)
                Math.min(Math.max((displayWidthDp - 600) / 600f + 0.15f, 0.15f), 0.35f) * Settings.readSplitSpacerScale(prefs, isLandscape, isFolded)
            else 0f
        quickPinToolbarKeys = toolbarMode == ToolbarMode.EXPANDABLE && prefs.getBoolean(Settings.PREF_QUICK_PIN_TOOLBAR_KEYS, Defaults.PREF_QUICK_PIN_TOOLBAR_KEYS)

        // Compute other readable settings
        keyLongpressTimeout = prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, Defaults.PREF_KEY_LONGPRESS_TIMEOUT)
        keypressVibrationDuration = prefs.getInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, Defaults.PREF_VIBRATION_DURATION_SETTINGS)
        keypressSoundVolume = prefs.getFloat(Settings.PREF_KEYPRESS_SOUND_VOLUME, Defaults.PREF_KEYPRESS_SOUND_VOLUME)
        enableEmojiAltPhysicalKey = prefs.getBoolean(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, Defaults.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY)
        gestureInputEnabled = JniUtils.haveGestureLib && prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
        gestureTrailEnabled = prefs.getBoolean(Settings.PREF_GESTURE_PREVIEW_TRAIL, Defaults.PREF_GESTURE_PREVIEW_TRAIL)
        gestureFloatingPreviewTextEnabled = !inputAttributes.disableGestureFloatingPreviewText
                && prefs.getBoolean(Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT, Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
        gestureFloatingPreviewDynamicEnabled = Settings.readGestureDynamicPreviewEnabled(prefs)
        gestureFastTypingCooldown = prefs.getInt(Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN, Defaults.PREF_GESTURE_FAST_TYPING_COOLDOWN)
        gestureTrailFadeoutDuration = prefs.getInt(Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, Defaults.PREF_GESTURE_TRAIL_FADEOUT_DURATION)
        suggestionStripHiddenPerUserSettings = toolbarMode == ToolbarMode.HIDDEN || toolbarMode == ToolbarMode.TOOLBAR_KEYS
        overrideShowingSuggestions = inputAttributes.mayOverrideShowingSuggestions
                && prefs.getBoolean(Settings.PREF_ALWAYS_SHOW_SUGGESTIONS, Defaults.PREF_ALWAYS_SHOW_SUGGESTIONS)
                && ((inputAttributes.inputType and InputType.TYPE_MASK_VARIATION) != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
                  || !prefs.getBoolean(Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT, Defaults.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT))
        suggestionsEnabled = prefs.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, Defaults.PREF_SHOW_SUGGESTIONS)
            && (inputAttributes.shouldShowSuggestions || overrideShowingSuggestions) && !suggestionStripHiddenPerUserSettings
        incognitoModeEnabled = prefs.getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE) || inputAttributes.noLearning
                || inputAttributes.isPasswordField
        bottomRowScale = Settings.readBottomRowScale(prefs, isLandscape, isFolded)
        spaceSwipeHorizontal = Settings.readHorizontalSpaceSwipe(prefs)
        spaceSwipeVertical = Settings.readVerticalSpaceSwipe(prefs)
        languageSwipeDistance = prefs.getInt(Settings.PREF_LANGUAGE_SWIPE_DISTANCE, Defaults.PREF_LANGUAGE_SWIPE_DISTANCE)
        touchpadSensitivity = prefs.getInt(Settings.PREF_TOUCHPAD_SENSITIVITY,
            Defaults.PREF_TOUCHPAD_SENSITIVITY)
        touchpadEdgeScroll = prefs.getBoolean(Settings.PREF_TOUCHPAD_EDGE_SCROLL, Defaults.PREF_TOUCHPAD_EDGE_SCROLL)
        deleteSwipeEnabled = prefs.getBoolean(Settings.PREF_DELETE_SWIPE, Defaults.PREF_DELETE_SWIPE)
        swipeDeleteByWord = prefs.getBoolean(Settings.PREF_SWIPE_DELETE_BY_WORD, Defaults.PREF_SWIPE_DELETE_BY_WORD)
        holdDeleteByWord = prefs.getBoolean(Settings.PREF_HOLD_DELETE_BY_WORD, Defaults.PREF_HOLD_DELETE_BY_WORD)
        autospaceAfterPunctuation = prefs.getBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, Defaults.PREF_AUTOSPACE_AFTER_PUNCTUATION)
        autospaceAfterSuggestion = prefs.getBoolean(Settings.PREF_AUTOSPACE_AFTER_SUGGESTION, Defaults.PREF_AUTOSPACE_AFTER_SUGGESTION)
        autospaceAfterGestureTyping = prefs.getBoolean(Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING, Defaults.PREF_AUTOSPACE_AFTER_GESTURE_TYPING)
        autospaceBeforeGestureTyping = prefs.getBoolean(Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING, Defaults.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING)
        shiftRemovesAutospace = prefs.getBoolean(Settings.PREF_SHIFT_REMOVES_AUTOSPACE, Defaults.PREF_SHIFT_REMOVES_AUTOSPACE)
        clipboardHistoryEnabled = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
        clipboardHistoryRetentionTime = prefs.getInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME).toLong()
        clipboardHistoryPinnedFirst = prefs.getBoolean(Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST, Defaults.PREF_CLIPBOARD_HISTORY_PINNED_FIRST)

        isFloatingKeyboard = !isLocked && isFloatingKeyboardEnabled(context)
        floatingWidth = readFloatingWidth(context)
        floatingHeight = if (isFloatingKeyboard && hasHardwareKeyboard && prefs.getBoolean(Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD, Defaults.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD))
                          0 else readFloatingHeight(context)
        keyboardHeightScale = if (isFloatingKeyboard) 1f else Settings.readHeightScale(prefs, isLandscape, isFolded)
        oneHandedModeEnabled = !isFloatingKeyboard && Settings.readOneHandedModeEnabled(prefs, isLandscape, isSplitKeyboardEnabled, isFolded)
        oneHandedModeGravity = Settings.readOneHandedModeGravity(prefs, isLandscape, isSplitKeyboardEnabled, isFolded)
        if (oneHandedModeEnabled) {
            val baseScale = res.getFraction(R.fraction.config_one_handed_mode_width, 1, 1)
            val extraScale = Settings.readOneHandedModeScale(prefs, isLandscape, isSplitKeyboardEnabled, isFolded)
            oneHandedModeScale = 1 - (1 - baseScale) * extraScale
        } else
            oneHandedModeScale = 1f
        secondaryLocales = getSecondaryLocales(selectedSubtype.extraValue)
        showMorePopupKeys = getMoreKeys(selectedSubtype, prefs,
            if (selectedSubtype.isAsciiCapable) Defaults.PREF_MORE_POPUP_KEYS else LocaleKeyboardInfos.POPUP_KEYS_NORMAL)
        colors = KeyboardTheme.getColorsForCurrentTheme(context)

        popupKeyOrder = getPopupKeyOrder(selectedSubtype, prefs)
        popupKeyHintOrder = getPopupKeyHintOrder(selectedSubtype, prefs)
        addToPersonalDictionary = prefs.getBoolean(Settings.PREF_ADD_TO_PERSONAL_DICTIONARY, Defaults.PREF_ADD_TO_PERSONAL_DICTIONARY)
        useContactsDictionary = readUseContactsEnabled(prefs, context)
        useAppsDictionary = prefs.getBoolean(Settings.PREF_USE_APPS, Defaults.PREF_USE_APPS)
        customNavBarColor = prefs.getBoolean(Settings.PREF_NAVBAR_COLOR, Defaults.PREF_NAVBAR_COLOR)
        keyGapScale = Settings.readKeyGapScale(prefs, isLandscape, isFolded)
        settingsValuesForSuggestion = SettingsValuesForSuggestion(
                blockPotentiallyOffensive,
                prefs.getBoolean(Settings.PREF_GESTURE_SPACE_AWARE, Defaults.PREF_GESTURE_SPACE_AWARE)
        )
        spacingAndPunctuations = SpacingAndPunctuations(res, urlDetectionEnabled)
        bottomPaddingScale = if (isFloatingKeyboard) 0f else Settings.readBottomPaddingScale(prefs, isLandscape, isFolded)
        sidePaddingScale = Settings.readSidePaddingScale(prefs, isLandscape, isSplitKeyboardEnabled, isFolded)
        longPressSymbolsForNumpad = prefs.getBoolean(Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD, Defaults.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD)
        autoShowToolbar = toolbarMode == ToolbarMode.EXPANDABLE && prefs.getBoolean(Settings.PREF_AUTO_SHOW_TOOLBAR, Defaults.PREF_AUTO_SHOW_TOOLBAR)
        autoHideToolbar = suggestionsEnabled && prefs.getBoolean(Settings.PREF_AUTO_HIDE_TOOLBAR, Defaults.PREF_AUTO_HIDE_TOOLBAR)
        alphaAfterEmojiInEmojiView = prefs.getBoolean(Settings.PREF_ABC_AFTER_EMOJI, Defaults.PREF_ABC_AFTER_EMOJI)
        alphaAfterClipHistoryEntry = prefs.getBoolean(Settings.PREF_ABC_AFTER_CLIP, Defaults.PREF_ABC_AFTER_CLIP)
        if (prefs.getBoolean(Settings.PREF_ABC_AFTER_SYMBOL_SPACE, Defaults.PREF_ABC_AFTER_SYMBOL_SPACE)) {
            alphaAfterSpace.add(KeyboardState.Mode.SYMBOLS)
            alphaAfterSpace.add(KeyboardState.Mode.SYMBOLS_SHIFTED)
        }
        if (prefs.getBoolean(Settings.PREF_ABC_AFTER_NUMPAD_SPACE, Defaults.PREF_ABC_AFTER_NUMPAD_SPACE)) {
            alphaAfterSpace.add(KeyboardState.Mode.NUMPAD)
        }
        removeRedundantPopups = prefs.getBoolean(Settings.PREF_REMOVE_REDUNDANT_POPUPS, Defaults.PREF_REMOVE_REDUNDANT_POPUPS)
        spaceBarText = prefs.getString(Settings.PREF_SPACE_BAR_TEXT, Defaults.PREF_SPACE_BAR_TEXT)!!
        fontSizeMultiplier = prefs.getFloat(Settings.PREF_FONT_SCALE, Defaults.PREF_FONT_SCALE)
        hintFontSizeMultiplier = if (showsHints) prefs.getFloat(Settings.PREF_HINT_FONT_SCALE, Defaults.PREF_HINT_FONT_SCALE) else 1f
        fontSizeMultiplierEmoji = prefs.getFloat(Settings.PREF_EMOJI_FONT_SCALE, Defaults.PREF_EMOJI_FONT_SCALE)
        emojiKeyFit = prefs.getBoolean(Settings.PREF_EMOJI_KEY_FIT, Defaults.PREF_EMOJI_KEY_FIT)
        punctuationSuggestions = Settings.readPunctuationSuggestions(context)
    }

    fun needsToLookupSuggestions(): Boolean {
        return (inputAttributes.shouldShowSuggestions || overrideShowingSuggestions)
                && (autoCorrectEnabled || suggestionsEnabled)
    }

    fun isWordSeparator(code: Int): Boolean {
        return spacingAndPunctuations.isWordSeparator(code)
    }

    fun isWordConnector(code: Int): Boolean {
        return spacingAndPunctuations.isWordConnector(code)
    }

    fun isWordCodePoint(code: Int): Boolean {
        return spacingAndPunctuations.isWordCodePoint(code)
    }

    fun isUsuallyPrecededBySpace(code: Int): Boolean {
        return spacingAndPunctuations.isUsuallyPrecededBySpace(code)
    }

    fun isUsuallyFollowedBySpace(code: Int): Boolean {
        return spacingAndPunctuations.isUsuallyFollowedBySpace(code)
    }

    fun shouldInsertSpacesAutomatically(): Boolean {
        return inputAttributes.shouldInsertSpacesAutomatically
    }

    fun isLanguageSwitchKeyEnabled(): Boolean {
        if (!showsLanguageSwitchKey) {
            return false
        }
        val imm = RichInputMethodManager.getInstance()
        if (!languageSwitchKeyToOtherSubtypes) {
            return imm.hasMultipleEnabledIMEsOrSubtypes(false /* include aux subtypes */)
        }
        if (!languageSwitchKeyToOtherImes) {
            return imm.hasMultipleEnabledSubtypesInThisIme(true /* include aux subtypes */)
        }
        return imm.hasMultipleEnabledSubtypesInThisIme(false /* include aux subtypes */)
            || imm.hasMultipleEnabledIMEsOrSubtypes(false /* include aux subtypes */)
    }

    fun isSameInputType(editorInfo: EditorInfo?): Boolean {
        return inputAttributes.isSameInputType(editorInfo)
    }

    fun hasSameOrientation(configuration: Configuration): Boolean {
        return displayOrientation == configuration.orientation
    }

    val isSecondaryStripVisible: Boolean
        get() = toolbarMode != ToolbarMode.HIDDEN || !toolbarHidingGlobal

    fun dump(): String {
        val sb = StringBuilder("Current settings :")
        sb.append("\n   spacingAndPunctuations = ")
        sb.append("" + spacingAndPunctuations.dump())
        sb.append("\n   autoCap = ")
        sb.append("" + autoCap)
        sb.append("\n   vibrateOn = ")
        sb.append("" + vibrateOn)
        sb.append("\n   soundOn = ")
        sb.append("" + soundOn)
        sb.append("\n   keyPreviewPopupOn = ")
        sb.append("" + keyPreviewPopupOn)
        sb.append("\n   showsVoiceInputKey = ")
        sb.append("" + showsVoiceInputKey)
        sb.append("\n   languageSwitchKeyToOtherImes = ")
        sb.append("" + languageSwitchKeyToOtherImes)
        sb.append("\n   languageSwitchKeyToOtherSubtypes = ")
        sb.append("" + languageSwitchKeyToOtherSubtypes)
        sb.append("\n   usePersonalizedDicts = ")
        sb.append("" + usePersonalizedDicts)
        sb.append("\n   useDoubleSpacePeriod = ")
        sb.append("" + useDoubleSpacePeriod)
        sb.append("\n   blockPotentiallyOffensive = ")
        sb.append("" + blockPotentiallyOffensive)
        sb.append("\n   bigramPredictionEnabled = ")
        sb.append("" + bigramPredictionEnabled)
        sb.append("\n   gestureInputEnabled = ")
        sb.append("" + gestureInputEnabled)
        sb.append("\n   gestureTrailEnabled = ")
        sb.append("" + gestureTrailEnabled)
        sb.append("\n   gestureFloatingPreviewTextEnabled = ")
        sb.append("" + gestureFloatingPreviewTextEnabled)
        sb.append("\n   slidingKeyInputPreviewEnabled = ")
        sb.append("" + slidingKeyInputPreviewEnabled)
        sb.append("\n   keyLongpressTimeout = ")
        sb.append("" + keyLongpressTimeout)
        sb.append("\n   mLocale = ")
        sb.append("" + locale)
        sb.append("\n   mInputAttributes = ")
        sb.append("" + inputAttributes)
        sb.append("\n   keypressVibrationDuration = ")
        sb.append("" + keypressVibrationDuration)
        sb.append("\n   keypressSoundVolume = ")
        sb.append("" + keypressSoundVolume)
        sb.append("\n   autoCorrectEnabled = ")
        sb.append("" + autoCorrectEnabled)
        sb.append("\n   autoCorrectionThreshold = ")
        sb.append("" + autoCorrectionThreshold)
        sb.append("\n   autoCorrectionEnabledPerUserSettings = ")
        sb.append("" + autoCorrectionEnabledPerUserSettings)
        sb.append("\n   mSuggestionsEnabledPerUserSettings = ")
        sb.append("" + suggestionsEnabled)
        sb.append("\n   displayOrientation = ")
        sb.append("" + displayOrientation)
        sb.append("\n   mAppWorkarounds = ")
        return sb.toString()
    }

    companion object {
        private fun readUseContactsEnabled(prefs: SharedPreferences, ctx: Context): Boolean {
            val setting = prefs.getBoolean(Settings.PREF_USE_CONTACTS, Defaults.PREF_USE_CONTACTS)
            if (!setting) return false
            if (PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.READ_CONTACTS)) {
                return true
            }
            // disable if permission not granted
            prefs.edit().putBoolean(Settings.PREF_USE_CONTACTS, false).apply()
            return false
        }
    }
}
