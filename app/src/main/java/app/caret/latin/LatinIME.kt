/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Message
import android.os.Process
import android.util.PrintWriterPrinter
import android.util.Printer
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import app.caret.latin.voice.InlineDictation
import android.view.Window
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype

import app.caret.accessibility.AccessibilityUtils
import app.caret.compat.locale
import app.caret.compat.EditorInfoCompatUtils
import app.caret.compat.ImeCompat
import app.caret.event.HapticEvent
import app.caret.keyboard.KeyboardActionListener
import app.caret.keyboard.KeyboardActionListenerImpl
import app.caret.keyboard.KeyboardMode
import app.caret.keyboard.emoji.EmojiPalettesView
import app.caret.keyboard.emoji.EmojiSearchActivity
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.common.InsetsOutlineProvider
import app.caret.dictionarypack.DictionaryPackConstants
import app.caret.event.Event
import app.caret.event.InputTransaction
import app.caret.keyboard.Keyboard
import app.caret.keyboard.KeyboardId
import app.caret.keyboard.KeyboardLayoutSet
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.MainKeyboardView
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.ColorType
import app.caret.latin.common.Constants
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.common.InputPointers
import app.caret.latin.common.setInsetsOutlineProvider
import app.caret.latin.define.DebugFlags
import app.caret.latin.inputlogic.InputLogic
import app.caret.latin.personalization.PersonalizationHelper
import app.caret.latin.settings.Settings
import app.caret.latin.settings.SettingsSubtype
import app.caret.latin.settings.SettingsValues
import app.caret.latin.suggestions.SuggestionStripView
import app.caret.latin.suggestions.SuggestionStripViewAccessor
import app.caret.latin.touchinputconsumer.GestureConsumer
import app.caret.latin.utils.isBrightColor
import app.caret.latin.utils.FloatingKeyboardUtils
import app.caret.latin.utils.FoldableUtils
import app.caret.latin.utils.gestureDataActiveFacilitator
import app.caret.latin.utils.useBackgroundGathering
import app.caret.latin.utils.GestureDataGatheringSettings
import app.caret.latin.utils.InlineAutofillUtils
import app.caret.latin.utils.createInputMethodPickerDialog
import app.caret.latin.utils.JniUtils
import app.caret.latin.utils.getDisplayContext
import app.caret.latin.utils.updateSoftInputWindowLayoutParameters
import app.caret.latin.utils.LeakGuardHandlerWrapper
import app.caret.latin.utils.Log
import app.caret.latin.utils.BackgroundGatheringCache
import app.caret.latin.utils.RecapitalizeMode
import app.caret.latin.utils.StatsUtils
import app.caret.latin.utils.StatsUtilsManager
import app.caret.latin.utils.SubtypeLocaleUtils
import app.caret.latin.utils.SubtypeSettings
import app.caret.latin.utils.SubtypeState
import app.caret.latin.utils.ToolbarMode
import app.caret.settings.SettingsActivity2

import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.TimeUnit

import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.caret.compat.ImeCompat.shouldSwitchToOtherInputMethods
import app.caret.compat.ImeCompat.switchInputMethod
import app.caret.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import app.caret.latin.utils.setUseBackgroundGathering

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
class LatinIME : InputMethodService(),
        SuggestionStripView.Listener, SuggestionStripViewAccessor,
        DictionaryFacilitator.DictionaryInitializationListener {

    @JvmField val settings: Settings
    @JvmField val keyboardActionListener: KeyboardActionListener
    private var originalNavBarColor = 0
    private var originalNavBarFlags = 0

    // UIHandler is needed when creating InputLogic
    @JvmField val handler: UIHandler = UIHandler(this)
    private var dictionaryFacilitator: DictionaryFacilitator = // non-final for active gesture data gathering, revert when data gathering phase is done (end of 2026 latest)
            DictionaryFacilitatorProvider.getDictionaryFacilitator(false)
    private val originalDictionaryFacilitator: DictionaryFacilitator = dictionaryFacilitator
    @JvmField val inputLogic: InputLogic = InputLogic(this, this, dictionaryFacilitator)

    // TODO: Move these {@link View}s to {@link KeyboardSwitcher}.
    private var inputView: View? = null
    private lateinit var insetsUpdater: InsetsOutlineProvider
    private var suggestionStripView: SuggestionStripView? = null

    private lateinit var richImm: RichInputMethodManager
    @JvmField val keyboardSwitcher: KeyboardSwitcher
    private val subtypeState: SubtypeState = SubtypeState { subtype: InputMethodSubtype? -> switchToSubtype(subtype) }
    private val statsUtilsManager: StatsUtilsManager
    // Working variable for {@link #startShowingInputView()} and
    // {@link #onEvaluateInputViewShown()}.
    private var isExecutingStartShowingInputView = false

    // Used for re-initialize keyboard layout after onConfigurationChange.
    private lateinit var displayContext: Context

    // Object for reacting to adding/removing a dictionary pack.
    private val dictionaryPackInstallReceiver: BroadcastReceiver =
            DictionaryPackInstallBroadcastReceiver(this)

    private val dictionaryDumpBroadcastReceiver: BroadcastReceiver =
            DictionaryDumpBroadcastReceiver(this)

    var foldableObserver: FoldableUtils.FoldableObserver? = null

    private val emojiSearchReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onEmojiSearchDone(intent)
        }
    }

    class RestartAfterDeviceUnlockReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent!!.getAction()
            // Restart the keyboard if credential encrypted storage is unlocked. This reloads the
            // dictionary and other data from credential-encrypted storage (with the onCreate()
            // method).
            if (Intent.ACTION_USER_UNLOCKED == action) {
                val myPid = Process.myPid()
                Log.i(TAG, "Killing my process: pid=" + myPid)
                Process.killProcess(myPid)
            } else {
                Log.e(TAG, "Unexpected intent " + intent)
            }
        }
    }
    val restartAfterDeviceUnlockReceiver: RestartAfterDeviceUnlockReceiver = RestartAfterDeviceUnlockReceiver()

    private var optionsDialog: AlertDialog? = null

    private val isHardwareAcceleratedDrawingEnabled: Boolean

    private var gestureConsumer: GestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER

    val clipboardHistoryManager: ClipboardHistoryManager = ClipboardHistoryManager(this)

    private val inlineDictation: InlineDictation = InlineDictation(this, object : InlineDictation.Host {
        override fun insertText(text: String) = onTextInput(text)
        override fun codePointBeforeCursor() = inputLogic.connection.getCodePointBeforeCursor()
        override fun locale(): Locale = richImm.currentSubtypeLocale
        override fun showStatus(text: String?) {
            val stripView = suggestionStripView ?: return
            if (text == null) {
                setNeutralSuggestionStrip()
                return
            }
            val view = TextView(stripView.context)
            view.text = text
            view.gravity = android.view.Gravity.CENTER
            view.maxLines = 1
            view.ellipsize = android.text.TextUtils.TruncateAt.START
            view.setTextColor(settings.current.colors.get(app.caret.latin.common.ColorType.KEY_TEXT))
            view.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            view.setOnClickListener { stopDictation() }
            stripView.setExternalSuggestionView(view, false)
        }
    })

    private fun stopDictation() {
        inlineDictation.stop()
    }

    // (declared at Java line 1727, after onKeyUp; moved here because part 1 declares all fields)
    // receive ringer mode change.
    private val ringerModeChangeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent!!.getAction()
            if (AudioManager.RINGER_MODE_CHANGED_ACTION == action) {
                var dnd: Boolean
                try {
                    dnd = android.provider.Settings.Global.getInt(context!!.getContentResolver(), "zen_mode") != 0
                } catch (e: android.provider.Settings.SettingNotFoundException) {
                    dnd = false
                    Log.w(TAG, "zen_mode setting not found, assuming disabled")
                }
                Log.i(TAG, "ringer mode changed, zen_mode on: " + dnd)
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged(dnd)
            }
        }
    }

    class UIHandler(ownerInstance: LatinIME) : LeakGuardHandlerWrapper<LatinIME>(ownerInstance) {
        companion object {
            private const val MSG_UPDATE_SHIFT_STATE = 0
            private const val MSG_PENDING_IMS_CALLBACK = 1
            private const val MSG_UPDATE_SUGGESTION_STRIP = 2
            private const val MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS = 3
            private const val MSG_RESUME_SUGGESTIONS = 4
            private const val MSG_REOPEN_DICTIONARIES = 5
            private const val MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED = 6
            private const val MSG_RESET_CACHES = 7
            private const val MSG_WAIT_FOR_DICTIONARY_LOAD = 8
            private const val MSG_DEALLOCATE_MEMORY = 9
            private const val MSG_SWITCH_LANGUAGE_AUTOMATICALLY = 10
            // Update this when adding new messages
            private const val MSG_LAST = MSG_SWITCH_LANGUAGE_AUTOMATICALLY

            private const val ARG1_NOT_GESTURE_INPUT = 0
            private const val ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT = 1
            private const val ARG1_SHOW_GESTURE_FLOATING_PREVIEW_TEXT = 2
            private const val ARG2_UNUSED = 0
            private const val ARG1_TRUE = 1
        }

        private var delayInMillisecondsToUpdateSuggestions = 0
        private var delayInMillisecondsToUpdateShiftState = 0

        fun onCreate() {
            val latinIme = this.ownerInstance
            if (latinIme == null) {
                return
            }
            val res = latinIme.getResources()
            delayInMillisecondsToUpdateSuggestions = res.getInteger(
                    R.integer.config_delay_in_milliseconds_to_update_suggestions)
            delayInMillisecondsToUpdateShiftState = res.getInteger(
                    R.integer.config_delay_in_milliseconds_to_update_shift_state)
        }

        override fun handleMessage(msg: Message) {
            val latinIme = this.ownerInstance
            if (latinIme == null) {
                return
            }
            when (msg.what) {
                MSG_UPDATE_SUGGESTION_STRIP -> {
                    cancelUpdateSuggestionStrip()
                    latinIme.inputLogic.performUpdateSuggestionStripSync(
                            latinIme.settings.current, msg.arg1 /* inputStyle */)
                }
                MSG_UPDATE_SHIFT_STATE ->
                    latinIme.keyboardSwitcher.updateShiftState(latinIme.getCurrentAutoCapsState(),
                            latinIme.getCurrentRecapitalizeState())
                MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS ->
                    if (msg.arg1 == ARG1_NOT_GESTURE_INPUT) {
                        val suggestedWords = msg.obj as SuggestedWords
                        latinIme.setSuggestedWords(suggestedWords)
                    } else {
                        latinIme.showGesturePreviewAndSetSuggestions(msg.obj as SuggestedWords,
                                msg.arg1 == ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT)
                    }
                MSG_RESUME_SUGGESTIONS ->
                    latinIme.inputLogic.restartSuggestionsOnWordTouchedByCursor(
                            latinIme.settings.current,
                            latinIme.keyboardSwitcher.getCurrentKeyboardScript())
                MSG_REOPEN_DICTIONARIES -> {
                    // We need to re-evaluate the currently composing word in case the script has
                    // changed.
                    postWaitForDictionaryLoad()
                    latinIme.resetDictionaryFacilitatorIfNecessary()
                }
                MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED -> {
                    val suggestedWords = msg.obj as SuggestedWords
                    latinIme.inputLogic.onUpdateTailBatchInputCompleted(
                            latinIme.settings.current,
                            suggestedWords, latinIme.keyboardSwitcher)
                    latinIme.onTailBatchInputResultShown(suggestedWords)
                }
                MSG_RESET_CACHES -> {
                    val settingsValues = latinIme.settings.current
                    if (latinIme.inputLogic.retryResetCachesAndReturnSuccess(
                            msg.arg1 == ARG1_TRUE /* tryResumeSuggestions */,
                            msg.arg2 /* remainingTries */, this /* handler */)) {
                        // If we were able to reset the caches, then we can reload the keyboard.
                        // Otherwise, we'll do it when we can.
                        latinIme.keyboardSwitcher.reloadMainKeyboard()
                    }
                }
                MSG_WAIT_FOR_DICTIONARY_LOAD ->
                    Log.i(TAG, "Timeout waiting for dictionary load")
                MSG_DEALLOCATE_MEMORY ->
                    latinIme.deallocateMemory()
                MSG_SWITCH_LANGUAGE_AUTOMATICALLY ->
                    latinIme.switchToSubtype(msg.obj as InputMethodSubtype?)
            }
        }

        fun postUpdateSuggestionStrip(inputStyle: Int) {
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SUGGESTION_STRIP, inputStyle,
                    0 /* ignored */), delayInMillisecondsToUpdateSuggestions.toLong())
        }

        fun postReopenDictionaries() {
            sendMessage(obtainMessage(MSG_REOPEN_DICTIONARIES))
        }

        fun postResumeSuggestions(shouldDelay: Boolean) {
            val latinIme = this.ownerInstance
            if (latinIme == null) {
                return
            }
            if (!latinIme.settings.current.needsToLookupSuggestions()) {
                return
            }
            removeMessages(MSG_RESUME_SUGGESTIONS)
            val message = MSG_RESUME_SUGGESTIONS
            if (shouldDelay) {
                sendMessageDelayed(obtainMessage(message),
                        delayInMillisecondsToUpdateSuggestions.toLong())
            } else {
                sendMessage(obtainMessage(message))
            }
        }

        fun postResetCaches(tryResumeSuggestions: Boolean, remainingTries: Int) {
            removeMessages(MSG_RESET_CACHES)
            sendMessage(obtainMessage(MSG_RESET_CACHES, if (tryResumeSuggestions) 1 else 0,
                    remainingTries, null))
        }

        fun postWaitForDictionaryLoad() {
            sendMessageDelayed(obtainMessage(MSG_WAIT_FOR_DICTIONARY_LOAD),
                    DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS)
        }

        fun cancelWaitForDictionaryLoad() {
            removeMessages(MSG_WAIT_FOR_DICTIONARY_LOAD)
        }

        fun hasPendingWaitForDictionaryLoad(): Boolean {
            return hasMessages(MSG_WAIT_FOR_DICTIONARY_LOAD)
        }

        fun cancelUpdateSuggestionStrip() {
            removeMessages(MSG_UPDATE_SUGGESTION_STRIP)
        }

        fun cancelResumeSuggestions() {
            removeMessages(MSG_RESUME_SUGGESTIONS)
        }

        fun hasPendingUpdateSuggestions(): Boolean {
            return hasMessages(MSG_UPDATE_SUGGESTION_STRIP)
        }

        fun hasPendingResumeSuggestions(): Boolean {
            return hasMessages(MSG_RESUME_SUGGESTIONS)
        }

        fun hasPendingReopenDictionaries(): Boolean {
            return hasMessages(MSG_REOPEN_DICTIONARIES)
        }

        fun postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE)
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_STATE),
                    delayInMillisecondsToUpdateShiftState.toLong())
        }

        fun postDeallocateMemory() {
            sendMessageDelayed(obtainMessage(MSG_DEALLOCATE_MEMORY),
                    DELAY_DEALLOCATE_MEMORY_MILLIS)
        }

        fun cancelDeallocateMemory() {
            removeMessages(MSG_DEALLOCATE_MEMORY)
        }

        fun hasPendingDeallocateMemory(): Boolean {
            return hasMessages(MSG_DEALLOCATE_MEMORY)
        }

        fun removeAllMessages() {
            for (i in 0..MSG_LAST) {
                removeMessages(i)
            }
        }

        fun showGesturePreviewAndSetSuggestions(suggestedWords: SuggestedWords?,
                                                dismissGestureFloatingPreviewText: Boolean) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS)
            val arg1 = if (dismissGestureFloatingPreviewText)
                    ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT
                    else ARG1_SHOW_GESTURE_FLOATING_PREVIEW_TEXT
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS, arg1,
                    ARG2_UNUSED, suggestedWords).sendToTarget()
        }

        fun setSuggestions(suggestedWords: SuggestedWords?) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS)
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS,
                    ARG1_NOT_GESTURE_INPUT, ARG2_UNUSED, suggestedWords).sendToTarget()
        }

        fun showTailBatchInputResult(suggestedWords: SuggestedWords?) {
            obtainMessage(MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED, suggestedWords).sendToTarget()
        }

        fun postSwitchLanguage(subtype: InputMethodSubtype?) {
            obtainMessage(MSG_SWITCH_LANGUAGE_AUTOMATICALLY, subtype).sendToTarget()
        }

        // Working variables for the following methods.
        private var isOrientationChanging = false
        private var pendingSuccessiveImsCallback = false
        private var hasPendingStartInput = false
        private var hasPendingFinishInputView = false
        private var hasPendingFinishInput = false
        private var appliedEditorInfo: EditorInfo? = null

        fun startOrientationChanging() {
            removeMessages(MSG_PENDING_IMS_CALLBACK)
            resetPendingImsCallback()
            isOrientationChanging = true
            val latinIme = this.ownerInstance
            if (latinIme == null) {
                return
            }
            if (latinIme.isInputViewShown()) {
                latinIme.keyboardSwitcher.saveKeyboardState()
            }
        }

        private fun resetPendingImsCallback() {
            hasPendingFinishInputView = false
            hasPendingFinishInput = false
            hasPendingStartInput = false
        }

        private fun executePendingImsCallback(latinIme: LatinIME, editorInfo: EditorInfo?,
                                              restarting: Boolean) {
            if (hasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(hasPendingFinishInput)
            }
            if (hasPendingFinishInput) {
                latinIme.onFinishInputInternal()
            }
            if (hasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting)
            }
            resetPendingImsCallback()
        }

        fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                hasPendingStartInput = true
            } else {
                if (isOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    isOrientationChanging = false
                    pendingSuccessiveImsCallback = true
                }
                val latinIme = this.ownerInstance
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting)
                    latinIme.onStartInputInternal(editorInfo, restarting)
                }
            }
        }

        fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, appliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback()
            } else {
                if (pendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    pendingSuccessiveImsCallback = false
                    resetPendingImsCallback()
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS.toLong())
                }
                val latinIme = this.ownerInstance
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting)
                    latinIme.onStartInputViewInternal(editorInfo, restarting)
                    appliedEditorInfo = editorInfo
                }
                cancelDeallocateMemory()
            }
        }

        fun onFinishInputView(finishingInput: Boolean) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                hasPendingFinishInputView = true
            } else {
                val latinIme = this.ownerInstance
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput)
                    appliedEditorInfo = null
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory()
                }
            }
        }

        fun onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                hasPendingFinishInput = true
            } else {
                val latinIme = this.ownerInstance
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false)
                    latinIme.onFinishInputInternal()
                }
            }
        }
    }

    // (Java constructor body; runs after all field initializers above, as in Java)
    init {
        settings = Settings.instance
        keyboardSwitcher = KeyboardSwitcher.getInstance()
        statsUtilsManager = StatsUtilsManager.getInstance()
        keyboardActionListener = KeyboardActionListenerImpl(this, inputLogic)
        isHardwareAcceleratedDrawingEnabled = this.enableHardwareAcceleration()
        Log.i(TAG, "Hardware accelerated drawing: " + isHardwareAcceleratedDrawingEnabled)
    }

    override fun onCreate() {
        settings.startListener()
        KeyboardIconsSet.instance.loadIcons(this)
        richImm = RichInputMethodManager.getInstance()
        AudioAndHapticFeedbackManager.init(this)
        AccessibilityUtils.init(this)
        statsUtilsManager.onCreate(this, dictionaryFacilitator)
        displayContext = this.getDisplayContext()
        KeyboardSwitcher.init(this)
        super.onCreate()

        loadSettings()
        clipboardHistoryManager.onCreate()
        handler.onCreate()
        if (FoldableUtils.isFoldable)
            foldableObserver = FoldableUtils.FoldableObserver(this)

        // Register to receive ringer mode change.
        val filter = IntentFilter()
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        registerReceiver(ringerModeChangeReceiver, filter)

        // Register to receive installation and removal of a dictionary pack.
        val packageFilter = IntentFilter()
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        packageFilter.addDataScheme(SCHEME_PACKAGE)
        registerReceiver(dictionaryPackInstallReceiver, packageFilter)

        val newDictFilter = IntentFilter()
        newDictFilter.addAction(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        // RECEIVER_EXPORTED is necessary because apparently Android 15 (and others?) don't recognize if the sender and receiver are the same app, see https://github.com/HeliBorg/HeliBoard/pull/1756
        ContextCompat.registerReceiver(this, dictionaryPackInstallReceiver, newDictFilter, ContextCompat.RECEIVER_EXPORTED)

        val dictDumpFilter = IntentFilter()
        dictDumpFilter.addAction(DictionaryDumpBroadcastReceiver.DICTIONARY_DUMP_INTENT_ACTION)
        ContextCompat.registerReceiver(this, dictionaryDumpBroadcastReceiver, dictDumpFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val restartAfterUnlockFilter = IntentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            restartAfterUnlockFilter.addAction(Intent.ACTION_USER_UNLOCKED)
        registerReceiver(restartAfterDeviceUnlockReceiver, restartAfterUnlockFilter)

        val emojiSearchFilter = IntentFilter()
        emojiSearchFilter.addAction(EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION)
        ContextCompat.registerReceiver(this, emojiSearchReceiver, emojiSearchFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        StatsUtils.onCreate(settings.current, richImm)
    }

    private fun loadSettings() {
        val locale = richImm.currentSubtypeLocale
        val editorInfo = getCurrentInputEditorInfo()
        val inputAttributes = InputAttributes(
                editorInfo, isFullscreenMode(), getPackageName())
        settings.loadSettings(this, locale, inputAttributes)
        val currentSettingsValues = settings.current
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues)
        // This method is called on startup and language switch, before the new layout has
        // been displayed. Opening dictionaries never affects responsivity as dictionaries are
        // asynchronously loaded.
        if (!handler.hasPendingReopenDictionaries()) {
            resetDictionaryFacilitatorIfNecessary()
        }
        refreshPersonalizationDictionarySession(currentSettingsValues)
        inputLogic.suggest.clearNextWordSuggestionsCache()
        inputLogic.updateEmojiDictionary(locale)
        statsUtilsManager.onLoadSettings(this, currentSettingsValues)
    }

    private fun refreshPersonalizationDictionarySession(
            currentSettingsValues: SettingsValues) {
        if (!currentSettingsValues.usePersonalizedDicts) {
            // Remove user history dictionaries.
            PersonalizationHelper.removeAllUserHistoryDictionaries(this)
            dictionaryFacilitator.clearUserHistoryDictionary(this)
        }
    }

    // Note that this method is called from a non-UI thread.
    override fun onUpdateMainDictionaryAvailability(isMainDictionaryAvailable: Boolean) {
        val mainKeyboardView: MainKeyboardView? = keyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardView != null) {
            mainKeyboardView.setMainDictionaryAvailability(isMainDictionaryAvailable)
        }
        if (handler.hasPendingWaitForDictionaryLoad()) {
            handler.cancelWaitForDictionaryLoad()
            handler.postResumeSuggestions(false /* shouldDelay */)
        }
    }

    fun resetDictionaryFacilitatorIfNecessary() {
        val subtypeSwitcherLocale: Locale? = richImm.currentSubtypeLocale
        val subtypeLocale: Locale
        if (subtypeSwitcherLocale == null) {
            // This happens in very rare corner cases - for example, immediately after a switch
            // to LatinIME has been requested, about a frame later another switch happens. In this
            // case, we are about to go down but we still don't know it, however the system tells
            // us there is no current subtype.
            Log.e(TAG, "System is reporting no current subtype.")
            subtypeLocale = getResources().getConfiguration().locale()
        } else {
            subtypeLocale = subtypeSwitcherLocale
        }
        val locales = ArrayList<Locale>()
        locales.add(subtypeLocale)
        locales.addAll(settings.current.secondaryLocales)
        if (dictionaryFacilitator.usesSameSettings(
                locales,
                settings.current.useContactsDictionary,
                settings.current.useAppsDictionary,
                settings.current.usePersonalizedDicts
        )) {
            return
        }
        resetDictionaryFacilitator(subtypeLocale)
    }

    /**
     * Reset the facilitator by loading dictionaries for the given locale and
     * the current settings values.
     *
     * @param locale the locale
     */
    // TODO: make sure the current settings always have the right locales, and read from them.
    private fun resetDictionaryFacilitator(locale: Locale) {
        val settingsValues = settings.current
        try {
            dictionaryFacilitator.resetDictionaries(this, locale,
                settingsValues.useContactsDictionary, settingsValues.useAppsDictionary,
                settingsValues.usePersonalizedDicts, false, "", this)
        } catch (e: Throwable) {
            // this should not happen, but in case it does we at least want to show a keyboard
            Log.e(TAG, "Could not reset dictionary facilitator, please fix ASAP", e)
        }
        inputLogic.suggest.setAutoCorrectionThreshold(settingsValues.autoCorrectionThreshold)
    }

    /**
     * Reset suggest by loading the main dictionary of the current locale.
     */
    /* package private */ fun resetSuggestMainDict() {
        val settingsValues = settings.current
        dictionaryFacilitator.resetDictionaries(this, dictionaryFacilitator.getMainLocale(),
                settingsValues.useContactsDictionary, settingsValues.useAppsDictionary,
                settingsValues.usePersonalizedDicts, true, "", this)
        keyboardSwitcher.setThemeNeedsReload() // necessary for emoji search
        EmojiPalettesView.closeDictionaryFacilitator()
        EmojiSearchActivity.closeDictionaryFacilitator()
    }

    // used for debug
    fun getLocaleAndConfidenceInfo(): String? {
        return dictionaryFacilitator.localesAndConfidences()
    }

    override fun onDestroy() {
        clipboardHistoryManager.onDestroy()
        dictionaryFacilitator.closeDictionaries()
        settings.onDestroy()
        foldableObserver?.unregister(this)
        unregisterReceiver(ringerModeChangeReceiver)
        unregisterReceiver(dictionaryPackInstallReceiver)
        unregisterReceiver(dictionaryDumpBroadcastReceiver)
        unregisterReceiver(restartAfterDeviceUnlockReceiver)
        unregisterReceiver(emojiSearchReceiver)
        statsUtilsManager.onDestroy(this /* context */)
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        deallocateMemory()
    }

    private fun isImeSuppressedByHardwareKeyboard(): Boolean {
        return !onEvaluateInputViewShown() && keyboardSwitcher.isImeSuppressedByHardwareKeyboard(
                settings.current, keyboardSwitcher.getKeyboardSwitchState())
    }

    override fun onConfigurationChanged(conf: Configuration) {
        val settingsValues = settings.current
        Log.i(TAG, "onConfigurationChanged")
        SubtypeSettings.reloadSystemLocales(this)
        if (settingsValues.displayOrientation != conf.orientation) {
            handler.startOrientationChanging()
            inputLogic.onOrientationChange(settings.current)
        }
        if (settingsValues.hasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            // If the state of having a hardware keyboard changed, then we want to reload the
            // settings to adjust for that.
            // TODO: we should probably do this unconditionally here, rather than only when we
            // have a change in hardware keyboard configuration.
            loadSettings()
            if (isImeSuppressedByHardwareKeyboard()) {
                // We call cleanupInternalStateForFinishInput() because it's the right thing to do;
                // however, it seems at the moment the framework is passing us a seemingly valid
                // but actually non-functional InputConnection object. So if this bug ever gets
                // fixed we'll be able to remove the composition, but until it is this code is
                // actually not doing much.
                cleanupInternalStateForFinishInput()
            }
        }
        // KeyboardSwitcher will check by itself if theme update is necessary
        keyboardSwitcher.updateKeyboardTheme(this.getDisplayContext())
        super.onConfigurationChanged(conf)
    }

    override fun onInitializeInterface() {
        displayContext = this.getDisplayContext()
        Log.d(TAG, "onInitializeInterface")
        keyboardSwitcher.updateKeyboardTheme(displayContext)
    }

    override fun onCreateInputView(): View? {
        StatsUtils.onCreateInputView()
        return keyboardSwitcher.onCreateInputView(this.getDisplayContext(), isHardwareAcceleratedDrawingEnabled)
    }

    override fun setInputView(view: View?) {
        super.setInputView(view)
        inputView = view
        insetsUpdater = setInsetsOutlineProvider(view!!)
        updateSoftInputWindowLayoutParameters(inputView)
        updateSuggestionStripView(view)
    }

    fun updateSuggestionStripView(view: View?) {
        if (settings.current.toolbarMode == ToolbarMode.HIDDEN || isEmojiSearch()) {
            suggestionStripView = null
            return
        }
        val nonNullView = view!!
        val stripView: SuggestionStripView? = nonNullView.findViewById(R.id.suggestion_strip_view)
        suggestionStripView = stripView
        if (stripView != null) {
            stripView.setRtl(richImm.currentSubtype.isRtlSubtype)
            stripView.setListener(this, nonNullView)
        }
    }

    override fun setCandidatesView(view: View?) {
        // To ensure that CandidatesView will never be set.
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        handler.onStartInput(editorInfo, restarting)
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        handler.onStartInputView(editorInfo, restarting)
        statsUtilsManager.onStartInputView()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        StatsUtils.onFinishInputView()
        handler.onFinishInputView(finishingInput)
        statsUtilsManager.onFinishInputView()
        gestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER
        BackgroundGatheringCache.saveOrClear(this)
    }

    override fun onFinishInput() {
        handler.onFinishInput()
        BackgroundGatheringCache.saveOrClear(this)
    }

    public override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype?) {
        // Note that the calling sequence of onCreate() and onCurrentInputMethodSubtypeChanged()
        // is not guaranteed. It may even be called at the same time on a different thread.
        if (subtype!!.hashCode() == 0x7000000f) {
            // For some reason sometimes the system wants to set the dummy subtype, which messes with the currently enabled subtype.
            // Now that the dummy subtype has a fixed id, we can easily avoid enabling it.
            return
        }
        val oldSubtype = richImm.currentSubtype.rawSubtype
        if (subtype == oldSubtype) {
            // onStartInput may be called more than once, resulting in duplicate subtype switches
            return
        }

        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "switching to subtype " + subtype.toSettingsSubtype())
        subtypeState.onSubtypeChanged(oldSubtype, subtype)
        StatsUtils.onSubtypeChanged(oldSubtype, subtype)
        richImm.onSubtypeChanged(subtype)
        inputLogic.onSubtypeChanged(SubtypeLocaleUtils.getCombiningRulesExtraValue(subtype),
                settings.current)
        loadKeyboard()
        suggestionStripView?.setRtl(richImm.currentSubtype.isRtlSubtype)
        settings.saveSubtypeForApp(richImm.currentSubtype, getCurrentInputEditorInfo().packageName)
    }

    /** alias to onCurrentInputMethodSubtypeChanged with a better name, as it's also used for internal switching */
    fun switchToSubtype(subtype: InputMethodSubtype?) {
        onCurrentInputMethodSubtypeChanged(subtype)
    }

    private fun onStartInputInternal(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)

        val subtypeForApp = if (editorInfo == null)
            null else
            settings.getSubtypeForApp(editorInfo.packageName)
        val hintLocales = EditorInfoCompatUtils.getHintLocales(editorInfo)
        val subtypeForLocales = subtypeState.getSubtypeForLocales(richImm, hintLocales, subtypeForApp)
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "onStartInputInternal: hint locales " + hintLocales + ", found matching subtype: " + (subtypeForLocales != null))
        if (subtypeForLocales != null) {
            // found a better subtype using hint locales and saved-per-app subtype, that we should switch to.
            handler.postSwitchLanguage(subtypeForLocales)
        }
    }

    fun onStartInputViewInternal(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)

        setGestureDataGatheringMode(editorInfo!!, restarting)

        dictionaryFacilitator.onStartInput()
        // Switch to the null consumer to handle cases leading to early exit below, for which we
        // also wouldn't be consuming gesture data.
        gestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER
        richImm.refreshSubtypeCaches()
        val switcher = keyboardSwitcher

        // If we are starting input in a different text field from before, we'll have to reload
        // settings, so currentSettingsValues can't be final.
        var currentSettingsValues = settings.current
        val inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo)
        val isDifferentTextField = !restarting || inputTypeChanged

        // we want to reload the settings before calling updateKeyboardTheme, because updateKeyboardTheme reads SettingsValues.mToolbarMode
        if (isDifferentTextField || !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings()
            suggestionStripView?.updateVoiceKey()
        }

        switcher.updateKeyboardTheme(displayContext)
        val mainKeyboardView: MainKeyboardView? = switcher.getMainKeyboardView()
        currentSettingsValues = settings.current // settingsValues may have been reloaded

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()")
            if (DebugFlags.DEBUG_ENABLED) {
                throw NullPointerException("Null EditorInfo in onStartInputView()")
            }
            return
        }
        Log.i(TAG, (if (restarting) "Res" else "S") + "tarting input. Cursor position = " + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd)
        if (DebugFlags.DEBUG_ENABLED) {
            EditorInfoCompatUtils.debugLog(editorInfo, TAG)
        }

        // In landscape mode, this method gets called without the input view being created.
        if (mainKeyboardView == null) {
            return
        }

        // Update to a gesture consumer with the current editor and IME state.
        gestureConsumer = GestureConsumer.newInstance(editorInfo,
                inputLogic.privateCommandPerformer,
                richImm.currentSubtypeLocale,
                switcher.keyboard)

        // Forward this event to the accessibility utilities, if enabled.
        val accessUtils = AccessibilityUtils.instance
        if (accessUtils.isTouchExplorationEnabled) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, editorInfo, restarting)
        }

        StatsUtils.onStartInputView(editorInfo.inputType,
                Settings.values.displayOrientation,
                !isDifferentTextField)

        // The EditorInfo might have a flag that affects fullscreen mode.
        // Note: This call should be done by InputMethodService?
        updateFullscreenMode()

        // ALERT: settings have not been reloaded and there is a chance they may be stale.
        // In the practice, if it is, we should have gotten onConfigurationChanged so it should
        // be fine, but this is horribly confusing and must be fixed AS SOON AS POSSIBLE.

        // In some cases the input connection has not been reset yet and we can't access it. In
        // this case we will need to call loadKeyboard() later, when it's accessible, so that we
        // can go into the correct mode, so we need to do some housekeeping here.
        // If we have a hardware keyboard we don't need to call loadKeyboard later anyway.
        val needToCallLoadKeyboardLater = !isImeSuppressedByHardwareKeyboard()
                && !connectInputLogicToField(editorInfo, currentSettingsValues, isDifferentTextField)

        showKeyboardForField(mainKeyboardView, currentSettingsValues, isDifferentTextField, restarting, needToCallLoadKeyboardLater)

        // Set neutral suggestions and show the toolbar if the "Auto show toolbar" setting is enabled.
        if (!handler.hasPendingResumeSuggestions()) {
            handler.cancelUpdateSuggestionStrip()
            setNeutralSuggestionStrip()
            val stripView = suggestionStripView
            if (stripView != null && currentSettingsValues.autoShowToolbar && !tryShowClipboardSuggestion()) {
                stripView.setToolbarVisibility(true)
            }
        }

        mainKeyboardView.setMainDictionaryAvailability(dictionaryFacilitator.hasAtLeastOneInitializedMainDictionary())
        mainKeyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.keyPreviewPopupOn)
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(currentSettingsValues.slidingKeyInputPreviewEnabled)
        mainKeyboardView.setGestureHandlingEnabledByUser(
                currentSettingsValues.gestureInputEnabled,
                currentSettingsValues.gestureTrailEnabled,
                currentSettingsValues.gestureFloatingPreviewTextEnabled)

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime")
    }

    /**
     * Resets the input logic for this field and reads the text around the cursor.
     * @return false when the text field could not be read yet. A retry is then scheduled, and the
     *  keyboard has to be loaded again once it succeeds.
     */
    private fun connectInputLogicToField(editorInfo: EditorInfo, currentSettingsValues: SettingsValues,
            isDifferentTextField: Boolean): Boolean {
        // The app calling setText() has the effect of clearing the composing
        // span, so we should reset our state unconditionally, even if restarting is true.
        // We also tell the input logic about the combining rules for the current subtype, so
        // it can adjust its combiners if needed.
        inputLogic.startInput(richImm.combiningRulesExtraValueOfCurrentSubtype, currentSettingsValues)

        resetDictionaryFacilitatorIfNecessary()

        // TODO[IL]: Can the following be moved to InputLogic#startInput?
        if (!inputLogic.connection.resetCachesUponCursorMoveAndReturnSuccess(
                editorInfo.initialSelStart, editorInfo.initialSelEnd,
                false /* shouldFinishComposition */)) {
            // Sometimes, while rotating, for some reason the framework tells the app we are not
            // connected to it and that means we can't refresh the cache. In this case, schedule
            // a refresh later.
            // We try resetting the caches up to 5 times before giving up.
            handler.postResetCaches(isDifferentTextField, 5 /* remainingTries */)
            return false
        }
        // When rotating, and when input is starting again in a field from where the focus
        // didn't move (the keyboard having been closed with the back key),
        // initialSelStart and initialSelEnd sometimes are lying. Make a best effort to
        // work around this bug.
        inputLogic.connection.tryFixIncorrectCursorPosition()
        if (inputLogic.connection.isCursorTouchingWord(currentSettingsValues.spacingAndPunctuations, true)) {
            handler.postResumeSuggestions(true /* shouldDelay */)
        }
        return true
    }

    /** Loads the keyboard for a new field, or only resets layout and shift state when input restarts in the same one. */
    private fun showKeyboardForField(mainKeyboardView: MainKeyboardView, currentSettingsValues: SettingsValues,
            isDifferentTextField: Boolean, restarting: Boolean, needToCallLoadKeyboardLater: Boolean) {
        val switcher = keyboardSwitcher
        if (isDifferentTextField) {
            mainKeyboardView.closing()
            inputLogic.suggest.setAutoCorrectionThreshold(currentSettingsValues.autoCorrectionThreshold)
            switcher.reloadMainKeyboard()
            if (needToCallLoadKeyboardLater) {
                // If we need to call loadKeyboard again later, we need to save its state now. The
                // later call will be done in #retryResetCaches.
                switcher.saveKeyboardState()
            }
        } else if (restarting) {
            // TODO: Come up with a more comprehensive way to reset the keyboard layout when
            // a keyboard layout set doesn't get reloaded in this method.
            switcher.resetKeyboardStateToAlphabet()
            // In apps like Talk, we come here when the text is sent and the field gets emptied and
            // we need to re-evaluate the shift state, but not the whole layout which would be
            // disruptive.
            // Space state must be updated before calling updateShiftState
            switcher.updateShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState())
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (isInputViewShown()) {
            if (inputView != null && Settings.values.isFloatingKeyboard)
                FloatingKeyboardUtils.setFloating(inputView)
            setNavigationBarColor()
            workaroundForHuaweiStatusBarIssue()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        KeyboardSwitcher.getInstance().stopResizeMode()
        Log.i(TAG, "onWindowHidden")
        val mainKeyboardView = keyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardView != null) {
            mainKeyboardView.closing()
        }
        clearNavigationBarColor()
    }

    fun onFinishInputInternal() {
        super.onFinishInput()
        Log.i(TAG, "onFinishInput")

        dictionaryFacilitator.onFinishInput()
        val mainKeyboardView = keyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardView != null) {
            mainKeyboardView.closing()
        }
    }

    fun onFinishInputViewInternal(finishingInput: Boolean) {
        inlineDictation.cancel()
        super.onFinishInputView(finishingInput)
        Log.i(TAG, "onFinishInputView")
        cleanupInternalStateForFinishInput()
    }

    private fun cleanupInternalStateForFinishInput() {
        // Remove pending messages related to update suggestions
        handler.cancelUpdateSuggestionStrip()
        // Should do the following in onFinishInputInternal but until JB MR2 it's not called :(
        inputLogic.finishInput()
        keyboardActionListener.resetMetaState()
    }

    protected fun deallocateMemory() {
        keyboardSwitcher.deallocateMemory()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int,
                                   newSelStart: Int, newSelEnd: Int,
                                   composingSpanStart: Int, composingSpanEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd)
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart + ", ose=" + oldSelEnd
                    + ", nss=" + newSelStart + ", nse=" + newSelEnd
                    + ", cs=" + composingSpanStart + ", ce=" + composingSpanEnd)
        }

        // This call happens whether our view is displayed or not, but if it's not then we should
        // not attempt recorrection. This is true even with a hardware keyboard connected: if the
        // view is not displayed we have no means of showing suggestions anyway, and if it is then
        // we want to show suggestions anyway.
        val settingsValues = settings.current
        if (isInputViewShown()
                && inputLogic.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd, settingsValues)) {
            // we don't want to update a manually set shift state if selection changed towards one side
            // because this may end the manual shift, which is unwanted in case of shift + arrow keys for changing selection
            // todo: this is not fully implemented yet, and maybe should be behind a setting
            val keyboard = keyboardSwitcher.keyboard
            if (keyboard != null && keyboard.id.element.isAlphabetShiftedManually
                && ((oldSelEnd == newSelEnd && oldSelStart != newSelStart) || (oldSelEnd != newSelEnd && oldSelStart == newSelStart)))
                return
            keyboardSwitcher.updateShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState())
        }
    }

    /**
     * This is called when the user has clicked on the extracted text view,
     * when running in fullscreen mode.  The default implementation hides
     * the suggestions view when this happens, but only if the extracted text
     * editor has a vertical scroll bar because its text doesn't fit.
     * Here we override the behavior due to the possibility that a re-correction could
     * cause the suggestions strip to disappear and re-appear.
     */
    override fun onExtractedTextClicked() {
        if (settings.current.needsToLookupSuggestions()) {
            return
        }

        super.onExtractedTextClicked()
    }

    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode.  The default
     * implementation hides the suggestions view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit.
     * Here we override the behavior due to the possibility that a re-correction could
     * cause the suggestions strip to disappear and re-appear.
     */
    override fun onExtractedCursorMovement(dx: Int, dy: Int) {
        if (settings.current.needsToLookupSuggestions()) {
            return
        }

        super.onExtractedCursorMovement(dx, dy)
    }

    override fun hideWindow() {
        Log.i(TAG, "hideWindow")
        val stripView = suggestionStripView
        if (stripView != null && settings.current.toolbarMode == ToolbarMode.EXPANDABLE)
            stripView.setToolbarVisibility(false)
        keyboardSwitcher.onHideWindow()

        if (TRACE) Debug.stopMethodTracing()
        val dialog = optionsDialog
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss()
            optionsDialog = null
        }
        super.hideWindow()
    }

    override fun requestHideSelf(flags: Int) {
        super.requestHideSelf(flags)
        Log.i(TAG, "requestHideSelf: " + flags)
    }

    override fun onSwipeDownOnToolbar() {
        requestHideSelf(0)
    }

    override fun onDisplayCompletions(applicationSpecifiedCompletions: Array<CompletionInfo?>?) {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "Received completions:")
            if (applicationSpecifiedCompletions != null) {
                for (i in 0 until applicationSpecifiedCompletions.size) {
                    Log.i(TAG, "  #" + i + ": " + applicationSpecifiedCompletions[i])
                }
            }
        }
        if (!settings.current.inputAttributes.applicationSpecifiedCompletionOn) {
            return
        }
        // If we have an update request in flight, we need to cancel it so it does not override
        // these completions.
        handler.cancelUpdateSuggestionStrip()
        if (applicationSpecifiedCompletions == null) {
            setNeutralSuggestionStrip()
            return
        }

        val applicationSuggestedWords: ArrayList<SuggestedWords.SuggestedWordInfo> =
                SuggestedWords.getFromApplicationSpecifiedCompletions(
                        applicationSpecifiedCompletions)
        val suggestedWords = SuggestedWords(applicationSuggestedWords,
                null /* rawSuggestions */,
                null /* typedWord */,
                false /* typedWordValid */,
                false /* willAutoCorrect */,
                false /* isObsoleteSuggestions */,
                SuggestedWords.INPUT_STYLE_APPLICATION_SPECIFIED /* inputStyle */,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER)
        // When in fullscreen mode, show completions generated by the application forcibly
        setSuggestedWords(suggestedWords)
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets?) {
        super.onComputeInsets(outInsets)
        // This method may be called before {@link #setInputView(View)}.
        val currentInputView = inputView
        if (currentInputView == null) {
            return
        }
        val visibleKeyboardView = keyboardSwitcher.wrapperView
        if (visibleKeyboardView == null) {
            return
        }
        // outInsets is nullable only because of the SDK signature
        val insets = outInsets!!
        val inputHeight = currentInputView.getHeight()
        if (isImeSuppressedByHardwareKeyboard() && !visibleKeyboardView.isShown()) {
            // If there is a hardware keyboard and a visible software keyboard view has been hidden,
            // no visual element will be shown on the screen.
            // for some reason setting contentTopInsets and visibleTopInsets broke somewhere along the
            // way from OpenBoard to HeliBoard (GH-702, GH-1455), but not setting anything seems to work
            insetsUpdater.setInsets(insets)
            return
        }
        val stripHeight = if (keyboardSwitcher.isShowingStripContainer) keyboardSwitcher.getStripContainer()!!.getHeight() else 0
        var visibleTopY = inputHeight - visibleKeyboardView.height - stripHeight
        if (Settings.values.isFloatingKeyboard)
            visibleTopY = getResources().getDisplayMetrics().heightPixels

        suggestionStripView?.setMoreSuggestionsHeight(visibleTopY)

        // Need to set expanded touchable region only if a keyboard view is being shown.
        if (visibleKeyboardView.isShown()) {
            var touchLeft = 0
            var touchTop = if (keyboardSwitcher.isShowingPopupKeysPanel()) 0 else visibleTopY
            var touchRight = visibleKeyboardView.width
            var touchBottom = inputHeight + EXTENDED_TOUCHABLE_REGION_HEIGHT // Extend touchable region below the keyboard.
            if (settings.current.isFloatingKeyboard) {
                val xy = FloatingKeyboardUtils.readPosition(this, Int.MAX_VALUE, Int.MAX_VALUE)
                touchLeft = xy.component1()
                touchTop = xy.component2()
                touchRight = touchLeft + settings.current.floatingWidth
                touchBottom = touchTop + inputHeight
            }
            insets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            insets.touchableRegion.set(touchLeft, touchTop, touchRight, touchBottom)
        }

        // Has to be subtracted after calculating touchableRegion
        visibleTopY -= getEmojiSearchActivityHeight()

        insets.contentTopInsets = visibleTopY
        insets.visibleTopInsets = visibleTopY
        insetsUpdater.setInsets(insets)
    }

    fun startShowingInputView(needsToLoadKeyboard: Boolean) {
        isExecutingStartShowingInputView = true
        // This {@link #showWindow(boolean)} will eventually call back
        // {@link #onEvaluateInputViewShown()}.
        showWindow(true /* showInput */)
        isExecutingStartShowingInputView = false
        if (needsToLoadKeyboard) {
            loadKeyboard()
        }
    }

    fun stopShowingInputView() {
        showWindow(false /* showInput */)
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (isImeSuppressedByHardwareKeyboard()) {
            return true
        }
        return super.onShowInputRequested(flags, configChange)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        if (isExecutingStartShowingInputView) {
            return true
        }
        return super.onEvaluateInputViewShown()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        if (isImeSuppressedByHardwareKeyboard() || settings.current.isFloatingKeyboard) {
            // If there is a hardware keyboard or we're floating, disable full screen mode.
            return false
        }
        // Reread resource value here, because this method is called by the framework as needed.
        val isFullscreenModeAllowed = Settings.readFullscreenModeAllowed(getResources())
        if (super.onEvaluateFullscreenMode() && isFullscreenModeAllowed) {
            // TODO: Remove this hack. Actually we should not really assume NO_EXTRACT_UI
            // implies NO_FULLSCREEN. However, the framework mistakenly does.  i.e. NO_EXTRACT_UI
            // without NO_FULLSCREEN doesn't work as expected. Because of this we need this
            // hack for now.  Let's get rid of this once the framework gets fixed.
            val ei = getCurrentInputEditorInfo()
            if (ei == null) return false
            val noExtractUi = (ei.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0
            val noFullscreen = (ei.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0
            if (noExtractUi || noFullscreen) return false
            val stripView = suggestionStripView
            val visibleKeyboardView = keyboardSwitcher.getVisibleKeyboardView()
            if (visibleKeyboardView == null || stripView == null) return false
            val usedHeight = visibleKeyboardView.getHeight() + stripView.getHeight()
            val availableHeight = getResources().getDisplayMetrics().heightPixels
            return usedHeight > availableHeight * 0.6 // if we have less than 40% available, use fullscreen mode
        }
        return false
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        updateSoftInputWindowLayoutParameters(inputView)
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        Log.d(TAG,"onCreateInlineSuggestionsRequest called")
        if (Settings.values.suggestionStripHiddenPerUserSettings) {
            return null
        }

        return InlineAutofillUtils.createInlineSuggestionRequest(displayContext)
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        Log.d(TAG,"onInlineSuggestionsResponse called")
        if (Settings.values.suggestionStripHiddenPerUserSettings) {
            return false
        }

        val inlineSuggestions: List<InlineSuggestion> = response.getInlineSuggestions()
        if (inlineSuggestions.isEmpty()) {
            return false
        }

        val inlineSuggestionView: View = InlineAutofillUtils.createView(inlineSuggestions, displayContext)

        // Without this function the inline autofill suggestions will not be visible
        handler.cancelResumeSuggestions()

        suggestionStripView!!.setExternalSuggestionView(inlineSuggestionView, true)

        return true
    }

    fun getCurrentAutoCapsState(): Int {
        return inputLogic.getCurrentAutoCapsState(settings.current)
    }

    fun getCurrentRecapitalizeState(): RecapitalizeMode? {
        return inputLogic.getCurrentRecapitalizeState()
    }

    /**
     * @param codePoints code points to get coordinates for.
     * @return x,y coordinates for this keyboard, as a flattened array.
     */
    fun getCoordinatesForCurrentKeyboard(codePoints: IntArray): IntArray {
        val keyboard = keyboardSwitcher.keyboard
        if (null == keyboard) {
            return CoordinateUtils.newCoordinateArray(codePoints.size,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE)
        }
        return keyboard.getCoordinates(codePoints)
    }

    fun displaySettingsDialog() {
        launchSettings()
    }

    fun showInputPickerDialog(): Boolean {
        if (isShowingOptionDialog) return false
        if (richImm.hasMultipleEnabledIMEsOrSubtypes(true)) {
            val dialog = createInputMethodPickerDialog(this, richImm, keyboardSwitcher.getMainKeyboardView()!!.getWindowToken())
            optionsDialog = dialog
            dialog.show()
            return true
        }
        return false
    }

    private val isShowingOptionDialog: Boolean
        get() = optionsDialog?.isShowing() == true

    // called when language switch key is pressed (either the keyboard key, or long-press comma)
    fun switchToNextSubtype() {
        val switchSubtype = settings.current.languageSwitchKeyToOtherSubtypes
        val switchIme = settings.current.languageSwitchKeyToOtherImes

        // switch IME if wanted and possible
        if (switchIme && !switchSubtype && this.switchInputMethod())
            return
        val hasMoreThanOneSubtype = richImm.hasMultipleEnabledSubtypesInThisIme(true)
        // switch subtype if wanted, do nothing if no other subtype is available
        if (switchSubtype && !switchIme) {
            if (hasMoreThanOneSubtype)
                // switch to previous subtype if current one was used, otherwise cycle through list
                subtypeState.switchSubtype(richImm)
            return
        }
        // language key set to switch both, or language key is not shown on keyboard -> switch both
        if (hasMoreThanOneSubtype && subtypeState.currentSubtypeHasBeenUsed) {
            subtypeState.switchSubtype(richImm)
            return
        }
        if (this.shouldSwitchToOtherInputMethods()) {
            val nextSubtype = richImm.getNextSubtypeInThisIme(false)
            if (nextSubtype != null) {
                switchToSubtype(nextSubtype)
                return
            } else if (this.switchInputMethod()) {
                return
            }
        }
        subtypeState.switchSubtype(richImm)
    }

    // Implementation of {@link SuggestionStripView.Listener}.
    override fun onCodeInput(codePoint: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
        keyboardActionListener.onCodeInput(codePoint, x, y, isKeyRepeat)
    }

    // This method is public for testability of LatinIME, but also in the future it should
    // completely replace #onCodeInput.
    fun onEvent(event: Event) {
        if (KeyCode.VOICE_INPUT == event.keyCode) {
            // dictate here when that is switched on and possible, otherwise go to the voice keyboard
            if (!(settings.current.inlineDictation && inlineDictation.toggle()))
                richImm.switchToShortcutIme(this)
        }
        val completeInputTransaction =
                inputLogic.onCodeInput(settings.current, event,
                        keyboardSwitcher.getKeyboardCapsMode(),
                        keyboardSwitcher.getCurrentKeyboardScript(), handler)
        updateStateAfterInputTransaction(completeInputTransaction)
        keyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState())
    }

    fun onTextInput(rawText: String?) {
        if (rawText == null) return
        // TODO: have the keyboard pass the correct key code when we need it.
        val event = Event.createSoftwareTextEvent(rawText, KeyCode.MULTIPLE_CODE_POINTS, null)
        val completeInputTransaction = inputLogic.onTextInput(settings.current,
            event, keyboardSwitcher.getKeyboardCapsMode(), handler)
        updateStateAfterInputTransaction(completeInputTransaction)
        inputLogic.restartSuggestionsOnWordTouchedByCursor(settings.current, keyboardSwitcher.getCurrentKeyboardScript())
        keyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState())
    }

    fun onStartBatchInput() {
        inputLogic.onStartBatchInput(settings.current, keyboardSwitcher, handler)
        gestureConsumer.onGestureStarted(richImm.currentSubtypeLocale, keyboardSwitcher.keyboard)
    }

    fun onUpdateBatchInput(batchPointers: InputPointers?) {
        inputLogic.onUpdateBatchInput(batchPointers!!)
    }

    fun onEndBatchInput(batchPointers: InputPointers?) {
        inputLogic.onEndBatchInput(batchPointers!!)
        gestureConsumer.onGestureCompleted(batchPointers)
    }

    fun onCancelBatchInput() {
        inputLogic.onCancelBatchInput(handler)
        gestureConsumer.onGestureCanceled()
    }

    /**
     * To be called after the InputLogic has gotten a chance to act on the suggested words by the
     * IME for the full gesture, possibly updating the TextView to reflect the first suggestion.
     * <p>
     * This method must be run on the UI Thread.
     * @param suggestedWords suggested words by the IME for the full gesture.
     */
    fun onTailBatchInputResultShown(suggestedWords: SuggestedWords?) {
        gestureConsumer.onImeSuggestionsProcessed(suggestedWords,
                inputLogic.getComposingStart(), inputLogic.composingLength,
                dictionaryFacilitator)
    }

    // This method must run on the UI Thread.
    private fun showGesturePreviewAndSetSuggestions(suggestedWords: SuggestedWords,
                                              dismissGestureFloatingPreviewText: Boolean) {
        setSuggestions(suggestedWords)
        val mainKeyboardView = keyboardSwitcher.getMainKeyboardView()
        mainKeyboardView!!.showGestureFloatingPreviewText(suggestedWords,
                dismissGestureFloatingPreviewText /* dismissDelayed */)
    }

    fun hasSuggestionStripView(): Boolean {
        return null != suggestionStripView
    }

    private fun setSuggestedWords(suggestedWords: SuggestedWords) {
        val currentSettingsValues = settings.current
        inputLogic.setSuggestedWords(suggestedWords)
        // TODO: Modify this when we support suggestions with hard keyboard
        val stripView = suggestionStripView ?: return
        if (!onEvaluateInputViewShown()) {
            return
        }

        val isEmptyApplicationSpecifiedCompletions =
                currentSettingsValues.inputAttributes.applicationSpecifiedCompletionOn
                        && suggestedWords.isEmpty
        val noSuggestionsFromDictionaries = suggestedWords.isEmpty
                || suggestedWords.isPunctuationSuggestions()
                || isEmptyApplicationSpecifiedCompletions

        if (currentSettingsValues.suggestionsEnabled
                || currentSettingsValues.inputAttributes.applicationSpecifiedCompletionOn
                // We should clear the contextual strip if there is no suggestion from dictionaries.
                || noSuggestionsFromDictionaries) {
            stripView.setSuggestions(suggestedWords,
                    richImm.currentSubtype.isRtlSubtype)
            // Auto hide the toolbar if dictionary suggestions are available
            if (currentSettingsValues.autoHideToolbar && !noSuggestionsFromDictionaries) {
                stripView.setToolbarVisibility(false)
            }
        }
    }

    override fun setSuggestions(suggestedWords: SuggestedWords) {
        if (suggestedWords.isEmpty) {
            // avoids showing clipboard suggestion when starting gesture typing
            // should be fine, as there will be another suggestion in a few ms
            // (but not a great style to avoid this visual glitch, maybe revert this commit and replace with sth better)
            if (suggestedWords.inputStyle != SuggestedWords.INPUT_STYLE_UPDATE_BATCH)
                setNeutralSuggestionStrip()
        } else {
            setSuggestedWords(suggestedWords)
        }
        // Cache the auto-correction in accessibility code so we can speak it if the user
        // touches a key that will insert it.
        AccessibilityUtils.instance.setAutoCorrection(suggestedWords)
    }

    override fun showSuggestionStrip() {
        suggestionStripView?.setToolbarVisibility(false)
    }

    // Called from {@link SuggestionStripView} through the {@link SuggestionStripView#Listener}
    // interface
    override fun pickSuggestionManually(suggestionInfo: SuggestedWordInfo?) {
        val completeInputTransaction = inputLogic.onPickSuggestionManually(
                settings.current, suggestionInfo!!,
                keyboardSwitcher.getKeyboardCapsMode(),
                keyboardSwitcher.getCurrentKeyboardScript(),
                handler)
        updateStateAfterInputTransaction(completeInputTransaction)
    }

    /**
     *  Checks if a recent clipboard suggestion is available. If available, it is set in suggestion strip.
     *  returns whether a clipboard suggestion has been set.
     */
    fun tryShowClipboardSuggestion(): Boolean {
        val stripView = suggestionStripView ?: return false
        val clipboardView = clipboardHistoryManager.getClipboardSuggestionView(getCurrentInputEditorInfo(), stripView)
        if (clipboardView != null) {
            stripView.setExternalSuggestionView(clipboardView, false)
            return true
        }
        return false
    }

    // This will first try showing a clipboard suggestion. On success, the toolbar will be hidden
    // if the "Auto hide toolbar" is enabled. Otherwise, an empty suggestion strip (if prediction
    // is enabled) or punctuation suggestions (if it's disabled) will be set.
    // Then, the toolbar will be shown automatically if the relevant setting is enabled
    // and there is a selection of text or it's the start of a line.
    override fun setNeutralSuggestionStrip() {
        val currentSettings = settings.current
        if (tryShowClipboardSuggestion()) {
            // clipboard suggestion has been set
            if (currentSettings.autoHideToolbar)
                suggestionStripView?.setToolbarVisibility(false)
            return
        }
        val neutralSuggestions: SuggestedWords = if (currentSettings.suggestPunctuation)
                currentSettings.punctuationSuggestions
                else SuggestedWords.emptyInstance
        setSuggestedWords(neutralSuggestions)
        val stripView = suggestionStripView
        if (stripView != null && currentSettings.autoShowToolbar) {
            val codePointBeforeCursor = inputLogic.connection.getCodePointBeforeCursor()
            if (inputLogic.connection.hasSelection()
                    || codePointBeforeCursor == Constants.NOT_A_CODE
                    || codePointBeforeCursor == Constants.CODE_ENTER) {
                stripView.setToolbarVisibility(true)
            }
        }
    }

    override fun removeSuggestion(word: String?) {
        dictionaryFacilitator.removeWord(word!!)
    }

    override fun removeExternalSuggestions() {
        setNeutralSuggestionStrip()
        handler.postResumeSuggestions(false)
    }

    private fun loadKeyboard() {
        // Since we are switching languages, the most urgent thing is to let the keyboard graphics
        // update. LoadKeyboard does that, but we need to wait for buffer flip for it to be on
        // the screen. Anything we do right now will delay this, so wait until the next frame
        // before we do the rest, like reopening dictionaries and updating suggestions. So we
        // post a message.
        handler.postReopenDictionaries()
        loadSettings()
        if (keyboardSwitcher.getMainKeyboardView() != null) {
            // Reload keyboard because the current language has been changed.
            keyboardSwitcher.reloadMainKeyboard()
        }
    }

    /**
     * After an input transaction has been executed, some state must be updated. This includes
     * the shift state of the keyboard and suggestions. This method looks at the finished
     * inputTransaction to find out what is necessary and updates the state accordingly.
     * @param inputTransaction The transaction that has been executed.
     */
    private fun updateStateAfterInputTransaction(inputTransaction: InputTransaction) {
        when (inputTransaction.requiredShiftUpdate) {
            InputTransaction.SHIFT_UPDATE_LATER -> handler.postUpdateShiftState()
            InputTransaction.SHIFT_UPDATE_NOW -> keyboardSwitcher
                    .updateShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState())
            else -> {
            } // SHIFT_NO_UPDATE
        }
        if (inputTransaction.requiresUpdateSuggestions()) {
            val inputStyle: Int
            if (inputTransaction.event.isSuggestionStripPress) {
                // Suggestion strip press: no input.
                inputStyle = SuggestedWords.INPUT_STYLE_NONE
            } else if (inputTransaction.event.isGesture) {
                inputStyle = SuggestedWords.INPUT_STYLE_TAIL_BATCH
            } else {
                inputStyle = SuggestedWords.INPUT_STYLE_TYPING
            }
            handler.postUpdateSuggestionStrip(inputStyle)
        }
        if (inputTransaction.didAffectContents()) {
            subtypeState.setCurrentSubtypeHasBeenUsed()
        }
    }

    fun hapticAndAudioFeedback(code: Int, repeatCount: Int,
                                       hapticEvent: HapticEvent) {
        val keyboardView = keyboardSwitcher.getMainKeyboardView()
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            // No need to feedback while finger is dragging.
            return
        }
        if (repeatCount > 0) {
            // No need to feedback when repeat delete/cursor keys will have no effect.
            when (code) {
                KeyCode.DELETE, KeyCode.ARROW_LEFT, KeyCode.ARROW_UP, KeyCode.WORD_LEFT, KeyCode.PAGE_UP ->
                    if (!inputLogic.connection.canDeleteCharacters())
                        return
                KeyCode.ARROW_RIGHT, KeyCode.ARROW_DOWN, KeyCode.WORD_RIGHT, KeyCode.PAGE_DOWN ->
                    if (!inputLogic.connection.hasTextAfterCursor())
                        return
            }
            // TODO: Use event time that the last feedback has been generated instead of relying on
            // a repeat count to thin out feedback.
            if (repeatCount % PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT == 0) {
                return
            }
        }
        val feedbackManager =
                AudioAndHapticFeedbackManager.getInstance()

        feedbackManager.performHapticFeedback(keyboardView, hapticEvent)
        feedbackManager.performAudioFeedback(code, hapticEvent)
    }

    // Hooks for hardware keyboard
    override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent?): Boolean {
        if (keyboardActionListener.onKeyDown(keyCode, keyEvent!!))
            return true
        return super.onKeyDown(keyCode, keyEvent)
    }

    override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent?): Boolean {
        if (keyboardActionListener.onKeyUp(keyCode, keyEvent!!))
            return true
        return super.onKeyUp(keyCode, keyEvent)
    }

    // onKeyDown and onKeyUp are the main events we are interested in. There are two more events
    // related to handling of hardware key events that we may want to implement in the future:
    // boolean onKeyLongPress(final int keyCode, final KeyEvent event);
    // boolean onKeyMultiple(final int keyCode, final int count, final KeyEvent event);



    fun launchSettings() {
        inputLogic.commitTyped(settings.current, LastComposedWord.NOT_A_SEPARATOR)
        requestHideSelf(0)
        val mainKeyboardView = keyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardView != null) {
            mainKeyboardView.closing()
        }
        val intent = Intent()
        intent.setClass(this@LatinIME, SettingsActivity2::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    fun launchEmojiSearch() {
        Log.d(app.caret.keyboard.emoji.TAG, "before activity launch")
        startActivity(Intent().setClass(this, EmojiSearchActivity::class.java)
                          .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }

    private fun onEmojiSearchDone(intent: Intent?) {
        Log.d(app.caret.keyboard.emoji.TAG, "after activity closing. isEmojiSearch: " + isEmojiSearch() + ". Intent: " + intent +
                (if (intent != null) ". imeClosed: " + isImeClosed(intent) + ". selected emoji: " + getSelectedEmoji(intent) else ""))
        if (intent != null && EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION == intent.getAction() && ! isEmojiSearch()) {
            if (isImeClosed(intent)) {
                requestHideSelf(0)
            } else {
                handler.postDelayed(keyboardSwitcher::setEmojiKeyboard, 100)
                if (intent.hasExtra(EmojiSearchActivity.EMOJI_KEY)) {
                     onTextInput(intent.getStringExtra(EmojiSearchActivity.EMOJI_KEY))
                }
            }
        }
    }

    fun isEmojiSearch(): Boolean {
        return getEmojiSearchActivityHeight() > 0
    }

    private fun getEmojiSearchActivityHeight(): Int {
        return EmojiSearchActivity.decodePrivateImeOptions(getCurrentInputEditorInfo()).height
    }

    fun dumpDictionaryForDebug(dictName: String) {
        if (!dictionaryFacilitator.isActive()) {
            resetDictionaryFacilitatorIfNecessary()
        }
        dictionaryFacilitator.dumpDictionaryForDebug(dictName)
    }

    fun debugDumpStateAndCrashWithException(context: String) {
        val settingsValues = settings.current
        val s = settingsValues.toString() + "\nAttributes : " + settingsValues.inputAttributes +
                "\nContext : " + context
        throw RuntimeException(s)
    }

    override fun dump(fd: FileDescriptor?, fout: PrintWriter?, args: Array<out String>?) {
        super.dump(fd, fout, args)

        val p: Printer = PrintWriterPrinter(fout)
        p.println("LatinIME state :")
        p.println("  VersionCode = " + BuildConfig.VERSION_CODE)
        p.println("  VersionName = " + BuildConfig.VERSION_NAME)
        val keyboard = keyboardSwitcher.keyboard
        val keyboardMode = if (keyboard != null) keyboard.id.mode else null
        p.println("  Keyboard mode = " + keyboardMode)
        val settingsValues = settings.current
        p.println(settingsValues.dump())
        p.println(dictionaryFacilitator.dump(this))
    }

    // slightly modified from Simple Keyboard: https://github.com/rkkr/simple-keyboard/blob/master/app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java
    @Suppress("DEPRECATION")
    private fun setNavigationBarColor() {
        val settingsValues = settings.current
        if (!settingsValues.customNavBarColor)
            return
        val color = settingsValues.colors.get(ColorType.NAVIGATION_BAR)
        val window = getWindow().getWindow()
        if (window == null)
            return
        originalNavBarColor = window.getNavigationBarColor()
        window.setNavigationBarColor(color)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        val view = window.getDecorView()
        originalNavBarFlags = view.getSystemUiVisibility()
        if (isBrightColor(color)) {
            view.setSystemUiVisibility(originalNavBarFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        } else {
            view.setSystemUiVisibility(originalNavBarFlags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv())
        }
    }

    @Suppress("DEPRECATION")
    private fun clearNavigationBarColor() {
        val settingsValues = settings.current
        if (!settingsValues.customNavBarColor)
            return
        val window = getWindow().getWindow()
        if (window == null) {
            return
        }
        window.setNavigationBarColor(originalNavBarColor)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        val view = window.getDecorView()
        view.setSystemUiVisibility(originalNavBarFlags)
    }

    // On HUAWEI devices with Android 12: a white bar may appear in landscape mode (issue #231)
    // We therefore need to make the color of the status bar transparent
    private fun workaroundForHuaweiStatusBarIssue() {
        val window = getWindow().getWindow()
        if (window == null) {
            return
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && Build.MANUFACTURER.equals("HUAWEI")) {
            window.setStatusBarColor(Color.TRANSPARENT)
        }
    }

    @SuppressLint("SwitchIntDef")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                KeyboardLayoutSet.onSystemLocaleChanged() // clears caches, nothing else
                keyboardSwitcher.trimMemory()
            }
            // deallocateMemory always called on hiding, and should not be called when showing
        }
    }

    fun setGestureDataGatheringMode(editorInfo: EditorInfo, restarting: Boolean) {
        // only for gesture data gathering, remove when data gathering phase is done (end of 2026 latest)
        if (GestureDataGatheringSettings.isInActiveGatheringMode(editorInfo)) {
            dictionaryFacilitator = gestureDataActiveFacilitator!!
            useBackgroundGathering = false
            keyboardSwitcher.setBackgroundGatheringIndicator(false, false, false)
        } else {
            dictionaryFacilitator = originalDictionaryFacilitator

            // no active mode, check for background mode
            val useBackground = setUseBackgroundGathering(this, editorInfo)
            keyboardSwitcher.setBackgroundGatheringIndicator(useBackground, false, false)
            // restarting means we're still in the same field, so don't clear anything in discard-by-default mode
            if (!restarting || !GestureDataGatheringSettings.isDiscardByDefault(this))
                BackgroundGatheringCache.saveOrClear(this)
        }
        GestureDataGatheringSettings.showEndNotificationIfNecessary(this) // will do nothing for a long time
        inputLogic.setFacilitator(dictionaryFacilitator)
    }

    companion object {
        @JvmField val TAG: String = LatinIME::class.java.getSimpleName()
        private const val TRACE = false

        private const val EXTENDED_TOUCHABLE_REGION_HEIGHT = 100
        private const val PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2
        private const val PENDING_IMS_CALLBACK_DURATION_MILLIS = 800
        @JvmField val DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS: Long = TimeUnit.SECONDS.toMillis(2)
        @JvmField val DELAY_DEALLOCATE_MEMORY_MILLIS: Long = TimeUnit.SECONDS.toMillis(10)

        /**
         * The name of the scheme used by the Package Manager to warn of a new package installation,
         * replacement or removal.
         */
        private const val SCHEME_PACKAGE = "package"

        // Loading the native library eagerly to avoid unexpected UnsatisfiedLinkError at the initial
        // JNI call as much as possible.
        init {
            JniUtils.loadNativeLibrary()
        }

        private fun isImeClosed(intent: Intent): Boolean {
            return intent.getBooleanExtra(EmojiSearchActivity.IME_CLOSED_KEY, false)
        }

        private fun getSelectedEmoji(intent: Intent): String? {
            return intent.getStringExtra(EmojiSearchActivity.EMOJI_KEY)
        }
    }
}
