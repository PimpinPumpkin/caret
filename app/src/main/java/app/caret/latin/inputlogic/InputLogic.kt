/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.inputlogic

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.SuggestionSpan
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import app.caret.compat.AppWorkarounds
import app.caret.event.Event
import app.caret.event.InputTransaction
import app.caret.keyboard.Keyboard
import app.caret.keyboard.KeyboardElement
import app.caret.keyboard.KeyboardLayoutSet
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.emoji.RecentEmojis
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode.isModifier
import app.caret.latin.CapsMode
import app.caret.latin.DictionaryFacilitator
import app.caret.latin.LastComposedWord
import app.caret.latin.LatinIME
import app.caret.latin.NgramContext
import app.caret.latin.RichInputConnection
import app.caret.latin.SingleDictionaryFacilitator
import app.caret.latin.Suggest
import app.caret.latin.Suggest.OnGetSuggestedWordsCallback
import app.caret.latin.SuggestedWords
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.WordComposer
import app.caret.latin.common.Constants
import app.caret.latin.common.InputPointers
import app.caret.latin.common.StringUtils
import app.caret.latin.common.getTextWithAutoCorrectionIndicatorUnderline
import app.caret.latin.common.getTextWithSuggestionSpan
import app.caret.latin.common.isEmoji
import app.caret.latin.common.lengthOfWordBeforeCursor
import app.caret.latin.common.splitOnWhitespace
import app.caret.latin.common.stripTrailingSeparatorsAndConnectors
import app.caret.latin.define.DebugFlags
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.dictionary.DictionaryFactory
import app.caret.latin.settings.Settings
import app.caret.latin.swipe.FutoSwipe
import app.caret.latin.CorrectionRecorder
import app.caret.latin.swipe.SwipeRecorder
import app.caret.latin.settings.SettingsValues
import app.caret.latin.settings.SpacingAndPunctuations
import app.caret.latin.suggestions.SuggestionStripViewAccessor
import app.caret.latin.utils.AsyncResultHolder
import app.caret.latin.utils.BackgroundGatheringCache
import app.caret.latin.utils.DictionaryInfoUtils
import app.caret.latin.utils.InputTypeUtils
import app.caret.latin.utils.IntentUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.RecapitalizeMode
import app.caret.latin.utils.RecapitalizeStatus
import app.caret.latin.utils.ScriptUtils
import app.caret.latin.utils.StatsUtils
import app.caret.latin.utils.TextPlacement
import app.caret.latin.utils.TextRange
import app.caret.latin.utils.getTimestamp
import app.caret.latin.utils.useBackgroundGathering
import java.util.ArrayList
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/**
 * This class manages the input logic.
 */
class InputLogic(
    // TODO : Remove this member when we can.
    @JvmField val latinIME: LatinIME,
    private val suggestionStripViewAccessor: SuggestionStripViewAccessor,
    // non-final for active gesture data gathering, revert when data gathering phase is done (end of 2026 latest)
    @JvmField var dictionaryFacilitator: DictionaryFacilitator
) {
    // This has package visibility so it can be accessed from InputLogicHandler.
    @JvmField val wordComposer: WordComposer = WordComposer()
    @JvmField val connection: RichInputConnection = RichInputConnection(latinIME)
    private val inputLogicHandler: InputLogicHandler = InputLogicHandler(latinIME.handler, this)

    // TODO : make all these fields private as soon as possible.
    // Current space state of the input method. This can be any of the above constants.
    private var spaceState = 0
    // Never null
    @JvmField var suggestedWords: SuggestedWords = SuggestedWords.emptyInstance
    @JvmField var suggest: Suggest = Suggest(dictionaryFacilitator) // non-final for active gesture data gathering, revert when data gathering phase is done (end of 2026 latest)
    private var emojiDictionaryFacilitator: SingleDictionaryFacilitator? = null
    fun setFacilitator(facilitator: DictionaryFacilitator) { // only for active gesture data gathering, remove when data gathering phase is done (end of 2026 latest)
        if (dictionaryFacilitator === facilitator) return
        dictionaryFacilitator = facilitator
        suggest = Suggest(dictionaryFacilitator)
    }

    @JvmField var lastComposedWord: LastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD
    private val recapitalizeStatus = RecapitalizeStatus()

    private var deleteCount = 0
    private var lastKeyTime: Long = 0
    // todo: this is not used, so either remove it or do something with it
    @JvmField val currentlyPressedHardwareKeys = TreeSet<Long>()

    // Keeps track of most recently inserted text (multi-character key) for reverting
    private var enteredText: String? = null

    // TODO: This boolean is persistent state and causes large side effects at unexpected times.
    // Find a way to remove it for readability.
    private var isAutoCorrectionIndicatorOn = false
    private var doubleSpacePeriodCountdownStart: Long = 0

    // The word being corrected while the cursor is in the middle of the word.
    // Note: This does not have a composing span, so it must be handled separately.
    private var wordBeingCorrectedByCursor: String? = null

    private var justRevertedACommit = false

    private var cursorMoveExpectedUntil = 0L

    private var autoCommitSequenceNumber = 1

    /**
     * Initializes the input logic for input in an editor.
     * <p>
     * Call this when input starts or restarts in some editor (typically, in onStartInputView).
     *
     * @param combiningSpec the combining spec string for this subtype (from extra value)
     * @param settingsValues the current settings values
     */
    fun startInput(combiningSpec: String?, settingsValues: SettingsValues) {
        enteredText = null
        wordBeingCorrectedByCursor = null
        connection.onStartInput()
        if (!wordComposer.typedWord.isEmpty()) {
            // For messaging apps that offer send button, the IME does not get the opportunity
            // to capture the last word. This block should capture those uncommitted words.
            // The timestamp at which it is captured is not accurate but close enough.
            StatsUtils.onWordCommitUserTyped(wordComposer.typedWord, wordComposer.isBatchMode)
        }
        wordComposer.restartCombining(combiningSpec)
        resetComposingState(true /* alsoResetLastComposedWord */)
        deleteCount = 0
        spaceState = SpaceState.NONE
        recapitalizeStatus.disable() // Do not perform recapitalize until the cursor is moved once
        currentlyPressedHardwareKeys.clear()
        suggestedWords = SuggestedWords.emptyInstance
        // In some cases (e.g. after rotation of the device, or when scrolling the text before bringing up keyboard)
        // editorInfo.initialSelStart is not the actual cursor position, so we try using some heuristics to find the correct position.
        connection.tryFixIncorrectCursorPosition()
        cancelDoubleSpacePeriodCountdown()
        inputLogicHandler.reset()
        connection.requestCursorUpdates(true, true)
        setInlineEmojiSearchAction(false)
    }

    /**
     * Call this when the subtype changes.
     * @param combiningSpec the spec string for the combining rules
     * @param settingsValues the current settings values
     */
    fun onSubtypeChanged(combiningSpec: String?, settingsValues: SettingsValues) {
        finishInput()
        startInput(combiningSpec, settingsValues)
    }

    /**
     * Call this when the orientation changes.
     * @param settingsValues the current values of the settings.
     */
    fun onOrientationChange(settingsValues: SettingsValues) {
        // If !isComposingWord, #commitTyped() is a no-op, but still, it's better to avoid
        // the useless IPC of {begin,end}BatchEdit.
        if (wordComposer.isComposingWord) {
            connection.beginBatchEdit()
            // If we had a composition in progress, we need to commit the word so that the
            // suggestionsSpan will be added. This will allow resuming on the same suggestions
            // after rotation is finished.
            commitTyped(settingsValues, LastComposedWord.NOT_A_SEPARATOR)
            connection.endBatchEdit()
        }
    }

    /**
     * Clean up the input logic after input is finished.
     */
    fun finishInput() {
        if (wordComposer.isComposingWord) {
            connection.finishComposingText()
            StatsUtils.onWordCommitUserTyped(wordComposer.typedWord, wordComposer.isBatchMode)
        }
        resetComposingState(true)
        inputLogicHandler.reset()
        spaceState = SpaceState.NONE
    }

    /**
     * React to a string input.
     * <p>
     * This is triggered by keys that input many characters at once, like the ".com" key or
     * some additional keys for example.
     *
     * @param settingsValues the current values of the settings.
     * @param event the input event containing the data.
     * @return the complete transaction object
     */
    fun onTextInput(settingsValues: SettingsValues, event: Event, keyboardCapsMode: CapsMode, handler: LatinIME.UIHandler): InputTransaction {
        val rawText = event.textToCommit!!.toString()
        val inputTransaction = InputTransaction(settingsValues, event,
                SystemClock.uptimeMillis(), spaceState,
                getActualCapsMode(settingsValues, keyboardCapsMode))
        connection.beginBatchEdit()
        if (useBackgroundGathering && connection.hasSelection())
            BackgroundGatheringCache.onEditSelection(connection.getSelectedText(0), connection.getTextBeforeCursor(40, 0), connection.getTextAfterCursor(40, 0))
        if (wordComposer.isComposingWord) {
            if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                if (useBackgroundGathering)
                    BackgroundGatheringCache.onEditWord(wordComposer.typedWord)

                // stop composing, otherwise the text will end up at the end of the current word
                connection.finishComposingText()
                resetComposingState(false)
            } else {
                commitCurrentAutoCorrection(settingsValues, rawText, handler)
                addToHistoryIfEmoji(rawText, settingsValues) // add emoji after committing text
            }
        } else {
            addToHistoryIfEmoji(rawText, settingsValues) // add emoji before resetting, otherwise lastComposedWord is empty
            resetComposingState(true /* alsoResetLastComposedWord */)
        }
        handler.postUpdateSuggestionStrip(SuggestedWords.INPUT_STYLE_TYPING)
        val text = performSpecificTldProcessingOnTextInput(rawText)
        if (SpaceState.PHANTOM == spaceState) {
            insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues)
        }
        connection.commitText(text, 1)
        StatsUtils.onWordCommitUserTyped(enteredText, wordComposer.isBatchMode)
        connection.endBatchEdit()
        // Space state must be updated before calling updateShiftState
        spaceState = SpaceState.NONE
        enteredText = text
        wordBeingCorrectedByCursor = null
        inputTransaction.setDidAffectContents()
        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW)
        return inputTransaction
    }

    /**
     * A suggestion was picked from the suggestion strip.
     * @param settingsValues the current values of the settings.
     * @param suggestionInfo the suggestion info.
     * @param keyboardCapsMode the shift state of the keyboard, as returned by
     *     [app.caret.keyboard.KeyboardSwitcher.getKeyboardCapsMode]
     * @return the complete transaction object
     */
    // Called from {@link SuggestionStripView} through the {@link SuggestionStripView#Listener}
    // interface
    fun onPickSuggestionManually(settingsValues: SettingsValues, suggestionInfo: SuggestedWordInfo,
            keyboardCapsMode: CapsMode, currentKeyboardScript: String, handler: LatinIME.UIHandler): InputTransaction {
        if (isInlineEmojiSearchAction()) {
            deleteTextReplacedByEmoji()
        }

        val suggestion = suggestionInfo.word
        // If this is a punctuation picked from the suggestion strip, pass it to onCodeInput
        if (suggestion.length == 1 && suggestedWords.isPunctuationSuggestions()) {
            // We still want to log a suggestion click.
            StatsUtils.onPickSuggestionManually(suggestedWords, suggestionInfo, dictionaryFacilitator)
            // Word separators are suggested before the user inputs something.
            // Rely on onCodeInput to do the complicated swapping/stripping logic consistently.
            val event = Event.createPunctuationSuggestionPickedEvent(suggestionInfo)
            return onCodeInput(settingsValues, event, keyboardCapsMode, currentKeyboardScript, handler)
        }
        if (wordComposer.isBatchMode) SwipeRecorder.onPicked(latinIME, suggestionInfo.word)
        if (useBackgroundGathering) {
            if (wordComposer.isBatchMode)
                // should only happen selecting different suggestion for gesture typed word
                BackgroundGatheringCache.onPickSuggestionAfterGesturing(suggestionInfo, wordComposer.typedWord)
            else
                BackgroundGatheringCache.onPickSuggestion(suggestionInfo, wordComposer.typedWord)
        }

        val event = Event.createSuggestionPickedEvent(suggestionInfo)
        val inputTransaction = InputTransaction(settingsValues, event,
            SystemClock.uptimeMillis(), spaceState, keyboardCapsMode)
        // Manual pick affects the contents of the editor, so we take note of this. It's important
        // for the sequence of language switching.
        inputTransaction.setDidAffectContents()
        connection.beginBatchEdit()
        insertSpaceBeforePickedSuggestionIfNeeded(settingsValues, suggestion)
        justRevertedACommit = false

        // TODO: We should not need the following branch. We should be able to take the same
        // code path as for other kinds, use commitChosenWord, and do everything normally. We will
        // however need to reset the suggestion strip right away, because we know we can't take
        // the risk of calling commitCompletion twice because we don't know how the app will react.
        if (suggestionInfo.isKindOf(SuggestedWordInfo.KIND_APP_DEFINED)) {
            suggestedWords = SuggestedWords.emptyInstance
            suggestionStripViewAccessor.setNeutralSuggestionStrip()
            inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW)
            resetComposingState(true /* alsoResetLastComposedWord */)
            connection.commitCompletion(suggestionInfo.applicationSpecifiedCompletionInfo!!)
            connection.endBatchEdit()
            return inputTransaction
        }

        extendComposingRegionOverLeadingPart(settingsValues, suggestion)
        commitChosenWord(settingsValues, suggestion, LastComposedWord.COMMIT_TYPE_MANUAL_PICK, LastComposedWord.NOT_A_SEPARATOR)
        connection.endBatchEdit()
        // Don't allow cancellation of manual pick
        lastComposedWord.deactivate()

        if (suggestionInfo.isEmoji()) {
            RecentEmojis.add(suggestion)
        }

        // Space state must be updated before calling updateShiftState
        if (settingsValues.autospaceAfterSuggestion)
            spaceState = SpaceState.PHANTOM
        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW)
        setInlineEmojiSearchAction(false)

        // If we're not showing the "Touch again to save", then update the suggestion strip.
        // That's going to be predictions (or punctuation suggestions), so INPUT_STYLE_NONE.
        handler.postUpdateSuggestionStrip(SuggestedWords.INPUT_STYLE_NONE)

        StatsUtils.onPickSuggestionManually(suggestedWords, suggestionInfo, dictionaryFacilitator)
        StatsUtils.onWordCommitSuggestionPickedManually(suggestionInfo.word, wordComposer.isBatchMode)
        return inputTransaction
    }

    /**
     * A picked word that follows another word needs the space the keyboard still owes (the
     * "phantom" space), unless the pick only replaces what is already there.
     */
    private fun insertSpaceBeforePickedSuggestionIfNeeded(settingsValues: SettingsValues, suggestion: String) {
        if (SpaceState.PHANTOM != spaceState || suggestion.isEmpty()) return
        // In the batch input mode, a manually picked suggested word should just replace
        // the current batch input text and there is no need for a phantom space.
        if (wordComposer.isBatchMode) return
        // when a commit was reverted and user chose a different suggestion, we don't want
        // to insert a space before the picked word
        if (justRevertedACommit) return
        val firstChar = Character.codePointAt(suggestion, 0)
        if (!settingsValues.isWordSeparator(firstChar) || settingsValues.isUsuallyPrecededBySpace(firstChar)) {
            insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues)
        }
    }

    /**
     * A word can extend before the composing region when it starts with characters that don't
     * start composition on their own, such as digits (e.g. "2u2j"): the leading part is
     * committed immediately, while only the rest ("u2j") is composing. Committing the picked
     * suggestion, which includes the leading part, would then duplicate it. If the picked word
     * starts with the whole word before the cursor, extend the composing region to cover it so
     * that committing replaces the whole word rather than only the composing part.
     * See <a href="https://github.com/HeliBorg/HeliBoard/issues/2611">HeliBoard issue 2611</a>.
     */
    private fun extendComposingRegionOverLeadingPart(settingsValues: SettingsValues, suggestion: String) {
        if (!wordComposer.isComposingWord || wordComposer.isBatchMode) return
        val typedWord = wordComposer.typedWord
        val cursor = connection.expectedSelectionStart
        val before = connection.getTextBeforeCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE, 0)
        if (before == null || cursor < 0 || before.length < typedWord.length) return
        // Non-separator characters committed right before the composing word still belong
        // to the same word (e.g. leading digits), so include them in the word to replace.
        var start = before.length - typedWord.length
        while (start > 0) {
            val cp = Character.codePointBefore(before, start)
            if (settingsValues.isWordSeparator(cp) || Character.isWhitespace(cp)) break
            start -= Character.charCount(cp)
        }
        val wordBeforeCursor = before.subSequence(start, before.length).toString()
        if (wordBeforeCursor.length > typedWord.length && suggestion.startsWith(wordBeforeCursor)) {
            connection.setComposingRegion(cursor - wordBeforeCursor.length, cursor)
        }
    }

    /** indicates that the next selection update is expected to be a cursor move (though not needed for arrow keys) */
    fun setExpectCursorMove() {
        cursorMoveExpectedUntil = SystemClock.elapsedRealtime() + 500
    }

    private fun mightBeExpectedCursorMove(): Boolean {
        val isMove = cursorMoveExpectedUntil > SystemClock.elapsedRealtime()
        cursorMoveExpectedUntil = 0L
        return isMove
    }

    /**
     * Consider an update to the cursor position. Evaluate whether this update has happened as
     * part of normal typing or whether it was an explicit cursor move by the user. In any case,
     * do the necessary adjustments.
     * @param oldSelStart old selection start
     * @param oldSelEnd old selection end
     * @param newSelStart new selection start
     * @param newSelEnd new selection end
     * @param settingsValues the current values of the settings.
     * @return whether the cursor has moved as a result of user interaction.
     */
    /**
     * Whether a reported cursor move ends the word being composed. It does not when the cursor only
     * moved inside that word, in which case the composer follows it.
     */
    private fun cursorLeftComposingWord(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
            newSelEnd: Int, settingsValues: SettingsValues): Boolean {
        val selectionChangedOrSafeToReset =
                oldSelStart != newSelStart || oldSelEnd != newSelEnd // selection changed
                || !wordComposer.isComposingWord // safe to reset
        val hasOrHadSelection = (oldSelStart != oldSelEnd || newSelStart != newSelEnd)
        val moveAmount = newSelStart - oldSelStart
        // As an added small gift from the framework, it happens upon rotation when there
        // is a selection that we get a wrong cursor position delivered to startInput() that
        // does not get reflected in the oldSel{Start,End} parameters to the next call to
        // onUpdateSelection. In this case, we may have set a composition, and when we're here
        // we realize we shouldn't have. In theory, in this case, selectionChangedOrSafeToReset
        // should be true, but that is if the framework had taken that wrong cursor position
        // into account, which means we have to reset the entire composing state whenever there
        // is or was a selection regardless of whether it changed or not.
        return hasOrHadSelection || !settingsValues.needsToLookupSuggestions()
                || (selectionChangedOrSafeToReset
                        && !wordComposer.moveCursorByAndReturnIfInsideComposingWord(moveAmount))
    }

    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
             newSelEnd: Int, composingSpanStart: Int, composingSpanEnd: Int, settingsValues: SettingsValues): Boolean {
        val expectCursorMove = mightBeExpectedCursorMove() // reset the timer
        if (connection.isBelatedExpectedUpdate(oldSelStart, newSelStart, oldSelEnd, newSelEnd, composingSpanStart, composingSpanEnd)) {
            // return whether we expect a user-initiated explicit cursor move (i.e. not as result of other input, but e.g. space swipe)
            // note that arrow keys are not considered, because for them isBelatedExpectedUpdate returns false
            return expectCursorMove
        }

        // if all text is gone, we treat it like onStartInput
        if (useBackgroundGathering && newSelStart == 0 && newSelEnd == 0 && !connection.hasTextAfterCursor())
            BackgroundGatheringCache.saveOrClear(latinIME)

        // TODO: the following is probably better done in resetEntireInputState().
        // it should only happen when the cursor moved, and the very purpose of the
        // test below is to narrow down whether this happened or not. Likewise with
        // the call to updateShiftState.
        // We set this to NONE because after a cursor move, we don't want the space
        // state-related special processing to kick in.
        spaceState = SpaceState.NONE

        if (cursorLeftComposingWord(oldSelStart, oldSelEnd, newSelStart, newSelEnd, settingsValues)) {
            // If we are composing a word and moving the cursor, we would want to set a
            // suggestion span for recorrection to work correctly. Unfortunately, that
            // would involve the keyboard committing some new text, which would move the
            // cursor back to where it was. Latin IME could then fix the position of the cursor
            // again, but the asynchronous nature of the calls results in this wreaking havoc
            // with selection on double tap and the like.
            // Another option would be to send suggestions each time we set the composing
            // text, but that is probably too expensive to do, so we decided to leave things
            // as is.
            // Also, we're posting a resume suggestions message, and this will update the
            // suggestions strip in a few milliseconds, so if we cleared the suggestion strip here
            // we'd have the suggestion strip noticeably janky. To avoid that, we don't clear
            // it here, which means we'll keep outdated suggestions for a split second but the
            // visual result is better.
            resetEntireInputState(newSelStart, newSelEnd, false /* clearSuggestionStrip */)
            // If the user is in the middle of correcting a word, we should learn it before moving
            // the cursor away.
            val wordBeingCorrected = wordBeingCorrectedByCursor
            if (!wordBeingCorrected.isNullOrEmpty()) {
                performAdditionToUserHistoryDictionary(settingsValues, wordBeingCorrected,
                        NgramContext.EMPTY_PREV_WORDS_INFO)
            }
        } else {
            // resetEntireInputState calls resetCachesUponCursorMove, but forcing the
            // composition to end. But in all cases where we don't reset the entire input
            // state, we still want to tell the rich input connection about the new cursor
            // position so that it can update its caches.
            connection.resetCachesUponCursorMoveAndReturnSuccess(
                    newSelStart, newSelEnd, false /* shouldFinishComposition */)
        }

        // The cursor has been moved : we now accept to perform recapitalization
        recapitalizeStatus.enable()
        // We moved the cursor. If we are touching a word, we need to resume suggestion.
        latinIME.handler.postResumeSuggestions(true /* shouldDelay */)
        // Stop the last recapitalization, if started.
        recapitalizeStatus.stop()
        wordBeingCorrectedByCursor = null
        return true
    }

    fun moveCursorByAndReturnIfInsideComposingWord(distance: Int): Boolean {
        return wordComposer.moveCursorByAndReturnIfInsideComposingWord(distance)
    }

    /**
     * React to a code input. It may be a code point to insert, or a symbolic value that influences
     * the keyboard behavior.
     * <p>
     * Typically, this is called whenever a key is pressed on the software keyboard. This is not
     * the entry point for gesture input; see the onBatchInput* family of functions for this.
     *
     * @param settingsValues the current settings values.
     * @param event the event to handle.
     * @param keyboardCapsMode the current shift mode of the keyboard, as returned by
     *     [app.caret.keyboard.KeyboardSwitcher.getKeyboardCapsMode]
     * @return the complete transaction object
     */
    fun onCodeInput(settingsValues: SettingsValues, event: Event,
            keyboardCapsMode: CapsMode, currentKeyboardScript: String, handler: LatinIME.UIHandler): InputTransaction {
        wordBeingCorrectedByCursor = null
        justRevertedACommit = false

        if (useBackgroundGathering && connection.hasSelection())
            BackgroundGatheringCache.onEditSelection(connection.getSelectedText(0), connection.getTextBeforeCursor(40, 0), connection.getTextAfterCursor(40, 0))
        if (useBackgroundGathering && wordComposer.isComposingWord && wordComposer.isCursorFrontOrMiddleOfComposingWord())
            BackgroundGatheringCache.onEditWord(wordComposer.typedWord)

        val processedEvent = wordComposer.processEvent(event)
        val inputTransaction = InputTransaction(settingsValues,
                processedEvent, SystemClock.uptimeMillis(), spaceState,
                getActualCapsMode(settingsValues, keyboardCapsMode))
        if (processedEvent.keyCode != KeyCode.DELETE
                || inputTransaction.timestamp > lastKeyTime + Constants.LONG_PRESS_MILLISECONDS) {
            deleteCount = 0
        }
        lastKeyTime = inputTransaction.timestamp
        connection.beginBatchEdit()
        if (!wordComposer.isComposingWord) {
            // TODO: is this useful? It doesn't look like it should be done here, but rather after
            // a word is committed.
            isAutoCorrectionIndicatorOn = false
        }

        // TODO: Consolidate the double-space period timer, lastKeyTime, and the space state.
        if (processedEvent.codePoint != Constants.CODE_SPACE) {
            cancelDoubleSpacePeriodCountdown()
        }

        var currentEvent: Event? = processedEvent
        while (null != currentEvent) {
            if (currentEvent.isConsumed) {
                handleConsumedEvent(currentEvent, inputTransaction)
            } else if (currentEvent.isFunctionalKeyEvent) {
                handleFunctionalEvent(currentEvent, inputTransaction, currentKeyboardScript, handler)
            } else {
                handleNonFunctionalEvent(currentEvent, inputTransaction, handler)
            }
            currentEvent = currentEvent.nextEvent
        }
        // Try to record the word being corrected when the user enters a word character or
        // the backspace key.
        if (!connection.hasSlowInputConnection() && !wordComposer.isComposingWord
                && (settingsValues.isWordCodePoint(processedEvent.codePoint)
                    || processedEvent.keyCode == KeyCode.DELETE)
                ) {
            wordBeingCorrectedByCursor = getWordAtCursor(settingsValues, currentKeyboardScript)
        }
        if (!inputTransaction.didAutoCorrect() && processedEvent.keyCode != KeyCode.SHIFT
                && processedEvent.keyCode != KeyCode.CAPS_LOCK
                && processedEvent.keyCode != KeyCode.SYMBOL_ALPHA
                && processedEvent.keyCode != KeyCode.ALPHA
                && processedEvent.keyCode != KeyCode.SYMBOL)
            lastComposedWord.deactivate()
        if (KeyCode.DELETE != processedEvent.keyCode) {
            enteredText = null
        }
        connection.endBatchEdit()
        return inputTransaction
    }

    fun onStartBatchInput(settingsValues: SettingsValues,
            keyboardSwitcher: KeyboardSwitcher, handler: LatinIME.UIHandler) {
        wordBeingCorrectedByCursor = null
        inputLogicHandler.onStartBatchInput()
        handler.showGesturePreviewAndSetSuggestions(SuggestedWords.emptyBatchInstance, false)
        handler.cancelUpdateSuggestionStrip()
        ++autoCommitSequenceNumber

        if (useBackgroundGathering && connection.hasSelection())
            BackgroundGatheringCache.onEditSelection(connection.getSelectedText(0), connection.getTextBeforeCursor(40, 0), connection.getTextAfterCursor(40, 0))
        if (useBackgroundGathering && wordComposer.isComposingWord && wordComposer.isCursorFrontOrMiddleOfComposingWord())
            BackgroundGatheringCache.onEditWord(wordComposer.typedWord)

        connection.beginBatchEdit()
        finishTextBeforeSwipe(settingsValues, handler)
        val codePointBeforeCursor = connection.getCodePointBeforeCursor()
        if (Character.isLetterOrDigit(codePointBeforeCursor)
                || settingsValues.isUsuallyFollowedBySpace(codePointBeforeCursor)) {
            oweSpaceBeforeSwipe(settingsValues, keyboardSwitcher)
        }
        connection.endBatchEdit()
        wordComposer.setCapitalizedModeAtStartComposingTime(
                getActualCapsMode(settingsValues, keyboardSwitcher.getKeyboardCapsMode()))
    }

    /** A swipe starts a new word: what was being typed is committed, a selection will be replaced. */
    private fun finishTextBeforeSwipe(settingsValues: SettingsValues, handler: LatinIME.UIHandler) {
        if (wordComposer.isComposingWord) {
            if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                // If we are in the middle of a recorrection, we need to commit the recorrection
                // first so that we can insert the batch input at the current cursor position.
                // We also need to unlearn the original word that is now being corrected.
                unlearnWord(wordComposer.typedWord, settingsValues, DictionaryFacilitator.UnlearnEvent.BACKSPACE)
                resetEntireInputState(connection.expectedSelectionStart, connection.expectedSelectionEnd, true)
            } else if (wordComposer.isSingleLetter && !isInlineEmojiSearchAction()) {
                // We auto-correct the previous (typed, not gestured) string iff it's one character
                // long. The reason for this is, even in the middle of gesture typing, you'll still
                // tap one-letter words and you want them auto-corrected (typically, "i" in English
                // should become "I"). However for any longer word, we assume that the reason for
                // tapping probably is that the word you intend to type is not in the dictionary,
                // so we do not attempt to correct, on the assumption that if that was a dictionary
                // word, the user would probably have gestured instead.
                commitCurrentAutoCorrection(settingsValues, LastComposedWord.NOT_A_SEPARATOR,
                        handler)
            } else {
                commitTyped(settingsValues, LastComposedWord.NOT_A_SEPARATOR)
            }
        } else if (connection.hasSelection()) {
            val selectedText = connection.getSelectedText(0)
            if (selectedText != null)
                // set selected text as rejected to avoid glide typing resulting in exactly the selected word again
                wordComposer.rejectedBatchModeSuggestion = selectedText.toString()
        }
    }

    /**
     * The swiped word follows a letter, digit or punctuation: note that a space is owed before it,
     * and let the shift state follow, since the owed space can mean a new sentence.
     */
    private fun oweSpaceBeforeSwipe(settingsValues: SettingsValues, keyboardSwitcher: KeyboardSwitcher) {
        // autoShiftHasBeenOverridden is weird
        // before switching CapsMode to enum, it was CapsMode != autoCapsState
        // autoCapsState is 0 (off), 0x1000, 0x2000, 0x4000 or a combination
        // old CapsMode was 0 (off), 1, 3, 5, 7
        // meaning both were incompatible, and the check was just returning whether both were 0
        // todo: maybe adjust this?
        val autoShiftHasBeenOverridden =
            (keyboardSwitcher.getKeyboardCapsMode() == CapsMode.OFF) != (getCurrentAutoCapsState(settingsValues) == 0)
        if (settingsValues.autospaceBeforeGestureTyping)
            spaceState = SpaceState.PHANTOM // influences autoCapsState
        if (!autoShiftHasBeenOverridden) {
            // When we change the space state, we need to update the shift state of the
            // keyboard unless it has been overridden manually. This is happening for example
            // after typing some letters and a period, then gesturing; the keyboard is not in
            // caps mode yet, but since a gesture is starting, it should go in caps mode,
            // unless the user explicitly said it should not.
            keyboardSwitcher.updateShiftState(getCurrentAutoCapsState(settingsValues), getCurrentRecapitalizeState())
        }
    }

    /* The sequence number member is only used in onUpdateBatchInput. It is increased each time
     * auto-commit happens. The reason we need this is, when auto-commit happens we trim the
     * input pointers that are held in a singleton, and to know how much to trim we rely on the
     * results of the suggestion process that is held in suggestedWords.
     * However, the suggestion process is asynchronous, and sometimes we may enter the
     * onUpdateBatchInput method twice without having recomputed suggestions yet, or having
     * received new suggestions generated from not-yet-trimmed input pointers. In this case, the
     * mIndexOfTouchPointOfSecondWords member will be out of date, and we must not use it lest we
     * remove an unrelated number of pointers (possibly even more than are left in the input
     * pointers, leading to a crash).
     * To avoid that, we increase the sequence number each time we auto-commit and trim the
     * input pointers, and we do not use any suggested words that have been generated with an
     * earlier sequence number.
     */
    fun onUpdateBatchInput(batchPointers: InputPointers) {
        inputLogicHandler.onUpdateBatchInput(batchPointers, autoCommitSequenceNumber)
    }

    fun onEndBatchInput(batchPointers: InputPointers) {
        inputLogicHandler.updateTailBatchInput(batchPointers, autoCommitSequenceNumber)
        ++autoCommitSequenceNumber
    }

    fun onCancelBatchInput(handler: LatinIME.UIHandler) {
        inputLogicHandler.onCancelBatchInput()
        handler.showGesturePreviewAndSetSuggestions(
                SuggestedWords.emptyInstance, true /* dismissGestureFloatingPreviewText */)
    }

    // TODO: on the long term, this method should become private, but it will be difficult.
    // Especially, how do we deal with InputMethodService.onDisplayCompletions?
    fun setSuggestedWords(suggestedWords: SuggestedWords) {
        if (!suggestedWords.isEmpty) {
            val suggestedWordInfo: SuggestedWordInfo?
            if (suggestedWords.willAutoCorrect) {
                suggestedWordInfo = suggestedWords.getInfo(SuggestedWords.INDEX_OF_AUTO_CORRECTION)
            } else {
                // We can't use suggestedWords.getWord(SuggestedWords.INDEX_OF_TYPED_WORD)
                // because it may differ from wordComposer.mTypedWord.
                suggestedWordInfo = suggestedWords.typedWordInfo
            }
            wordComposer.setAutoCorrection(suggestedWordInfo)
        }
        this.suggestedWords = suggestedWords
        val newAutoCorrectionIndicator = suggestedWords.willAutoCorrect

        // Put a blue underline to a word in TextView which will be auto-corrected.
        if (isAutoCorrectionIndicatorOn != newAutoCorrectionIndicator && wordComposer.isComposingWord) {
            isAutoCorrectionIndicatorOn = newAutoCorrectionIndicator
            val textWithUnderline = getTextWithUnderline(wordComposer.typedWord)
            // TODO: when called from an updateSuggestionStrip() call that results from a posted
            // message, this is called outside any batch edit. Potentially, this may result in some
            // janky flickering of the screen, although the display speed makes it unlikely in
            // the practice.
            setComposingTextInternal(textWithUnderline, 1)
        }
    }

    /**
     * Handle a consumed event.
     *
     * Consumed events represent events that have already been consumed, typically by the
     * combining chain.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private fun handleConsumedEvent(event: Event, inputTransaction: InputTransaction) {
        // A consumed event may have text to commit and an update to the composing state, so
        // we evaluate both. With some combiners, it's possible than an event contains both
        // and we enter both of the following if clauses.
        val textToCommit = event.textToCommit
        if (!TextUtils.isEmpty(textToCommit)) {
            connection.commitText(textToCommit, 1)
            inputTransaction.setDidAffectContents()
        }
        if (wordComposer.isComposingWord) {
            // Khipro auto-space after suggestion: when user picks a suggestion and starts composing the next word,
            // insert space automatically, but skip it if the next character is punctuation (. , ; : ! ?) or word connector.
            val codePoint = event.codePoint
            val settingsValues = inputTransaction.settingsValues
            if (SpaceState.PHANTOM == inputTransaction.spaceState
                    && "bn_khipro" == wordComposer.combiningSpec
                    && !settingsValues.isWordConnector(codePoint)
                    && !settingsValues.isUsuallyFollowedBySpace(codePoint)) {
                insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues)
                spaceState = SpaceState.NONE
            }
            setComposingTextInternal(wordComposer.typedWord, 1)
            inputTransaction.setDidAffectContents()
            inputTransaction.setRequiresUpdateSuggestions()
        }
    }

    /**
     * Handle a functional key event.
     *
     * A functional event is a special key, like delete, shift, emoji, or the settings key.
     * Non-special keys are those that generate a single code point.
     * This includes all letters, digits, punctuation, separators, emoji. It excludes keys that
     * manage keyboard-related stuff like shift, language switch, settings, layout switch, or
     * any key that results in multiple code points like the ".com" key.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private fun handleFunctionalEvent(event: Event, inputTransaction: InputTransaction, currentKeyboardScript: String, handler: LatinIME.UIHandler) {
        val keyCode = event.keyCode
        val sv = inputTransaction.settingsValues
        if (sv.isLocked && KeyCode.isIsBlockedWhenLocked(keyCode)) {
            Log.w(TAG, "Blocked keycode while device was locked, this should not happen")
        }
        when (keyCode) {
            KeyCode.DELETE -> {
                handleBackspaceEvent(event, inputTransaction, currentKeyboardScript)
                // Backspace is a functional key, but it affects the contents of the editor.
                inputTransaction.setDidAffectContents()
            }
            KeyCode.SHIFT ->
                handleShiftKey(inputTransaction)
            KeyCode.CAPS_LOCK -> {
                val keyboard = KeyboardSwitcher.getInstance().keyboard
                if (keyboard == null || keyboard.id.element.isAlphabet)
                    inputTransaction.setRequiresUpdateSuggestions()
            }
            KeyCode.SETTINGS ->
                onSettingsKeyPressed()
            KeyCode.ACTION_NEXT ->
                performEditorAction(EditorInfo.IME_ACTION_NEXT)
            KeyCode.ACTION_PREVIOUS ->
                performEditorAction(EditorInfo.IME_ACTION_PREVIOUS)
            KeyCode.LANGUAGE_SWITCH ->
                handleLanguageSwitchKey()
            KeyCode.CLIPBOARD -> {
                // Note: If clipboard history is enabled, switching to clipboard keyboard
                // is being handled in {@link KeyboardState#onEvent(Event,int)}.
                // If disabled, current clipboard content is committed.
                if (!sv.clipboardHistoryEnabled) {
                    paste(latinIME.getCurrentInputEditorInfo().packageName)
                }
            }
            KeyCode.CLIPBOARD_PASTE ->
                paste(latinIME.getCurrentInputEditorInfo().packageName)
            KeyCode.SHIFT_ENTER -> {
                // todo: try using sendDownUpKeyEventWithMetaState() and remove the key code maybe
                val tmpEvent = Event.createSoftwareKeypressEvent(Constants.CODE_ENTER,
                        keyCode, 0, event.x, event.y, event.isKeyRepeat)
                handleNonSpecialCharacterEvent(tmpEvent, inputTransaction, handler)
                // Shift + Enter is treated as a functional key but it results in adding a new
                // line, so that does affect the contents of the editor.
                inputTransaction.setDidAffectContents()
            }
            KeyCode.MULTIPLE_CODE_POINTS ->
                // added in the hangul branch, createEventChainFromSequence
                // this introduces issues like space being added behind cursor, or input deleting
                // a word, but the keepCursorPosition applyProcessedEvent seems to help here
                wordComposer.applyProcessedEvent(event, true)
            KeyCode.CLIPBOARD_SELECT_ALL ->
                connection.selectAll()
            KeyCode.CLIPBOARD_SELECT_WORD ->
                connection.selectWord(sv.spacingAndPunctuations, currentKeyboardScript)
            KeyCode.CLIPBOARD_COPY ->
                connection.copyText(true)
            KeyCode.CLIPBOARD_COPY_ALL ->
                connection.copyText(false)
            KeyCode.CLIPBOARD_CLEAR_HISTORY ->
                latinIME.clipboardHistoryManager.clearHistory()
            KeyCode.CLIPBOARD_CUT ->
                cutSelection(event, inputTransaction, currentKeyboardScript)
            KeyCode.WORD_LEFT ->
                sendDownUpKeyEventWithMetaState(
                    if (ScriptUtils.isScriptRtl(currentKeyboardScript)) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.META_CTRL_ON or event.metaState)
            KeyCode.WORD_RIGHT ->
                sendDownUpKeyEventWithMetaState(
                    if (ScriptUtils.isScriptRtl(currentKeyboardScript)) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.META_CTRL_ON or event.metaState)
            KeyCode.MOVE_START_OF_PAGE ->
                moveToStartOfPage(event)
            KeyCode.MOVE_END_OF_PAGE ->
                moveToEndOfPage(event)
            KeyCode.UNDO -> {
                if (useBackgroundGathering)
                    BackgroundGatheringCache.onUndo(if (wordComposer.isComposingWord) wordComposer.typedWord else lastComposedWord.committedWord)
                sendDownUpKeyEventWithMetaState(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            }
            KeyCode.REDO ->
                sendDownUpKeyEventWithMetaState(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            KeyCode.SPLIT_LAYOUT ->
                KeyboardSwitcher.getInstance().toggleSplitKeyboardMode()
            KeyCode.TIMESTAMP ->
                latinIME.onTextInput(getTimestamp(latinIME))
            KeyCode.EMOJI_SEARCH -> {
                commitTyped(sv, LastComposedWord.NOT_A_SEPARATOR)
                latinIME.launchEmojiSearch()
            }
            KeyCode.SEND_INTENT_ONE, KeyCode.SEND_INTENT_TWO, KeyCode.SEND_INTENT_THREE -> {
                IntentUtils.handleSendIntentKey(latinIME, event.keyCode)
                // no break: the keyboard hides after sending an intent, it has been so since the keys exist
                // (Java fell through into the IME_HIDE_UI case here)
                latinIME.requestHideSelf(0)
            }
            KeyCode.IME_HIDE_UI ->
                latinIME.requestHideSelf(0)
            KeyCode.INLINE_EMOJI_SEARCH_DONE -> {
                setInlineEmojiSearchAction(false)
                inputTransaction.setRequiresUpdateSuggestions()
            }
            KeyCode.SYSTEM_INPUT_METHOD_PICKER ->
                latinIME.showInputPickerDialog()
            KeyCode.VOICE_INPUT,
                // switching to shortcut IME, shift state, keyboard,... is handled by LatinIME,
                // {@link KeyboardSwitcher#onEvent(Event)}, or {@link #onPressKey(int,int,boolean)} and {@link #onReleaseKey(int,boolean)}.
                // We need to switch to the shortcut IME. This is handled by LatinIME since the
                // input logic has no business with IME switching.
            KeyCode.EMOJI, KeyCode.TOGGLE_ONE_HANDED_MODE, KeyCode.SWITCH_ONE_HANDED_MODE, KeyCode.TOGGLE_FLOATING_WINDOW,
            KeyCode.KEY_REPEAT -> { // can be configured on main layout using !code/-11000, and we shouldn't crash on this in debug mode
            }
            else ->
                handleOtherFunctionalKey(event, inputTransaction, handler)
        }
    }

    /** Shift on the letters or the d-pad: change the capitalization of the word or selection at the cursor. */
    private fun handleShiftKey(inputTransaction: InputTransaction) {
        val sv = inputTransaction.settingsValues
        val keyboard = KeyboardSwitcher.getInstance().keyboard
        if (keyboard != null) {
            val element = keyboard.id.element
            if (!element.isAlphabet && element != KeyboardElement.DPAD) {
                // recapitalization and follow-up code should only trigger for alphabet/d-pad shift, see #1256
                return
            }
        }
        performRecapitalization(sv)
        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW)
        inputTransaction.setRequiresUpdateSuggestions()
        if (spaceState == SpaceState.PHANTOM && sv.shiftRemovesAutospace)
            spaceState = SpaceState.NONE
    }

    /** Cut: copy the selection, then remove it the way backspace would. */
    private fun cutSelection(event: Event, inputTransaction: InputTransaction, currentKeyboardScript: String) {
        if (!connection.hasSelection()) return
        connection.copyText(true)
        // fake delete keypress to remove the text
        val backspaceEvent = Event.createSoftwareKeypressEvent(KeyCode.DELETE, 0,
                event.x, event.y, event.isKeyRepeat)
        handleBackspaceEvent(backspaceEvent, inputTransaction, currentKeyboardScript)
        inputTransaction.setDidAffectContents()
    }

    private fun moveToStartOfPage(event: Event) {
        val selectionEnd = connection.expectedSelectionEnd
        val selectionStart = connection.expectedSelectionStart
        sendDownUpKeyEventWithMetaState(KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_CTRL_ON or event.metaState)
        if (connection.expectedSelectionStart == selectionStart && connection.expectedSelectionEnd == selectionEnd) {
            // unchanged -> try a different method (necessary for compose fields)
            val newEnd = if ((event.metaState and KeyEvent.META_SHIFT_MASK) != 0) selectionEnd else 0
            // Caret: let go of the word being composed first, or the next letter is
            // added to that word at its old place instead of at the cursor
            finishInput()
            connection.setSelection(0, newEnd)
        }
    }

    private fun moveToEndOfPage(event: Event) {
        val selectionStart = connection.expectedSelectionStart
        val selectionEnd = connection.expectedSelectionEnd
        sendDownUpKeyEventWithMetaState(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON or event.metaState)
        if (connection.expectedSelectionStart == selectionStart && connection.expectedSelectionEnd == selectionEnd) {
            // unchanged, try fallback e.g. for compose fields that don't care about ctrl + end
            // we just move to a very large index, and hope the field is prepared to deal with this
            // getting the actual length of the text for setting the correct position can be tricky for some apps...
            try {
                val newStart = if ((event.metaState and KeyEvent.META_SHIFT_MASK) != 0) selectionStart else Int.MAX_VALUE
                finishInput() // Caret: as for the start of the page
                connection.setSelection(newStart, Int.MAX_VALUE)
            } catch (e: Exception) {
                // better catch potential errors and just do nothing in this case
                Log.i(TAG, "error when trying to move cursor to last position: " + e)
            }
        }
    }

    /** A function key with no case of its own: send it as a key event if there is one for it. */
    private fun handleOtherFunctionalKey(event: Event, inputTransaction: InputTransaction,
            handler: LatinIME.UIHandler) {
        val keyCode = event.keyCode
        if (keyCode.isModifier())
            return // modifiers are held in a separate place
        val keyEventCode = if (keyCode > 0)
            keyCode
        else if (event.codePoint >= 0)
            KeyCode.codePointToKeyEventCode(event.codePoint)
        else
            KeyCode.keyCodeToKeyEventCode(keyCode)
        if (keyEventCode != KeyEvent.KEYCODE_UNKNOWN) {
            sendDownUpKeyEventWithMetaState(keyEventCode, event.metaState)
            return
        }
        if (event.metaState != 0 && event.codePoint >= 32) {
            // Try handling it as normal key event, this essentially just ignore the meta state.
            // The conversion is good, as this way we are able to use e.g. ctrl + V. But if we don't have a codePointToKeyEventCode
            // for that key, we will just input the normal key now, e.g. ctrl + @ will (probably) do nothing, but ctrl + _ will input _
            // Maybe we should just return instead?
            handleNonFunctionalEvent(event, inputTransaction, handler)
            return
        }
        // unknown event
        Log.e(TAG, "unknown event, key code: "+keyCode+", codepoint "+event.codePoint+", meta: "+event.metaState)
        if (DebugFlags.DEBUG_ENABLED)
            throw RuntimeException("Unknown event")
    }

    /**
     * Handle an event that is not a functional event.
     * <p>
     * These events are generally events that cause input, but in some cases they may do other
     * things like trigger an editor action.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private fun handleNonFunctionalEvent(event: Event, inputTransaction: InputTransaction, handler: LatinIME.UIHandler) {
        inputTransaction.setDidAffectContents()
        if (event.codePoint == Constants.CODE_ENTER) {
            val editorInfo = currentInputEditorInfo
            val imeOptionsActionId = InputTypeUtils.getImeOptionsActionIdFromEditorInfo(editorInfo)
            if (InputTypeUtils.IME_ACTION_CUSTOM_LABEL == imeOptionsActionId) {
                // Either we have an actionLabel and we should performEditorAction with
                // actionId regardless of its value.
                performEditorAction(editorInfo!!.actionId)
            } else if (EditorInfo.IME_ACTION_NONE != imeOptionsActionId) {
                // We didn't have an actionLabel, but we had another action to execute.
                // EditorInfo.IME_ACTION_NONE explicitly means no action. In contrast,
                // EditorInfo.IME_ACTION_UNSPECIFIED is the default value for an action, so it
                // means there should be an action and the app didn't bother to set a specific
                // code for it - presumably it only handles one. It does not have to be treated
                // in any specific way: anything that is not IME_ACTION_NONE should be sent to
                // performEditorAction.
                performEditorAction(imeOptionsActionId)
            } else {
                // No action label, and the action from imeOptions is NONE: this is a regular
                // enter key that should input a carriage return.
                handleNonSpecialCharacterEvent(event, inputTransaction, handler)
            }
        } else {
            handleNonSpecialCharacterEvent(event, inputTransaction, handler)
        }
    }

    /**
     * Handle inputting a code point to the editor.
     * <p>
     * Non-special keys are those that generate a single code point.
     * This includes all letters, digits, punctuation, separators, emoji. It excludes keys that
     * manage keyboard-related stuff like shift, language switch, settings, layout switch, or
     * any key that results in multiple code points like the ".com" key.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private fun handleNonSpecialCharacterEvent(
        event: Event,
        inputTransaction: InputTransaction,
        handler: LatinIME.UIHandler
    ) {
        val codePoint = event.codePoint
        spaceState = SpaceState.NONE
        val sv = inputTransaction.settingsValues

        // wrap / unwrap selected text in codepoint pairs
        if (!wordComposer.isComposingWord && connection.hasSelection()) { // we should never be composing when something is selected
            val pairedCodepoint = sv.spacingAndPunctuations.getSecondInSymbolPair(codePoint)
            if (pairedCodepoint != Constants.NOT_A_CODE) {
                wrapSelection(codePoint, pairedCodepoint)
                inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW)
                return
            }
        }

        if (actsAsSeparatorHere(codePoint, sv)) {
            handleSeparatorEvent(event, inputTransaction, handler)
            addToHistoryIfEmoji(StringUtils.newSingleCodePointString(codePoint), sv)
        } else {
            if (SpaceState.PHANTOM == inputTransaction.spaceState) {
                if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                    // If we are in the middle of a recorrection, we need to commit the recorrection
                    // first so that we can insert the character at the current cursor position.
                    // We also need to unlearn the original word that is now being corrected.
                    unlearnWord(wordComposer.typedWord, sv, DictionaryFacilitator.UnlearnEvent.BACKSPACE)
                    resetEntireInputState(connection.expectedSelectionStart,
                            connection.expectedSelectionEnd, true /* clearSuggestionStrip */)
                } else {
                    commitTyped(sv, LastComposedWord.NOT_A_SEPARATOR)
                }
            }
            handleNonSeparatorEvent(event, sv, inputTransaction)
        }
    }

    /**
     * Handle a non-separator.
     * @param event The event to handle.
     * @param settingsValues The current settings values.
     * @param inputTransaction The transaction in progress.
     */
    /**
     * Whether this character ends a word where the cursor is. Symbols and emoji always do. A word
     * separator does too, except inside something that looks like a URL or similar, where
     * separators are part of the text: otherwise the word would not be selected until the next
     * character is entered, the changing selection would be confusing, and partial URLs would be
     * added to the history.
     */
    private fun actsAsSeparatorHere(codePoint: Int, sv: SettingsValues): Boolean {
        if (Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()) return true
        // outdated java doesn't detect some emojis
        if (Character.getType(codePoint) == Character.UNASSIGNED.toInt() && StringUtils.mightBeEmoji(codePoint)) return true
        if (!sv.isWordSeparator(codePoint)) return false
        return Character.isWhitespace(codePoint) // whitespace is always a separator
                || !textBeforeCursorMayBeUrlOrSimilar(sv, false) // if text before is not URL or similar, it's a separator
                || (codePoint == '/'.code && wordComposer.lastChar() == '/') // break composing at 2 consecutive slashes
    }

    private fun handleNonSeparatorEvent(
        event: Event, settingsValues: SettingsValues,
        inputTransaction: InputTransaction
    ) {
        val codePoint = event.codePoint
        var isComposingWord = wordComposer.isComposingWord
        wordComposer.unsetBatchMode() // relevant in case we continue a batch word with normal typing

        if (!isComposingWord && continuesWordAfterConnector(settingsValues, inputTransaction)) {
            val text = connection.textBeforeCursorUntilLastWhitespaceOrDoubleSlash()
            val range = TextRange(text, 0, text.length, text.length, false)
            isComposingWord = true
            restartSuggestions(range)
        }
        // TODO: remove isWordConnector() and use isUsuallyFollowedBySpace() instead.
        // See onStartBatchInput() to see how to do it.
        if (SpaceState.PHANTOM == inputTransaction.spaceState
                && !settingsValues.isWordConnector(codePoint)
                && !settingsValues.isUsuallyFollowedBySpace(codePoint) // only relevant in rare cases
        ) {
            if (isComposingWord) {
                // Sanity check
                throw RuntimeException("Should not be composing here")
            }
            insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues)
        }

        if (wordComposer.isCursorInFrontOfComposingWord) {
            // we add something in front of the composing word, this is likely for adding something
            // and not for a correction
            // keep composing and don't unlearn word in this case
            resetEntireInputState(connection.expectedSelectionStart, connection.expectedSelectionEnd, false)
            isComposingWord = false
        } else if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
            // If we are in the middle of a recorrection, we need to commit the recorrection
            // first so that we can insert the character at the current cursor position.
            // We also need to unlearn the original word that is now being corrected.
            unlearnWord(wordComposer.typedWord, inputTransaction.settingsValues, DictionaryFacilitator.UnlearnEvent.BACKSPACE)
            resetEntireInputState(connection.expectedSelectionStart, connection.expectedSelectionEnd, true)
            isComposingWord = false
        }
        // We only start composing if we're not already composing.
        if (!isComposingWord && mayStartComposingWith(codePoint, settingsValues)) {
            // Reset entirely the composing state anyway, then start composing a new word unless
            // the character is a word connector. The idea here is, word connectors are not
            // separators and they should be treated as normal characters, except in the first
            // position where they should not start composing a word.
            isComposingWord = !settingsValues.spacingAndPunctuations.isWordConnector(codePoint)
            // Here we don't need to reset the last composed word. It will be reset
            // when we commit this one, if we ever do; if on the other hand we backspace
            // it entirely and resume suggestions on the previous word, we'd like to still
            // have touch coordinates for it.
            resetComposingState(false /* alsoResetLastComposedWord */)
        }

        enterInlineEmojiSearchIfNeeded(codePoint, settingsValues)

        if (isComposingWord) {
            wordComposer.applyProcessedEvent(event)
            // If it's the first letter, make note of auto-caps state
            if (wordComposer.isSingleLetter) {
                wordComposer.setCapitalizedModeAtStartComposingTime(inputTransaction.shiftState)
            }
            setComposingTextInternal(getTextWithUnderline(wordComposer.typedWord), 1)
        } else {
            commitCharacterOutsideWord(event, settingsValues, inputTransaction)
        }
        inputTransaction.setRequiresUpdateSuggestions()
    }

    /**
     * Whether a character typed right after a "sometimes word connector" (a period inside a URL, for
     * example) continues the word in front of it, so that suggestions restart for the whole thing.
     * Only with URL detection and suggestions enabled.
     */
    private fun continuesWordAfterConnector(settingsValues: SettingsValues, inputTransaction: InputTransaction): Boolean {
        return settingsValues.urlDetectionEnabled && settingsValues.needsToLookupSuggestions()
                && SpaceState.NONE == inputTransaction.spaceState
                && settingsValues.spacingAndPunctuations.isSometimesWordConnector(connection.getCodePointBeforeCursor())
                // but not if there are two consecutive sometimesWordConnectors (e.g. "...bla")
                && !settingsValues.spacingAndPunctuations.isSometimesWordConnector(connection.getCharBeforeBeforeCursor())
                // and not if there is no letter before the separator
                && connection.hasLetterBeforeLastSpaceBeforeCursor()
    }

    /**
     * Whether a new word may start composing with this character. The order of the tests is
     * important for good performance.
     */
    private fun mayStartComposingWith(codePoint: Int, settingsValues: SettingsValues): Boolean {
        // We only start composing if this is a word code point. Essentially that means it's a
        // a letter or a word connector.
        if (!settingsValues.isWordCodePoint(codePoint)) return false
        // We never go into composing state if suggestions are not requested.
        if (!settingsValues.needsToLookupSuggestions()) return false
        // In languages with spaces, we only start composing a word when we are not already
        // in the middle or at the end of a word. In languages without spaces, the above conditions are sufficient.
        // NOTE: If the InputConnection is slow, we skip the text-after-cursor check since it
        // can incur a very expensive getTextAfterCursor() lookup, potentially making the
        // keyboard UI slow and non-responsive.
        // TODO: Cache the text after the cursor so we don't need to go to the InputConnection
        // each time. We are already doing this for getTextBeforeCursor().
        return !settingsValues.spacingAndPunctuations.currentLanguageHasSpaces
                || !connection.isCursorTouchingWord(settingsValues.spacingAndPunctuations,
                        !connection.hasSlowInputConnection() /* checkTextAfter */)
                || isCursorAtStartOrAfterSeparator(settingsValues)
    }

    /** A character that is not part of a composing word goes straight into the text field. */
    private fun commitCharacterOutsideWord(
        event: Event, settingsValues: SettingsValues,
        inputTransaction: InputTransaction
    ) {
        val codePoint = event.codePoint
        val swapWeakSpace = tryStripSpaceAndReturnWhetherShouldSwapInstead(event, inputTransaction)

        if (swapWeakSpace && trySwapSwapperAndSpace(event, inputTransaction)) {
            spaceState = SpaceState.WEAK
        } else if ((settingsValues.inputAttributes.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                && codePoint >= '0'.code && codePoint <= '9'.code) {
            // weird issue when committing text: https://github.com/HeliBorg/HeliBoard/issues/585
            // but at the same time we don't always want to do it for numbers because it might interfere with url detection
            // todo: consider always using sendDownUpKeyEvent for non-text-inputType
            sendDownUpKeyEvent(codePoint - '0'.code + KeyEvent.KEYCODE_0)
        } else {
            connection.commitCodePoint(codePoint)
        }
    }

    private fun isCursorAtStartOrAfterSeparator(settingsValues: SettingsValues): Boolean {
        val codePointBeforeCursor = connection.getCodePointBeforeCursor()
        return codePointBeforeCursor == Constants.NOT_A_CODE
                || settingsValues.spacingAndPunctuations.isWordSeparator(codePointBeforeCursor)
    }

    /**
     * Handle input of a separator code point.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private fun handleSeparatorEvent(
        event: Event, inputTransaction: InputTransaction,
        handler: LatinIME.UIHandler
    ) {
        val codePoint = event.codePoint
        val settingsValues = inputTransaction.settingsValues
        val wasComposingWord = wordComposer.isComposingWord
        // We avoid sending spaces in languages without spaces if we were composing.
        val shouldAvoidSendingCode = Constants.CODE_SPACE == codePoint
                && !settingsValues.spacingAndPunctuations.currentLanguageHasSpaces
                && wasComposingWord

        if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
            // If we are in the middle of a recorrection, we need to commit the recorrection
            // first so that we can insert the separator at the current cursor position.
            // We also need to unlearn the original word that is now being corrected.
            unlearnWord(wordComposer.typedWord, settingsValues, DictionaryFacilitator.UnlearnEvent.BACKSPACE)
            resetEntireInputState(connection.expectedSelectionStart,
                    connection.expectedSelectionEnd, true /* clearSuggestionStrip */)
        }
        // isComposingWord() may have changed since we stored wasComposing
        if (wordComposer.isComposingWord) {
            commitComposingWordBeforeSeparator(codePoint, shouldAvoidSendingCode, inputTransaction, handler)
        }

        val swapWeakSpace = tryStripSpaceAndReturnWhetherShouldSwapInstead(event, inputTransaction)

        val isInsideDoubleQuoteOrAfterDigit = Constants.CODE_DOUBLE_QUOTE == codePoint
                && connection.isInsideDoubleQuoteOrAfterDigit()

        if (needsSpaceBeforeSeparator(codePoint, inputTransaction, isInsideDoubleQuoteOrAfterDigit)) {
            insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues)
        }

        if (tryPerformDoubleSpacePeriod(event, inputTransaction)) {
            spaceState = SpaceState.DOUBLE
            inputTransaction.setRequiresUpdateSuggestions()
            StatsUtils.onDoubleSpacePeriod()
        } else if (swapWeakSpace && trySwapSwapperAndSpace(event, inputTransaction)) {
            spaceState = SpaceState.SWAP_PUNCTUATION
            suggestionStripViewAccessor.setNeutralSuggestionStrip()
        } else if (Constants.CODE_SPACE == codePoint) {
            commitSpace(inputTransaction, wasComposingWord, shouldAvoidSendingCode)
        } else {
            commitSeparatorOtherThanSpace(codePoint, inputTransaction, wasComposingWord, isInsideDoubleQuoteOrAfterDigit)
        }

        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW)
    }

    /** A separator ends the word being composed: commit it, autocorrected if that is on. */
    private fun commitComposingWordBeforeSeparator(
        codePoint: Int, shouldAvoidSendingCode: Boolean,
        inputTransaction: InputTransaction, handler: LatinIME.UIHandler
    ) {
        val settingsValues = inputTransaction.settingsValues
        if (settingsValues.autoCorrectEnabled && !isInlineEmojiSearchAction()) {
            val separator = if (shouldAvoidSendingCode) LastComposedWord.NOT_A_SEPARATOR
                    else StringUtils.newSingleCodePointString(codePoint)
            commitCurrentAutoCorrection(settingsValues, separator, handler)
            inputTransaction.setDidAutoCorrect()
        } else {
            commitTyped(settingsValues, StringUtils.newSingleCodePointString(codePoint))
        }
    }

    /** Whether the space the keyboard still owes (phantom space) has to go in before this separator. */
    private fun needsSpaceBeforeSeparator(
        codePoint: Int, inputTransaction: InputTransaction,
        isInsideDoubleQuoteOrAfterDigit: Boolean
    ): Boolean {
        val settingsValues = inputTransaction.settingsValues
        if (SpaceState.PHANTOM != inputTransaction.spaceState) {
            return false
        }
        if (Constants.CODE_DOUBLE_QUOTE == codePoint) {
            // Double quotes behave like they are usually preceded by space iff we are
            // not inside a double quote or after a digit.
            return !isInsideDoubleQuoteOrAfterDigit
        }
        if (settingsValues.spacingAndPunctuations.isClusteringSymbol(codePoint)
                && settingsValues.spacingAndPunctuations.isClusteringSymbol(connection.getCodePointBeforeCursor())) {
            return false
        }
        return settingsValues.isUsuallyPrecededBySpace(codePoint) || isEmoji(codePoint)
    }

    /** A plain space: remember it may be swapped or doubled, and put it in. */
    private fun commitSpace(
        inputTransaction: InputTransaction, wasComposingWord: Boolean,
        shouldAvoidSendingCode: Boolean
    ) {
        if (!suggestedWords.isPunctuationSuggestions()) {
            spaceState = SpaceState.WEAK
        }

        startDoubleSpacePeriodCountdown(inputTransaction)
        if (wasComposingWord || suggestedWords.isEmpty) {
            inputTransaction.setRequiresUpdateSuggestions()
        }

        if (!shouldAvoidSendingCode) {
            connection.commitCodePoint(Constants.CODE_SPACE)
        }
    }

    /** Any separator but space: decide whether a space is owed after it, and put it in. */
    private fun commitSeparatorOtherThanSpace(
        codePoint: Int, inputTransaction: InputTransaction,
        wasComposingWord: Boolean, isInsideDoubleQuoteOrAfterDigit: Boolean
    ) {
        val settingsValues = inputTransaction.settingsValues
        if (SpaceState.PHANTOM == inputTransaction.spaceState
                && (settingsValues.isUsuallyFollowedBySpace(codePoint) || isInsideDoubleQuoteOrAfterDigit)) {
            // If we are in phantom space state, and the user presses a separator, we want to
            // stay in phantom space state so that the next keypress has a chance to add the
            // space. For example, if I type "Good dat", pick "day" from the suggestion strip
            // then insert a comma and go on to typing the next word, I want the space to be
            // inserted automatically before the next word, the same way it is when I don't
            // input the comma. Also when closing a quote the phantom state should be preserved.
            // The case is a little different if the separator is a space stripper. Such a
            // separator does not normally need a space on the right (that's the difference
            // between swappers and strippers), so we should not stay in phantom space state if
            // the separator is a stripper. Hence the additional test above.
            spaceState = SpaceState.PHANTOM
        } else {
            // spaceState is still SpaceState.NONE, but some characters should typically
            // be followed by space. Set phantom space state for such characters if the user
            // enabled the setting and was not composing a word. The latter avoids setting
            // phantom space state when typing decimal numbers, with the drawback of not
            // setting phantom space state after ending a sentence with a non-word.
            // A double quote behaves like it's usually followed by space if we're inside
            // a double quote.
            if (wasComposingWord
                    && settingsValues.autospaceAfterPunctuation
                    && (settingsValues.isUsuallyFollowedBySpace(codePoint) || isInsideDoubleQuoteOrAfterDigit)) {
                spaceState = SpaceState.PHANTOM
            }
        }

        enterInlineEmojiSearchIfNeeded(codePoint, settingsValues)

        connection.commitCodePoint(codePoint)

        if (isInlineEmojiSearchAction()) {
            inputTransaction.setRequiresUpdateSuggestions()
        } else {
            // Set punctuation right away. onUpdateSelection will fire but tests whether it is
            // already displayed or not, so it's okay.
            suggestionStripViewAccessor.setNeutralSuggestionStrip()
        }
    }

    /**
     * Handle a press on the backspace key.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private fun handleBackspaceEvent(
        event: Event, inputTransaction: InputTransaction,
        currentKeyboardScript: String
    ) {
        spaceState = SpaceState.NONE
        deleteCount++

        // In many cases after backspace, we need to update the shift state. Normally we need
        // to do this right away to avoid the shift state being out of date in case the user types
        // backspace then some other character very fast. However, in the case of backspace key
        // repeat, this can lead to flashiness when the cursor flies over positions where the
        // shift state should be updated, so if this is a key repeat, we update after a small delay.
        // Then again, even in the case of a key repeat, if the cursor is at start of text, it
        // can't go any further back, so we can update right away even if it's a key repeat.
        val shiftUpdateKind = if (event.isKeyRepeat && connection.expectedSelectionStart > 0)
                InputTransaction.SHIFT_UPDATE_LATER
                else InputTransaction.SHIFT_UPDATE_NOW
        inputTransaction.requireShiftUpdate(shiftUpdateKind)

        val settingsValues = inputTransaction.settingsValues
        if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
            // If we are in the middle of a recorrection, we need to commit the recorrection
            // first so that we can remove the character at the current cursor position.
            // We also need to unlearn the original word that is now being corrected.
            unlearnWord(wordComposer.typedWord, settingsValues, DictionaryFacilitator.UnlearnEvent.BACKSPACE)
            resetEntireInputState(connection.expectedSelectionStart,
                    connection.expectedSelectionEnd, true /* clearSuggestionStrip */)
            // When we exit this if-clause, wordComposer.isComposingWord() will return false.
        }
        if (wordComposer.isComposingWord) {
            backspaceInComposingWord(event, inputTransaction)
            return
        }
        if (revertOnBackspace(inputTransaction, currentKeyboardScript)) return

        // No cancelling of commit/double space/swap: we have a regular backspace.
        // We should backspace one char and restart suggestion if at the end of a word.
        val result: BackspaceResult
        if (connection.hasSelection()) {
            result = backspaceSelection(settingsValues)
        } else if (settingsValues.inputAttributes.isTypeNull
                || Constants.NOT_A_CURSOR_POSITION == connection.expectedSelectionEnd) {
            result = backspaceWithKeyEvents(settingsValues, currentKeyboardScript)
        } else {
            result = backspaceBeforeCursor(event, settingsValues, currentKeyboardScript)
        }
        if (result == BackspaceResult.DONE) return
        if (result != BackspaceResult.DELETED_AND_UNLEARNED) {
            // Consider unlearning the word being deleted (if we have not done so already).
            unlearnWordBeingDeleted(settingsValues, currentKeyboardScript)
        }
        if (connection.hasSlowInputConnection()) {
            suggestionStripViewAccessor.setNeutralSuggestionStrip()
        } else if (settingsValues.needsToLookupSuggestions()
                && settingsValues.spacingAndPunctuations.currentLanguageHasSpaces) {
            restartSuggestionsOnWordTouchedByCursor(settingsValues, currentKeyboardScript)
        }
    }

    /** What a plain backspace did, for the steps that follow it in [handleBackspaceEvent]. */
    private enum class BackspaceResult {
        /** text was deleted, the word under the cursor may still need unlearning */
        DELETED,
        /** text was deleted and the word it belonged to was already unlearned */
        DELETED_AND_UNLEARNED,
        /** nothing more to do: no unlearning, no restart of suggestions */
        DONE
    }

    /** Backspace while a word is being composed: drop a swiped word whole, a typed one by a character. */
    private fun backspaceInComposingWord(event: Event, inputTransaction: InputTransaction) {
        if (wordComposer.isBatchMode) {
            val rejectedSuggestion = wordComposer.typedWord
            SwipeRecorder.onDeleted(latinIME)
            if (useBackgroundGathering)
                BackgroundGatheringCache.onRejectedSuggestion(rejectedSuggestion)
            wordComposer.reset()
            wordComposer.rejectedBatchModeSuggestion = rejectedSuggestion
            if (!TextUtils.isEmpty(rejectedSuggestion)) {
                unlearnWord(rejectedSuggestion, inputTransaction.settingsValues, DictionaryFacilitator.UnlearnEvent.REJECTION)
            }
            StatsUtils.onBackspaceWordDelete(rejectedSuggestion.length)
        } else {
            if (useBackgroundGathering)
                BackgroundGatheringCache.removeLast(wordComposer.typedWord)
            wordComposer.applyProcessedEvent(event)
            StatsUtils.onBackspacePressed(1)
        }
        if (wordComposer.isComposingWord) {
            setComposingTextInternal(getTextWithUnderline(wordComposer.typedWord), 1)
        } else {
            connection.commitText("", 1)
        }
        updateInlineEmojiSearch()
        inputTransaction.setRequiresUpdateSuggestions()
    }

    /**
     * Backspace right after something the keyboard did on its own takes that back instead of
     * deleting a character: an autocorrection, a double space turned into a period, or swapped
     * punctuation.
     * @return whether something was reverted, in which case the backspace is fully handled
     */
    private fun revertOnBackspace(inputTransaction: InputTransaction, currentKeyboardScript: String): Boolean {
        val settingsValues = inputTransaction.settingsValues
        if (lastComposedWord.canRevertCommit() && settingsValues.backspaceRevertsAutocorrect) {
            val typedWord = lastComposedWord.typedWord
            revertCommit(inputTransaction)
            StatsUtils.onRevertAutoCorrect()
            StatsUtils.onWordCommitUserTyped(typedWord, wordComposer.isBatchMode)
            // Restart suggestions when backspacing into a reverted word. This is required for
            // the final corrected word to be learned, as learning only occurs when suggestions
            // are active.
            //
            // Note: restartSuggestionsOnWordTouchedByCursor is already called for normal
            // (non-revert) backspace handling.
            if (settingsValues.needsToLookupSuggestions()
                    && settingsValues.spacingAndPunctuations.currentLanguageHasSpaces) {
                restartSuggestionsOnWordTouchedByCursor(settingsValues, currentKeyboardScript)
            }
            return true
        }
        // Removing a multi-character input (smiley key, .com key) whole on backspace used to be
        // here. It was switched off upstream for causing inconsistencies, see
        // https://github.com/HeliBorg/HeliBoard/issues/1019, and is gone in Caret.
        if (SpaceState.DOUBLE == inputTransaction.spaceState) {
            cancelDoubleSpacePeriodCountdown()
            if (connection.revertDoubleSpacePeriod(settingsValues.spacingAndPunctuations)) {
                // No need to reset spaceState, it has already be done (that's why we
                // receive it as a parameter)
                inputTransaction.setRequiresUpdateSuggestions()
                wordComposer.setCapitalizedModeAtStartComposingTime(CapsMode.OFF)
                StatsUtils.onRevertDoubleSpacePeriod()
                return true
            }
        } else if (SpaceState.SWAP_PUNCTUATION == inputTransaction.spaceState) {
            if (connection.revertSwapPunctuation()) {
                StatsUtils.onRevertSwapPunctuation()
                return true
            }
        }
        return false
    }

    /** Backspace with text selected removes the selection and unlearns it. */
    private fun backspaceSelection(settingsValues: SettingsValues): BackspaceResult {
        var result = BackspaceResult.DELETED
        val selection = connection.getSelectedText(0 /* 0 for no styles */)
        if (!TextUtils.isEmpty(selection)) {
            unlearnWord(selection.toString(), settingsValues, DictionaryFacilitator.UnlearnEvent.BACKSPACE)
            result = BackspaceResult.DELETED_AND_UNLEARNED
        }
        val numCharsDeleted = connection.expectedSelectionEnd -
                connection.expectedSelectionStart
        connection.setSelection(connection.expectedSelectionEnd,
                connection.expectedSelectionEnd)
        connection.deleteTextBeforeCursor(numCharsDeleted)
        StatsUtils.onBackspaceSelectedText(numCharsDeleted)
        return result
    }

    /**
     * Backspace as key events. There are three possible reasons to send a key event: either the
     * field has type TYPE_NULL, in which case the keyboard should send events, or we are
     * running in backward compatibility mode, or we don't know the cursor position.
     * Before Jelly bean, the keyboard would simulate a hardware keyboard event on
     * pressing enter or delete. This is bad for many reasons (there are race
     * conditions with commits) but some applications are relying on this behavior
     * so we continue to support it for older apps, so we retain this behavior if
     * the app has target SDK < JellyBean.
     * As for the case where we don't know the cursor position, it can happen
     * because of bugs in the framework. But the framework should know, so the next
     * best thing is to leave it to whatever it thinks is best.
     */
    private fun backspaceWithKeyEvents(settingsValues: SettingsValues, currentKeyboardScript: String): BackspaceResult {
        var result = BackspaceResult.DELETED
        sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL)
        var totalDeletedLength = 1
        if (deleteCount > Constants.DELETE_ACCELERATE_AT) {
            // If this is an accelerated (i.e., double) deletion, then we need to
            // consider unlearning here because we may have already reached
            // the previous word, and will lose it after next deletion.
            if (unlearnWordBeingDeleted(settingsValues, currentKeyboardScript))
                result = BackspaceResult.DELETED_AND_UNLEARNED
            sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL)
            totalDeletedLength++
        }
        StatsUtils.onBackspacePressed(totalDeletedLength)
        return result
    }

    /**
     * The usual backspace: delete before the cursor. One character, two once the key has been
     * held for a while, or whole words when "hold backspace to delete words" is on.
     */
    private fun backspaceBeforeCursor(event: Event, settingsValues: SettingsValues,
            currentKeyboardScript: String): BackspaceResult {
        val codePointBeforeCursor = connection.getCodePointBeforeCursor()
        if (codePointBeforeCursor == Constants.NOT_A_CODE) {
            // HACK for backward compatibility with broken apps that haven't realized
            // yet that hardware keyboards are not the only way of inputting text.
            // Nothing to delete before the cursor. We should not do anything, but many
            // broken apps expect something to happen in this case so that they can
            // catch it and have their broken interface react. If you need the keyboard
            // to do this, you're doing it wrong -- please fix your app.
            //  To make this more interesting, web browsers, and apps that are basically
            // browsers under the hood, in too many cases don't understand "deleteSurroundingText".
            // So we try to send a backspace keypress instead.
            if ((currentInputEditorInfo!!.inputType and InputType.TYPE_MASK_VARIATION)
                    == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL)
            else connection.deleteTextBeforeCursor(1)
            // TODO: Add a new StatsUtils method onBackspaceWhenNoText()
            return BackspaceResult.DONE
        }
        var result = BackspaceResult.DELETED
        var lengthToDelete = connection.getCharCountToDeleteBeforeCursor()
        // Caret: holding backspace deletes characters first, then whole words
        val deleteWords = event.isKeyRepeat && settingsValues.holdDeleteByWord
                && deleteCount > Constants.DELETE_WORDS_AT
        if (deleteWords) {
            // one word every few repeats, or a paragraph is gone before the finger lifts
            if (deleteCount % Constants.DELETE_WORDS_EVERY != 0) return BackspaceResult.DONE
            val before = connection.getTextBeforeCursor(Constants.DELETE_WORDS_LOOKBACK, 0)
            val wordLength = if (before == null) 0
                    else lengthOfWordBeforeCursor(before, settingsValues.spacingAndPunctuations)
            if (wordLength > lengthToDelete) {
                if (unlearnWordBeingDeleted(settingsValues, currentKeyboardScript))
                    result = BackspaceResult.DELETED_AND_UNLEARNED
                lengthToDelete = wordLength
            }
        }
        connection.deleteTextBeforeCursor(lengthToDelete)
        var totalDeletedLength = lengthToDelete
        if (!deleteWords && deleteCount > Constants.DELETE_ACCELERATE_AT) {
            // If this is an accelerated (i.e., double) deletion, then we need to
            // consider unlearning here because we may have already reached
            // the previous word, and will lose it after next deletion.
            if (unlearnWordBeingDeleted(settingsValues, currentKeyboardScript))
                result = BackspaceResult.DELETED_AND_UNLEARNED
            val codePointBeforeCursorToDeleteAgain = connection.getCodePointBeforeCursor()
            if (codePointBeforeCursorToDeleteAgain != Constants.NOT_A_CODE) {
                val lengthToDeleteAgain = connection.getCharCountToDeleteBeforeCursor()
                connection.deleteTextBeforeCursor(lengthToDeleteAgain)
                totalDeletedLength += lengthToDeleteAgain
            }
        }
        StatsUtils.onBackspacePressed(totalDeletedLength)
        return result
    }

    fun getWordAtCursor(settingsValues: SettingsValues, currentKeyboardScript: String): String {
        if (!connection.hasSelection()
                && settingsValues.needsToLookupSuggestions()
                && settingsValues.spacingAndPunctuations.currentLanguageHasSpaces) {
            val range = connection.getWordRangeAtCursor(settingsValues.spacingAndPunctuations, currentKeyboardScript)
            if (range != null) {
                return range.word.toString()
            }
        }
        return ""
    }

    fun unlearnWordBeingDeleted(
            settingsValues: SettingsValues, currentKeyboardScript: String): Boolean {
        if (connection.hasSlowInputConnection()) {
            // TODO: Refactor unlearning so that it does not incur any extra calls
            // to the InputConnection. That way it can still be performed on a slow
            // InputConnection.
            Log.w(TAG, "Skipping unlearning due to slow InputConnection.")
            return false
        }
        // If we just started backspacing to delete a previous word (but have not
        // entered the composing state yet), unlearn the word.
        // TODO: Consider tracking whether or not this word was typed by the user.
        if (!connection.isCursorFollowedByWordCharacter(settingsValues.spacingAndPunctuations)) {
            val wordBeingDeleted = getWordAtCursor(settingsValues, currentKeyboardScript)
            if (!TextUtils.isEmpty(wordBeingDeleted)) {
                unlearnWord(wordBeingDeleted, settingsValues, DictionaryFacilitator.UnlearnEvent.BACKSPACE)
                return true
            }
        }
        return false
    }

    fun unlearnWord(word: String, settingsValues: SettingsValues, event: DictionaryFacilitator.UnlearnEvent) {
        val ngramContext = connection.getNgramContextFromNthPreviousWord(settingsValues.spacingAndPunctuations, 2)
        val timeStampInSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        dictionaryFacilitator.unlearnFromUserHistory(word, ngramContext, timeStampInSeconds, event)
    }

    /**
     * Handle a press on the language switch key (the "globe key")
     */
    private fun handleLanguageSwitchKey() {
        latinIME.switchToNextSubtype()
    }

    /**
     * Swap a space with a space-swapping punctuation sign.
     * <p>
     * This method will check that there are two characters before the cursor and that the first
     * one is a space before it does the actual swapping.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     * @return true if the swap has been performed, false if it was prevented by preliminary checks.
     */
    private fun trySwapSwapperAndSpace(event: Event,
            inputTransaction: InputTransaction): Boolean {
        val codePointBeforeCursor = connection.getCodePointBeforeCursor()
        if (Constants.CODE_SPACE != codePointBeforeCursor) {
            return false
        }
        connection.deleteTextBeforeCursor(1)
        val text = event.textToCommit.toString() + " "
        connection.commitText(text, 1)
        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW)
        return true
    }

    /*
     * Strip a trailing space if necessary and returns whether it's a swap weak space situation.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     * @return whether we should swap the space instead of removing it.
     */
    private fun tryStripSpaceAndReturnWhetherShouldSwapInstead(event: Event,
            inputTransaction: InputTransaction): Boolean {
        val codePoint = event.codePoint
        if (codePoint == INLINE_EMOJI_SEARCH_MARKER.code && emojiDictionaryFacilitator != null) {
            // Avoid interfering with inline emoji search
            return false
        }

        val isFromSuggestionStrip = event.isSuggestionStripPress
        if (Constants.CODE_ENTER == codePoint &&
                SpaceState.SWAP_PUNCTUATION == inputTransaction.spaceState) {
            connection.removeTrailingSpace()
            return false
        }
        if ((SpaceState.WEAK == inputTransaction.spaceState
                || SpaceState.SWAP_PUNCTUATION == inputTransaction.spaceState)
                && isFromSuggestionStrip) {
            if (inputTransaction.settingsValues.isUsuallyPrecededBySpace(codePoint)) {
                return false
            }
            if (inputTransaction.settingsValues.isUsuallyFollowedBySpace(codePoint)) {
                return true
            }
            connection.removeTrailingSpace()
        }
        return false
    }

    fun startDoubleSpacePeriodCountdown(inputTransaction: InputTransaction) {
        doubleSpacePeriodCountdownStart = inputTransaction.timestamp
    }

    fun cancelDoubleSpacePeriodCountdown() {
        doubleSpacePeriodCountdownStart = 0
    }

    fun isDoubleSpacePeriodCountdownActive(inputTransaction: InputTransaction): Boolean {
        return inputTransaction.timestamp - doubleSpacePeriodCountdownStart <
                inputTransaction.settingsValues.doubleSpacePeriodTimeout
    }

    /**
     * Apply the double-space-to-period transformation if applicable.
     * <p>
     * The double-space-to-period transformation means that we replace two spaces with a
     * period-space sequence of characters. This typically happens when the user presses space
     * twice in a row quickly.
     * This method will check that the double-space-to-period is active in settings, that the
     * two spaces have been input close enough together, that the typed character is a space
     * and that the previous character allows for the transformation to take place. If all of
     * these conditions are fulfilled, this method applies the transformation and returns true.
     * Otherwise, it does nothing and returns false.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     * @return true if we applied the double-space-to-period transformation, false otherwise.
     */
    private fun tryPerformDoubleSpacePeriod(event: Event,
            inputTransaction: InputTransaction): Boolean {
        // Check the setting, the typed character and the countdown. If any of the conditions is
        // not fulfilled, return false.
        if (!inputTransaction.settingsValues.useDoubleSpacePeriod
                || Constants.CODE_SPACE != event.codePoint
                || !isDoubleSpacePeriodCountdownActive(inputTransaction)) {
            return false
        }
        // We only do this when we see one space and an accepted code point before the cursor.
        // The code point may be a surrogate pair but the space may not, so we need 3 chars.
        val lastTwo = connection.getTextBeforeCursor(3, 0)
        if (null == lastTwo) return false
        val length = lastTwo.length
        if (length < 2) return false
        if (lastTwo[length - 1].code != Constants.CODE_SPACE) {
            return false
        }
        // We know there is a space in pos -1, and we have at least two chars. If we have only two
        // chars, isSurrogatePairs can't return true as charAt(1) is a space, so this is fine.
        val firstCodePoint = if (Character.isSurrogatePair(lastTwo[0], lastTwo[1]))
                        Character.codePointAt(lastTwo, length - 3)
                        else lastTwo[length - 2].code
        if (canBeFollowedByDoubleSpacePeriod(firstCodePoint)) {
            cancelDoubleSpacePeriodCountdown()
            connection.deleteTextBeforeCursor(1)
            val textToInsert = inputTransaction.settingsValues.spacingAndPunctuations
                    .sentenceSeparatorAndSpace
            connection.commitText(textToInsert, 1)
            inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW)
            inputTransaction.setRequiresUpdateSuggestions()
            return true
        }
        return false
    }

    /**
     * Performs a recapitalization event.
     * @param settingsValues The current settings values.
     */
    private fun performRecapitalization(settingsValues: SettingsValues) {
        if (!connection.hasSelection() || !recapitalizeStatus.isEnabled) {
            return // No selection or recapitalize is disabled for now
        }
        val selectionStart = connection.expectedSelectionStart
        val selectionEnd = connection.expectedSelectionEnd
        val numCharsSelected = selectionEnd - selectionStart
        if (numCharsSelected > Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION
                || numCharsSelected < -Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION) {
            // We bail out if we have too many characters for performance reasons. We don't want
            // to suck possibly multiple-megabyte data.
            return
        }
        // If we have a recapitalize in progress, use it; otherwise, start a new one.
        if (!recapitalizeStatus.isStarted
                || !recapitalizeStatus.isSetAt(selectionStart, selectionEnd)) {
            val selectedText = connection.getSelectedText(0 /* flags, 0 for no styles */)
            if (TextUtils.isEmpty(selectedText)) return // Race condition with the input connection
            recapitalizeStatus.start(selectedText.toString(), selectionStart, settingsValues.locale,
                    settingsValues.spacingAndPunctuations.sortedWordSeparators)
        }
        connection.finishComposingText()
        recapitalizeStatus.rotate()
        connection.setSelection(selectionEnd, selectionEnd)
        connection.deleteTextBeforeCursor(numCharsSelected)
        val replacement = recapitalizeStatus.textReplacement()
        connection.commitText(replacement.text, 0)
        connection.setSelection(replacement.startPosition, replacement.endPosition())
    }

    private fun performAdditionToUserHistoryDictionary(settingsValues: SettingsValues,
            suggestion: String, ngramContext: NgramContext) {
        // For addition to user history we want suggestions (even if just for autocorrect) or a gestured word.
        // That's to avoid unintended additions in some sensitive fields, or fields that
        // expect to receive non-words.
        if ((!settingsValues.needsToLookupSuggestions() && !wordComposer.isBatchMode) || TextUtils.isEmpty(suggestion))
            return
        val wasAutoCapitalized = wordComposer.wasAutoCapitalized() && !wordComposer.isMostlyCaps
        val word = stripTrailingSeparatorsAndConnectors(suggestion, settingsValues.spacingAndPunctuations)
        if (settingsValues.incognitoModeEnabled) {
            // don't add to history, but still adjust confidences
            // otherwise incognito input fields can be very annoying when the wrong language is active
            dictionaryFacilitator.adjustConfidences(word, wasAutoCapitalized)
            return
        }
        if (connection.hasSlowInputConnection()) {
            // Since we don't unlearn when the user backspaces on a slow InputConnection,
            // turn off learning to guard against adding typos that the user later deletes.
            Log.w(TAG, "Skipping learning due to slow InputConnection.")
            // but we still want to adjust confidences for multilingual typing
            dictionaryFacilitator.adjustConfidences(word, wasAutoCapitalized)
            return
        }
        val timeStampInSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()).toInt()
        dictionaryFacilitator.addToUserHistory(word, wasAutoCapitalized, ngramContext,
                timeStampInSeconds.toLong(), settingsValues.blockPotentiallyOffensive)
    }

    private fun addToHistoryIfEmoji(text: String, settingsValues: SettingsValues) {
        if (lastComposedWord == LastComposedWord.NOT_A_COMPOSED_WORD // we want a last composed word, also to avoid storing consecutive emojis
            || wordComposer.isComposingWord // emoji will be part of the word in this case, better do nothing
            || !settingsValues.bigramPredictionEnabled // this is only for next word suggestions, so they need to be enabled
            || settingsValues.incognitoModeEnabled
            || !settingsValues.needsToLookupSuggestions()
            || !isEmoji(text)
            || connection.hasSlowInputConnection()
        ) return
        lastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD // avoid storing consecutive emojis

        // commit emoji to dictionary, so it ends up in history and can be suggested as next word
        dictionaryFacilitator.addToUserHistory(
            text,
            false,
            connection.getNgramContextFromNthPreviousWord(settingsValues.spacingAndPunctuations, 2),
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()).toInt().toLong(),
            settingsValues.blockPotentiallyOffensive
        )
    }

    fun performUpdateSuggestionStripSync(settingsValues: SettingsValues, inputStyle: Int) {
        var startTimeMillis: Long = 0
        if (DebugFlags.DEBUG_ENABLED) {
            startTimeMillis = SystemClock.elapsedRealtime()
            Log.d(TAG, "performUpdateSuggestionStripSync()")
        }
        // Check if we have a suggestion engine attached.
        if (!settingsValues.needsToLookupSuggestions()) {
            if (wordComposer.isComposingWord) {
                Log.w(TAG, "Called updateSuggestionsOrPredictions but suggestions were not "
                        + "requested!")
            }
            // Clear the suggestions strip.
            suggestionStripViewAccessor.setSuggestions(SuggestedWords.emptyInstance)
            return
        }

        if (!wordComposer.isComposingWord && !settingsValues.bigramPredictionEnabled) {
            suggestionStripViewAccessor.setNeutralSuggestionStrip()
            return
        }

        val holder = AsyncResultHolder<SuggestedWords>("Suggest")
        inputLogicHandler.getSuggestedWords { getSuggestedWords(
            inputStyle, SuggestedWords.NOT_A_SEQUENCE_NUMBER,
            object : OnGetSuggestedWordsCallback {
                override fun onGetSuggestedWords(suggestedWords: SuggestedWords) {
                    val typedWordString = wordComposer.typedWord
                    val typedWordInfo = SuggestedWordInfo(
                        typedWordString, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED,
                        Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
                    )
                    // Show new suggestions if we have at least one. Otherwise keep the old
                    // suggestions with the new typed word. Exception: if the length of the
                    // typed word is <= 1 (after a deletion typically) we clear old suggestions.
                    if (suggestedWords.size() > 1 || typedWordString.length <= 1) {
                        holder.set(suggestedWords)
                    } else {
                        holder.set(retrieveOlderSuggestions(typedWordInfo, this@InputLogic.suggestedWords))
                    }
                }
            }
        ) }
        // This line may cause the current thread to wait.
        val newSuggestedWords = holder.get(null,
                Constants.GET_SUGGESTED_WORDS_TIMEOUT.toLong())
        if (newSuggestedWords != null) {
            // Prefer clipboard suggestions (if available and setting is enabled) over beginning of sentence predictions.
            if (!(newSuggestedWords.inputStyle == SuggestedWords.INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION
                    && latinIME.tryShowClipboardSuggestion())) {
                suggestionStripViewAccessor.setSuggestions(newSuggestedWords)
            }
            if (!newSuggestedWords.isEmpty && settingsValues.suggestionsEnabled && isInlineEmojiSearchAction()) {
                suggestionStripViewAccessor.showSuggestionStrip()
            }
        }
        if (DebugFlags.DEBUG_ENABLED) {
            val runTimeMillis = SystemClock.elapsedRealtime() - startTimeMillis
            Log.d(TAG, "performUpdateSuggestionStripSync() : " + runTimeMillis + " ms to finish")
        }
    }

    /**
     * Check if the cursor is touching a word. If so, restart suggestions on this word, else
     * do nothing.
     *
     * @param settingsValues the current values of the settings.
     */
    fun restartSuggestionsOnWordTouchedByCursor(settingsValues: SettingsValues,
            // TODO: remove this argument, put it into settingsValues
            currentKeyboardScript: String) {
        // HACK: We may want to special-case some apps that exhibit bad behavior in case of
        // recorrection. This is a temporary, stopgap measure that will be removed later.
        // TODO: remove this.
        if (!settingsValues.spacingAndPunctuations.currentLanguageHasSpaces
                // If no suggestions are requested, don't try restarting suggestions.
                || !settingsValues.needsToLookupSuggestions()
                // If we are currently in a batch input, we must not resume suggestions, or the result
                // of the batch input will replace the new composition. This may happen in the corner case
                // that the app moves the cursor on its own accord during a batch input.
                || inputLogicHandler.isInBatchInput
                // If the cursor is not touching a word, or if there is a selection, return right away.
                || connection.hasSelection()
                // If we don't know the cursor location, return.
                || connection.expectedSelectionStart < 0) {
            suggestionStripViewAccessor.setNeutralSuggestionStrip()
            return
        }

        updateInlineEmojiSearch()
        if (isInlineEmojiSearchAction()) {
            inputLogicHandler.getSuggestedWords { getSuggestedWords(SuggestedWords.INPUT_STYLE_TYPING,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER, object : OnGetSuggestedWordsCallback {
                    override fun onGetSuggestedWords(suggestedWords: SuggestedWords) {
                        doShowSuggestionsAndClearAutoCorrectionIndicator(suggestedWords)
                    }
                }) }
            return
        }

        if (!connection.isCursorTouchingWord(settingsValues.spacingAndPunctuations, true /* checkTextAfter */)) {
            // Show predictions.
            wordComposer.setCapitalizedModeAtStartComposingTime(CapsMode.OFF)
            latinIME.handler.postUpdateSuggestionStrip(SuggestedWords.INPUT_STYLE_RECORRECTION)
            // "unselect" the previous text
            connection.finishComposingText()
            return
        }
        val range = connection.getWordRangeAtCursor(settingsValues.spacingAndPunctuations, currentKeyboardScript)
        if (null == range) return // Happens if we don't have an input connection at all
        if (range.length() <= 0) {
            // Race condition, or touching a word in a non-supported script.
            latinIME.setNeutralSuggestionStrip()
            connection.finishComposingText()
            return
        }
        // If for some strange reason (editor bug or so) we measure the text before the cursor as
        // longer than what the entire text is supposed to be, the safe thing to do is bail out.
        if (range.hasUrlSpans) return
        // If there are links, we don't resume suggestions. Making
        // edits to a linkified text through batch commands would ruin the URL spans, and unless
        // we take very complicated steps to preserve the whole link, we can't do things right so
        // we just do not resume because it's safer.
        if (!isResumableWord(settingsValues, range.word.toString())) {
            suggestionStripViewAccessor.setNeutralSuggestionStrip()
            // "unselect" the previous text
            connection.finishComposingText()
            return
        }
        restartSuggestions(range)
    }

    private fun restartSuggestions(range: TextRange) {
        val numberOfCharsInWordBeforeCursor = range.numberOfCharsInWordBeforeCursor
        val expectedCursorPosition = connection.expectedSelectionStart
        if (numberOfCharsInWordBeforeCursor > expectedCursorPosition) return
        val suggestions = ArrayList<SuggestedWordInfo>()
        val typedWordString = range.word.toString()
        val typedWordInfo = SuggestedWordInfo(typedWordString,
                "" /* prevWordsContext */, SuggestedWords.MAX_SUGGESTIONS + 1,
                SuggestedWordInfo.KIND_TYPED, Dictionary.DICTIONARY_USER_TYPED,
                SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
                SuggestedWordInfo.NOT_A_CONFIDENCE /* autoCommitFirstWordConfidence */)
        suggestions.add(typedWordInfo)
        var i = 0
        for (span in range.getSuggestionSpansAtWord()) {
            for (s in span.getSuggestions()) {
                ++i
                if (!TextUtils.equals(s, typedWordString)) {
                    suggestions.add(SuggestedWordInfo(s,
                            "" /* prevWordsContext */, SuggestedWords.MAX_SUGGESTIONS - i,
                            SuggestedWordInfo.KIND_RESUMED, Dictionary.DICTIONARY_RESUMED,
                            SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
                            SuggestedWordInfo.NOT_A_CONFIDENCE
                                    /* autoCommitFirstWordConfidence */))
                }
            }
        }
        val codePoints = StringUtils.toCodePointArray(typedWordString)
        wordComposer.setComposingWord(codePoints, latinIME.getCoordinatesForCurrentKeyboard(codePoints))
        wordComposer.setCursorPositionWithinWord(typedWordString.codePointCount(0, numberOfCharsInWordBeforeCursor))
        connection.setComposingRegion(expectedCursorPosition - numberOfCharsInWordBeforeCursor,
                expectedCursorPosition + range.numberOfCharsInWordAfterCursor)
        if (suggestions.size <= 1) {
            // If there weren't any suggestion spans on this word, suggestions#size() will be 1
            // if shouldIncludeResumedWordInSuggestions is true, 0 otherwise. In this case, we
            // have no useful suggestions, so we will try to compute some for it instead.
            inputLogicHandler.getSuggestedWords { getSuggestedWords(SuggestedWords.INPUT_STYLE_TYPING,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER, object : OnGetSuggestedWordsCallback {
                    override fun onGetSuggestedWords(suggestedWords: SuggestedWords) {
                        doShowSuggestionsAndClearAutoCorrectionIndicator(suggestedWords)
                    }
                }) }
        } else {
            // We found suggestion spans in the word. We'll create the SuggestedWords out of
            // them, and make willAutoCorrect false. We make typedWordValid false, because the
            // color of the word in the suggestion strip changes according to this parameter,
            // and false gives the correct color.
            val recorrectionWords = SuggestedWords(suggestions,
                    null /* rawSuggestions */, typedWordInfo, false /* typedWordValid */,
                    false /* willAutoCorrect */, false /* isObsoleteSuggestions */,
                    SuggestedWords.INPUT_STYLE_RECORRECTION, SuggestedWords.NOT_A_SEQUENCE_NUMBER)
            doShowSuggestionsAndClearAutoCorrectionIndicator(recorrectionWords)
        }
    }

    private fun doShowSuggestionsAndClearAutoCorrectionIndicator(words: SuggestedWords) {
        isAutoCorrectionIndicatorOn = false
        latinIME.handler.setSuggestions(words)
    }

    /**
     * Reverts a previous commit with auto-correction.
     * <p>
     * This is triggered upon pressing backspace just after a commit with auto-correction.
     *
     * @param inputTransaction The transaction in progress.
     */
    private fun revertCommit(inputTransaction: InputTransaction) {
        val originallyTypedWord = lastComposedWord.typedWord
        val committedWord = lastComposedWord.committedWord
        if (lastComposedWord.inputPointers.pointerSize > 0)
            CorrectionRecorder.onUndone(latinIME, KeyboardSwitcher.getInstance().keyboard, lastComposedWord.inputPointers,
                originallyTypedWord, committedWord.toString(), FutoSwipe.textBeforeWord)
        val committedWordString = committedWord.toString()
        val cancelLength = committedWord.length
        val separatorString = lastComposedWord.separatorString
        // If our separator is a space, we won't actually commit it,
        // but set the space state to PHANTOM so that a space will be inserted
        // on the next keypress
        val usePhantomSpace = separatorString.equals(Constants.STRING_SPACE)
        // We want java chars, not codepoints for the following.
        val separatorLength = separatorString.length
        // TODO: should we check our saved separator against the actual contents of the text view?
        val deleteLength = cancelLength + separatorLength
        if (DebugFlags.DEBUG_ENABLED) {
            checkCommittedWordIsBeforeCursor(committedWord, deleteLength)
        }
        connection.deleteTextBeforeCursor(deleteLength)
        if (!TextUtils.isEmpty(committedWord)) {
            unlearnWord(committedWordString, inputTransaction.settingsValues, DictionaryFacilitator.UnlearnEvent.REVERT)
        }
        val stringToCommit = "" + originallyTypedWord +
                (if (usePhantomSpace) "" else separatorString)
        val textToCommit = withCommittedWordAsSuggestion(stringToCommit, committedWord,
                inputTransaction.settingsValues)

        if (inputTransaction.settingsValues.spacingAndPunctuations.currentLanguageHasSpaces) {
            connection.commitText(textToCommit, 1)
            if (usePhantomSpace) {
                justRevertedACommit = true
                spaceState = SpaceState.PHANTOM
            }
        } else {
            // For languages without spaces, we revert the typed string but the cursor is flush
            // with the typed word, so we need to resume suggestions right away.
            val codePoints = StringUtils.toCodePointArray(stringToCommit)
            wordComposer.setComposingWord(codePoints, latinIME.getCoordinatesForCurrentKeyboard(codePoints))
            setComposingTextInternal(textToCommit, 1)
        }
        // Don't restart suggestion yet. We'll restart if the user deletes the separator.
        lastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD

        // We have a separator between the word and the cursor: we should show predictions.
        inputTransaction.setRequiresUpdateSuggestions()
    }

    /** Debug builds only: fail loudly when the word about to be reverted is not what is in the text field. */
    private fun checkCommittedWordIsBeforeCursor(committedWord: CharSequence, deleteLength: Int) {
        if (wordComposer.isComposingWord) {
            throw RuntimeException("revertCommit, but we are composing a word")
        }
        val wordBeforeCursor =
                connection.getTextBeforeCursor(deleteLength, 0)!!.subSequence(0, committedWord.length)
        if (!TextUtils.equals(committedWord, wordBeforeCursor)) {
            throw RuntimeException("revertCommit check failed: we thought we were "
                    + "reverting \"" + committedWord
                    + "\", but before the cursor we found \"" + wordBeforeCursor + "\"")
        }
    }

    /**
     * The typed word that goes back into the text field when an autocorrection is undone. It
     * carries the corrected word, and whatever else was suggested for it, as suggestions, so the
     * app can still offer them.
     */
    private fun withCommittedWordAsSuggestion(stringToCommit: String, committedWord: CharSequence,
            settingsValues: SettingsValues): SpannableString {
        val textToCommit = SpannableString(stringToCommit)
        if (committedWord !is SpannableString) return textToCommit
        val committedWordWithSuggestionSpans: SpannableString = committedWord
        val committedWordString = committedWord.toString()
        val spans = committedWordWithSuggestionSpans.getSpans(0, committedWord.length, Any::class.java)
        val lastCharIndex = textToCommit.length - 1
        // We will collect all suggestions in the following array.
        val suggestions = ArrayList<String>()
        // First, add the committed word to the list of suggestions.
        suggestions.add(committedWordString)
        for (span in spans) {
            // If this is a suggestion span, we check that the word is not the committed word.
            // That should mostly be the case.
            // Given this, we add it to the list of suggestions, otherwise we discard it.
            if (span is SuggestionSpan) {
                val suggestionSpan: SuggestionSpan = span
                for (suggestion in suggestionSpan.getSuggestions()) {
                    if (suggestion != committedWordString) {
                        suggestions.add(suggestion)
                    }
                }
            } else {
                // If this is not a suggestion span, we just add it as is.
                textToCommit.setSpan(span, 0, lastCharIndex, committedWordWithSuggestionSpans.getSpanFlags(span))
            }
        }
        // Add the suggestion list to the list of suggestions.
        textToCommit.setSpan(SuggestionSpan(latinIME, settingsValues.locale,
                suggestions.toTypedArray(), 0, null),
                0, lastCharIndex, 0)
        return textToCommit
    }

    /**
     * Factor in auto-caps and manual caps and compute the current caps mode.
     * @param settingsValues the current settings values.
     * @param keyboardCapsMode the current shift mode of the keyboard. See
     *   KeyboardSwitcher#getKeyboardShiftMode() for possible values.
     * @return the actual caps mode the keyboard is in right now.
     */
    private fun getActualCapsMode(settingsValues: SettingsValues, keyboardCapsMode: CapsMode): CapsMode {
        if (keyboardCapsMode != CapsMode.AUTO) {
            return keyboardCapsMode
        }
        val auto = getCurrentAutoCapsState(settingsValues)
        if (0 != (auto and TextUtils.CAP_MODE_CHARACTERS)) {
            return CapsMode.AUTO_LOCKED
        }
        if (0 != auto) {
            return CapsMode.AUTO
        }
        return CapsMode.OFF
    }

    /**
     * Gets the current auto-caps state, factoring in the space state.
     * <p>
     * This method tries its best to do this in the most efficient possible manner. It avoids
     * getting text from the editor if possible at all.
     * This is called from the KeyboardSwitcher (through a trampoline in LatinIME) because it
     * needs to know auto caps state to display the right layout.
     *
     * @param settingsValues the relevant settings values
     * @return a caps mode from TextUtils.CAP_MODE_* or Constants.TextUtils.CAP_MODE_OFF.
     */
    fun getCurrentAutoCapsState(settingsValues: SettingsValues): Int {
        if (!settingsValues.autoCap) return Constants.TextUtils.CAP_MODE_OFF

        val ei = currentInputEditorInfo
        if (ei == null) return Constants.TextUtils.CAP_MODE_OFF
        val inputType = ei.inputType
        // Warning: this depends on spaceState, which may not be the most current value. If
        // spaceState gets updated later, whoever called this may need to be told about it.
        return connection.getCursorCapsMode(inputType, settingsValues.spacingAndPunctuations,
                SpaceState.PHANTOM == spaceState)
    }

    fun getCurrentRecapitalizeState(): RecapitalizeMode? {
        if (!recapitalizeStatus.isStarted
                || !recapitalizeStatus.isSetAt(connection.expectedSelectionStart,
                        connection.expectedSelectionEnd)) {
            // Not recapitalizing at the moment
            return null
        }
        return recapitalizeStatus.currentMode
    }

    /**
     * @return the editor info for the current editor
     */
    private val currentInputEditorInfo: EditorInfo?
        get() = latinIME.getCurrentInputEditorInfo()

    /** The text in front of the word being typed or swiped, for the swipe models. */
    private fun textBeforeWord(): String? {
        val text = connection.getTextBeforeCursor(FutoSwipe.CONTEXT_LENGTH, 0)?.toString() ?: return null
        // a word typed key by key is already in the text field, a swiped one is not there yet
        val typed = if (wordComposer.isComposingWord && !wordComposer.isBatchMode) wordComposer.typedWord else ""
        return if (typed.isNotEmpty() && text.endsWith(typed)) text.dropLast(typed.length) else text
    }

    /**
     * Get n-gram context from the nth previous word before the cursor as context
     * for the suggestion process.
     * @param spacingAndPunctuations the current spacing and punctuations settings.
     * @param nthPreviousWord reverse index of the word to get (1-indexed)
     * @return the information of previous words
     */
    fun getNgramContextFromNthPreviousWordForSuggestion(
            spacingAndPunctuations: SpacingAndPunctuations, nthPreviousWord: Int): NgramContext {
        if (spacingAndPunctuations.currentLanguageHasSpaces) {
            // If we are typing in a language with spaces we can just look up the previous
            // word information from textview.
            return connection.getNgramContextFromNthPreviousWord(spacingAndPunctuations, nthPreviousWord)
        }
        if (LastComposedWord.NOT_A_COMPOSED_WORD == lastComposedWord) {
            return NgramContext.BEGINNING_OF_SENTENCE
        }
        return NgramContext(NgramContext.WordInfo(lastComposedWord.committedWord.toString()))
    }

    /**
     * @param actionId the action to perform
     */
    private fun performEditorAction(actionId: Int) {
        connection.performEditorAction(actionId)
    }

    /**
     * Perform the processing specific to inputting TLDs.
     * <p>
     * Some keys input a TLD (specifically, the ".com" key) and this warrants some specific
     * processing. First, if this is a TLD, we ignore PHANTOM spaces -- this is done by type
     * of character in onCodeInput, but since this gets inputted as a whole string we need to
     * do it here specifically. Then, if the last character before the cursor is a period, then
     * we cut the dot at the start of ".com". This is because humans tend to type "www.google."
     * and then press the ".com" key and instinctively don't expect to get "www.google..com".
     *
     * @param text the raw text supplied to onTextInput
     * @return the text to actually send to the editor
     */
    private fun performSpecificTldProcessingOnTextInput(text: String): String {
        if (text.length <= 1 || text[0].code != Constants.CODE_PERIOD
                || !Character.isLetter(text[1])) {
            // Not a tld: do nothing.
            return text
        }
        // We have a TLD (or something that looks like this): make sure we don't add
        // a space even if currently in phantom mode.
        spaceState = SpaceState.NONE
        val codePointBeforeCursor = connection.getCodePointBeforeCursor()
        // If no code point, #getCodePointBeforeCursor returns NOT_A_CODE_POINT.
        if (Constants.CODE_PERIOD == codePointBeforeCursor) {
            return text.substring(1)
        }
        return text
    }

    /**
     * Handle a press on the settings key.
     */
    private fun onSettingsKeyPressed() {
        latinIME.displaySettingsDialog()
    }

    /**
     * Resets the whole input state to the starting state.
     * <p>
     * This will clear the composing word, reset the last composed word, clear the suggestion
     * strip and tell the input connection about it so that it can refresh its caches.
     *
     * @param newSelStart the new selection start, in java characters.
     * @param newSelEnd the new selection end, in java characters.
     * @param clearSuggestionStrip whether this method should clear the suggestion strip.
     */
    // TODO: how is this different from startInput ?!
    private fun resetEntireInputState(newSelStart: Int, newSelEnd: Int,
            clearSuggestionStrip: Boolean) {
        val shouldFinishComposition = wordComposer.isComposingWord
        resetComposingState(true /* alsoResetLastComposedWord */)
        if (clearSuggestionStrip) {
            suggestionStripViewAccessor.setNeutralSuggestionStrip()
        }
        connection.resetCachesUponCursorMoveAndReturnSuccess(newSelStart, newSelEnd, shouldFinishComposition)
    }

    /**
     * Resets only the composing state.
     * <p>
     * Compare #resetEntireInputState, which also clears the suggestion strip and resets the
     * input connection caches. This only deals with the composing state.
     *
     * @param alsoResetLastComposedWord whether to also reset the last composed word.
     */
    private fun resetComposingState(alsoResetLastComposedWord: Boolean) {
        wordComposer.reset()
        if (alsoResetLastComposedWord) {
            lastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD
        }
    }

    /**
     * @return the current {@link Locale} of the {@link #dictionaryFacilitator} if available. Otherwise
     * {@link Locale#ROOT}.
     */
    private fun getDictionaryFacilitatorLocale(): Locale {
        return if (dictionaryFacilitator != null) dictionaryFacilitator.getCurrentLocale() else Locale.ROOT
    }

    /**
     * Gets a chunk of text with or the auto-correction indicator underline span as appropriate.
     * <p>
     * This method looks at the old state of the auto-correction indicator to put or not put
     * the underline span as appropriate. It is important to note that this does not correspond
     * exactly to whether this word will be auto-corrected to or not: what's important here is
     * to keep the same indication as before.
     * When we add a new code point to a composing word, we don't know yet if we are going to
     * auto-correct it until the suggestions are computed. But in the mean time, we still need
     * to display the character and to extend the previous underline. To avoid any flickering,
     * the underline should keep the same color it used to have, even if that's not ultimately
     * the correct color for this new word. When the suggestions are finished evaluating, we
     * will call this method again to fix the color of the underline.
     *
     * @param text the text on which to maybe apply the span.
     * @return the same text, with the auto-correction underline span if that's appropriate.
     */
    // TODO: Shouldn't this go in some *Utils class instead?
    private fun getTextWithUnderline(text: String): CharSequence {
        // TODO: Locale should be determined based on context and the text given.
        return if (isAutoCorrectionIndicatorOn)
            getTextWithAutoCorrectionIndicatorUnderline(
                    latinIME, text, getDictionaryFacilitatorLocale())
        else text
    }

    /**
     * Sends a DOWN key event followed by an UP key event to the editor.
     * <p>
     * If possible at all, avoid using this method. It causes all sorts of race conditions with
     * the text view because it goes through a different, asynchronous binder. Also, batch edits
     * are ignored for key events. Use the normal software input methods instead.
     *
     * @param keyCode the key code to send inside the key event.
     */
    fun sendDownUpKeyEvent(keyCode: Int) {
        sendDownUpKeyEventWithMetaState(keyCode, 0)
    }

    /**
     * Sends a DOWN key event followed by an UP key event to the editor.
     * <p>
     * If possible at all, avoid using this method. It causes all sorts of race conditions with
     * the text view because it goes through a different, asynchronous binder. Also, batch edits
     * are ignored for key events. Use the normal software input methods instead.
     *
     * @param keyCode the key code to send inside the key event.
     * @param metaState the meta state to send inside the key event, e.g. KeyEvent.META_CTRL_ON
     */
    fun sendDownUpKeyEventWithMetaState(keyCode: Int, metaState: Int) {
        val eventTime = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
        connection.sendKeyEvent(KeyEvent(SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
    }

    /**
     * Insert an automatic space, if the options allow it.
     * <p>
     * This checks the options and the text before the cursor are appropriate before inserting
     * an automatic space.
     *
     * @param settingsValues the current values of the settings.
     */
    private fun insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues: SettingsValues) {
        if (settingsValues.shouldInsertSpacesAutomatically()
                && settingsValues.spacingAndPunctuations.currentLanguageHasSpaces
                && !textBeforeCursorMayBeUrlOrSimilar(settingsValues, true)
                && !connection.textBeforeCursorLooksLikeURL() // adding this check to textBeforeCursorMayBeUrlOrSimilar might not be wanted for word continuation (see effect on unit tests)
                && !(connection.getCodePointBeforeCursor() == Constants.CODE_PERIOD && connection.wordBeforeCursorMayBeEmail())
        ) {
            connection.commitCodePoint(Constants.CODE_SPACE)
            // todo: why not remove phantom space state?
        }
    }

    private fun textBeforeCursorMayBeUrlOrSimilar(settingsValues: SettingsValues, forAutoSpace: Boolean): Boolean {
        // URL / mail field and no space -> may be URL
        if (InputTypeUtils.isUriOrEmailType(settingsValues.inputAttributes.inputType) &&
                // we never want to commit the first part of the url, but we want to insert autospace if text might be a normal word
                (if (forAutoSpace) connection.nonWordCodePointAndNoSpaceBeforeCursor(settingsValues.spacingAndPunctuations) // avoid detecting URL if it could be a word
                else !connection.spaceBeforeCursor()))
            return true
        // already contains a SometimesWordConnector -> may be URL (not so sure, only do with detection enabled
        if (settingsValues.urlDetectionEnabled && settingsValues.spacingAndPunctuations.containsSometimesWordConnector(wordComposer.typedWord))
            return true
        // "://" before typed word -> very much looks like URL
        val textBeforeCursor = connection.getTextBeforeCursor(wordComposer.typedWord.length + 3, 0)
        if (textBeforeCursor != null && textBeforeCursor.toString().startsWith("://"))
            return true
        return false
    }

    /**
     * Do the final processing after a batch input has ended. This commits the word to the editor.
     * @param settingsValues the current values of the settings.
     * @param suggestedWords suggestedWords to use.
     */
    fun onUpdateTailBatchInputCompleted(settingsValues: SettingsValues,
            suggestedWords: SuggestedWords, keyboardSwitcher: KeyboardSwitcher) {
        val batchInputText: String? = if (suggestedWords.isEmpty) null else suggestedWords.getWord(0)
        if (batchInputText.isNullOrEmpty()) {
            return
        }
        connection.beginBatchEdit()
        if (SpaceState.PHANTOM == spaceState) {
            insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues)
            spaceState = SpaceState.NONE
        }
        wordComposer.setBatchInputWord(batchInputText)
        enterInlineEmojiSearchIfNeeded(batchInputText.codePointAt(0), settingsValues)
        setComposingTextInternal(batchInputText, 1)
        connection.endBatchEdit()
        // Space state must be updated before calling updateShiftState
        if (settingsValues.autospaceAfterGestureTyping)
            spaceState = SpaceState.PHANTOM
        keyboardSwitcher.updateShiftState(getCurrentAutoCapsState(settingsValues), getCurrentRecapitalizeState())

        if (isInlineEmojiSearchAction()) {
            searchForEmojiInline(SuggestedWords.NOT_A_SEQUENCE_NUMBER, object : OnGetSuggestedWordsCallback {
                override fun onGetSuggestedWords(suggestedWords: SuggestedWords) {
                    latinIME.setSuggestions(suggestedWords)
                }
            })
        }
    }

    /**
     * Commit the typed string to the editor.
     * <p>
     * This is typically called when we should commit the currently composing word without applying
     * auto-correction to it. Typically, we come here upon pressing a separator when the keyboard
     * is configured to not do auto-correction at all (because of the settings or the properties of
     * the editor). In this case, `separatorString' is set to the separator that was pressed.
     * We also come here in a variety of cases with external user action. For example, when the
     * cursor is moved while there is a composition, or when the keyboard is closed, or when the
     * user presses the Send button for an SMS, we don't auto-correct as that would be unexpected.
     * In this case, `separatorString' is set to NOT_A_SEPARATOR.
     *
     * @param settingsValues the current values of the settings.
     * @param separatorString the separator that's causing the commit, or NOT_A_SEPARATOR if none.
     */
    fun commitTyped(settingsValues: SettingsValues, separatorString: String) {
        if (!wordComposer.isComposingWord) return
        val typedWord = wordComposer.typedWord
        if (typedWord.length > 0) {
            val isBatchMode = wordComposer.isBatchMode
            commitChosenWord(settingsValues, typedWord, LastComposedWord.COMMIT_TYPE_USER_TYPED_WORD, separatorString)
            StatsUtils.onWordCommitUserTyped(typedWord, isBatchMode)
        }
    }

    /**
     * Commit the current auto-correction.
     * <p>
     * This will commit the best guess of the keyboard regarding what the user meant by typing
     * the currently composing word. The IME computes suggestions and assigns a confidence score
     * to each of them; when it's confident enough in one suggestion, it replaces the typed string
     * by this suggestion at commit time. When it's not confident enough, or when it has no
     * suggestions, or when the settings or environment does not allow for auto-correction, then
     * this method just commits the typed string.
     * Note that if suggestions are currently being computed in the background, this method will
     * block until the computation returns. This is necessary for consistency (it would be very
     * strange if pressing space would commit a different word depending on how fast you press).
     *
     * @param settingsValues the current value of the settings.
     * @param separator the separator that's causing the commit to happen.
     */
    private fun commitCurrentAutoCorrection(settingsValues: SettingsValues,
            separator: String, handler: LatinIME.UIHandler) {
        // Complete any pending suggestions query first
        if (handler.hasPendingUpdateSuggestions()) {
            handler.cancelUpdateSuggestionStrip()
            // To know the input style here, we should retrieve the in-flight "update suggestions"
            // message and read its arg1 member here. However, the Handler class does not let
            // us retrieve this message, so we can't do that. But in fact, we notice that
            // we only ever come here when the input style was typing. In the case of batch
            // input, we update the suggestions synchronously when the tail batch comes. Likewise
            // for application-specified completions. As for recorrections, we never auto-correct,
            // so we don't come here either. Hence, the input style is necessarily
            // INPUT_STYLE_TYPING.
            performUpdateSuggestionStripSync(settingsValues, SuggestedWords.INPUT_STYLE_TYPING)
        }
        val autoCorrectionOrNull: SuggestedWordInfo? = wordComposer.autoCorrectionOrNull
        val typedWord = wordComposer.typedWord
        val stringToCommit: String? = if (autoCorrectionOrNull != null) autoCorrectionOrNull.word else typedWord
        if (stringToCommit != null) {
            val isBatchMode = wordComposer.isBatchMode
            commitChosenWord(settingsValues, stringToCommit, LastComposedWord.COMMIT_TYPE_DECIDED_WORD, separator)
            if (typedWord != stringToCommit) {
                // This will make the correction flash for a short while as a visual clue
                // to the user that auto-correction happened. It has no other effect; in particular
                // note that this won't affect the text inside the text field AT ALL: it only makes
                // the segment of text starting at the supplied index and running for the length
                // of the auto-correction flash. At this moment, the "typedWord" argument is
                // ignored by TextView.
                connection.commitCorrection(CorrectionInfo(
                        connection.expectedSelectionEnd - stringToCommit.length,
                        typedWord, stringToCommit))
                val prevWordsContext = if (autoCorrectionOrNull != null)
                        autoCorrectionOrNull.prevWordsContext
                        else ""
                StatsUtils.onAutoCorrection(typedWord, stringToCommit, isBatchMode,
                        dictionaryFacilitator, prevWordsContext)
                StatsUtils.onWordCommitAutoCorrect(stringToCommit, isBatchMode)
            } else {
                StatsUtils.onWordCommitUserTyped(stringToCommit, isBatchMode)
            }
        }
    }

    /**
     * Commits the chosen word to the text field and saves it for later retrieval.
     *
     * @param settingsValues the current values of the settings.
     * @param chosenWord the word we want to commit.
     * @param commitType the type of the commit, as one of LastComposedWord.COMMIT_TYPE_*
     * @param separatorString the separator that's causing the commit, or NOT_A_SEPARATOR if none.
     */
    private fun commitChosenWord(settingsValues: SettingsValues, chosenWord: String,
            commitType: Int, separatorString: String) {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "commitChosenWord() : [" + chosenWord + "]")
        }
        // essentially reverted https://github.com/lineageos/android_packages_inputmethods_LatinIME/commit/ee6de1466bc98e27bd414c9a7451f2aee3f9e721
        // can't find any drawback (performance, neither when setting nor when reading)
        val chosenWordWithSuggestions = getTextWithSuggestionSpan(latinIME, chosenWord,
                suggestedWords, getDictionaryFacilitatorLocale())
        // When we are composing word, get n-gram context from the 2nd previous word because the
        // 1st previous word is the word to be committed. Otherwise get n-gram context from the 1st
        // previous word.
        val ngramContext = connection.getNgramContextFromNthPreviousWord(
                settingsValues.spacingAndPunctuations, if (wordComposer.isComposingWord) 2 else 1)
        // Caret: what the correction recorder needs to know before the word is gone from the composer
        val typing = wordComposer.isComposingWord && !wordComposer.isBatchMode
        val wordStart = if (typing) connection.expectedSelectionStart - wordComposer.typedWord.length else -1
        val wouldHaveBeen = if (suggestedWords.willAutoCorrect && suggestedWords.size() > SuggestedWords.INDEX_OF_AUTO_CORRECTION)
                suggestedWords.getWord(SuggestedWords.INDEX_OF_AUTO_CORRECTION) else wordComposer.typedWord
        connection.commitText(chosenWordWithSuggestions, 1)
        // Add the word to the user history dictionary
        performAdditionToUserHistoryDictionary(settingsValues, chosenWord, ngramContext)
        // TODO: figure out here if this is an auto-correct or if the best word is actually
        // what user typed. Note: currently this is done much later in
        // LastComposedWord#didCommitTypedWord by string equality of the remembered
        // strings.
        lastComposedWord = wordComposer.commitWord(commitType, chosenWord, separatorString, ngramContext)
        if (typing) CorrectionRecorder.onCommit(latinIME, KeyboardSwitcher.getInstance().keyboard, lastComposedWord.inputPointers,
            lastComposedWord.typedWord, chosenWord, wouldHaveBeen, commitType == LastComposedWord.COMMIT_TYPE_MANUAL_PICK,
            wordStart, FutoSwipe.textBeforeWord)
    }

    /**
     *  Wraps the selected text into the codepoints. If the same codepoints are
     *  already present before and after the selection, they are removed instead.
     *  (unfortunately Android long-press selection may select one of those codepoints, so unwrap may not work well)
     */
    private fun wrapSelection(start: Int, end: Int) {
        val selected = connection.getSelectedText(0)
        if (connection.getCodePointBeforeCursor() == start) {
            // maybe we want to revert it
            val afterCursor = connection.getTextAfterCursor(1, 0)!!
            if (afterCursor.length > 0 && afterCursor[0].code == end) {
                connection.setSelection(connection.expectedSelectionStart - 1, connection.expectedSelectionEnd + 1)
                connection.commitText(selected, 1)
                return
            }
        }
        connection.commitText(StringUtils.newSingleCodePointString(start) + selected + StringUtils.newSingleCodePointString(end), 1)
    }

    /**
     * Retry resetting caches in the rich input connection.
     * <p>
     * When the editor can't be accessed we can't reset the caches, so we schedule a retry.
     * This method handles the retry, and re-schedules a new retry if we still can't access.
     * We only retry up to 5 times before giving up.
     *
     * @param tryResumeSuggestions Whether we should resume suggestions or not.
     * @param remainingTries How many times we may try again before giving up.
     * @return whether true if the caches were successfully reset, false otherwise.
     */
    fun retryResetCachesAndReturnSuccess(tryResumeSuggestions: Boolean,
            remainingTries: Int, handler: LatinIME.UIHandler): Boolean {
        val shouldFinishComposition = connection.hasSelection()
                || !connection.isCursorPositionKnown
        if (!connection.resetCachesUponCursorMoveAndReturnSuccess(
                connection.expectedSelectionStart, connection.expectedSelectionEnd,
                shouldFinishComposition)) {
            if (0 < remainingTries) {
                handler.postResetCaches(tryResumeSuggestions, remainingTries - 1)
                return false
            }
            // If remainingTries is 0, we should stop waiting for new tries, however we'll still
            // return true as we need to perform other tasks (for example, loading the keyboard).
        }
        connection.tryFixIncorrectCursorPosition()
        if (tryResumeSuggestions) {
            handler.postResumeSuggestions(true /* shouldDelay */)
        }
        return true
    }

    // we used to provide keyboard, settingsValues and keyboardShiftMode, but every time read it from current instance anyway
    fun getSuggestedWords(inputStyle: Int, sequenceNumber: Int, callback: OnGetSuggestedWordsCallback) {
        val keyboard = KeyboardSwitcher.getInstance().keyboard
        if (keyboard == null) {
            callback.onGetSuggestedWords(SuggestedWords.emptyInstance)
            return
        }
        if (inputStyle != SuggestedWords.INPUT_STYLE_UPDATE_BATCH && inputStyle != SuggestedWords.INPUT_STYLE_TAIL_BATCH
                        && isInlineEmojiSearchAction()) {
            searchForEmojiInline(sequenceNumber, callback)
            return
        }
        val settingsValues = Settings.values
        wordComposer.adviseCapitalizedModeBeforeFetchingSuggestions(
                getActualCapsMode(settingsValues, KeyboardSwitcher.getInstance().getKeyboardCapsMode()))
        FutoSwipe.textBeforeWord = textBeforeWord()
        try {
            val words = suggest.getSuggestedWords(wordComposer.copy(),
                    getNgramContextFromNthPreviousWordForSuggestion(
                    settingsValues.spacingAndPunctuations,
                    // Get the word on which we should search the bigrams. If we are composing
                    // a word, it's whatever is *before* the half-committed word in the buffer,
                    // hence 2; if we aren't, we should just skip whitespace if any, so 1.
                    if (wordComposer.isComposingWord) 2 else 1),
                    keyboard,
                    settingsValues.settingsValuesForSuggestion,
                    settingsValues.autoCorrectEnabled,
                    inputStyle, sequenceNumber)
            callback.onGetSuggestedWords(words)
        } catch (e: Exception) {
            // better go without suggestions than have the keyboard crash
            Log.e(TAG, "Error fetching suggested words, using empty words instead", e)
            callback.onGetSuggestedWords(SuggestedWords.emptyInstance)
            KeyboardSwitcher.getInstance().showToast("Error getting suggestions", true)
        }
    }

    /**
     * Used as an injection point for each call of
     * {@link RichInputConnection#setComposingText(CharSequence, int)}.
     *
     * <p>Currently using this method is optional and you can still directly call
     * {@link RichInputConnection#setComposingText(CharSequence, int)}, but it is recommended to
     * use this method whenever possible.<p>
     * <p>TODO: Should we move this mechanism to {@link RichInputConnection}?</p>
     *
     * @param newComposingText the composing text to be set
     * @param newCursorPosition the new cursor position
     */
    private fun setComposingTextInternal(newComposingText: CharSequence,
            newCursorPosition: Int) {
        setComposingTextInternalWithBackgroundColor(newComposingText, newCursorPosition,
                Color.TRANSPARENT, newComposingText.length)
    }

    /**
     * Equivalent to {@link #setComposingTextInternal(CharSequence, int)} except that this method
     * allows to set {@link BackgroundColorSpan} to the composing text with the given color.
     *
     * <p>TODO: Currently the background color is exclusive with the black underline, which is
     * automatically added by the framework. We need to change the framework if we need to have both
     * of them at the same time.</p>
     * <p>TODO: Should we move this method to {@link RichInputConnection}?</p>
     *
     * @param newComposingText the composing text to be set
     * @param newCursorPosition the new cursor position
     * @param backgroundColor the background color to be set to the composing text. Set
     * {@link Color#TRANSPARENT} to disable the background color.
     * @param coloredTextLength the length of text, in Java chars, which should be rendered with
     * the given background color.
     */
    private fun setComposingTextInternalWithBackgroundColor(newComposingText: CharSequence,
            newCursorPosition: Int, backgroundColor: Int, coloredTextLength: Int) {
        val composingTextToBeSet: CharSequence
        if (backgroundColor == Color.TRANSPARENT) {
            composingTextToBeSet = newComposingText
        } else {
            val spannable = SpannableString(newComposingText)
            val backgroundColorSpan = BackgroundColorSpan(backgroundColor)
            val spanLength = Math.min(coloredTextLength, spannable.length)
            spannable.setSpan(backgroundColorSpan, 0, spanLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
            composingTextToBeSet = spannable
        }
        if (!connection.setComposingText(composingTextToBeSet, newCursorPosition))
            // inconsistency in set and found composing text, better cancel composing (should be restarted automatically)
            wordComposer.reset()
    }

    /**
     * Gets an object allowing private IME commands to be sent to the
     * underlying editor.
     * @return An object for sending private commands to the underlying editor.
     */
    val privateCommandPerformer: PrivateCommandPerformer
        get() = connection

    /**
     * Gets the expected index of the first char of the composing span within the editor's text.
     * Returns a negative value in case there appears to be no valid composing span.
     *
     * @see #getComposingLength()
     * @see RichInputConnection#hasSelection()
     * @see RichInputConnection#isCursorPositionKnown()
     * @see RichInputConnection#getExpectedSelectionStart()
     * @see RichInputConnection#getExpectedSelectionEnd()
     * @return The expected index in Java chars of the first char of the composing span.
     */
    // TODO: try and see if we can get rid of this method. Ideally the users of this class should
    // never need to know this.
    fun getComposingStart(): Int {
        if (!connection.isCursorPositionKnown || connection.hasSelection()) {
            return -1
        }
        return connection.expectedSelectionStart - wordComposer.size()
    }

    /**
     * Gets the expected length in Java chars of the composing span.
     * May be 0 if there is no valid composing span.
     * @see #getComposingStart()
     * @return The expected length of the composing span.
     */
    // TODO: try and see if we can get rid of this method. Ideally the users of this class should
    // never need to know this.
    val composingLength: Int
        get() = wordComposer.size()

    private fun paste(packageName: String) {
        // some apps ignore KeyEvent.KEYCODE_PASTE, other apps ignore CRTL+V
        // we try to deal with this by committing the text if the clipboard content is simply text,
        // and use KeyEvent.KEYCODE_PASTE otherwise except for apps that are known to ignore it
        val primaryClip = latinIME.clipboardHistoryManager.getPrimaryClipIfText()
        if (primaryClip != null) {
            connection.commitText(primaryClip, 1)
            return
        }
        Log.d(TAG, "pasting non-text content")
        if (AppWorkarounds.doesntCareAboutKeycodePaste(packageName) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            sendDownUpKeyEventWithMetaState(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
        else sendDownUpKeyEvent(KeyEvent.KEYCODE_PASTE)
    }

    private fun enterInlineEmojiSearchIfNeeded(codePoint: Int, settingsValues: SettingsValues) {
        if (emojiDictionaryFacilitator == null || isInlineEmojiSearchAction()) {
            return
        }

        if (isStartOfInlineEmojiSearch(codePoint, connection.getCodePointBeforeCursor(), connection.getCharBeforeBeforeCursor(),
                                       settingsValues)) {
            if (wordComposer.isBatchMode)
                // when entering inline emoji search with glide typing, the action is not set when the word is added
                // this means we don't detect inline search mode, so we remove to word now
                BackgroundGatheringCache.removeLast(wordComposer.typedWord)
            setInlineEmojiSearchAction(true)
        }
    }

    private fun updateInlineEmojiSearch() {
        setInlineEmojiSearchAction(getInlineEmojiSearchString() != null)
    }

    private fun setInlineEmojiSearchAction(on: Boolean) {
        if (on != isInlineEmojiSearchAction()) {
            KeyboardSwitcher.getInstance().loadKeyboard(latinIME.getCurrentInputEditorInfo(), Settings.values,
                            latinIME.getCurrentAutoCapsState(), latinIME.getCurrentRecapitalizeState(),
                            if (on) KeyboardLayoutSet.InternalAction(KeyCode.INLINE_EMOJI_SEARCH_DONE,"!icon/close_history") else null)
        }
    }

    private fun searchForEmojiInline(sequenceNumber: Int, callback: OnGetSuggestedWordsCallback) {
        // getInlineEmojiSearchString() returns null when there is no emoji dictionary facilitator
        val input = getInlineEmojiSearchString()
        val facilitator = emojiDictionaryFacilitator
        if (input.isNullOrEmpty() || facilitator == null) {
            callback.onGetSuggestedWords(SuggestedWords.emptyInstance)
            return
        }

        val suggestions = facilitator.getSuggestions(input.splitOnWhitespace())
        if (suggestions.isEmpty()) {
            callback.onGetSuggestedWords(SuggestedWords.emptyInstance)
            return
        }

        val typedWordInfo = SuggestedWordInfo(input, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED,
                                 Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        val suggestedWordInfos = ArrayList<SuggestedWordInfo>(suggestions.size + 1)
        suggestedWordInfos.add(typedWordInfo)
        for (suggestion in suggestions) {
            if (suggestion.isEmoji()) {
                Suggest.addDebugInfo(suggestion, input)
                suggestedWordInfos.add(Suggest.useDefaultEmojiSkinTone(suggestion))
            }
        }
        callback.onGetSuggestedWords(SuggestedWords(suggestedWordInfos, suggestions.rawSuggestions, typedWordInfo,
                                     false /* typedWordValid */, false /* autoCorrectEnabled */,
                                     false /* isObsoleteSuggestions */, SuggestedWords.INPUT_STYLE_TYPING, sequenceNumber))
    }

    private fun deleteTextReplacedByEmoji() {
        connection.finishComposingText()
        val inlineEmojiSearchString = getInlineEmojiSearchString()
        if (inlineEmojiSearchString != null) {
            connection.deleteTextBeforeCursor(inlineEmojiSearchString.length + 1)
        } else {
            Log.e("inlineEmojiSearch", "Inconsistent state - inlineEmojiSearchString is null")
        }
    }

    private fun getInlineEmojiSearchString(): String? {
        if (emojiDictionaryFacilitator == null) {
            return null
        }

        return getInlineEmojiSearchString(connection.getTextBeforeCursor(50, 0))
    }

    fun updateEmojiDictionary(locale: Locale) {
        if (Settings.values.inlineEmojiSearch && Settings.values.needsToLookupSuggestions() && ! latinIME.isEmojiSearch()) {
            val facilitator = emojiDictionaryFacilitator
            if (facilitator == null || ! facilitator.isForLocale(locale)) {
                closeEmojiDictionary()
                val dictFile = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, "emoji", latinIME)
                val dictionary = if (dictFile != null) DictionaryFactory.getDictionary(dictFile, locale) else null
                emojiDictionaryFacilitator = if (dictionary != null) SingleDictionaryFacilitator(dictionary) else null
            }
        } else {
            closeEmojiDictionary()
        }
    }

    private fun closeEmojiDictionary() {
        emojiDictionaryFacilitator?.let {
            it.closeDictionaries()
            emojiDictionaryFacilitator = null
        }
    }

    companion object {
        private val TAG: String = InputLogic::class.java.simpleName
        private const val INLINE_EMOJI_SEARCH_MARKER = ':'
        private val EMPTY_CODE_POINTS = IntArray(0)

        /**
         * Returns whether this code point can be followed by the double-space-to-period transformation.
         * <p>
         * See #maybeDoubleSpaceToPeriod for details.
         * Generally, most word characters can be followed by the double-space-to-period transformation,
         * while most punctuation can't. Some punctuation however does allow for this to take place
         * after them, like the closing parenthesis for example.
         *
         * @param codePoint the code point after which we may want to apply the transformation
         * @return whether it's fine to apply the transformation after this code point.
         */
        private fun canBeFollowedByDoubleSpacePeriod(codePoint: Int): Boolean {
            // TODO: This should probably be a blacklist rather than a whitelist.
            // TODO: This should probably be language-dependant...
            return Character.isLetterOrDigit(codePoint)
                    || codePoint == Constants.CODE_SINGLE_QUOTE
                    || codePoint == Constants.CODE_DOUBLE_QUOTE
                    || codePoint == Constants.CODE_CLOSING_PARENTHESIS
                    || codePoint == Constants.CODE_CLOSING_SQUARE_BRACKET
                    || codePoint == Constants.CODE_CLOSING_CURLY_BRACKET
                    || codePoint == Constants.CODE_CLOSING_ANGLE_BRACKET
                    || codePoint == Constants.CODE_PLUS
                    || codePoint == Constants.CODE_PERCENT
                    || Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()
        }

        /**
         * Tests the passed word for resumability.
         * <p>
         * We can resume suggestions on words whose first code point is a word code point (with some
         * nuances: check the code for details).
         *
         * @param settings the current values of the settings.
         * @param word the word to evaluate.
         * @return whether it's fine to resume suggestions on this word.
         */
        private fun isResumableWord(settings: SettingsValues, word: String): Boolean {
            val firstCodePoint = word.codePointAt(0)
            return settings.isWordCodePoint(firstCodePoint)
                    && Constants.CODE_SINGLE_QUOTE != firstCodePoint
                    && Constants.CODE_DASH != firstCodePoint
        }

        /**
         * Make a {@link app.caret.latin.SuggestedWords} object containing a typed word
         * and obsolete suggestions.
         * See {@link app.caret.latin.SuggestedWords#getTypedWordAndPreviousSuggestions(
         *      SuggestedWordInfo, app.caret.latin.SuggestedWords)}.
         * @param typedWordInfo The typed word as a SuggestedWordInfo.
         * @param previousSuggestedWords The previously suggested words.
         * @return Obsolete suggestions with the newly typed word.
         */
        @JvmStatic
        fun retrieveOlderSuggestions(typedWordInfo: SuggestedWordInfo,
                previousSuggestedWords: SuggestedWords): SuggestedWords {
            val oldSuggestedWords = if (previousSuggestedWords.isPunctuationSuggestions())
                    SuggestedWords.emptyInstance else previousSuggestedWords
            val typedWordAndPreviousSuggestions: ArrayList<SuggestedWords.SuggestedWordInfo> =
                    SuggestedWords.getTypedWordAndPreviousSuggestions(typedWordInfo, oldSuggestedWords)
            return SuggestedWords(typedWordAndPreviousSuggestions, null /* rawSuggestions */,
                    typedWordInfo, false /* typedWordValid */, false /* hasAutoCorrectionCandidate */,
                    true /* isObsoleteSuggestions */, oldSuggestedWords.inputStyle,
                    SuggestedWords.NOT_A_SEQUENCE_NUMBER)
        }

        private fun isInlineEmojiSearchAction(): Boolean {
            val keyboard = KeyboardSwitcher.getInstance().keyboard
            val internalAction = if (keyboard != null) keyboard.id.internalAction else null
            return internalAction != null && internalAction.code == KeyCode.INLINE_EMOJI_SEARCH_DONE
        }

        /**
         * Gets the inline emoji search string. Rules:
         * - string starts with last colon before the cursor
         * - the character before the colon has to be non-word, non-digit
         * - the character after the colon has to be non-space
         * - the string cannot contain newlines
         * <p>
         * Public for testing.
         */
        @JvmStatic
        fun getInlineEmojiSearchString(textBeforeCursor: CharSequence?): String? {
            if (textBeforeCursor == null) {
                return null
            }

            val text = textBeforeCursor.toString()
            val markerIndex = text.lastIndexOf(INLINE_EMOJI_SEARCH_MARKER)
            if (markerIndex < 0 || text.length < markerIndex + 2) {
                return null
            }

            if (markerIndex > 0 && ! isValidInlineEmojiSearchPreviousChar(text.codePointAt(markerIndex - 1), Settings.values)) {
                return null
            }

            if (Character.isWhitespace(text.codePointAt(markerIndex + 1))) {
                return null
            }

            if (text.indexOf('\n', markerIndex + 2) >= 0) {
                return null
            }

            return text.substring(markerIndex + 1)
        }

        // public for testing
        @JvmStatic
        fun isStartOfInlineEmojiSearch(codePoint: Int, codePointBeforeCursor: Int, charBeforeBeforeCursor: Int,
                                                          settingsValues: SettingsValues): Boolean {
            return codePointBeforeCursor == INLINE_EMOJI_SEARCH_MARKER.code && codePoint != INLINE_EMOJI_SEARCH_MARKER.code
                    && ! Character.isWhitespace(codePoint) && isValidInlineEmojiSearchPreviousChar(charBeforeBeforeCursor, settingsValues)
        }

        private fun isValidInlineEmojiSearchPreviousChar(charBeforeBeforeCursor: Int, settingsValues: SettingsValues): Boolean {
            return ! Character.isDigit(charBeforeBeforeCursor) && ! settingsValues.isWordCodePoint(charBeforeBeforeCursor)
        }
    }
}
