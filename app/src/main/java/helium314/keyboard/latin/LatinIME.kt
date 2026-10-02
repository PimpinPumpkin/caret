/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin

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
import android.view.Window
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype

import helium314.keyboard.accessibility.AccessibilityUtils
import helium314.keyboard.compat.locale
import helium314.keyboard.compat.EditorInfoCompatUtils
import helium314.keyboard.compat.ImeCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardActionListenerImpl
import helium314.keyboard.keyboard.KeyboardMode
import helium314.keyboard.keyboard.emoji.EmojiPalettesView
import helium314.keyboard.keyboard.emoji.EmojiSearchActivity
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.InsetsOutlineProvider
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.event.Event
import helium314.keyboard.event.InputTransaction
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.CoordinateUtils
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.setInsetsOutlineProvider
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.suggestions.SuggestionStripViewAccessor
import helium314.keyboard.latin.touchinputconsumer.GestureConsumer
import helium314.keyboard.latin.utils.isBrightColor
import helium314.keyboard.latin.utils.FloatingKeyboardUtils
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.gestureDataActiveFacilitator
import helium314.keyboard.latin.utils.useBackgroundGathering
import helium314.keyboard.latin.utils.GestureDataGatheringSettings
import helium314.keyboard.latin.utils.InlineAutofillUtils
import helium314.keyboard.latin.utils.createInputMethodPickerDialog
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.getDisplayContext
import helium314.keyboard.latin.utils.updateSoftInputWindowLayoutParameters
import helium314.keyboard.latin.utils.LeakGuardHandlerWrapper
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.BackgroundGatheringCache
import helium314.keyboard.latin.utils.RecapitalizeMode
import helium314.keyboard.latin.utils.StatsUtils
import helium314.keyboard.latin.utils.StatsUtilsManager
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeState
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.settings.SettingsActivity2

import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.TimeUnit

import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import helium314.keyboard.compat.ImeCompat.shouldSwitchToOtherInputMethods
import helium314.keyboard.compat.ImeCompat.switchInputMethod
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.setUseBackgroundGathering

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
class LatinIME : InputMethodService(),
        SuggestionStripView.Listener, SuggestionStripViewAccessor,
        DictionaryFacilitator.DictionaryInitializationListener {

    @JvmField val mSettings: Settings
    @JvmField val mKeyboardActionListener: KeyboardActionListener
    private var mOriginalNavBarColor = 0
    private var mOriginalNavBarFlags = 0

    // UIHandler is needed when creating InputLogic
    @JvmField val mHandler: UIHandler = UIHandler(this)
    private var mDictionaryFacilitator: DictionaryFacilitator = // non-final for active gesture data gathering, revert when data gathering phase is done (end of 2026 latest)
            DictionaryFacilitatorProvider.getDictionaryFacilitator(false)
    private val mOriginalDictionaryFacilitator: DictionaryFacilitator = mDictionaryFacilitator
    @JvmField val mInputLogic: InputLogic = InputLogic(this, this, mDictionaryFacilitator)

    // TODO: Move these {@link View}s to {@link KeyboardSwitcher}.
    private var mInputView: View? = null
    private lateinit var mInsetsUpdater: InsetsOutlineProvider
    private var mSuggestionStripView: SuggestionStripView? = null

    private lateinit var mRichImm: RichInputMethodManager
    @JvmField val mKeyboardSwitcher: KeyboardSwitcher
    private val mSubtypeState: SubtypeState = SubtypeState { subtype: InputMethodSubtype? -> switchToSubtype(subtype) }
    private val mStatsUtilsManager: StatsUtilsManager
    // Working variable for {@link #startShowingInputView()} and
    // {@link #onEvaluateInputViewShown()}.
    private var mIsExecutingStartShowingInputView = false

    // Used for re-initialize keyboard layout after onConfigurationChange.
    private var mDisplayContext: Context? = null

    // Object for reacting to adding/removing a dictionary pack.
    private val mDictionaryPackInstallReceiver: BroadcastReceiver =
            DictionaryPackInstallBroadcastReceiver(this)

    private val mDictionaryDumpBroadcastReceiver: BroadcastReceiver =
            DictionaryDumpBroadcastReceiver(this)

    var foldableObserver: FoldableUtils.FoldableObserver? = null

    private val mEmojiSearchReceiver: BroadcastReceiver = object : BroadcastReceiver() {
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
    val mRestartAfterDeviceUnlockReceiver: RestartAfterDeviceUnlockReceiver = RestartAfterDeviceUnlockReceiver()

    private var mOptionsDialog: AlertDialog? = null

    private val mIsHardwareAcceleratedDrawingEnabled: Boolean

    private var mGestureConsumer: GestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER

    private val mClipboardHistoryManager: ClipboardHistoryManager = ClipboardHistoryManager(this)

    // (declared at Java line 1727, after onKeyUp; moved here because part 1 declares all fields)
    // receive ringer mode change.
    private val mRingerModeChangeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
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

        private var mDelayInMillisecondsToUpdateSuggestions = 0
        private var mDelayInMillisecondsToUpdateShiftState = 0

        fun onCreate() {
            val latinIme = getOwnerInstance()
            if (latinIme == null) {
                return
            }
            val res = latinIme.getResources()
            mDelayInMillisecondsToUpdateSuggestions = res.getInteger(
                    R.integer.config_delay_in_milliseconds_to_update_suggestions)
            mDelayInMillisecondsToUpdateShiftState = res.getInteger(
                    R.integer.config_delay_in_milliseconds_to_update_shift_state)
        }

        override fun handleMessage(msg: Message) {
            val latinIme = getOwnerInstance()
            if (latinIme == null) {
                return
            }
            when (msg.what) {
                MSG_UPDATE_SUGGESTION_STRIP -> {
                    cancelUpdateSuggestionStrip()
                    latinIme.mInputLogic.performUpdateSuggestionStripSync(
                            latinIme.mSettings.getCurrent(), msg.arg1 /* inputStyle */)
                }
                MSG_UPDATE_SHIFT_STATE ->
                    latinIme.mKeyboardSwitcher.updateShiftState(latinIme.getCurrentAutoCapsState(),
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
                    latinIme.mInputLogic.restartSuggestionsOnWordTouchedByCursor(
                            latinIme.mSettings.getCurrent(),
                            latinIme.mKeyboardSwitcher.getCurrentKeyboardScript())
                MSG_REOPEN_DICTIONARIES -> {
                    // We need to re-evaluate the currently composing word in case the script has
                    // changed.
                    postWaitForDictionaryLoad()
                    latinIme.resetDictionaryFacilitatorIfNecessary()
                }
                MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED -> {
                    val suggestedWords = msg.obj as SuggestedWords
                    latinIme.mInputLogic.onUpdateTailBatchInputCompleted(
                            latinIme.mSettings.getCurrent(),
                            suggestedWords, latinIme.mKeyboardSwitcher)
                    latinIme.onTailBatchInputResultShown(suggestedWords)
                }
                MSG_RESET_CACHES -> {
                    val settingsValues = latinIme.mSettings.getCurrent()
                    if (latinIme.mInputLogic.retryResetCachesAndReturnSuccess(
                            msg.arg1 == ARG1_TRUE /* tryResumeSuggestions */,
                            msg.arg2 /* remainingTries */, this /* handler */)) {
                        // If we were able to reset the caches, then we can reload the keyboard.
                        // Otherwise, we'll do it when we can.
                        latinIme.mKeyboardSwitcher.reloadMainKeyboard()
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
                    0 /* ignored */), mDelayInMillisecondsToUpdateSuggestions.toLong())
        }

        fun postReopenDictionaries() {
            sendMessage(obtainMessage(MSG_REOPEN_DICTIONARIES))
        }

        fun postResumeSuggestions(shouldDelay: Boolean) {
            val latinIme = getOwnerInstance()
            if (latinIme == null) {
                return
            }
            if (!latinIme.mSettings.getCurrent().needsToLookupSuggestions()) {
                return
            }
            removeMessages(MSG_RESUME_SUGGESTIONS)
            val message = MSG_RESUME_SUGGESTIONS
            if (shouldDelay) {
                sendMessageDelayed(obtainMessage(message),
                        mDelayInMillisecondsToUpdateSuggestions.toLong())
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
                    mDelayInMillisecondsToUpdateShiftState.toLong())
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
        private var mIsOrientationChanging = false
        private var mPendingSuccessiveImsCallback = false
        private var mHasPendingStartInput = false
        private var mHasPendingFinishInputView = false
        private var mHasPendingFinishInput = false
        private var mAppliedEditorInfo: EditorInfo? = null

        fun startOrientationChanging() {
            removeMessages(MSG_PENDING_IMS_CALLBACK)
            resetPendingImsCallback()
            mIsOrientationChanging = true
            val latinIme = getOwnerInstance()
            if (latinIme == null) {
                return
            }
            if (latinIme.isInputViewShown()) {
                latinIme.mKeyboardSwitcher.saveKeyboardState()
            }
        }

        private fun resetPendingImsCallback() {
            mHasPendingFinishInputView = false
            mHasPendingFinishInput = false
            mHasPendingStartInput = false
        }

        private fun executePendingImsCallback(latinIme: LatinIME, editorInfo: EditorInfo?,
                                              restarting: Boolean) {
            if (mHasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput)
            }
            if (mHasPendingFinishInput) {
                latinIme.onFinishInputInternal()
            }
            if (mHasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting)
            }
            resetPendingImsCallback()
        }

        fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false
                    mPendingSuccessiveImsCallback = true
                }
                val latinIme = getOwnerInstance()
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting)
                    latinIme.onStartInputInternal(editorInfo, restarting)
                }
            }
        }

        fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback()
            } else {
                if (mPendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    mPendingSuccessiveImsCallback = false
                    resetPendingImsCallback()
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS.toLong())
                }
                val latinIme = getOwnerInstance()
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting)
                    latinIme.onStartInputViewInternal(editorInfo, restarting)
                    mAppliedEditorInfo = editorInfo
                }
                cancelDeallocateMemory()
            }
        }

        fun onFinishInputView(finishingInput: Boolean) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true
            } else {
                val latinIme = getOwnerInstance()
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput)
                    mAppliedEditorInfo = null
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory()
                }
            }
        }

        fun onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true
            } else {
                val latinIme = getOwnerInstance()
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false)
                    latinIme.onFinishInputInternal()
                }
            }
        }
    }

    // (Java constructor body; runs after all field initializers above, as in Java)
    init {
        mSettings = Settings.getInstance()
        mKeyboardSwitcher = KeyboardSwitcher.getInstance()
        mStatsUtilsManager = StatsUtilsManager.getInstance()
        mKeyboardActionListener = KeyboardActionListenerImpl(this, mInputLogic)
        mIsHardwareAcceleratedDrawingEnabled = this.enableHardwareAcceleration()
        Log.i(TAG, "Hardware accelerated drawing: " + mIsHardwareAcceleratedDrawingEnabled)
    }

    override fun onCreate() {
        mSettings.startListener()
        KeyboardIconsSet.instance.loadIcons(this)
        mRichImm = RichInputMethodManager.getInstance()
        AudioAndHapticFeedbackManager.init(this)
        AccessibilityUtils.init(this)
        mStatsUtilsManager.onCreate(this, mDictionaryFacilitator)
        mDisplayContext = this.getDisplayContext()
        KeyboardSwitcher.init(this)
        super.onCreate()

        loadSettings()
        mClipboardHistoryManager.onCreate()
        mHandler.onCreate()
        if (FoldableUtils.isFoldable)
            foldableObserver = FoldableUtils.FoldableObserver(this)

        // Register to receive ringer mode change.
        val filter = IntentFilter()
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        registerReceiver(mRingerModeChangeReceiver, filter)

        // Register to receive installation and removal of a dictionary pack.
        val packageFilter = IntentFilter()
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        packageFilter.addDataScheme(SCHEME_PACKAGE)
        registerReceiver(mDictionaryPackInstallReceiver, packageFilter)

        val newDictFilter = IntentFilter()
        newDictFilter.addAction(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        // RECEIVER_EXPORTED is necessary because apparently Android 15 (and others?) don't recognize if the sender and receiver are the same app, see https://github.com/HeliBorg/HeliBoard/pull/1756
        ContextCompat.registerReceiver(this, mDictionaryPackInstallReceiver, newDictFilter, ContextCompat.RECEIVER_EXPORTED)

        val dictDumpFilter = IntentFilter()
        dictDumpFilter.addAction(DictionaryDumpBroadcastReceiver.DICTIONARY_DUMP_INTENT_ACTION)
        ContextCompat.registerReceiver(this, mDictionaryDumpBroadcastReceiver, dictDumpFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val restartAfterUnlockFilter = IntentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            restartAfterUnlockFilter.addAction(Intent.ACTION_USER_UNLOCKED)
        registerReceiver(mRestartAfterDeviceUnlockReceiver, restartAfterUnlockFilter)

        val emojiSearchFilter = IntentFilter()
        emojiSearchFilter.addAction(EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION)
        ContextCompat.registerReceiver(this, mEmojiSearchReceiver, emojiSearchFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        StatsUtils.onCreate(mSettings.getCurrent(), mRichImm)
    }

    private fun loadSettings() {
        val locale = mRichImm.currentSubtypeLocale
        val editorInfo = getCurrentInputEditorInfo()
        val inputAttributes = InputAttributes(
                editorInfo, isFullscreenMode(), getPackageName())
        mSettings.loadSettings(this, locale, inputAttributes)
        val currentSettingsValues = mSettings.getCurrent()
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues)
        // This method is called on startup and language switch, before the new layout has
        // been displayed. Opening dictionaries never affects responsivity as dictionaries are
        // asynchronously loaded.
        if (!mHandler.hasPendingReopenDictionaries()) {
            resetDictionaryFacilitatorIfNecessary()
        }
        refreshPersonalizationDictionarySession(currentSettingsValues)
        mInputLogic.mSuggest.clearNextWordSuggestionsCache()
        mInputLogic.updateEmojiDictionary(locale)
        mStatsUtilsManager.onLoadSettings(this, currentSettingsValues)
    }

    private fun refreshPersonalizationDictionarySession(
            currentSettingsValues: SettingsValues) {
        if (!currentSettingsValues.mUsePersonalizedDicts) {
            // Remove user history dictionaries.
            PersonalizationHelper.removeAllUserHistoryDictionaries(this)
            mDictionaryFacilitator.clearUserHistoryDictionary(this)
        }
    }

    // Note that this method is called from a non-UI thread.
    override fun onUpdateMainDictionaryAvailability(isMainDictionaryAvailable: Boolean) {
        val mainKeyboardView: MainKeyboardView? = mKeyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardView != null) {
            mainKeyboardView.setMainDictionaryAvailability(isMainDictionaryAvailable)
        }
        if (mHandler.hasPendingWaitForDictionaryLoad()) {
            mHandler.cancelWaitForDictionaryLoad()
            mHandler.postResumeSuggestions(false /* shouldDelay */)
        }
    }

    fun resetDictionaryFacilitatorIfNecessary() {
        val subtypeSwitcherLocale: Locale? = mRichImm.currentSubtypeLocale
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
        locales.addAll(mSettings.getCurrent().mSecondaryLocales)
        if (mDictionaryFacilitator.usesSameSettings(
                locales,
                mSettings.getCurrent().mUseContactsDictionary,
                mSettings.getCurrent().mUseAppsDictionary,
                mSettings.getCurrent().mUsePersonalizedDicts
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
        val settingsValues = mSettings.getCurrent()
        try {
            mDictionaryFacilitator.resetDictionaries(this, locale,
                settingsValues.mUseContactsDictionary, settingsValues.mUseAppsDictionary,
                settingsValues.mUsePersonalizedDicts, false, "", this)
        } catch (e: Throwable) {
            // this should not happen, but in case it does we at least want to show a keyboard
            Log.e(TAG, "Could not reset dictionary facilitator, please fix ASAP", e)
        }
        mInputLogic.mSuggest.setAutoCorrectionThreshold(settingsValues.mAutoCorrectionThreshold)
    }

    /**
     * Reset suggest by loading the main dictionary of the current locale.
     */
    /* package private */ fun resetSuggestMainDict() {
        val settingsValues = mSettings.getCurrent()
        mDictionaryFacilitator.resetDictionaries(this, mDictionaryFacilitator.getMainLocale(),
                settingsValues.mUseContactsDictionary, settingsValues.mUseAppsDictionary,
                settingsValues.mUsePersonalizedDicts, true, "", this)
        mKeyboardSwitcher.setThemeNeedsReload() // necessary for emoji search
        EmojiPalettesView.closeDictionaryFacilitator()
        EmojiSearchActivity.closeDictionaryFacilitator()
    }

    // used for debug
    fun getLocaleAndConfidenceInfo(): String? {
        return mDictionaryFacilitator.localesAndConfidences()
    }

    override fun onDestroy() {
        mClipboardHistoryManager.onDestroy()
        mDictionaryFacilitator.closeDictionaries()
        mSettings.onDestroy()
        if (foldableObserver != null)
            foldableObserver!!.unregister(this)
        unregisterReceiver(mRingerModeChangeReceiver)
        unregisterReceiver(mDictionaryPackInstallReceiver)
        unregisterReceiver(mDictionaryDumpBroadcastReceiver)
        unregisterReceiver(mRestartAfterDeviceUnlockReceiver)
        unregisterReceiver(mEmojiSearchReceiver)
        mStatsUtilsManager.onDestroy(this /* context */)
        super.onDestroy()
        mHandler.removeCallbacksAndMessages(null)
        deallocateMemory()
    }

    private fun isImeSuppressedByHardwareKeyboard(): Boolean {
        return !onEvaluateInputViewShown() && mKeyboardSwitcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), mKeyboardSwitcher.getKeyboardSwitchState())
    }

    override fun onConfigurationChanged(conf: Configuration) {
        val settingsValues = mSettings.getCurrent()
        Log.i(TAG, "onConfigurationChanged")
        SubtypeSettings.reloadSystemLocales(this)
        if (settingsValues.mDisplayOrientation != conf.orientation) {
            mHandler.startOrientationChanging()
            mInputLogic.onOrientationChange(mSettings.getCurrent())
        }
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
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
        mKeyboardSwitcher.updateKeyboardTheme(this.getDisplayContext())
        super.onConfigurationChanged(conf)
    }

    override fun onInitializeInterface() {
        mDisplayContext = this.getDisplayContext()
        Log.d(TAG, "onInitializeInterface")
        mKeyboardSwitcher.updateKeyboardTheme(mDisplayContext!!)
    }

    override fun onCreateInputView(): View? {
        StatsUtils.onCreateInputView()
        return mKeyboardSwitcher.onCreateInputView(this.getDisplayContext(), mIsHardwareAcceleratedDrawingEnabled)
    }

    override fun setInputView(view: View?) {
        super.setInputView(view)
        mInputView = view
        mInsetsUpdater = setInsetsOutlineProvider(view!!)
        updateSoftInputWindowLayoutParameters(mInputView)
        updateSuggestionStripView(view)
    }

    fun updateSuggestionStripView(view: View?) {
        mSuggestionStripView = if (mSettings.getCurrent().mToolbarMode == ToolbarMode.HIDDEN || isEmojiSearch())
                        null else view!!.findViewById<SuggestionStripView>(R.id.suggestion_strip_view)
        if (hasSuggestionStripView()) {
            mSuggestionStripView!!.setRtl(mRichImm.currentSubtype.isRtlSubtype)
            mSuggestionStripView!!.setListener(this, view!!)
        }
    }

    override fun setCandidatesView(view: View?) {
        // To ensure that CandidatesView will never be set.
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        mHandler.onStartInput(editorInfo, restarting)
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        mHandler.onStartInputView(editorInfo, restarting)
        mStatsUtilsManager.onStartInputView()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        StatsUtils.onFinishInputView()
        mHandler.onFinishInputView(finishingInput)
        mStatsUtilsManager.onFinishInputView()
        mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER
        BackgroundGatheringCache.saveOrClear(this)
    }

    override fun onFinishInput() {
        mHandler.onFinishInput()
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
        val oldSubtype = mRichImm.currentSubtype.rawSubtype
        if (subtype == oldSubtype) {
            // onStartInput may be called more than once, resulting in duplicate subtype switches
            return
        }

        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "switching to subtype " + subtype.toSettingsSubtype())
        mSubtypeState.onSubtypeChanged(oldSubtype, subtype)
        StatsUtils.onSubtypeChanged(oldSubtype, subtype)
        mRichImm.onSubtypeChanged(subtype)
        mInputLogic.onSubtypeChanged(SubtypeLocaleUtils.getCombiningRulesExtraValue(subtype),
                mSettings.getCurrent())
        loadKeyboard()
        if (hasSuggestionStripView()) {
            mSuggestionStripView!!.setRtl(mRichImm.currentSubtype.isRtlSubtype)
        }
        mSettings.saveSubtypeForApp(mRichImm.currentSubtype, getCurrentInputEditorInfo().packageName)
    }

    /** alias to onCurrentInputMethodSubtypeChanged with a better name, as it's also used for internal switching */
    fun switchToSubtype(subtype: InputMethodSubtype?) {
        onCurrentInputMethodSubtypeChanged(subtype)
    }

    private fun onStartInputInternal(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)

        val subtypeForApp = if (editorInfo == null)
            null else
            mSettings.getSubtypeForApp(editorInfo.packageName)
        val hintLocales = EditorInfoCompatUtils.getHintLocales(editorInfo)
        val subtypeForLocales = mSubtypeState.getSubtypeForLocales(mRichImm, hintLocales, subtypeForApp)
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "onStartInputInternal: hint locales " + hintLocales + ", found matching subtype: " + (subtypeForLocales != null))
        if (subtypeForLocales != null) {
            // found a better subtype using hint locales and saved-per-app subtype, that we should switch to.
            mHandler.postSwitchLanguage(subtypeForLocales)
        }
    }

    fun onStartInputViewInternal(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)

        setGestureDataGatheringMode(editorInfo!!, restarting)

        mDictionaryFacilitator.onStartInput()
        // Switch to the null consumer to handle cases leading to early exit below, for which we
        // also wouldn't be consuming gesture data.
        mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER
        mRichImm.refreshSubtypeCaches()
        val switcher = mKeyboardSwitcher

        // If we are starting input in a different text field from before, we'll have to reload
        // settings, so currentSettingsValues can't be final.
        var currentSettingsValues = mSettings.getCurrent()
        val inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo)
        val isDifferentTextField = !restarting || inputTypeChanged

        // we want to reload the settings before calling updateKeyboardTheme, because updateKeyboardTheme reads SettingsValues.mToolbarMode
        if (isDifferentTextField || !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings()
            if (hasSuggestionStripView())
                mSuggestionStripView!!.updateVoiceKey()
        }

        switcher.updateKeyboardTheme(mDisplayContext!!)
        val mainKeyboardView: MainKeyboardView? = switcher.getMainKeyboardView()
        currentSettingsValues = mSettings.getCurrent() // settingsValues may have been reloaded

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
        mGestureConsumer = GestureConsumer.newInstance(editorInfo,
                mInputLogic.getPrivateCommandPerformer(),
                mRichImm.currentSubtypeLocale,
                switcher.getKeyboard())

        // Forward this event to the accessibility utilities, if enabled.
        val accessUtils = AccessibilityUtils.instance
        if (accessUtils.isTouchExplorationEnabled) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, editorInfo, restarting)
        }

        StatsUtils.onStartInputView(editorInfo.inputType,
                Settings.getValues().mDisplayOrientation,
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
        if (!mHandler.hasPendingResumeSuggestions()) {
            mHandler.cancelUpdateSuggestionStrip()
            setNeutralSuggestionStrip()
            if (hasSuggestionStripView() && currentSettingsValues.mAutoShowToolbar && !tryShowClipboardSuggestion()) {
                mSuggestionStripView!!.setToolbarVisibility(true)
            }
        }

        mainKeyboardView.setMainDictionaryAvailability(mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary())
        mainKeyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn)
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(currentSettingsValues.mSlidingKeyInputPreviewEnabled)
        mainKeyboardView.setGestureHandlingEnabledByUser(
                currentSettingsValues.mGestureInputEnabled,
                currentSettingsValues.mGestureTrailEnabled,
                currentSettingsValues.mGestureFloatingPreviewTextEnabled)

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
        mInputLogic.startInput(mRichImm.combiningRulesExtraValueOfCurrentSubtype, currentSettingsValues)

        resetDictionaryFacilitatorIfNecessary()

        // TODO[IL]: Can the following be moved to InputLogic#startInput?
        if (!mInputLogic.mConnection.resetCachesUponCursorMoveAndReturnSuccess(
                editorInfo.initialSelStart, editorInfo.initialSelEnd,
                false /* shouldFinishComposition */)) {
            // Sometimes, while rotating, for some reason the framework tells the app we are not
            // connected to it and that means we can't refresh the cache. In this case, schedule
            // a refresh later.
            // We try resetting the caches up to 5 times before giving up.
            mHandler.postResetCaches(isDifferentTextField, 5 /* remainingTries */)
            return false
        }
        // When rotating, and when input is starting again in a field from where the focus
        // didn't move (the keyboard having been closed with the back key),
        // initialSelStart and initialSelEnd sometimes are lying. Make a best effort to
        // work around this bug.
        mInputLogic.mConnection.tryFixIncorrectCursorPosition()
        if (mInputLogic.mConnection.isCursorTouchingWord(currentSettingsValues.mSpacingAndPunctuations, true)) {
            mHandler.postResumeSuggestions(true /* shouldDelay */)
        }
        return true
    }

    /** Loads the keyboard for a new field, or only resets layout and shift state when input restarts in the same one. */
    private fun showKeyboardForField(mainKeyboardView: MainKeyboardView, currentSettingsValues: SettingsValues,
            isDifferentTextField: Boolean, restarting: Boolean, needToCallLoadKeyboardLater: Boolean) {
        val switcher = mKeyboardSwitcher
        if (isDifferentTextField) {
            mainKeyboardView.closing()
            mInputLogic.mSuggest.setAutoCorrectionThreshold(currentSettingsValues.mAutoCorrectionThreshold)
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
            if (mInputView != null && Settings.getValues().mIsFloatingKeyboard)
                FloatingKeyboardUtils.setFloating(mInputView)
            setNavigationBarColor()
            workaroundForHuaweiStatusBarIssue()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        KeyboardSwitcher.getInstance().stopResizeMode()
        Log.i(TAG, "onWindowHidden")
        val mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardView != null) {
            mainKeyboardView.closing()
        }
        clearNavigationBarColor()
    }

    fun onFinishInputInternal() {
        super.onFinishInput()
        Log.i(TAG, "onFinishInput")

        mDictionaryFacilitator.onFinishInput()
        val mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardView != null) {
            mainKeyboardView.closing()
        }
    }

    fun onFinishInputViewInternal(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.i(TAG, "onFinishInputView")
        cleanupInternalStateForFinishInput()
    }

    private fun cleanupInternalStateForFinishInput() {
        // Remove pending messages related to update suggestions
        mHandler.cancelUpdateSuggestionStrip()
        // Should do the following in onFinishInputInternal but until JB MR2 it's not called :(
        mInputLogic.finishInput()
        mKeyboardActionListener.resetMetaState()
    }

    protected fun deallocateMemory() {
        mKeyboardSwitcher.deallocateMemory()
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
        val settingsValues = mSettings.getCurrent()
        if (isInputViewShown()
                && mInputLogic.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd, settingsValues)) {
            // we don't want to update a manually set shift state if selection changed towards one side
            // because this may end the manual shift, which is unwanted in case of shift + arrow keys for changing selection
            // todo: this is not fully implemented yet, and maybe should be behind a setting
            if (mKeyboardSwitcher.getKeyboard() != null && mKeyboardSwitcher.getKeyboard()!!.mId.element.isAlphabetShiftedManually
                && ((oldSelEnd == newSelEnd && oldSelStart != newSelStart) || (oldSelEnd != newSelEnd && oldSelStart == newSelStart)))
                return
            mKeyboardSwitcher.updateShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState())
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
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
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
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return
        }

        super.onExtractedCursorMovement(dx, dy)
    }

    override fun hideWindow() {
        Log.i(TAG, "hideWindow")
        if (hasSuggestionStripView() && mSettings.getCurrent().mToolbarMode == ToolbarMode.EXPANDABLE)
            mSuggestionStripView!!.setToolbarVisibility(false)
        mKeyboardSwitcher.onHideWindow()

        if (TRACE) Debug.stopMethodTracing()
        if (isShowingOptionDialog()) {
            mOptionsDialog!!.dismiss()
            mOptionsDialog = null
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
        if (!mSettings.getCurrent().mInputAttributes.mApplicationSpecifiedCompletionOn) {
            return
        }
        // If we have an update request in flight, we need to cancel it so it does not override
        // these completions.
        mHandler.cancelUpdateSuggestionStrip()
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
        if (mInputView == null) {
            return
        }
        val visibleKeyboardView = mKeyboardSwitcher.getWrapperView()
        if (visibleKeyboardView == null) {
            return
        }
        val inputHeight = mInputView!!.getHeight()
        if (isImeSuppressedByHardwareKeyboard() && !visibleKeyboardView.isShown()) {
            // If there is a hardware keyboard and a visible software keyboard view has been hidden,
            // no visual element will be shown on the screen.
            // for some reason setting contentTopInsets and visibleTopInsets broke somewhere along the
            // way from OpenBoard to HeliBoard (GH-702, GH-1455), but not setting anything seems to work
            mInsetsUpdater.setInsets(outInsets!!)
            return
        }
        val stripHeight = if (mKeyboardSwitcher.isShowingStripContainer()) mKeyboardSwitcher.getStripContainer().getHeight() else 0
        var visibleTopY = inputHeight - visibleKeyboardView.getHeight() - stripHeight
        if (Settings.getValues().mIsFloatingKeyboard)
            visibleTopY = getResources().getDisplayMetrics().heightPixels

        if (hasSuggestionStripView()) {
            mSuggestionStripView!!.setMoreSuggestionsHeight(visibleTopY)
        }

        // Need to set expanded touchable region only if a keyboard view is being shown.
        if (visibleKeyboardView.isShown()) {
            var touchLeft = 0
            var touchTop = if (mKeyboardSwitcher.isShowingPopupKeysPanel()) 0 else visibleTopY
            var touchRight = visibleKeyboardView.getWidth()
            var touchBottom = inputHeight + EXTENDED_TOUCHABLE_REGION_HEIGHT // Extend touchable region below the keyboard.
            if (mSettings.getCurrent().mIsFloatingKeyboard) {
                val xy = FloatingKeyboardUtils.readPosition(this, Int.MAX_VALUE, Int.MAX_VALUE)
                touchLeft = xy.component1()
                touchTop = xy.component2()
                touchRight = touchLeft + mSettings.getCurrent().mFloatingWidth
                touchBottom = touchTop + inputHeight
            }
            outInsets!!.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(touchLeft, touchTop, touchRight, touchBottom)
        }

        // Has to be subtracted after calculating touchableRegion
        visibleTopY -= getEmojiSearchActivityHeight()

        outInsets!!.contentTopInsets = visibleTopY
        outInsets.visibleTopInsets = visibleTopY
        mInsetsUpdater.setInsets(outInsets)
    }

    fun startShowingInputView(needsToLoadKeyboard: Boolean) {
        mIsExecutingStartShowingInputView = true
        // This {@link #showWindow(boolean)} will eventually call back
        // {@link #onEvaluateInputViewShown()}.
        showWindow(true /* showInput */)
        mIsExecutingStartShowingInputView = false
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
        if (mIsExecutingStartShowingInputView) {
            return true
        }
        return super.onEvaluateInputViewShown()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        if (isImeSuppressedByHardwareKeyboard() || mSettings.getCurrent().mIsFloatingKeyboard) {
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
            if (mKeyboardSwitcher.getVisibleKeyboardView() == null || mSuggestionStripView == null) return false
            val usedHeight = mKeyboardSwitcher.getVisibleKeyboardView().getHeight() + mSuggestionStripView!!.getHeight()
            val availableHeight = getResources().getDisplayMetrics().heightPixels
            return usedHeight > availableHeight * 0.6 // if we have less than 40% available, use fullscreen mode
        }
        return false
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        updateSoftInputWindowLayoutParameters(mInputView)
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        Log.d(TAG,"onCreateInlineSuggestionsRequest called")
        if (Settings.getValues().mSuggestionStripHiddenPerUserSettings) {
            return null
        }

        return InlineAutofillUtils.createInlineSuggestionRequest(mDisplayContext)
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        Log.d(TAG,"onInlineSuggestionsResponse called")
        if (Settings.getValues().mSuggestionStripHiddenPerUserSettings) {
            return false
        }

        val inlineSuggestions: List<InlineSuggestion> = response.getInlineSuggestions()
        if (inlineSuggestions.isEmpty()) {
            return false
        }

        val inlineSuggestionView: View = InlineAutofillUtils.createView(inlineSuggestions, mDisplayContext)

        // Without this function the inline autofill suggestions will not be visible
        mHandler.cancelResumeSuggestions()

        mSuggestionStripView!!.setExternalSuggestionView(inlineSuggestionView, true)

        return true
    }

    fun getCurrentAutoCapsState(): Int {
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent())
    }

    fun getCurrentRecapitalizeState(): RecapitalizeMode? {
        return mInputLogic.getCurrentRecapitalizeState()
    }

    /**
     * @param codePoints code points to get coordinates for.
     * @return x,y coordinates for this keyboard, as a flattened array.
     */
    fun getCoordinatesForCurrentKeyboard(codePoints: IntArray): IntArray {
        val keyboard = mKeyboardSwitcher.getKeyboard()
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
        if (isShowingOptionDialog()) return false
        if (mRichImm.hasMultipleEnabledIMEsOrSubtypes(true)) {
            mOptionsDialog = createInputMethodPickerDialog(this, mRichImm, mKeyboardSwitcher.getMainKeyboardView().getWindowToken())
            mOptionsDialog!!.show()
            return true
        }
        return false
    }

    private fun isShowingOptionDialog(): Boolean {
        return mOptionsDialog != null && mOptionsDialog!!.isShowing()
    }

    // called when language switch key is pressed (either the keyboard key, or long-press comma)
    fun switchToNextSubtype() {
        val switchSubtype = mSettings.getCurrent().mLanguageSwitchKeyToOtherSubtypes
        val switchIme = mSettings.getCurrent().mLanguageSwitchKeyToOtherImes

        // switch IME if wanted and possible
        if (switchIme && !switchSubtype && this.switchInputMethod())
            return
        val hasMoreThanOneSubtype = mRichImm.hasMultipleEnabledSubtypesInThisIme(true)
        // switch subtype if wanted, do nothing if no other subtype is available
        if (switchSubtype && !switchIme) {
            if (hasMoreThanOneSubtype)
                // switch to previous subtype if current one was used, otherwise cycle through list
                mSubtypeState.switchSubtype(mRichImm)
            return
        }
        // language key set to switch both, or language key is not shown on keyboard -> switch both
        if (hasMoreThanOneSubtype && mSubtypeState.currentSubtypeHasBeenUsed) {
            mSubtypeState.switchSubtype(mRichImm)
            return
        }
        if (this.shouldSwitchToOtherInputMethods()) {
            val nextSubtype = mRichImm.getNextSubtypeInThisIme(false)
            if (nextSubtype != null) {
                switchToSubtype(nextSubtype)
                return
            } else if (this.switchInputMethod()) {
                return
            }
        }
        mSubtypeState.switchSubtype(mRichImm)
    }

    // Implementation of {@link SuggestionStripView.Listener}.
    override fun onCodeInput(codePoint: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
        mKeyboardActionListener.onCodeInput(codePoint, x, y, isKeyRepeat)
    }

    // This method is public for testability of LatinIME, but also in the future it should
    // completely replace #onCodeInput.
    fun onEvent(event: Event) {
        if (KeyCode.VOICE_INPUT == event.keyCode) {
            mRichImm.switchToShortcutIme(this)
        }
        val completeInputTransaction =
                mInputLogic.onCodeInput(mSettings.getCurrent(), event,
                        mKeyboardSwitcher.getKeyboardCapsMode(),
                        mKeyboardSwitcher.getCurrentKeyboardScript(), mHandler)
        updateStateAfterInputTransaction(completeInputTransaction)
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState())
    }

    fun onTextInput(rawText: String?) {
        if (rawText == null) return
        // TODO: have the keyboard pass the correct key code when we need it.
        val event = Event.createSoftwareTextEvent(rawText, KeyCode.MULTIPLE_CODE_POINTS, null)
        val completeInputTransaction = mInputLogic.onTextInput(mSettings.getCurrent(),
            event, mKeyboardSwitcher.getKeyboardCapsMode(), mHandler)
        updateStateAfterInputTransaction(completeInputTransaction)
        mInputLogic.restartSuggestionsOnWordTouchedByCursor(mSettings.getCurrent(), mKeyboardSwitcher.getCurrentKeyboardScript())
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState())
    }

    fun onStartBatchInput() {
        mInputLogic.onStartBatchInput(mSettings.getCurrent(), mKeyboardSwitcher, mHandler)
        mGestureConsumer.onGestureStarted(mRichImm.currentSubtypeLocale, mKeyboardSwitcher.getKeyboard())
    }

    fun onUpdateBatchInput(batchPointers: InputPointers?) {
        mInputLogic.onUpdateBatchInput(batchPointers!!)
    }

    fun onEndBatchInput(batchPointers: InputPointers?) {
        mInputLogic.onEndBatchInput(batchPointers!!)
        mGestureConsumer.onGestureCompleted(batchPointers)
    }

    fun onCancelBatchInput() {
        mInputLogic.onCancelBatchInput(mHandler)
        mGestureConsumer.onGestureCanceled()
    }

    /**
     * To be called after the InputLogic has gotten a chance to act on the suggested words by the
     * IME for the full gesture, possibly updating the TextView to reflect the first suggestion.
     * <p>
     * This method must be run on the UI Thread.
     * @param suggestedWords suggested words by the IME for the full gesture.
     */
    fun onTailBatchInputResultShown(suggestedWords: SuggestedWords?) {
        mGestureConsumer.onImeSuggestionsProcessed(suggestedWords,
                mInputLogic.getComposingStart(), mInputLogic.getComposingLength(),
                mDictionaryFacilitator)
    }

    // This method must run on the UI Thread.
    private fun showGesturePreviewAndSetSuggestions(suggestedWords: SuggestedWords,
                                              dismissGestureFloatingPreviewText: Boolean) {
        setSuggestions(suggestedWords)
        val mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView()
        mainKeyboardView.showGestureFloatingPreviewText(suggestedWords,
                dismissGestureFloatingPreviewText /* dismissDelayed */)
    }

    fun hasSuggestionStripView(): Boolean {
        return null != mSuggestionStripView
    }

    private fun setSuggestedWords(suggestedWords: SuggestedWords) {
        val currentSettingsValues = mSettings.getCurrent()
        mInputLogic.setSuggestedWords(suggestedWords)
        // TODO: Modify this when we support suggestions with hard keyboard
        if (!hasSuggestionStripView()) {
            return
        }
        if (!onEvaluateInputViewShown()) {
            return
        }

        val isEmptyApplicationSpecifiedCompletions =
                currentSettingsValues.mInputAttributes.mApplicationSpecifiedCompletionOn
                        && suggestedWords.isEmpty()
        val noSuggestionsFromDictionaries = suggestedWords.isEmpty()
                || suggestedWords.isPunctuationSuggestions()
                || isEmptyApplicationSpecifiedCompletions

        if (currentSettingsValues.mSuggestionsEnabled
                || currentSettingsValues.mInputAttributes.mApplicationSpecifiedCompletionOn
                // We should clear the contextual strip if there is no suggestion from dictionaries.
                || noSuggestionsFromDictionaries) {
            mSuggestionStripView!!.setSuggestions(suggestedWords,
                    mRichImm.currentSubtype.isRtlSubtype)
            // Auto hide the toolbar if dictionary suggestions are available
            if (currentSettingsValues.mAutoHideToolbar && !noSuggestionsFromDictionaries) {
                mSuggestionStripView!!.setToolbarVisibility(false)
            }
        }
    }

    override fun setSuggestions(suggestedWords: SuggestedWords) {
        if (suggestedWords.isEmpty()) {
            // avoids showing clipboard suggestion when starting gesture typing
            // should be fine, as there will be another suggestion in a few ms
            // (but not a great style to avoid this visual glitch, maybe revert this commit and replace with sth better)
            if (suggestedWords.mInputStyle != SuggestedWords.INPUT_STYLE_UPDATE_BATCH)
                setNeutralSuggestionStrip()
        } else {
            setSuggestedWords(suggestedWords)
        }
        // Cache the auto-correction in accessibility code so we can speak it if the user
        // touches a key that will insert it.
        AccessibilityUtils.instance.setAutoCorrection(suggestedWords)
    }

    override fun showSuggestionStrip() {
        if (hasSuggestionStripView()) {
            mSuggestionStripView!!.setToolbarVisibility(false)
        }
    }

    // Called from {@link SuggestionStripView} through the {@link SuggestionStripView#Listener}
    // interface
    override fun pickSuggestionManually(suggestionInfo: SuggestedWordInfo?) {
        val completeInputTransaction = mInputLogic.onPickSuggestionManually(
                mSettings.getCurrent(), suggestionInfo!!,
                mKeyboardSwitcher.getKeyboardCapsMode(),
                mKeyboardSwitcher.getCurrentKeyboardScript(),
                mHandler)
        updateStateAfterInputTransaction(completeInputTransaction)
    }

    /**
     *  Checks if a recent clipboard suggestion is available. If available, it is set in suggestion strip.
     *  returns whether a clipboard suggestion has been set.
     */
    fun tryShowClipboardSuggestion(): Boolean {
        if (!hasSuggestionStripView()) return false
        val clipboardView = mClipboardHistoryManager.getClipboardSuggestionView(getCurrentInputEditorInfo(), mSuggestionStripView)
        if (clipboardView != null) {
            mSuggestionStripView!!.setExternalSuggestionView(clipboardView, false)
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
        val currentSettings = mSettings.getCurrent()
        if (tryShowClipboardSuggestion()) {
            // clipboard suggestion has been set
            if (hasSuggestionStripView() && currentSettings.mAutoHideToolbar)
                mSuggestionStripView!!.setToolbarVisibility(false)
            return
        }
        val neutralSuggestions: SuggestedWords = if (currentSettings.mSuggestPunctuation)
                currentSettings.mPunctuationSuggestions
                else SuggestedWords.getEmptyInstance()
        setSuggestedWords(neutralSuggestions)
        if (hasSuggestionStripView() && currentSettings.mAutoShowToolbar) {
            val codePointBeforeCursor = mInputLogic.mConnection.getCodePointBeforeCursor()
            if (mInputLogic.mConnection.hasSelection()
                    || codePointBeforeCursor == Constants.NOT_A_CODE
                    || codePointBeforeCursor == Constants.CODE_ENTER) {
                mSuggestionStripView!!.setToolbarVisibility(true)
            }
        }
    }

    override fun removeSuggestion(word: String?) {
        mDictionaryFacilitator.removeWord(word)
    }

    override fun removeExternalSuggestions() {
        setNeutralSuggestionStrip()
        mHandler.postResumeSuggestions(false)
    }

    private fun loadKeyboard() {
        // Since we are switching languages, the most urgent thing is to let the keyboard graphics
        // update. LoadKeyboard does that, but we need to wait for buffer flip for it to be on
        // the screen. Anything we do right now will delay this, so wait until the next frame
        // before we do the rest, like reopening dictionaries and updating suggestions. So we
        // post a message.
        mHandler.postReopenDictionaries()
        loadSettings()
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            // Reload keyboard because the current language has been changed.
            mKeyboardSwitcher.reloadMainKeyboard()
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
            InputTransaction.SHIFT_UPDATE_LATER -> mHandler.postUpdateShiftState()
            InputTransaction.SHIFT_UPDATE_NOW -> mKeyboardSwitcher
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
            mHandler.postUpdateSuggestionStrip(inputStyle)
        }
        if (inputTransaction.didAffectContents()) {
            mSubtypeState.setCurrentSubtypeHasBeenUsed()
        }
    }

    fun hapticAndAudioFeedback(code: Int, repeatCount: Int,
                                       hapticEvent: HapticEvent) {
        val keyboardView = mKeyboardSwitcher.getMainKeyboardView()
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            // No need to feedback while finger is dragging.
            return
        }
        if (repeatCount > 0) {
            // No need to feedback when repeat delete/cursor keys will have no effect.
            when (code) {
                KeyCode.DELETE, KeyCode.ARROW_LEFT, KeyCode.ARROW_UP, KeyCode.WORD_LEFT, KeyCode.PAGE_UP ->
                    if (!mInputLogic.mConnection.canDeleteCharacters())
                        return
                KeyCode.ARROW_RIGHT, KeyCode.ARROW_DOWN, KeyCode.WORD_RIGHT, KeyCode.PAGE_DOWN ->
                    if (!mInputLogic.mConnection.hasTextAfterCursor())
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
        if (mKeyboardActionListener.onKeyDown(keyCode, keyEvent))
            return true
        return super.onKeyDown(keyCode, keyEvent)
    }

    override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent?): Boolean {
        if (mKeyboardActionListener.onKeyUp(keyCode, keyEvent))
            return true
        return super.onKeyUp(keyCode, keyEvent)
    }

    // onKeyDown and onKeyUp are the main events we are interested in. There are two more events
    // related to handling of hardware key events that we may want to implement in the future:
    // boolean onKeyLongPress(final int keyCode, final KeyEvent event);
    // boolean onKeyMultiple(final int keyCode, final int count, final KeyEvent event);


    fun getClipboardHistoryManager(): ClipboardHistoryManager {
        return mClipboardHistoryManager
    }

    fun launchSettings() {
        mInputLogic.commitTyped(mSettings.getCurrent(), LastComposedWord.NOT_A_SEPARATOR)
        requestHideSelf(0)
        val mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView()
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
        Log.d(helium314.keyboard.keyboard.emoji.TAG, "before activity launch")
        startActivity(Intent().setClass(this, EmojiSearchActivity::class.java)
                          .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }

    private fun onEmojiSearchDone(intent: Intent?) {
        Log.d(helium314.keyboard.keyboard.emoji.TAG, "after activity closing. isEmojiSearch: " + isEmojiSearch() + ". Intent: " + intent +
                (if (intent != null) ". imeClosed: " + isImeClosed(intent) + ". selected emoji: " + getSelectedEmoji(intent) else ""))
        if (intent != null && EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION == intent.getAction() && ! isEmojiSearch()) {
            if (isImeClosed(intent)) {
                requestHideSelf(0)
            } else {
                mHandler.postDelayed(mKeyboardSwitcher::setEmojiKeyboard, 100)
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
        if (!mDictionaryFacilitator.isActive()) {
            resetDictionaryFacilitatorIfNecessary()
        }
        mDictionaryFacilitator.dumpDictionaryForDebug(dictName)
    }

    fun debugDumpStateAndCrashWithException(context: String) {
        val settingsValues = mSettings.getCurrent()
        val s = settingsValues.toString() + "\nAttributes : " + settingsValues.mInputAttributes +
                "\nContext : " + context
        throw RuntimeException(s)
    }

    override fun dump(fd: FileDescriptor?, fout: PrintWriter?, args: Array<out String>?) {
        super.dump(fd, fout, args)

        val p: Printer = PrintWriterPrinter(fout)
        p.println("LatinIME state :")
        p.println("  VersionCode = " + BuildConfig.VERSION_CODE)
        p.println("  VersionName = " + BuildConfig.VERSION_NAME)
        val keyboard = mKeyboardSwitcher.getKeyboard()
        val keyboardMode = if (keyboard != null) keyboard.mId.mode else null
        p.println("  Keyboard mode = " + keyboardMode)
        val settingsValues = mSettings.getCurrent()
        p.println(settingsValues.dump())
        p.println(mDictionaryFacilitator.dump(this))
    }

    // slightly modified from Simple Keyboard: https://github.com/rkkr/simple-keyboard/blob/master/app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java
    @Suppress("DEPRECATION")
    private fun setNavigationBarColor() {
        val settingsValues = mSettings.getCurrent()
        if (!settingsValues.mCustomNavBarColor)
            return
        val color = settingsValues.mColors.get(ColorType.NAVIGATION_BAR)
        val window = getWindow().getWindow()
        if (window == null)
            return
        mOriginalNavBarColor = window.getNavigationBarColor()
        window.setNavigationBarColor(color)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        val view = window.getDecorView()
        mOriginalNavBarFlags = view.getSystemUiVisibility()
        if (isBrightColor(color)) {
            view.setSystemUiVisibility(mOriginalNavBarFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        } else {
            view.setSystemUiVisibility(mOriginalNavBarFlags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv())
        }
    }

    @Suppress("DEPRECATION")
    private fun clearNavigationBarColor() {
        val settingsValues = mSettings.getCurrent()
        if (!settingsValues.mCustomNavBarColor)
            return
        val window = getWindow().getWindow()
        if (window == null) {
            return
        }
        window.setNavigationBarColor(mOriginalNavBarColor)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        val view = window.getDecorView()
        view.setSystemUiVisibility(mOriginalNavBarFlags)
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
                mKeyboardSwitcher.trimMemory()
            }
            // deallocateMemory always called on hiding, and should not be called when showing
        }
    }

    fun setGestureDataGatheringMode(editorInfo: EditorInfo, restarting: Boolean) {
        // only for gesture data gathering, remove when data gathering phase is done (end of 2026 latest)
        if (GestureDataGatheringSettings.isInActiveGatheringMode(editorInfo)) {
            mDictionaryFacilitator = gestureDataActiveFacilitator!!
            useBackgroundGathering = false
            mKeyboardSwitcher.setBackgroundGatheringIndicator(false, false, false)
        } else {
            mDictionaryFacilitator = mOriginalDictionaryFacilitator

            // no active mode, check for background mode
            val useBackground = setUseBackgroundGathering(this, editorInfo)
            mKeyboardSwitcher.setBackgroundGatheringIndicator(useBackground, false, false)
            // restarting means we're still in the same field, so don't clear anything in discard-by-default mode
            if (!restarting || !GestureDataGatheringSettings.isDiscardByDefault(this))
                BackgroundGatheringCache.saveOrClear(this)
        }
        GestureDataGatheringSettings.showEndNotificationIfNecessary(this) // will do nothing for a long time
        mInputLogic.setFacilitator(mDictionaryFacilitator)
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
