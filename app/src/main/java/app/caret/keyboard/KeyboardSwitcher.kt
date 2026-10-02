/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import app.caret.event.Event
import app.caret.keyboard.clipboard.ClipboardHistoryView
import app.caret.keyboard.emoji.EmojiPalettesView
import app.caret.keyboard.internal.KeyboardState
import app.caret.keyboard.internal.LayoutDirective
import app.caret.keyboard.internal.ShiftMode
import app.caret.keyboard.internal.keyboard_parser.loadEmojiDefaultVersionsAndPopupSpecs
import app.caret.latin.CapsMode
import app.caret.latin.InputView
import app.caret.latin.KeyboardWrapperView
import app.caret.latin.LatinIME
import app.caret.latin.R
import app.caret.latin.RichInputMethodManager
import app.caret.latin.RichInputMethodSubtype
import app.caret.latin.settings.Settings
import app.caret.latin.settings.setFloatingKeyboardEnabled
import app.caret.latin.settings.SettingsValues
import app.caret.latin.suggestions.SuggestionStripView
import app.caret.latin.utils.CapsModeUtils
import app.caret.latin.utils.FloatingKeyboardUtils
import app.caret.latin.utils.FoldableUtils
import app.caret.latin.utils.dpToPx
import app.caret.latin.utils.prefs
import app.caret.latin.utils.LanguageOnSpacebarUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.RecapitalizeMode
import app.caret.latin.utils.ResourceUtils
import app.caret.latin.utils.ScriptUtils
import app.caret.latin.utils.SubtypeUtilsAdditional
import app.caret.latin.utils.ToolbarMode

class KeyboardSwitcher private constructor() {
    // Intentional empty constructor for singleton.

    private var mCurrentInputView: InputView? = null
    private var mKeyboardViewWrapper: KeyboardWrapperView? = null
    private var mMainKeyboardFrame: View? = null
    private var mKeyboardView: MainKeyboardView? = null
    private var mEmojiPalettesView: EmojiPalettesView? = null
    private var mEmojiTabStripView: View? = null
    private var mClipboardStripView: LinearLayout? = null
    private var mClipboardStripScrollView: HorizontalScrollView? = null
    private var mSuggestionStripView: SuggestionStripView? = null
    private var mStripContainer: FrameLayout? = null
    private var mClipboardHistoryView: ClipboardHistoryView? = null
    private var mFakeToastView: TextView? = null
    private var mBackgroundGatheringIndicator: ImageView? = null
    private var mLatinIME: LatinIME? = null
    private lateinit var mRichImm: RichInputMethodManager
    private var mIsHardwareAcceleratedDrawingEnabled = false

    private lateinit var mState: KeyboardState

    private var mKeyboardLayoutSet: KeyboardLayoutSet? = null

    private var mKeyboardTheme: KeyboardTheme? = null
    private var mThemeContext: Context? = null
    private var mCurrentUiMode = 0
    private var mCurrentOrientation = 0
    private var mCurrentDpi = 0
    private var mThemeNeedsReload = false

    private fun initInternal(latinIme: LatinIME) {
        mLatinIME = latinIme
        mRichImm = RichInputMethodManager.getInstance()
        mState = KeyboardState(SwitchActions())
        mIsHardwareAcceleratedDrawingEnabled = mLatinIME!!.enableHardwareAcceleration()
    }

    fun updateKeyboardTheme(displayContext: Context) {
        val themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext))
        if (themeUpdated) {
            val settings = Settings.getInstance()
            settings.loadSettings(displayContext, settings.getCurrent().mLocale, settings.getCurrent().mInputAttributes)
            if (mKeyboardView != null)
                mLatinIME!!.setInputView(onCreateInputView(displayContext, mIsHardwareAcceleratedDrawingEnabled))
        } else if (mCurrentInputView != null && mLatinIME!!.hasSuggestionStripView()
                    == (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN || mLatinIME!!.isEmojiSearch())) {
            mLatinIME!!.updateSuggestionStripView(mCurrentInputView)
        }
    }

    private fun updateKeyboardThemeAndContextThemeWrapper(context: Context, keyboardTheme: KeyboardTheme): Boolean {
        val res = context.getResources()
        if (mThemeNeedsReload
                || mThemeContext == null
                || !keyboardTheme.equals(mKeyboardTheme)
                || mCurrentDpi != res.getDisplayMetrics().densityDpi
                || mCurrentOrientation != res.getConfiguration().orientation
                || (mCurrentUiMode and Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK)
                || !mThemeContext!!.getResources().equals(res)
                || Settings.getValues().mColors.haveColorsChanged(context)) {
            mThemeNeedsReload = false
            mKeyboardTheme = keyboardTheme
            mThemeContext = ContextThemeWrapper(context, keyboardTheme.mStyleId)
            mCurrentUiMode = res.getConfiguration().uiMode
            mCurrentOrientation = res.getConfiguration().orientation
            mCurrentDpi = res.getDisplayMetrics().densityDpi
            KeyboardLayoutSet.onKeyboardThemeChanged()
            return true
        }
        return false
    }

    fun loadKeyboard(editorInfo: EditorInfo?, settingsValues: SettingsValues, currentAutoCapsState: Int,
                     currentRecapitalizeState: RecapitalizeMode?, internalAction: KeyboardLayoutSet.InternalAction?) {
        val builder = KeyboardLayoutSet.Builder(mThemeContext!!, editorInfo, settingsValues)
        val keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues)
        val keyboardHeight = ResourceUtils.getKeyboardHeight(mThemeContext!!.getResources(), settingsValues)
        mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(mRichImm.currentSubtype)
                .setInternalAction(internalAction)
                .build()
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, settingsValues.mOneHandedModeEnabled)
        } catch (e: KeyboardLayoutSet.Companion.KeyboardLayoutSetException) {
            Log.e(TAG, "loading keyboard failed: " + e.keyboardId, e.cause)
            try {
                val defaults = SubtypeUtilsAdditional.createDefaultSubtype(mRichImm.currentSubtypeLocale)
                mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.get(defaults))
                        .build()
                mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, false)
                showToast("error loading the keyboard, falling back to defaults", false)
            } catch (e2: KeyboardLayoutSet.Companion.KeyboardLayoutSetException) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.keyboardId, e2.cause)
            }
        }
    }

    fun saveKeyboardState() {
        if (getKeyboard() != null || isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            mState.onSaveKeyboardState()
        }
    }

    fun onHideWindow() {
        if (mKeyboardView != null) {
            mKeyboardView!!.onHideWindow()
        }
    }

    fun getKeyboard(): Keyboard? {
        if (mKeyboardView != null) {
            return mKeyboardView!!.getKeyboard()
        }
        return null
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    fun resetKeyboardStateToAlphabet() {
        mState.onResetKeyboardStateToAlphabet(mLatinIME!!.getCurrentAutoCapsState(), mLatinIME!!.getCurrentRecapitalizeState())
    }

    fun onPressKey(code: Int, pointerCount: Int, currentAutoCapsState: Int,
            currentRecapitalizeState: RecapitalizeMode?) {
        mState.onPressKey(code, pointerCount, currentAutoCapsState, currentRecapitalizeState)
    }

    fun onReleaseKey(code: Int, withSliding: Boolean,
            currentAutoCapsState: Int, currentRecapitalizeState: RecapitalizeMode?) {
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState)
    }

    fun onFinishSlidingInput(currentAutoCapsState: Int,
            currentRecapitalizeState: RecapitalizeMode?) {
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState)
    }

    fun setEmojiKeyboard() {
        mState.setLayout(LayoutDirective.Utility.EMOJI)
    }

    fun setClipboardKeyboard() {
        mState.setLayout(LayoutDirective.Utility.CLIPBOARD)
    }

    fun isImeSuppressedByHardwareKeyboard(
            settingsValues: SettingsValues,
            toggleState: KeyboardSwitchState): Boolean {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN
    }

    private fun setMainKeyboardFrame(
            settingsValues: SettingsValues,
            toggleState: KeyboardSwitchState) {
        val visibility = if (isImeSuppressedByHardwareKeyboard(settingsValues, toggleState)) View.GONE else View.VISIBLE
        val stripVisibility = if (mLatinIME!!.hasSuggestionStripView()) View.VISIBLE else View.GONE
        mStripContainer!!.setVisibility(stripVisibility)
        PointerTracker.switchTo(mKeyboardView!!)
        mKeyboardView!!.setVisibility(visibility)
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mMainKeyboardFrame!!.setVisibility(visibility)
        mKeyboardViewWrapper!!.setVisibility(if (Settings.getInstance().readShowToolbarOnly()) View.GONE else View.VISIBLE)
        mEmojiPalettesView!!.setVisibility(View.GONE)
        mEmojiPalettesView!!.stopEmojiPalettes()
        mEmojiTabStripView!!.setVisibility(View.GONE)
        mClipboardStripScrollView!!.setVisibility(View.GONE)
        mSuggestionStripView!!.setVisibility(stripVisibility)
        mClipboardHistoryView!!.setVisibility(View.GONE)
        mClipboardHistoryView!!.stopClipboardHistory()
    }

    fun toggleLayout(layout: LayoutDirective.Utility, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {
        mState.toggleLayout(layout, autoCapsFlags, recapitalizeMode)
    }

    fun onLongPressAlphaSymbolForNumpad() {
        if (KeyboardState.SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "onLongPressAlphaSymbol")
        }
        mState.onLongPressAlphaSymbolForNumpad()
    }

    enum class KeyboardSwitchState(val mKeyboardElement: KeyboardElement?) {
        HIDDEN(null),
        SYMBOLS_SHIFTED(KeyboardElement.SYMBOLS_SHIFTED),
        EMOJI(KeyboardElement.EMOJI_RECENTS),
        CLIPBOARD(KeyboardElement.CLIPBOARD),
        OTHER(null);
    }

    fun getKeyboardSwitchState(): KeyboardSwitchState {
        val hidden = !isShowingEmojiPalettes() && !isShowingClipboardHistory()
                && (mKeyboardLayoutSet == null
                || mKeyboardView == null
                || !mKeyboardView!!.isShown())
        if (hidden) {
            return KeyboardSwitchState.HIDDEN
        } else if (isShowingEmojiPalettes()) {
            return KeyboardSwitchState.EMOJI
        } else if (isShowingClipboardHistory()) {
            return KeyboardSwitchState.CLIPBOARD
        } else if (isShowingKeyboardId(KeyboardElement.SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED
        }
        return KeyboardSwitchState.OTHER
    }

    fun onToggleKeyboard(toggleState: KeyboardSwitchState) {
        val currentState = getKeyboardSwitchState()
        Log.w(TAG, "onToggleKeyboard() : Current = " + currentState + " : Toggle = " + toggleState)
        if (currentState == toggleState) {
            mLatinIME!!.stopShowingInputView()
            mLatinIME!!.hideWindow()
            resetKeyboardStateToAlphabet()
        } else {
            mLatinIME!!.startShowingInputView(true)
            if (toggleState == KeyboardSwitchState.EMOJI) {
                setEmojiKeyboard()
            } else if (toggleState == KeyboardSwitchState.CLIPBOARD) {
                setClipboardKeyboard()
            } else {
                mEmojiPalettesView!!.stopEmojiPalettes()
                mEmojiPalettesView!!.setVisibility(View.GONE)

                mClipboardHistoryView!!.stopClipboardHistory()
                mClipboardHistoryView!!.setVisibility(View.GONE)

                mMainKeyboardFrame!!.setVisibility(View.VISIBLE)
                mKeyboardView!!.setVisibility(View.VISIBLE)
                if (toggleState == KeyboardSwitchState.SYMBOLS_SHIFTED)
                    // possible other states OTHER and HIDDEN have keyboardElement null, which we just ignore
                    // might need to be adjusted when functionality is extended
                    mState.setLayout(LayoutDirective.Utility.SYMBOLS_SHIFTED)
            }
        }
    }

    fun updateShiftState(autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {
        if (KeyboardState.SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "updateShiftState: " + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags) + " recapitalizeMode=" + recapitalizeMode)
        }
        mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode)
    }

    fun setOneHandedModeEnabled(enabled: Boolean, force: Boolean) {
        if (!force && mKeyboardViewWrapper!!.oneHandedModeEnabled == enabled) {
            return
        }
        val settings = Settings.getInstance()
        mKeyboardViewWrapper!!.oneHandedModeEnabled = enabled
        mKeyboardViewWrapper!!.oneHandedGravity = settings.getCurrent().mOneHandedModeGravity

        // oneHandeMode is always disabled when floating, and we shouldn't mess up the setting
        if (enabled != settings.getCurrent().mOneHandedModeEnabled)
            settings.writeOneHandedModeEnabled(enabled)
        reloadKeyboard()
    }

    fun toggleSplitKeyboardMode() {
        val settings = Settings.getInstance()
        settings.writeSplitKeyboardEnabled(
            !settings.getCurrent().mIsSplitKeyboardEnabled,
            mCurrentOrientation == Configuration.ORIENTATION_LANDSCAPE,
            FoldableUtils.isFolded
        )
        setOneHandedModeEnabled(settings.getCurrent().mOneHandedModeEnabled, true)
        reloadKeyboard()
    }

    /** Caret: shows the drag handles for resizing the keyboard. */
    fun startResizeMode() {
        if (mKeyboardViewWrapper != null) mKeyboardViewWrapper!!.resizeMode = true
    }

    fun stopResizeMode() {
        if (mKeyboardViewWrapper != null) {
            mKeyboardViewWrapper!!.resizeMode = false
            mKeyboardViewWrapper!!.actionsPanelShown = false
        }
    }

    /** Caret: shows the grid with every action over the keys. */
    fun showActionsPanel() {
        if (mKeyboardViewWrapper != null) mKeyboardViewWrapper!!.actionsPanelShown = true
    }

    fun reloadKeyboard() {
        if (mCurrentInputView == null)
            return
        mEmojiPalettesView!!.clearKeyboardCache()
        reloadMainKeyboard()
    }

    fun reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout
        val wasEmoji = isShowingEmojiPalettes()
        val wasClipboard = isShowingClipboardHistory()
        loadKeyboard(mLatinIME!!.getCurrentInputEditorInfo(), Settings.getValues(),
                mLatinIME!!.getCurrentAutoCapsState(), mLatinIME!!.getCurrentRecapitalizeState(), null)
        if (wasEmoji) {
            setEmojiKeyboard()
        } else if (wasClipboard) {
            setClipboardKeyboard()
        }
    }

    /**
     * Displays a toast message.
     *
     * @param text The text to display in the toast message.
     * @param briefToast If true, the toast duration will be short; otherwise, it will last longer.
     */
    fun showToast(text: String, briefToast: Boolean) {
        // In API 32 and below, toasts can be shown without a notification permission.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            val toastLength = if (briefToast) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            val toast = Toast.makeText(mLatinIME!!, text, toastLength)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
        } else {
            val toastLength = if (briefToast) 2000 else 3500
            showFakeToast(text, toastLength)
        }
    }

    // Displays a toast-like message with the provided text for a specified duration.
    private fun showFakeToast(text: String, timeMillis: Int) {
        if (mFakeToastView!!.visibility == View.VISIBLE) return

        val appIcon = mFakeToastView!!.compoundDrawables[0]
        if (appIcon != null) {
            val bound = mFakeToastView!!.lineHeight
            appIcon.setBounds(0, 0, bound, bound)
            mFakeToastView!!.setCompoundDrawables(appIcon, null, null, null)
        }
        mFakeToastView!!.setText(text)
        mFakeToastView!!.visibility = View.VISIBLE
        mFakeToastView!!.bringToFront()
        mFakeToastView!!.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_in))

        mFakeToastView!!.postDelayed({
            mFakeToastView!!.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_out))
            mFakeToastView!!.visibility = View.GONE
        }, timeMillis.toLong())
    }

    fun setBackgroundGatheringIndicator(enabled: Boolean, hasData: Boolean, saving: Boolean) {
        if (mCurrentInputView == null) return
        mBackgroundGatheringIndicator!!.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return
        mBackgroundGatheringIndicator!!.setImageResource(if (hasData) R.drawable.btn_keyboard_key_action_normal_lxx_base else R.drawable.ring)
        setBackgroundGatheringIndicatorPosition()
        if (!saving) return
        mBackgroundGatheringIndicator!!.imageTintList = ColorStateList.valueOf(0xff00a000.toInt())
        mBackgroundGatheringIndicator!!.postDelayed({ mBackgroundGatheringIndicator!!.imageTintList = ColorStateList.valueOf(0xffa00000.toInt()) }, 1500)
    }

    private fun setBackgroundGatheringIndicatorPosition() {
        if (mBackgroundGatheringIndicator == null || mBackgroundGatheringIndicator!!.visibility != View.VISIBLE) return
        val margin = mBackgroundGatheringIndicator!!.layoutParams
        if (margin is ViewGroup.MarginLayoutParams) {
            val kb = mKeyboardView!!.getKeyboard()
            if (kb != null)
                margin.topMargin = kb.mOccupiedHeight - 16.dpToPx(mCurrentInputView!!.resources)
            mBackgroundGatheringIndicator!!.layoutParams = mBackgroundGatheringIndicator!!.layoutParams
        }
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    fun onEvent(event: Event, currentAutoCapsState: Int, currentRecapitalizeState: RecapitalizeMode?) {
        mState!!.onEvent(event, currentAutoCapsState, currentRecapitalizeState)
    }

    fun isShowingKeyboardId(vararg keyboardElements: KeyboardElement): Boolean {
        if (mKeyboardView == null || !mKeyboardView!!.isShown()) {
            return false
        }
        val keyboard = mKeyboardView!!.getKeyboard()
        if (keyboard == null) // may happen when using hardware keyboard
            return false
        val activeKeyboardId = keyboard.mId.element
        for (keyboardElement in keyboardElements) {
            if (activeKeyboardId == keyboardElement) {
                return true
            }
        }
        return false
    }

    fun isShowingEmojiPalettes(): Boolean {
        return mEmojiPalettesView != null && mEmojiPalettesView!!.isShown()
    }

    fun isShowingClipboardHistory(): Boolean {
        return mClipboardHistoryView != null && mClipboardHistoryView!!.isShown()
    }

    fun isShowingPopupKeysPanel(): Boolean {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            return false
        }
        return mKeyboardView!!.isShowingPopupKeysPanel()
    }

    fun isShowingStripContainer(): Boolean {
        return mStripContainer!!.isShown()
    }

    fun getVisibleKeyboardView(): View? {
        if (isShowingEmojiPalettes()) {
            return mEmojiPalettesView
        } else if (isShowingClipboardHistory()) {
            return mClipboardHistoryView
        }
        return mKeyboardView
    }

    fun getWrapperView(): View? {
        return mKeyboardViewWrapper
    }

    fun getEmojiTabStrip(): View? {
        return mEmojiTabStripView
    }

    fun getClipboardStrip(): LinearLayout? {
        return mClipboardStripView
    }

    fun getMainKeyboardView(): MainKeyboardView? {
        return mKeyboardView
    }

    fun getStripContainer(): FrameLayout? { return mStripContainer }

    fun deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView!!.cancelAllOngoingEvents()
            mKeyboardView!!.deallocateMemory()
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView!!.stopEmojiPalettes()
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView!!.stopClipboardHistory()
        }
    }

    fun trimMemory() {
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView!!.clearKeyboardCache()
        }
    }

    @SuppressLint("InflateParams")
    fun onCreateInputView(displayContext: Context, isHardwareAcceleratedDrawingEnabled: Boolean): View {
        Log.d(TAG, "create new input view")
        if (mKeyboardView != null) {
            mKeyboardView!!.closing()
        }
        PointerTracker.clearOldViewData()
        val prefs = displayContext.prefs()
        if (mSuggestionStripView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mSuggestionStripView)
        if (mClipboardHistoryView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mClipboardHistoryView)
        if (mThemeNeedsReload) // necessary in some cases (e.g. theme switch) when mThemeNeedsReload is set before first keyboard load
            Settings.getInstance().loadSettings(displayContext, Settings.getValues().mLocale, Settings.getValues().mInputAttributes)

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext))
        mCurrentInputView = LayoutInflater.from(mThemeContext).inflate(R.layout.input_view, null) as InputView?
        mMainKeyboardFrame = mCurrentInputView!!.findViewById(R.id.main_keyboard_frame)
        mEmojiPalettesView = mCurrentInputView!!.findViewById(R.id.emoji_palettes_view)
        mClipboardHistoryView = mCurrentInputView!!.findViewById(R.id.clipboard_history_view)
        mFakeToastView = mCurrentInputView!!.findViewById(R.id.fakeToast)

        mKeyboardViewWrapper = mCurrentInputView!!.findViewById(R.id.keyboard_view_wrapper)
        mKeyboardViewWrapper!!.keyboardActionListener = mLatinIME!!.keyboardActionListener
        mKeyboardView = mCurrentInputView!!.findViewById(R.id.keyboard_view)
        mKeyboardView!!.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled)
        mKeyboardView!!.setKeyboardActionListener(mLatinIME!!.keyboardActionListener)
        mEmojiPalettesView!!.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled)
        mEmojiPalettesView!!.setKeyboardActionListener(mLatinIME!!.keyboardActionListener)
        mClipboardHistoryView!!.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled)
        mClipboardHistoryView!!.keyboardActionListener = mLatinIME!!.keyboardActionListener
        mEmojiTabStripView = mCurrentInputView!!.findViewById(R.id.emoji_tab_strip)
        mClipboardStripView = mCurrentInputView!!.findViewById(R.id.clipboard_strip)
        mClipboardStripScrollView = mCurrentInputView!!.findViewById(R.id.clipboard_strip_scroll_view)
        mSuggestionStripView = mCurrentInputView!!.findViewById(R.id.suggestion_strip_view)
        mStripContainer = mCurrentInputView!!.findViewById(R.id.strip_container)
        mBackgroundGatheringIndicator = mCurrentInputView!!.findViewById(R.id.backgroundGatheringIndicator)

        prefs.registerOnSharedPreferenceChangeListener(mSuggestionStripView)
        prefs.registerOnSharedPreferenceChangeListener(mClipboardHistoryView)
        PointerTracker.switchTo(mKeyboardView!!)
        return mCurrentInputView!!
    }

    fun getKeyboardCapsMode(): CapsMode {
        val keyboard = getKeyboard()
        if (keyboard == null) {
            return CapsMode.OFF
        }
        return keyboard.mId.element.capsMode
    }

    fun getCurrentKeyboardScript(): String {
        if (null == mKeyboardLayoutSet) {
            return ScriptUtils.SCRIPT_UNKNOWN
        }
        return mKeyboardLayoutSet!!.script
    }

    fun switchToSubtype(subtype: InputMethodSubtype?) {
        mLatinIME!!.switchToSubtype(subtype)
    }

    // used for debug
    fun getLocaleAndConfidenceInfo(): String? {
        return mLatinIME!!.getLocaleAndConfidenceInfo()
    }

    /** Marks the theme as outdated. The theme will be reloaded next time the keyboard is shown.
     *  If the keyboard is currently showing, theme will be reloaded immediately. */
    fun setThemeNeedsReload() {
        mThemeNeedsReload = true
        if (mLatinIME == null || !mLatinIME!!.isInputViewShown())
            return // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the user shouldn't notice.
        mLatinIME!!.hideWindow()
        try {
            mLatinIME!!.showWindow(true)
        } catch (e: IllegalStateException) {
            // in tests isInputViewShown returns true, but showWindow throws "IllegalStateException: Window token is not set yet."
        }
    }

    // private SwitchActions implementation so e.g. setEmojiKeyboard can only be called via KeyboardState (avoid inconsistencies!)
    private inner class SwitchActions : KeyboardState.SwitchActions {
        override fun setAlphabetKeyboard(shiftMode: ShiftMode) {
            if (KeyboardState.SwitchActions.DEBUG_ACTION) {
                Log.d(TAG, "setAlphabetKeyboard")
            }
            setKeyboard(shiftMode.element, KeyboardSwitchState.OTHER)
        }

        override fun setSymbolsKeyboard() {
            if (KeyboardState.SwitchActions.DEBUG_ACTION) {
                Log.d(TAG, "setSymbolsKeyboard")
            }
            setKeyboard(KeyboardElement.SYMBOLS, KeyboardSwitchState.OTHER)
        }

        override fun setSymbolsShiftedKeyboard() {
            if (KeyboardState.SwitchActions.DEBUG_ACTION) {
                Log.d(TAG, "setSymbolsShiftedKeyboard")
            }
            setKeyboard(KeyboardElement.SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED)
        }

        override fun setEmojiKeyboard() {
            if (KeyboardState.SwitchActions.DEBUG_ACTION) {
                Log.d(TAG, "setEmojiKeyboard")
            }
            mMainKeyboardFrame!!.visibility = View.VISIBLE
            // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            mKeyboardView!!.visibility = View.GONE
            mSuggestionStripView!!.visibility = View.GONE
            mStripContainer!!.visibility = getSecondaryStripVisibility()
            mClipboardStripScrollView!!.visibility = View.GONE
            mEmojiTabStripView!!.visibility = View.VISIBLE
            mClipboardHistoryView!!.visibility = View.GONE
            mEmojiPalettesView!!.startEmojiPalettes(mKeyboardView!!.getKeyVisualAttribute(),
                mLatinIME!!.getCurrentInputEditorInfo(), mLatinIME!!.keyboardActionListener)
            mEmojiPalettesView!!.visibility = View.VISIBLE
        }

        override fun setClipboardKeyboard() {
            if (KeyboardState.SwitchActions.DEBUG_ACTION) {
                Log.d(TAG, "setClipboardKeyboard")
            }
            mMainKeyboardFrame!!.visibility = View.VISIBLE
            // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            mKeyboardView!!.visibility = View.GONE
            mEmojiTabStripView!!.visibility = View.GONE
            mSuggestionStripView!!.visibility = View.GONE
            mStripContainer!!.visibility = getSecondaryStripVisibility()
            mClipboardStripScrollView!!.post { mClipboardStripScrollView!!.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
            mClipboardStripScrollView!!.visibility = View.VISIBLE
            mEmojiPalettesView!!.visibility = View.GONE
            mClipboardHistoryView!!.startClipboardHistory(mLatinIME!!.clipboardHistoryManager, mKeyboardView!!.getKeyVisualAttribute(),
                mLatinIME!!.getCurrentInputEditorInfo(), mLatinIME!!.keyboardActionListener)
            mClipboardHistoryView!!.visibility = View.VISIBLE
        }

        override fun setNumpadKeyboard() {
            if (KeyboardState.SwitchActions.DEBUG_ACTION) {
                Log.d(TAG, "setNumpadKeyboard")
            }
            setKeyboard(KeyboardElement.NUMPAD, KeyboardSwitchState.OTHER)
        }

        override fun setDpadKeyboard() {
            if (KeyboardState.SwitchActions.DEBUG_ACTION) {
                Log.d(TAG, "setDpadKeyboard")
            }
            setKeyboard(KeyboardElement.DPAD, KeyboardSwitchState.OTHER)
        }

        override fun startDoubleTapShiftKeyTimer() {
            if (KeyboardState.SwitchActions.DEBUG_TIMER_ACTION) {
                Log.d(TAG, "startDoubleTapShiftKeyTimer")
            }
            val keyboardView = getMainKeyboardView()
            if (keyboardView != null) {
                keyboardView.startDoubleTapShiftKeyTimer()
            }
        }

        override fun cancelDoubleTapShiftKeyTimer() {
            if (KeyboardState.SwitchActions.DEBUG_TIMER_ACTION) {
                Log.d(TAG, "cancelDoubleTapShiftKeyTimer")
            }
            val keyboardView = getMainKeyboardView()
            if (keyboardView != null) {
                keyboardView.cancelDoubleTapShiftKeyTimer()
            }
        }

        override fun setOneHandedModeEnabled(enabled: Boolean) {
            this@KeyboardSwitcher.setOneHandedModeEnabled(enabled, false)
        }

        override fun switchOneHandedMode() {
            mKeyboardViewWrapper!!.switchOneHandedModeSide()
            Settings.getInstance().writeOneHandedModeGravity(mKeyboardViewWrapper!!.oneHandedGravity)
        }

        override fun setFloatingKeyboardEnabled(enabled: Boolean) {
            if (enabled != Settings.getValues().mIsFloatingKeyboard)
                // mIsFloatingKeyboard is always disabled when device is locked, and we shouldn't mess up the setting
                setFloatingKeyboardEnabled(mThemeContext!!, enabled)
            if (enabled) FloatingKeyboardUtils.setFloating(mCurrentInputView)
            else FloatingKeyboardUtils.disableFloating(mCurrentInputView)
            setBackgroundGatheringIndicatorPosition()
        }

        override fun popDoubleTapShiftKeyTimer(): Boolean {
            if (KeyboardState.SwitchActions.DEBUG_TIMER_ACTION) {
                Log.d(TAG, "isInDoubleTapShiftKeyTimeout")
            }
            val keyboardView = getMainKeyboardView()
            return keyboardView != null && keyboardView.popDoubleTapShiftKeyTimer()
        }

        // not a SwitchAction, but should only be called from a SwitchAction to avoid inconsistent state / actual layout
        private fun setKeyboard(keyboardElement: KeyboardElement, toggleState: KeyboardSwitchState) {
            // with a hardware keyboard we might get here without ever calling onCreateInputView, so don't crash
            if (mKeyboardView == null) return

            // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
            val currentSettingsValues = Settings.getValues()
            setMainKeyboardFrame(currentSettingsValues, toggleState)
            // TODO: pass this object to setKeyboard instead of getting the current values.
            val keyboardView = mKeyboardView!!
            val oldKeyboard = keyboardView.getKeyboard()
            val newKeyboard = mKeyboardLayoutSet!!.getKeyboard(keyboardElement)
            keyboardView.setKeyboard(newKeyboard)
            mCurrentInputView!!.setKeyboardTopPadding(newKeyboard.mTopPadding)
            keyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn)
            keyboardView.updateShortcutKey(mRichImm!!.isShortcutImeReady)
            val subtypeChanged = (oldKeyboard == null) || newKeyboard.mId.subtype != oldKeyboard.mId.subtype
            val languageOnSpacebarFormatType = LanguageOnSpacebarUtils.getLanguageOnSpacebarFormatType(newKeyboard.mId.subtype)
            val hasMultipleEnabledIMEsOrSubtypes = mRichImm!!.hasMultipleEnabledIMEsOrSubtypes(true)
            keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes)

            if (currentSettingsValues.needsToLookupSuggestions()
                && (currentSettingsValues.mInlineEmojiSearch || currentSettingsValues.mSuggestEmojis)) {
                loadEmojiDefaultVersionsAndPopupSpecs(mThemeContext!!)
            }
        }
    }

    companion object {
        private val TAG = KeyboardSwitcher::class.java.simpleName

        @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
        private val sInstance = KeyboardSwitcher()

        @JvmStatic
        fun getInstance(): KeyboardSwitcher {
            return sInstance
        }

        @JvmStatic
        fun init(latinIme: LatinIME) {
            sInstance.initInternal(latinIme)
        }

        private fun getSecondaryStripVisibility(): Int {
            return if (Settings.getValues().isSecondaryStripVisible()) View.VISIBLE else View.GONE
        }
    }
}
