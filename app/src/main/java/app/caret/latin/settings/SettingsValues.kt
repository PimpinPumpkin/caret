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
    @JvmField val mSpacingAndPunctuations: SpacingAndPunctuations
    @JvmField val mDoubleSpacePeriodTimeout: Long
    // From configuration:
    @JvmField val mLocale: Locale
    @JvmField val mHasHardwareKeyboard: Boolean
    @JvmField val mDisplayOrientation: Int
    // From preferences
    @JvmField val mAutoCap: Boolean
    @JvmField val mVibrateOn: Boolean
    @JvmField val mVibrateInDndMode: Boolean
    @JvmField val mSoundOn: Boolean
    @JvmField val mSuggestEmojis: Boolean
    @JvmField val mInlineEmojiSearch: Boolean
    @JvmField val mShowEmojiDescriptions: Boolean
    @JvmField val mKeyPreviewPopupOn: Boolean
    @JvmField val mShowsVoiceInputKey: Boolean
    @JvmField val mLanguageSwitchKeyToOtherImes: Boolean
    @JvmField val mLanguageSwitchKeyToOtherSubtypes: Boolean
    private val mShowsLanguageSwitchKey: Boolean
    @JvmField val mShowsNumberRow: Boolean
    @JvmField val mShowsNumberRowInSymbols: Boolean
    @JvmField val mLocalizedNumberRow: Boolean
    @JvmField val mShowNumberRowHints: Boolean
    @JvmField val mShowsHints: Boolean
    @JvmField val mShowsPopupHints: Boolean
    @JvmField val mShowTldPopupKeys: Boolean
    @JvmField val mUrlEmailLayout: Boolean
    @JvmField val mSpaceForLangChange: Boolean
    @JvmField val mShowsEmojiKey: Boolean
    @JvmField val mShowsDpadKey: Boolean
    @JvmField val mVarToolbarDirection: Boolean
    @JvmField val mUsePersonalizedDicts: Boolean
    @JvmField val mUseDoubleSpacePeriod: Boolean
    @JvmField val mBlockPotentiallyOffensive: Boolean
    @JvmField val mSpaceSwipeHorizontal: KeyboardActionListener.SwipeAction
    @JvmField val mSpaceSwipeVertical: KeyboardActionListener.SwipeAction
    @JvmField val mLanguageSwipeDistance: Int
    @JvmField val mTouchpadSensitivity: Int
    @JvmField val mTouchpadEdgeScroll: Boolean
    @JvmField val mDeleteSwipeEnabled: Boolean
    @JvmField val mSwipeDeleteByWord: Boolean
    @JvmField val mHoldDeleteByWord: Boolean
    @JvmField val mAutospaceAfterPunctuation: Boolean
    @JvmField val mAutospaceAfterSuggestion: Boolean
    @JvmField val mAutospaceAfterGestureTyping: Boolean
    @JvmField val mAutospaceBeforeGestureTyping: Boolean
    @JvmField val mShiftRemovesAutospace: Boolean
    @JvmField val mClipboardHistoryEnabled: Boolean
    @JvmField val mClipboardHistoryRetentionTime: Long
    @JvmField val mClipboardHistoryPinnedFirst: Boolean
    @JvmField val mOneHandedModeEnabled: Boolean
    @JvmField val mOneHandedModeGravity: Int
    @JvmField val mOneHandedModeScale: Float
    @JvmField val mKeyGapScale: Float
    @JvmField val mShowMorePopupKeys: String
    @JvmField val mPopupKeyOrder: List<String>
    @JvmField val mPopupKeyHintOrder: List<String>
    @JvmField val mSecondaryLocales: List<Locale>
    @JvmField val mBigramPredictionEnabled: Boolean // Use bigrams to predict the next word when there is no input for it yet
    @JvmField val mSuggestPunctuation: Boolean
    @JvmField val mCenterSuggestionTextToEnter: Boolean
    @JvmField val mGestureInputEnabled: Boolean
    @JvmField val mGestureTrailEnabled: Boolean
    @JvmField val mGestureFloatingPreviewTextEnabled: Boolean
    @JvmField val mGestureFloatingPreviewDynamicEnabled: Boolean
    @JvmField val mGestureFastTypingCooldown: Int
    @JvmField val mGestureTrailFadeoutDuration: Int
    @JvmField val mSlidingKeyInputPreviewEnabled: Boolean
    @JvmField val mKeyLongpressTimeout: Int
    @JvmField val mEnableEmojiAltPhysicalKey: Boolean
    @JvmField val mIsSplitKeyboardEnabled: Boolean
    @JvmField val mSplitKeyboardSpacerRelativeWidth: Float
    @JvmField val mQuickPinToolbarKeys: Boolean
    @JvmField val mAddToPersonalDictionary: Boolean
    @JvmField val mUseContactsDictionary: Boolean
    @JvmField val mUseAppsDictionary: Boolean
    @JvmField val mCustomNavBarColor: Boolean
    @JvmField val mKeyboardHeightScale: Float
    @JvmField val mBottomRowScale: Float
    @JvmField val mUrlDetectionEnabled: Boolean
    @JvmField val mBottomPaddingScale: Float
    @JvmField val mSidePaddingScale: Float
    @JvmField val mToolbarMode: ToolbarMode
    @JvmField val mToolbarSwipeDownToHide: Boolean
    @JvmField val mToolbarHidingGlobal: Boolean
    @JvmField val mAutoShowToolbar: Boolean
    @JvmField val mAutoHideToolbar: Boolean
    @JvmField val mAlphaAfterEmojiInEmojiView: Boolean
    @JvmField val mAlphaAfterClipHistoryEntry: Boolean
    @JvmField val mAlphaAfterSpace: EnumSet<KeyboardState.Mode> = EnumSet.noneOf(KeyboardState.Mode::class.java)
    @JvmField val mRemoveRedundantPopups: Boolean
    @JvmField val mSpaceBarText: String
    @JvmField val mFontSizeMultiplier: Float
    @JvmField val mHintFontSizeMultiplier: Float
    @JvmField val mFontSizeMultiplierEmoji: Float
    @JvmField val mEmojiKeyFit: Boolean
    @JvmField val mPunctuationSuggestions: PunctuationSuggestions
    @JvmField val mIsFloatingKeyboard: Boolean
    @JvmField val mFloatingWidth: Int
    @JvmField val mFloatingHeight: Int
    @JvmField val mKeypressVibrationDuration: Int
    @JvmField val mKeypressSoundVolume: Float
    @JvmField val mAutoCorrectionEnabledPerUserSettings: Boolean
    @JvmField val mAutoCorrectCapitalizedSuggestion: Boolean
    @JvmField val mBackspaceRevertsAutocorrect: Boolean
    @JvmField val mAutoCorrectShortcuts: Boolean
    @JvmField val mSuggestionsEnabled: Boolean
    private val mOverrideShowingSuggestions: Boolean
    @JvmField val mSuggestClipboardContent: Boolean
    @JvmField val mIncognitoModeEnabled: Boolean
    @JvmField val mLongPressSymbolsForNumpad: Boolean

    // From the input box
    @JvmField val mInputAttributes: InputAttributes

    // Deduced settings
    @JvmField val mSuggestionStripHiddenPerUserSettings: Boolean
    @JvmField val mAutoCorrectEnabled: Boolean
    @JvmField val mAutoCorrectionThreshold: Float
    @JvmField val mScoreLimitForAutocorrect: Int
    @JvmField val mSettingsValuesForSuggestion: SettingsValuesForSuggestion
    @JvmField val mIsLocked: Boolean

    // User-defined colors
    @JvmField val mColors: Colors

    init {
        mLocale = res.configuration.locale()
        mDisplayOrientation = res.configuration.orientation
        val selectedSubtype = SubtypeSettings.getSelectedSubtype(prefs)

        // Store the input attributes
        mInputAttributes = inputAttributes

        // Get the settings preferences
        mIsLocked = isDeviceLocked(context)
        // we want to hide the toolbar / suggestion strip entirely if device is locked
        mToolbarMode = if (mIsLocked) ToolbarMode.HIDDEN else Settings.readToolbarMode(prefs)
        mToolbarSwipeDownToHide = prefs.getBoolean(Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE, Defaults.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE)
        mToolbarHidingGlobal = mIsLocked || prefs.getBoolean(Settings.PREF_TOOLBAR_HIDING_GLOBAL, Defaults.PREF_TOOLBAR_HIDING_GLOBAL)
        mAutoCap = prefs.getBoolean(Settings.PREF_AUTO_CAP, Defaults.PREF_AUTO_CAP) && ScriptUtils.scriptSupportsUppercase(mLocale)
        mVibrateOn = Settings.readVibrationEnabled(prefs)
        mVibrateInDndMode = prefs.getBoolean(Settings.PREF_VIBRATE_IN_DND_MODE, Defaults.PREF_VIBRATE_IN_DND_MODE)
        mSoundOn = prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)
        mSuggestEmojis = prefs.getBoolean(Settings.PREF_SUGGEST_EMOJIS, Defaults.PREF_SUGGEST_EMOJIS)
        mInlineEmojiSearch = prefs.getBoolean(Settings.PREF_INLINE_EMOJI_SEARCH, Defaults.PREF_INLINE_EMOJI_SEARCH)
        mShowEmojiDescriptions = prefs.getBoolean(Settings.PREF_SHOW_EMOJI_DESCRIPTIONS, Defaults.PREF_SHOW_EMOJI_DESCRIPTIONS)
        mKeyPreviewPopupOn = prefs.getBoolean(Settings.PREF_POPUP_ON, Defaults.PREF_POPUP_ON)
        mSlidingKeyInputPreviewEnabled = prefs.getBoolean(
                DebugSettings.PREF_SLIDING_KEY_INPUT_PREVIEW, Defaults.PREF_SLIDING_KEY_INPUT_PREVIEW)
        mShowsVoiceInputKey = mInputAttributes.mShouldShowVoiceInputKey
        val languagePref = prefs.getString(Settings.PREF_LANGUAGE_SWITCH_KEY, Defaults.PREF_LANGUAGE_SWITCH_KEY)
        mLanguageSwitchKeyToOtherImes = languagePref!! == "input_method" || languagePref == "both"
        mLanguageSwitchKeyToOtherSubtypes = mIsLocked || languagePref == "internal" || languagePref == "both"
        mShowsLanguageSwitchKey = prefs.getBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY)
        mShowsNumberRow = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW)
        mShowsNumberRowInSymbols = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, Defaults.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS)
        mLocalizedNumberRow = getHasLocalizedNumberRow(selectedSubtype, prefs)
        mShowNumberRowHints = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW_HINTS, Defaults.PREF_SHOW_NUMBER_ROW_HINTS)
        mShowsHints = prefs.getBoolean(Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS)
        mShowsPopupHints = prefs.getBoolean(Settings.PREF_SHOW_POPUP_HINTS, Defaults.PREF_SHOW_POPUP_HINTS)
        mShowTldPopupKeys = prefs.getBoolean(Settings.PREF_SHOW_TLD_POPUP_KEYS, Defaults.PREF_SHOW_TLD_POPUP_KEYS)
        mUrlEmailLayout = prefs.getBoolean(Settings.PREF_URL_EMAIL_LAYOUT, Defaults.PREF_URL_EMAIL_LAYOUT)
        mSpaceForLangChange = prefs.getBoolean(Settings.PREF_SPACE_TO_CHANGE_LANG, Defaults.PREF_SPACE_TO_CHANGE_LANG)
        mShowsEmojiKey = prefs.getBoolean(Settings.PREF_SHOW_EMOJI_KEY, Defaults.PREF_SHOW_EMOJI_KEY)
        mShowsDpadKey = prefs.getBoolean(Settings.PREF_SHOW_DPAD_KEY, Defaults.PREF_SHOW_DPAD_KEY)
        mVarToolbarDirection = mToolbarMode != ToolbarMode.HIDDEN && prefs.getBoolean(Settings.PREF_VARIABLE_TOOLBAR_DIRECTION, Defaults.PREF_VARIABLE_TOOLBAR_DIRECTION)
        mUsePersonalizedDicts = prefs.getBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, Defaults.PREF_KEY_USE_PERSONALIZED_DICTS)
        mUseDoubleSpacePeriod = prefs.getBoolean(Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD)
                && inputAttributes.mIsGeneralTextInput
        mBlockPotentiallyOffensive = prefs.getBoolean(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Defaults.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
        mUrlDetectionEnabled = prefs.getBoolean(Settings.PREF_URL_DETECTION, Defaults.PREF_URL_DETECTION)
        mAutoCorrectionEnabledPerUserSettings = prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION)
        mAutoCorrectEnabled = mAutoCorrectionEnabledPerUserSettings
                && (mInputAttributes.mInputTypeShouldAutoCorrect || prefs.getBoolean(Settings.PREF_MORE_AUTO_CORRECTION, Defaults.PREF_MORE_AUTO_CORRECTION))
                && (mUrlDetectionEnabled || !InputTypeUtils.isUriOrEmailType(mInputAttributes.mInputType))
        mCenterSuggestionTextToEnter = prefs.getBoolean(Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER, Defaults.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER)
        val autoCorrectConfidence = prefs.getFloat(Settings.PREF_AUTO_CORRECT_CONFIDENCE, Defaults.PREF_AUTO_CORRECT_CONFIDENCE)
        // confidence -> threshold and score limit are just some formulas that give something similar to the old values, so that confidence can be in a nice 0-1 range
        mAutoCorrectionThreshold = if (mAutoCorrectEnabled)
                0.5f - 0.5f * Math.pow(autoCorrectConfidence.toDouble(), 0.33).toFloat()
            else Float.MAX_VALUE
        mScoreLimitForAutocorrect = ((1.14 - 0.2 * Math.pow(autoCorrectConfidence + 0.47, 3.5)) * 900000).toInt()
        mAutoCorrectCapitalizedSuggestion = prefs.getBoolean(Settings.PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION, Defaults.PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION)
        mAutoCorrectShortcuts = prefs.getBoolean(Settings.PREF_AUTOCORRECT_SHORTCUTS, Defaults.PREF_AUTOCORRECT_SHORTCUTS)
        mBackspaceRevertsAutocorrect = prefs.getBoolean(Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT, Defaults.PREF_BACKSPACE_REVERTS_AUTOCORRECT)
        mBigramPredictionEnabled = prefs.getBoolean(Settings.PREF_BIGRAM_PREDICTIONS, Defaults.PREF_BIGRAM_PREDICTIONS)
        mSuggestPunctuation = prefs.getBoolean(Settings.PREF_SUGGEST_PUNCTUATION, Defaults.PREF_SUGGEST_PUNCTUATION)
        mSuggestClipboardContent = prefs.getBoolean(Settings.PREF_SUGGEST_CLIPBOARD_CONTENT, Defaults.PREF_SUGGEST_CLIPBOARD_CONTENT)
        mDoubleSpacePeriodTimeout = 1100 // ms
        mHasHardwareKeyboard = Settings.readHasHardwareKeyboard(res.configuration)
        val isLandscape = mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
        val displayWidthDp = TypedValueCompat.pxToDp(res.displayMetrics.widthPixels.toFloat(), res.displayMetrics)
        val isFolded = FoldableUtils.isFolded
        mIsSplitKeyboardEnabled = Settings.readSplitKeyboardEnabled(prefs, isLandscape, isFolded)
        // determine spacerWidth from display width and scale setting
        mSplitKeyboardSpacerRelativeWidth = if (mIsSplitKeyboardEnabled)
                Math.min(Math.max((displayWidthDp - 600) / 600f + 0.15f, 0.15f), 0.35f) * Settings.readSplitSpacerScale(prefs, isLandscape, isFolded)
            else 0f
        mQuickPinToolbarKeys = mToolbarMode == ToolbarMode.EXPANDABLE && prefs.getBoolean(Settings.PREF_QUICK_PIN_TOOLBAR_KEYS, Defaults.PREF_QUICK_PIN_TOOLBAR_KEYS)

        // Compute other readable settings
        mKeyLongpressTimeout = prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, Defaults.PREF_KEY_LONGPRESS_TIMEOUT)
        mKeypressVibrationDuration = prefs.getInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, Defaults.PREF_VIBRATION_DURATION_SETTINGS)
        mKeypressSoundVolume = prefs.getFloat(Settings.PREF_KEYPRESS_SOUND_VOLUME, Defaults.PREF_KEYPRESS_SOUND_VOLUME)
        mEnableEmojiAltPhysicalKey = prefs.getBoolean(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, Defaults.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY)
        mGestureInputEnabled = JniUtils.sHaveGestureLib && prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
        mGestureTrailEnabled = prefs.getBoolean(Settings.PREF_GESTURE_PREVIEW_TRAIL, Defaults.PREF_GESTURE_PREVIEW_TRAIL)
        mGestureFloatingPreviewTextEnabled = !mInputAttributes.mDisableGestureFloatingPreviewText
                && prefs.getBoolean(Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT, Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
        mGestureFloatingPreviewDynamicEnabled = Settings.readGestureDynamicPreviewEnabled(prefs)
        mGestureFastTypingCooldown = prefs.getInt(Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN, Defaults.PREF_GESTURE_FAST_TYPING_COOLDOWN)
        mGestureTrailFadeoutDuration = prefs.getInt(Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, Defaults.PREF_GESTURE_TRAIL_FADEOUT_DURATION)
        mSuggestionStripHiddenPerUserSettings = mToolbarMode == ToolbarMode.HIDDEN || mToolbarMode == ToolbarMode.TOOLBAR_KEYS
        mOverrideShowingSuggestions = mInputAttributes.mMayOverrideShowingSuggestions
                && prefs.getBoolean(Settings.PREF_ALWAYS_SHOW_SUGGESTIONS, Defaults.PREF_ALWAYS_SHOW_SUGGESTIONS)
                && ((inputAttributes.mInputType and InputType.TYPE_MASK_VARIATION) != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
                  || !prefs.getBoolean(Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT, Defaults.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT))
        mSuggestionsEnabled = prefs.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, Defaults.PREF_SHOW_SUGGESTIONS)
            && (mInputAttributes.mShouldShowSuggestions || mOverrideShowingSuggestions) && !mSuggestionStripHiddenPerUserSettings
        mIncognitoModeEnabled = prefs.getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE) || mInputAttributes.mNoLearning
                || mInputAttributes.mIsPasswordField
        mBottomRowScale = Settings.readBottomRowScale(prefs, isLandscape, isFolded)
        mSpaceSwipeHorizontal = Settings.readHorizontalSpaceSwipe(prefs)
        mSpaceSwipeVertical = Settings.readVerticalSpaceSwipe(prefs)
        mLanguageSwipeDistance = prefs.getInt(Settings.PREF_LANGUAGE_SWIPE_DISTANCE, Defaults.PREF_LANGUAGE_SWIPE_DISTANCE)
        mTouchpadSensitivity = prefs.getInt(Settings.PREF_TOUCHPAD_SENSITIVITY,
            Defaults.PREF_TOUCHPAD_SENSITIVITY)
        mTouchpadEdgeScroll = prefs.getBoolean(Settings.PREF_TOUCHPAD_EDGE_SCROLL, Defaults.PREF_TOUCHPAD_EDGE_SCROLL)
        mDeleteSwipeEnabled = prefs.getBoolean(Settings.PREF_DELETE_SWIPE, Defaults.PREF_DELETE_SWIPE)
        mSwipeDeleteByWord = prefs.getBoolean(Settings.PREF_SWIPE_DELETE_BY_WORD, Defaults.PREF_SWIPE_DELETE_BY_WORD)
        mHoldDeleteByWord = prefs.getBoolean(Settings.PREF_HOLD_DELETE_BY_WORD, Defaults.PREF_HOLD_DELETE_BY_WORD)
        mAutospaceAfterPunctuation = prefs.getBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, Defaults.PREF_AUTOSPACE_AFTER_PUNCTUATION)
        mAutospaceAfterSuggestion = prefs.getBoolean(Settings.PREF_AUTOSPACE_AFTER_SUGGESTION, Defaults.PREF_AUTOSPACE_AFTER_SUGGESTION)
        mAutospaceAfterGestureTyping = prefs.getBoolean(Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING, Defaults.PREF_AUTOSPACE_AFTER_GESTURE_TYPING)
        mAutospaceBeforeGestureTyping = prefs.getBoolean(Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING, Defaults.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING)
        mShiftRemovesAutospace = prefs.getBoolean(Settings.PREF_SHIFT_REMOVES_AUTOSPACE, Defaults.PREF_SHIFT_REMOVES_AUTOSPACE)
        mClipboardHistoryEnabled = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
        mClipboardHistoryRetentionTime = prefs.getInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME).toLong()
        mClipboardHistoryPinnedFirst = prefs.getBoolean(Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST, Defaults.PREF_CLIPBOARD_HISTORY_PINNED_FIRST)

        mIsFloatingKeyboard = !mIsLocked && isFloatingKeyboardEnabled(context)
        mFloatingWidth = readFloatingWidth(context)
        mFloatingHeight = if (mIsFloatingKeyboard && mHasHardwareKeyboard && prefs.getBoolean(Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD, Defaults.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD))
                          0 else readFloatingHeight(context)
        mKeyboardHeightScale = if (mIsFloatingKeyboard) 1f else Settings.readHeightScale(prefs, isLandscape, isFolded)
        mOneHandedModeEnabled = !mIsFloatingKeyboard && Settings.readOneHandedModeEnabled(prefs, isLandscape, mIsSplitKeyboardEnabled, isFolded)
        mOneHandedModeGravity = Settings.readOneHandedModeGravity(prefs, isLandscape, mIsSplitKeyboardEnabled, isFolded)
        if (mOneHandedModeEnabled) {
            val baseScale = res.getFraction(R.fraction.config_one_handed_mode_width, 1, 1)
            val extraScale = Settings.readOneHandedModeScale(prefs, isLandscape, mIsSplitKeyboardEnabled, isFolded)
            mOneHandedModeScale = 1 - (1 - baseScale) * extraScale
        } else
            mOneHandedModeScale = 1f
        mSecondaryLocales = getSecondaryLocales(selectedSubtype.extraValue)
        mShowMorePopupKeys = getMoreKeys(selectedSubtype, prefs,
            if (selectedSubtype.isAsciiCapable) Defaults.PREF_MORE_POPUP_KEYS else LocaleKeyboardInfos.POPUP_KEYS_NORMAL)
        mColors = KeyboardTheme.getColorsForCurrentTheme(context)

        mPopupKeyOrder = getPopupKeyOrder(selectedSubtype, prefs)
        mPopupKeyHintOrder = getPopupKeyHintOrder(selectedSubtype, prefs)
        mAddToPersonalDictionary = prefs.getBoolean(Settings.PREF_ADD_TO_PERSONAL_DICTIONARY, Defaults.PREF_ADD_TO_PERSONAL_DICTIONARY)
        mUseContactsDictionary = readUseContactsEnabled(prefs, context)
        mUseAppsDictionary = prefs.getBoolean(Settings.PREF_USE_APPS, Defaults.PREF_USE_APPS)
        mCustomNavBarColor = prefs.getBoolean(Settings.PREF_NAVBAR_COLOR, Defaults.PREF_NAVBAR_COLOR)
        mKeyGapScale = Settings.readKeyGapScale(prefs, isLandscape, isFolded)
        mSettingsValuesForSuggestion = SettingsValuesForSuggestion(
                mBlockPotentiallyOffensive,
                prefs.getBoolean(Settings.PREF_GESTURE_SPACE_AWARE, Defaults.PREF_GESTURE_SPACE_AWARE)
        )
        mSpacingAndPunctuations = SpacingAndPunctuations(res, mUrlDetectionEnabled)
        mBottomPaddingScale = if (mIsFloatingKeyboard) 0f else Settings.readBottomPaddingScale(prefs, isLandscape, isFolded)
        mSidePaddingScale = Settings.readSidePaddingScale(prefs, isLandscape, mIsSplitKeyboardEnabled, isFolded)
        mLongPressSymbolsForNumpad = prefs.getBoolean(Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD, Defaults.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD)
        mAutoShowToolbar = mToolbarMode == ToolbarMode.EXPANDABLE && prefs.getBoolean(Settings.PREF_AUTO_SHOW_TOOLBAR, Defaults.PREF_AUTO_SHOW_TOOLBAR)
        mAutoHideToolbar = mSuggestionsEnabled && prefs.getBoolean(Settings.PREF_AUTO_HIDE_TOOLBAR, Defaults.PREF_AUTO_HIDE_TOOLBAR)
        mAlphaAfterEmojiInEmojiView = prefs.getBoolean(Settings.PREF_ABC_AFTER_EMOJI, Defaults.PREF_ABC_AFTER_EMOJI)
        mAlphaAfterClipHistoryEntry = prefs.getBoolean(Settings.PREF_ABC_AFTER_CLIP, Defaults.PREF_ABC_AFTER_CLIP)
        if (prefs.getBoolean(Settings.PREF_ABC_AFTER_SYMBOL_SPACE, Defaults.PREF_ABC_AFTER_SYMBOL_SPACE)) {
            mAlphaAfterSpace.add(KeyboardState.Mode.SYMBOLS)
            mAlphaAfterSpace.add(KeyboardState.Mode.SYMBOLS_SHIFTED)
        }
        if (prefs.getBoolean(Settings.PREF_ABC_AFTER_NUMPAD_SPACE, Defaults.PREF_ABC_AFTER_NUMPAD_SPACE)) {
            mAlphaAfterSpace.add(KeyboardState.Mode.NUMPAD)
        }
        mRemoveRedundantPopups = prefs.getBoolean(Settings.PREF_REMOVE_REDUNDANT_POPUPS, Defaults.PREF_REMOVE_REDUNDANT_POPUPS)
        mSpaceBarText = prefs.getString(Settings.PREF_SPACE_BAR_TEXT, Defaults.PREF_SPACE_BAR_TEXT)!!
        mFontSizeMultiplier = prefs.getFloat(Settings.PREF_FONT_SCALE, Defaults.PREF_FONT_SCALE)
        mHintFontSizeMultiplier = if (mShowsHints) prefs.getFloat(Settings.PREF_HINT_FONT_SCALE, Defaults.PREF_HINT_FONT_SCALE) else 1f
        mFontSizeMultiplierEmoji = prefs.getFloat(Settings.PREF_EMOJI_FONT_SCALE, Defaults.PREF_EMOJI_FONT_SCALE)
        mEmojiKeyFit = prefs.getBoolean(Settings.PREF_EMOJI_KEY_FIT, Defaults.PREF_EMOJI_KEY_FIT)
        mPunctuationSuggestions = Settings.readPunctuationSuggestions(context)
    }

    fun needsToLookupSuggestions(): Boolean {
        return (mInputAttributes.mShouldShowSuggestions || mOverrideShowingSuggestions)
                && (mAutoCorrectEnabled || mSuggestionsEnabled)
    }

    fun isWordSeparator(code: Int): Boolean {
        return mSpacingAndPunctuations.isWordSeparator(code)
    }

    fun isWordConnector(code: Int): Boolean {
        return mSpacingAndPunctuations.isWordConnector(code)
    }

    fun isWordCodePoint(code: Int): Boolean {
        return mSpacingAndPunctuations.isWordCodePoint(code)
    }

    fun isUsuallyPrecededBySpace(code: Int): Boolean {
        return mSpacingAndPunctuations.isUsuallyPrecededBySpace(code)
    }

    fun isUsuallyFollowedBySpace(code: Int): Boolean {
        return mSpacingAndPunctuations.isUsuallyFollowedBySpace(code)
    }

    fun shouldInsertSpacesAutomatically(): Boolean {
        return mInputAttributes.mShouldInsertSpacesAutomatically
    }

    fun isLanguageSwitchKeyEnabled(): Boolean {
        if (!mShowsLanguageSwitchKey) {
            return false
        }
        val imm = RichInputMethodManager.getInstance()
        if (!mLanguageSwitchKeyToOtherSubtypes) {
            return imm.hasMultipleEnabledIMEsOrSubtypes(false /* include aux subtypes */)
        }
        if (!mLanguageSwitchKeyToOtherImes) {
            return imm.hasMultipleEnabledSubtypesInThisIme(true /* include aux subtypes */)
        }
        return imm.hasMultipleEnabledSubtypesInThisIme(false /* include aux subtypes */)
            || imm.hasMultipleEnabledIMEsOrSubtypes(false /* include aux subtypes */)
    }

    fun isSameInputType(editorInfo: EditorInfo?): Boolean {
        return mInputAttributes.isSameInputType(editorInfo)
    }

    fun hasSameOrientation(configuration: Configuration): Boolean {
        return mDisplayOrientation == configuration.orientation
    }

    fun isSecondaryStripVisible(): Boolean {
        return mToolbarMode != ToolbarMode.HIDDEN || !mToolbarHidingGlobal
    }

    fun dump(): String {
        val sb = StringBuilder("Current settings :")
        sb.append("\n   mSpacingAndPunctuations = ")
        sb.append("" + mSpacingAndPunctuations.dump())
        sb.append("\n   mAutoCap = ")
        sb.append("" + mAutoCap)
        sb.append("\n   mVibrateOn = ")
        sb.append("" + mVibrateOn)
        sb.append("\n   mSoundOn = ")
        sb.append("" + mSoundOn)
        sb.append("\n   mKeyPreviewPopupOn = ")
        sb.append("" + mKeyPreviewPopupOn)
        sb.append("\n   mShowsVoiceInputKey = ")
        sb.append("" + mShowsVoiceInputKey)
        sb.append("\n   mLanguageSwitchKeyToOtherImes = ")
        sb.append("" + mLanguageSwitchKeyToOtherImes)
        sb.append("\n   mLanguageSwitchKeyToOtherSubtypes = ")
        sb.append("" + mLanguageSwitchKeyToOtherSubtypes)
        sb.append("\n   mUsePersonalizedDicts = ")
        sb.append("" + mUsePersonalizedDicts)
        sb.append("\n   mUseDoubleSpacePeriod = ")
        sb.append("" + mUseDoubleSpacePeriod)
        sb.append("\n   mBlockPotentiallyOffensive = ")
        sb.append("" + mBlockPotentiallyOffensive)
        sb.append("\n   mBigramPredictionEnabled = ")
        sb.append("" + mBigramPredictionEnabled)
        sb.append("\n   mGestureInputEnabled = ")
        sb.append("" + mGestureInputEnabled)
        sb.append("\n   mGestureTrailEnabled = ")
        sb.append("" + mGestureTrailEnabled)
        sb.append("\n   mGestureFloatingPreviewTextEnabled = ")
        sb.append("" + mGestureFloatingPreviewTextEnabled)
        sb.append("\n   mSlidingKeyInputPreviewEnabled = ")
        sb.append("" + mSlidingKeyInputPreviewEnabled)
        sb.append("\n   mKeyLongpressTimeout = ")
        sb.append("" + mKeyLongpressTimeout)
        sb.append("\n   mLocale = ")
        sb.append("" + mLocale)
        sb.append("\n   mInputAttributes = ")
        sb.append("" + mInputAttributes)
        sb.append("\n   mKeypressVibrationDuration = ")
        sb.append("" + mKeypressVibrationDuration)
        sb.append("\n   mKeypressSoundVolume = ")
        sb.append("" + mKeypressSoundVolume)
        sb.append("\n   mAutoCorrectEnabled = ")
        sb.append("" + mAutoCorrectEnabled)
        sb.append("\n   mAutoCorrectionThreshold = ")
        sb.append("" + mAutoCorrectionThreshold)
        sb.append("\n   mAutoCorrectionEnabledPerUserSettings = ")
        sb.append("" + mAutoCorrectionEnabledPerUserSettings)
        sb.append("\n   mSuggestionsEnabledPerUserSettings = ")
        sb.append("" + mSuggestionsEnabled)
        sb.append("\n   mDisplayOrientation = ")
        sb.append("" + mDisplayOrientation)
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
