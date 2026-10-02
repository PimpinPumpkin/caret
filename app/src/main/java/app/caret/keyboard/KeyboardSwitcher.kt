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

    private var currentInputView: InputView? = null
    private var keyboardViewWrapper: KeyboardWrapperView? = null
    private lateinit var mainKeyboardFrame: View
    private var keyboardView: MainKeyboardView? = null
    private lateinit var emojiPalettesView: EmojiPalettesView
    private lateinit var emojiTabStripView: View
    private var clipboardStripView: LinearLayout? = null
    private lateinit var clipboardStripScrollView: HorizontalScrollView
    private lateinit var suggestionStripView: SuggestionStripView
    private lateinit var stripContainer: FrameLayout
    private lateinit var clipboardHistoryView: ClipboardHistoryView
    private lateinit var fakeToastView: TextView
    private lateinit var backgroundGatheringIndicator: ImageView
    private lateinit var latinIME: LatinIME
    private lateinit var richImm: RichInputMethodManager
    private var isHardwareAcceleratedDrawingEnabled = false

    private lateinit var state: KeyboardState

    private var keyboardLayoutSet: KeyboardLayoutSet? = null

    private var keyboardTheme: KeyboardTheme? = null
    private lateinit var themeContext: Context
    private var currentUiMode = 0
    private var currentOrientation = 0
    private var currentDpi = 0
    private var themeNeedsReload = false

    private fun initInternal(latinIme: LatinIME) {
        latinIME = latinIme
        richImm = RichInputMethodManager.getInstance()
        state = KeyboardState(SwitchActions())
        isHardwareAcceleratedDrawingEnabled = latinIME.enableHardwareAcceleration()
    }

    fun updateKeyboardTheme(displayContext: Context) {
        val themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext))
        if (themeUpdated) {
            val settings = Settings.instance
            settings.loadSettings(displayContext, settings.current.locale, settings.current.inputAttributes)
            if (keyboardView != null)
                latinIME.setInputView(onCreateInputView(displayContext, isHardwareAcceleratedDrawingEnabled))
        } else if (currentInputView != null && latinIME.hasSuggestionStripView()
                    == (Settings.values.toolbarMode == ToolbarMode.HIDDEN || latinIME.isEmojiSearch())) {
            latinIME.updateSuggestionStripView(currentInputView)
        }
    }

    private fun updateKeyboardThemeAndContextThemeWrapper(context: Context, newTheme: KeyboardTheme): Boolean {
        val res = context.getResources()
        if (themeNeedsReload
                || !::themeContext.isInitialized
                || !newTheme.equals(keyboardTheme)
                || currentDpi != res.getDisplayMetrics().densityDpi
                || currentOrientation != res.getConfiguration().orientation
                || (currentUiMode and Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK)
                || !themeContext.getResources().equals(res)
                || Settings.values.colors.haveColorsChanged(context)) {
            themeNeedsReload = false
            keyboardTheme = newTheme
            themeContext = ContextThemeWrapper(context, newTheme.styleId)
            currentUiMode = res.getConfiguration().uiMode
            currentOrientation = res.getConfiguration().orientation
            currentDpi = res.getDisplayMetrics().densityDpi
            KeyboardLayoutSet.onKeyboardThemeChanged()
            return true
        }
        return false
    }

    fun loadKeyboard(editorInfo: EditorInfo?, settingsValues: SettingsValues, currentAutoCapsState: Int,
                     currentRecapitalizeState: RecapitalizeMode?, internalAction: KeyboardLayoutSet.InternalAction?) {
        val builder = KeyboardLayoutSet.Builder(themeContext, editorInfo, settingsValues)
        val keyboardWidth = ResourceUtils.getKeyboardWidth(themeContext, settingsValues)
        val keyboardHeight = ResourceUtils.getKeyboardHeight(themeContext.getResources(), settingsValues)
        keyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(richImm.currentSubtype)
                .setInternalAction(internalAction)
                .build()
        try {
            state.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, settingsValues.oneHandedModeEnabled)
        } catch (e: KeyboardLayoutSet.Companion.KeyboardLayoutSetException) {
            Log.e(TAG, "loading keyboard failed: " + e.keyboardId, e.cause)
            try {
                val defaults = SubtypeUtilsAdditional.createDefaultSubtype(richImm.currentSubtypeLocale)
                keyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.get(defaults))
                        .build()
                state.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, false)
                showToast("error loading the keyboard, falling back to defaults", false)
            } catch (e2: KeyboardLayoutSet.Companion.KeyboardLayoutSetException) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.keyboardId, e2.cause)
            }
        }
    }

    fun saveKeyboardState() {
        if (keyboard != null || isShowingEmojiPalettes || isShowingClipboardHistory) {
            state.onSaveKeyboardState()
        }
    }

    fun onHideWindow() {
        keyboardView?.onHideWindow()
    }

    val keyboard: Keyboard?
        get() = keyboardView?.keyboard

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    fun resetKeyboardStateToAlphabet() {
        state.onResetKeyboardStateToAlphabet(latinIME.getCurrentAutoCapsState(), latinIME.getCurrentRecapitalizeState())
    }

    fun onPressKey(code: Int, pointerCount: Int, currentAutoCapsState: Int,
            currentRecapitalizeState: RecapitalizeMode?) {
        state.onPressKey(code, pointerCount, currentAutoCapsState, currentRecapitalizeState)
    }

    fun onReleaseKey(code: Int, withSliding: Boolean,
            currentAutoCapsState: Int, currentRecapitalizeState: RecapitalizeMode?) {
        state.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState)
    }

    fun onFinishSlidingInput(currentAutoCapsState: Int,
            currentRecapitalizeState: RecapitalizeMode?) {
        state.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState)
    }

    fun setEmojiKeyboard() {
        state.setLayout(LayoutDirective.Utility.EMOJI)
    }

    fun setClipboardKeyboard() {
        state.setLayout(LayoutDirective.Utility.CLIPBOARD)
    }

    fun isImeSuppressedByHardwareKeyboard(
            settingsValues: SettingsValues,
            toggleState: KeyboardSwitchState): Boolean {
        return settingsValues.hasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN
    }

    private fun setMainKeyboardFrame(
            view: MainKeyboardView,
            settingsValues: SettingsValues,
            toggleState: KeyboardSwitchState) {
        val visibility = if (isImeSuppressedByHardwareKeyboard(settingsValues, toggleState)) View.GONE else View.VISIBLE
        val stripVisibility = if (latinIME.hasSuggestionStripView()) View.VISIBLE else View.GONE
        stripContainer.setVisibility(stripVisibility)
        PointerTracker.switchTo(view)
        view.setVisibility(visibility)
        // The visibility of {@link #keyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mainKeyboardFrame.setVisibility(visibility)
        keyboardViewWrapper!!.setVisibility(if (Settings.instance.readShowToolbarOnly()) View.GONE else View.VISIBLE)
        emojiPalettesView.setVisibility(View.GONE)
        emojiPalettesView.stopEmojiPalettes()
        emojiTabStripView.setVisibility(View.GONE)
        clipboardStripScrollView.setVisibility(View.GONE)
        suggestionStripView.setVisibility(stripVisibility)
        clipboardHistoryView.setVisibility(View.GONE)
        clipboardHistoryView.stopClipboardHistory()
    }

    fun toggleLayout(layout: LayoutDirective.Utility, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {
        state.toggleLayout(layout, autoCapsFlags, recapitalizeMode)
    }

    fun onLongPressAlphaSymbolForNumpad() {
        if (KeyboardState.SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "onLongPressAlphaSymbol")
        }
        state.onLongPressAlphaSymbolForNumpad()
    }

    enum class KeyboardSwitchState(val keyboardElement: KeyboardElement?) {
        HIDDEN(null),
        SYMBOLS_SHIFTED(KeyboardElement.SYMBOLS_SHIFTED),
        EMOJI(KeyboardElement.EMOJI_RECENTS),
        CLIPBOARD(KeyboardElement.CLIPBOARD),
        OTHER(null);
    }

    fun getKeyboardSwitchState(): KeyboardSwitchState {
        val view = keyboardView
        val hidden = !isShowingEmojiPalettes && !isShowingClipboardHistory
                && (keyboardLayoutSet == null
                || view == null
                || !view.isShown())
        if (hidden) {
            return KeyboardSwitchState.HIDDEN
        } else if (isShowingEmojiPalettes) {
            return KeyboardSwitchState.EMOJI
        } else if (isShowingClipboardHistory) {
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
            latinIME.stopShowingInputView()
            latinIME.hideWindow()
            resetKeyboardStateToAlphabet()
        } else {
            latinIME.startShowingInputView(true)
            if (toggleState == KeyboardSwitchState.EMOJI) {
                setEmojiKeyboard()
            } else if (toggleState == KeyboardSwitchState.CLIPBOARD) {
                setClipboardKeyboard()
            } else {
                emojiPalettesView.stopEmojiPalettes()
                emojiPalettesView.setVisibility(View.GONE)

                clipboardHistoryView.stopClipboardHistory()
                clipboardHistoryView.setVisibility(View.GONE)

                mainKeyboardFrame.setVisibility(View.VISIBLE)
                keyboardView!!.setVisibility(View.VISIBLE)
                if (toggleState == KeyboardSwitchState.SYMBOLS_SHIFTED)
                    // possible other states OTHER and HIDDEN have keyboardElement null, which we just ignore
                    // might need to be adjusted when functionality is extended
                    state.setLayout(LayoutDirective.Utility.SYMBOLS_SHIFTED)
            }
        }
    }

    fun updateShiftState(autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {
        if (KeyboardState.SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "updateShiftState: " + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags) + " recapitalizeMode=" + recapitalizeMode)
        }
        state.onUpdateShiftState(autoCapsFlags, recapitalizeMode)
    }

    fun setOneHandedModeEnabled(enabled: Boolean, force: Boolean) {
        val wrapper = keyboardViewWrapper!!
        if (!force && wrapper.oneHandedModeEnabled == enabled) {
            return
        }
        val settings = Settings.instance
        wrapper.oneHandedModeEnabled = enabled
        wrapper.oneHandedGravity = settings.current.oneHandedModeGravity

        // oneHandeMode is always disabled when floating, and we shouldn't mess up the setting
        if (enabled != settings.current.oneHandedModeEnabled)
            settings.writeOneHandedModeEnabled(enabled)
        reloadKeyboard()
    }

    fun toggleSplitKeyboardMode() {
        val settings = Settings.instance
        settings.writeSplitKeyboardEnabled(
            !settings.current.isSplitKeyboardEnabled,
            currentOrientation == Configuration.ORIENTATION_LANDSCAPE,
            FoldableUtils.isFolded
        )
        setOneHandedModeEnabled(settings.current.oneHandedModeEnabled, true)
        reloadKeyboard()
    }

    /** Caret: shows the drag handles for resizing the keyboard. */
    fun startResizeMode() {
        keyboardViewWrapper?.resizeMode = true
    }

    fun stopResizeMode() {
        keyboardViewWrapper?.let {
            it.resizeMode = false
            it.actionsPanelShown = false
        }
    }

    /** Caret: shows the grid with every action over the keys. */
    fun showActionsPanel() {
        keyboardViewWrapper?.actionsPanelShown = true
    }

    fun reloadKeyboard() {
        if (currentInputView == null)
            return
        emojiPalettesView.clearKeyboardCache()
        reloadMainKeyboard()
    }

    fun reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout
        val wasEmoji = isShowingEmojiPalettes
        val wasClipboard = isShowingClipboardHistory
        loadKeyboard(latinIME.getCurrentInputEditorInfo(), Settings.values,
                latinIME.getCurrentAutoCapsState(), latinIME.getCurrentRecapitalizeState(), null)
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
            val toast = Toast.makeText(latinIME, text, toastLength)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
        } else {
            val toastLength = if (briefToast) 2000 else 3500
            showFakeToast(text, toastLength)
        }
    }

    // Displays a toast-like message with the provided text for a specified duration.
    private fun showFakeToast(text: String, timeMillis: Int) {
        if (fakeToastView.visibility == View.VISIBLE) return

        val appIcon = fakeToastView.compoundDrawables[0]
        if (appIcon != null) {
            val bound = fakeToastView.lineHeight
            appIcon.setBounds(0, 0, bound, bound)
            fakeToastView.setCompoundDrawables(appIcon, null, null, null)
        }
        fakeToastView.setText(text)
        fakeToastView.visibility = View.VISIBLE
        fakeToastView.bringToFront()
        fakeToastView.startAnimation(AnimationUtils.loadAnimation(latinIME, R.anim.fade_in))

        fakeToastView.postDelayed({
            fakeToastView.startAnimation(AnimationUtils.loadAnimation(latinIME, R.anim.fade_out))
            fakeToastView.visibility = View.GONE
        }, timeMillis.toLong())
    }

    fun setBackgroundGatheringIndicator(enabled: Boolean, hasData: Boolean, saving: Boolean) {
        if (currentInputView == null) return
        backgroundGatheringIndicator.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return
        backgroundGatheringIndicator.setImageResource(if (hasData) R.drawable.btn_keyboard_key_action_normal_lxx_base else R.drawable.ring)
        setBackgroundGatheringIndicatorPosition()
        if (!saving) return
        backgroundGatheringIndicator.imageTintList = ColorStateList.valueOf(0xff00a000.toInt())
        backgroundGatheringIndicator.postDelayed({ backgroundGatheringIndicator.imageTintList = ColorStateList.valueOf(0xffa00000.toInt()) }, 1500)
    }

    private fun setBackgroundGatheringIndicatorPosition() {
        if (!::backgroundGatheringIndicator.isInitialized || backgroundGatheringIndicator.visibility != View.VISIBLE) return
        val margin = backgroundGatheringIndicator.layoutParams
        if (margin is ViewGroup.MarginLayoutParams) {
            val kb = keyboardView!!.keyboard
            if (kb != null)
                margin.topMargin = kb.occupiedHeight - 16.dpToPx(currentInputView!!.resources)
            backgroundGatheringIndicator.layoutParams = backgroundGatheringIndicator.layoutParams
        }
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    fun onEvent(event: Event, currentAutoCapsState: Int, currentRecapitalizeState: RecapitalizeMode?) {
        state.onEvent(event, currentAutoCapsState, currentRecapitalizeState)
    }

    fun isShowingKeyboardId(vararg keyboardElements: KeyboardElement): Boolean {
        val view = keyboardView
        if (view == null || !view.isShown()) {
            return false
        }
        val keyboard = view.keyboard
        if (keyboard == null) // may happen when using hardware keyboard
            return false
        val activeKeyboardId = keyboard.id.element
        for (keyboardElement in keyboardElements) {
            if (activeKeyboardId == keyboardElement) {
                return true
            }
        }
        return false
    }

    val isShowingEmojiPalettes: Boolean
        get() = ::emojiPalettesView.isInitialized && emojiPalettesView.isShown()

    val isShowingClipboardHistory: Boolean
        get() = ::clipboardHistoryView.isInitialized && clipboardHistoryView.isShown()

    fun isShowingPopupKeysPanel(): Boolean {
        if (isShowingEmojiPalettes || isShowingClipboardHistory) {
            return false
        }
        return keyboardView!!.isShowingPopupKeysPanel
    }

    val isShowingStripContainer: Boolean
        get() = stripContainer.isShown()

    fun getVisibleKeyboardView(): View? {
        if (isShowingEmojiPalettes) {
            return emojiPalettesView
        } else if (isShowingClipboardHistory) {
            return clipboardHistoryView
        }
        return keyboardView
    }

    val wrapperView: View?
        get() = keyboardViewWrapper

    val emojiTabStrip: View?
        get() = if (::emojiTabStripView.isInitialized) emojiTabStripView else null

    val clipboardStrip: LinearLayout?
        get() = clipboardStripView

    fun getMainKeyboardView(): MainKeyboardView? {
        return keyboardView
    }

    fun getStripContainer(): FrameLayout? { return if (::stripContainer.isInitialized) stripContainer else null }

    fun deallocateMemory() {
        keyboardView?.let {
            it.cancelAllOngoingEvents()
            it.deallocateMemory()
        }
        if (::emojiPalettesView.isInitialized) {
            emojiPalettesView.stopEmojiPalettes()
        }
        if (::clipboardHistoryView.isInitialized) {
            clipboardHistoryView.stopClipboardHistory()
        }
    }

    fun trimMemory() {
        if (::emojiPalettesView.isInitialized) {
            emojiPalettesView.clearKeyboardCache()
        }
    }

    @SuppressLint("InflateParams")
    fun onCreateInputView(displayContext: Context, isHardwareAcceleratedDrawingEnabled: Boolean): View {
        Log.d(TAG, "create new input view")
        keyboardView?.closing()
        PointerTracker.clearOldViewData()
        val prefs = displayContext.prefs()
        if (::suggestionStripView.isInitialized)
            prefs.unregisterOnSharedPreferenceChangeListener(suggestionStripView)
        if (::clipboardHistoryView.isInitialized)
            prefs.unregisterOnSharedPreferenceChangeListener(clipboardHistoryView)
        if (themeNeedsReload) // necessary in some cases (e.g. theme switch) when themeNeedsReload is set before first keyboard load
            Settings.instance.loadSettings(displayContext, Settings.values.locale, Settings.values.inputAttributes)

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext))
        val inputView = LayoutInflater.from(themeContext).inflate(R.layout.input_view, null) as InputView
        currentInputView = inputView
        mainKeyboardFrame = inputView.findViewById(R.id.main_keyboard_frame)
        emojiPalettesView = inputView.findViewById(R.id.emoji_palettes_view)
        clipboardHistoryView = inputView.findViewById(R.id.clipboard_history_view)
        fakeToastView = inputView.findViewById(R.id.fakeToast)

        val wrapper: KeyboardWrapperView = inputView.findViewById(R.id.keyboard_view_wrapper)
        keyboardViewWrapper = wrapper
        wrapper.keyboardActionListener = latinIME.keyboardActionListener
        val view: MainKeyboardView = inputView.findViewById(R.id.keyboard_view)
        keyboardView = view
        view.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled)
        view.setKeyboardActionListener(latinIME.keyboardActionListener)
        emojiPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled)
        emojiPalettesView.setKeyboardActionListener(latinIME.keyboardActionListener)
        clipboardHistoryView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled)
        clipboardHistoryView.keyboardActionListener = latinIME.keyboardActionListener
        emojiTabStripView = inputView.findViewById(R.id.emoji_tab_strip)
        clipboardStripView = inputView.findViewById(R.id.clipboard_strip)
        clipboardStripScrollView = inputView.findViewById(R.id.clipboard_strip_scroll_view)
        suggestionStripView = inputView.findViewById(R.id.suggestion_strip_view)
        stripContainer = inputView.findViewById(R.id.strip_container)
        backgroundGatheringIndicator = inputView.findViewById(R.id.backgroundGatheringIndicator)

        prefs.registerOnSharedPreferenceChangeListener(suggestionStripView)
        prefs.registerOnSharedPreferenceChangeListener(clipboardHistoryView)
        PointerTracker.switchTo(view)
        return inputView
    }

    fun getKeyboardCapsMode(): CapsMode {
        val keyboard = this.keyboard
        if (keyboard == null) {
            return CapsMode.OFF
        }
        return keyboard.id.element.capsMode
    }

    fun getCurrentKeyboardScript(): String {
        val layoutSet = keyboardLayoutSet ?: return ScriptUtils.SCRIPT_UNKNOWN
        return layoutSet.script
    }

    fun switchToSubtype(subtype: InputMethodSubtype?) {
        latinIME.switchToSubtype(subtype)
    }

    // used for debug
    fun getLocaleAndConfidenceInfo(): String? {
        return latinIME.getLocaleAndConfidenceInfo()
    }

    /** Marks the theme as outdated. The theme will be reloaded next time the keyboard is shown.
     *  If the keyboard is currently showing, theme will be reloaded immediately. */
    fun setThemeNeedsReload() {
        themeNeedsReload = true
        if (!::latinIME.isInitialized || !latinIME.isInputViewShown())
            return // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the user shouldn't notice.
        latinIME.hideWindow()
        try {
            latinIME.showWindow(true)
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
            mainKeyboardFrame.visibility = View.VISIBLE
            // The visibility of {@link #keyboardView} must be aligned with {@link #MainKeyboardFrame}.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            val view = keyboardView!!
            view.visibility = View.GONE
            suggestionStripView.visibility = View.GONE
            stripContainer.visibility = getSecondaryStripVisibility()
            clipboardStripScrollView.visibility = View.GONE
            emojiTabStripView.visibility = View.VISIBLE
            clipboardHistoryView.visibility = View.GONE
            emojiPalettesView.startEmojiPalettes(view.keyVisualAttributes,
                latinIME.getCurrentInputEditorInfo(), latinIME.keyboardActionListener)
            emojiPalettesView.visibility = View.VISIBLE
        }

        override fun setClipboardKeyboard() {
            if (KeyboardState.SwitchActions.DEBUG_ACTION) {
                Log.d(TAG, "setClipboardKeyboard")
            }
            mainKeyboardFrame.visibility = View.VISIBLE
            // The visibility of {@link #keyboardView} must be aligned with {@link #MainKeyboardFrame}.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            val view = keyboardView!!
            view.visibility = View.GONE
            emojiTabStripView.visibility = View.GONE
            suggestionStripView.visibility = View.GONE
            stripContainer.visibility = getSecondaryStripVisibility()
            clipboardStripScrollView.post { clipboardStripScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
            clipboardStripScrollView.visibility = View.VISIBLE
            emojiPalettesView.visibility = View.GONE
            clipboardHistoryView.startClipboardHistory(latinIME.clipboardHistoryManager, view.keyVisualAttributes,
                latinIME.getCurrentInputEditorInfo(), latinIME.keyboardActionListener)
            clipboardHistoryView.visibility = View.VISIBLE
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
            val view = getMainKeyboardView()
            if (view != null) {
                view.startDoubleTapShiftKeyTimer()
            }
        }

        override fun cancelDoubleTapShiftKeyTimer() {
            if (KeyboardState.SwitchActions.DEBUG_TIMER_ACTION) {
                Log.d(TAG, "cancelDoubleTapShiftKeyTimer")
            }
            val view = getMainKeyboardView()
            if (view != null) {
                view.cancelDoubleTapShiftKeyTimer()
            }
        }

        override fun setOneHandedModeEnabled(enabled: Boolean) {
            this@KeyboardSwitcher.setOneHandedModeEnabled(enabled, false)
        }

        override fun switchOneHandedMode() {
            val wrapper = keyboardViewWrapper!!
            wrapper.switchOneHandedModeSide()
            Settings.instance.writeOneHandedModeGravity(wrapper.oneHandedGravity)
        }

        override fun setFloatingKeyboardEnabled(enabled: Boolean) {
            if (enabled != Settings.values.isFloatingKeyboard)
                // mIsFloatingKeyboard is always disabled when device is locked, and we shouldn't mess up the setting
                setFloatingKeyboardEnabled(themeContext, enabled)
            if (enabled) FloatingKeyboardUtils.setFloating(currentInputView)
            else FloatingKeyboardUtils.disableFloating(currentInputView)
            setBackgroundGatheringIndicatorPosition()
        }

        override fun popDoubleTapShiftKeyTimer(): Boolean {
            if (KeyboardState.SwitchActions.DEBUG_TIMER_ACTION) {
                Log.d(TAG, "isInDoubleTapShiftKeyTimeout")
            }
            val view = getMainKeyboardView()
            return view != null && view.popDoubleTapShiftKeyTimer()
        }

        // not a SwitchAction, but should only be called from a SwitchAction to avoid inconsistent state / actual layout
        private fun setKeyboard(keyboardElement: KeyboardElement, toggleState: KeyboardSwitchState) {
            // with a hardware keyboard we might get here without ever calling onCreateInputView, so don't crash
            val view = keyboardView ?: return

            // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
            val currentSettingsValues = Settings.values
            setMainKeyboardFrame(view, currentSettingsValues, toggleState)
            // TODO: pass this object to setKeyboard instead of getting the current values.
            val oldKeyboard = view.keyboard
            val newKeyboard = keyboardLayoutSet!!.getKeyboard(keyboardElement)
            view.setKeyboard(newKeyboard)
            currentInputView!!.setKeyboardTopPadding(newKeyboard.topPadding)
            view.setKeyPreviewPopupEnabled(currentSettingsValues.keyPreviewPopupOn)
            view.updateShortcutKey(richImm.isShortcutImeReady)
            val subtypeChanged = (oldKeyboard == null) || newKeyboard.id.subtype != oldKeyboard.id.subtype
            val languageOnSpacebarFormatType = LanguageOnSpacebarUtils.getLanguageOnSpacebarFormatType(newKeyboard.id.subtype)
            val hasMultipleEnabledIMEsOrSubtypes = richImm.hasMultipleEnabledIMEsOrSubtypes(true)
            view.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes)

            if (currentSettingsValues.needsToLookupSuggestions()
                && (currentSettingsValues.inlineEmojiSearch || currentSettingsValues.suggestEmojis)) {
                loadEmojiDefaultVersionsAndPopupSpecs(themeContext)
            }
        }
    }

    companion object {
        private val TAG = KeyboardSwitcher::class.java.simpleName

        @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
        private val instance = KeyboardSwitcher()

        @JvmStatic
        fun getInstance(): KeyboardSwitcher {
            return instance
        }

        @JvmStatic
        fun init(latinIme: LatinIME) {
            instance.initInternal(latinIme)
        }

        private fun getSecondaryStripVisibility(): Int {
            return if (Settings.values.isSecondaryStripVisible) View.VISIBLE else View.GONE
        }
    }
}
