/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.CharacterStyle

import app.caret.keyboard.KeyboardSwitcher
import app.caret.latin.common.combiningRange
import app.caret.latin.define.DebugFlags
import app.caret.latin.settings.Settings
import app.caret.latin.utils.Log
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat

import app.caret.latin.common.Constants
import app.caret.latin.common.StringUtils
import app.caret.latin.common.endsWithWordCodepoint
import app.caret.latin.common.getTouchedWordRange
import app.caret.latin.common.hasLetterBeforeLastSpaceBeforeCursor
import app.caret.latin.common.lastGrapheme
import app.caret.latin.common.nonWordCodePointAndNoSpaceBeforeCursor
import app.caret.latin.common.UnicodeSurrogate
import app.caret.latin.inputlogic.PrivateCommandPerformer
import app.caret.latin.settings.SpacingAndPunctuations
import app.caret.latin.utils.CapsModeUtils
import app.caret.latin.utils.DebugLogUtils
import app.caret.latin.utils.NgramContextUtils
import app.caret.latin.utils.StatsUtils
import app.caret.latin.utils.TextRange

import java.util.concurrent.TimeUnit

/**
 * Enrichment class for InputConnection to simplify interaction and add functionality.
 * <p>
 * This class serves as a wrapper to be able to simply add hooks to any calls to the underlying
 * InputConnection. It also keeps track of a number of things to avoid having to call upon IPC
 * all the time to find out what text is in the buffer, when we need it to determine caps mode
 * for example.
 */
class RichInputConnection(private val parent: InputMethodService) : PrivateCommandPerformer {

    /**
     * This variable contains an expected value for the selection start position. This is where the
     * cursor or selection start may end up after all the keyboard-triggered updates have passed. We
     * keep this to compare it to the actual selection start to guess whether the move was caused by
     * a keyboard command or not.
     * It's not really the selection start position: the selection start may not be there yet, and
     * in some cases, it may never arrive there.
     */
    var expectedSelectionStart = INVALID_CURSOR_POSITION // in chars, not code points
        private set
    /**
     * The expected selection end.  Only differs from expectedSelectionStart if a non-empty selection is
     * expected.  The same caveats as expectedSelectionStart apply.
     */
    var expectedSelectionEnd = INVALID_CURSOR_POSITION // in chars, not code points
        private set
    /**
     * This contains the committed text immediately preceding the cursor and the composing
     * text, if any. It is refreshed when the cursor moves by calling upon the TextView.
     */
    private val committedTextBeforeComposingText = StringBuilder()
    /**
     * This contains the currently composing text, as LatinIME thinks the TextView is seeing it.
     */
    private val composingText = StringBuilder()

    /**
     * This variable is a temporary object used in [commitText]
     * to avoid object creation.
     */
    private val tempObjectForCommitText = SpannableStringBuilder()

    private var ic: InputConnection? = null
    private var nestLevel: Int = 0

    /**
     * The timestamp of the last slow InputConnection operation
     */
    private var lastSlowInputConnectionTime = -SLOW_INPUTCONNECTION_PERSIST_MS

    private val isConnected: Boolean
        get() = ic != null

    /**
     * Returns whether or not the underlying InputConnection is slow. When true, we want to avoid
     * calling InputConnection methods that trigger an IPC round-trip (e.g., getTextAfterCursor).
     */
    fun hasSlowInputConnection(): Boolean {
        return (SystemClock.uptimeMillis() - lastSlowInputConnectionTime) <=
                        SLOW_INPUTCONNECTION_PERSIST_MS
    }

    fun onStartInput() {
        lastSlowInputConnectionTime = -SLOW_INPUTCONNECTION_PERSIST_MS
    }

    private fun checkConsistencyForDebug() {
        val r = ExtractedTextRequest()
        r.hintMaxChars = 0
        r.hintMaxLines = 0
        r.token = 1
        r.flags = 0
        val et = ic!!.getExtractedText(r, 0)
        val beforeCursor = getTextBeforeCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE,
                0)
        val `internal` = StringBuilder(committedTextBeforeComposingText)
                .append(composingText)
        if (null == et || null == beforeCursor) return
        val actualLength = Math.min(beforeCursor.length, `internal`.length)
        if (`internal`.length > actualLength) {
            `internal`.delete(0, `internal`.length - actualLength)
        }
        val reference = if (beforeCursor.length <= actualLength) beforeCursor.toString()
                else beforeCursor.subSequence(beforeCursor.length - actualLength,
                        beforeCursor.length).toString()
        if (et.selectionStart != expectedSelectionStart
                || !(reference == `internal`.toString())) {
            val context = "Expected selection start = " + expectedSelectionStart +
                    "\nActual selection start = " + et.selectionStart +
                    "\nExpected text = " + `internal`.length + " " + `internal` +
                    "\nActual text = " + reference.length + " " + reference
            (parent as LatinIME).debugDumpStateAndCrashWithException(context)
        } else {
            Log.e(TAG, DebugLogUtils.getStackTrace(2))
            Log.e(TAG, "Exp <> Actual : " + expectedSelectionStart + " <> " + et.selectionStart)
        }
    }

    fun beginBatchEdit() {
        if (++nestLevel == 1) {
            ic = parent.getCurrentInputConnection()
            ic?.beginBatchEdit()
        } else {
            if (DBG) {
                throw RuntimeException("Nest level too deep")
            }
            Log.e(TAG, "Nest level too deep : " + nestLevel)
        }
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
    }

    fun endBatchEdit() {
        if (nestLevel <= 0) Log.e(TAG, "Batch edit not in progress!") // TODO: exception instead
        if (--nestLevel == 0) {
            ic?.endBatchEdit()
        }
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
    }

    /**
     * Reset the cached text and retrieve it again from the editor.
     * <p>
     * This should be called when the cursor moved. It's possible that we can't connect to
     * the application when doing this; notably, this happens sometimes during rotation, probably
     * because of a race condition in the framework. In this case, we just can't retrieve the
     * data, so we empty the cache and note that we don't know the new cursor position, and we
     * return false so that the caller knows about this and can retry later.
     *
     * @param newSelStart the new position of the selection start, as received from the system.
     * @param newSelEnd the new position of the selection end, as received from the system.
     * @param shouldFinishComposition whether we should finish the composition in progress.
     * @return true if we were able to connect to the editor successfully, false otherwise. When
     *   this method returns false, the caches could not be correctly refreshed so they were only
     *   reset: the caller should try again later to return to normal operation.
     */
    fun resetCachesUponCursorMoveAndReturnSuccess(newSelStart: Int,
            newSelEnd: Int, shouldFinishComposition: Boolean): Boolean {
        composingText.setLength(0)
        val didReloadTextSuccessfully = reloadTextCache()
        if (!didReloadTextSuccessfully) {
            Log.d(TAG, "Will try to retrieve text later.")
            // selection is set to INVALID_CURSOR_POSITION if reloadTextCache return false
            return false
        }
        if (expectedSelectionStart != newSelStart || expectedSelectionEnd != newSelEnd) {
            expectedSelectionStart = newSelStart
            expectedSelectionEnd = newSelEnd
            reloadTextCache()
            if (expectedSelectionStart != newSelStart || expectedSelectionEnd != newSelEnd) {
                Log.i(TAG, "resetCachesUponCursorMove: tried to set "+newSelStart+"/"+newSelEnd+", but input field has "+expectedSelectionStart+"/"+expectedSelectionEnd)
            }
        }
        if (shouldFinishComposition) {
            ic?.finishComposingText()
        }
        return true
    }

    /**
     * Reload the cached text from the InputConnection.
     *
     * @return true if successful
     */
    private fun reloadTextCache(): Boolean {
        committedTextBeforeComposingText.setLength(0)
        // Clearing composing text was not in original AOSP and OpenBoard, but why? should actually
        // be necessary when reloading text. Only when called by setSelection, composingText isn't
        // always empty, but looks like things still work normally
        composingText.setLength(0)
        ic = parent.getCurrentInputConnection()
        // Call upon the inputconnection directly since our own method is using the cache, and
        // we want to refresh it.
        val textBeforeCursor = getTextBeforeCursorAndDetectLaggyConnection(
                OPERATION_RELOAD_TEXT_CACHE,
                SLOW_INPUT_CONNECTION_ON_FULL_RELOAD_MS,
                Constants.EDITOR_CONTENTS_CACHE_SIZE,
                0 /* flags */)
        if (null == textBeforeCursor) {
            // For some reason the app thinks we are not connected to it. This looks like a
            // framework bug... Fall back to ground state and return false.
            expectedSelectionStart = INVALID_CURSOR_POSITION
            expectedSelectionEnd = INVALID_CURSOR_POSITION
            Log.e(TAG, "Unable to connect to the editor to retrieve text.")
            return false
        }
        committedTextBeforeComposingText.append(textBeforeCursor)
        return true
    }

    private fun reloadCursorPosition() {
        val ic = ic ?: return
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (et == null) return
        expectedSelectionStart = et.selectionStart + et.startOffset
        expectedSelectionEnd = et.selectionEnd + et.startOffset
    }

    private fun checkBatchEdit() {
        if (nestLevel != 1) {
            // TODO: exception instead
            Log.e(TAG, "Batch edit level incorrect : " + nestLevel)
            Log.e(TAG, DebugLogUtils.getStackTrace(4))
        }
    }

    fun finishComposingText() {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        // TODO: this is not correct! The cursor is not necessarily after the composing text.
        // In the practice right now this is only called when input ends so it will be reset so
        // it works, but it's wrong and should be fixed.
        committedTextBeforeComposingText.append(composingText)
        composingText.setLength(0)
        ic?.finishComposingText()
    }

    fun commitCodePoint(codePoint: Int) {
        commitText(StringUtils.newSingleCodePointString(codePoint), 1)
    }

    /**
     * Calls [InputConnection.commitText].
     *
     * @param text The text to commit. This may include styles.
     * @param newCursorPosition The new cursor position around the text.
     */
    fun commitText(nullableText: CharSequence?, newCursorPosition: Int) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        val text = nullableText!!
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "committing "+text.length+" characters")
        committedTextBeforeComposingText.append(text)
        // TODO: the following is exceedingly error-prone. Right now when the cursor is in the
        //  middle of the composing word composingText only holds the part of the composing text
        //  that is before the cursor, so this actually works, but it's terribly confusing. Fix this.
        expectedSelectionStart += text.length - composingText.length
        expectedSelectionEnd = expectedSelectionStart
        composingText.setLength(0)
        val ic = ic
        if (ic != null) {
            tempObjectForCommitText.clear()
            tempObjectForCommitText.append(text)
            val spans = tempObjectForCommitText.getSpans(
                    0, text.length, CharacterStyle::class.java)
            for (span in spans) {
                val spanStart = tempObjectForCommitText.getSpanStart(span)
                val spanEnd = tempObjectForCommitText.getSpanEnd(span)
                val spanFlags = tempObjectForCommitText.getSpanFlags(span)
                // We have to adjust the end of the span to include an additional character.
                // This is to avoid splitting a unicode surrogate pair.
                // See app.caret.latin.common.Constants.UnicodeSurrogate
                // See https://b.corp.google.com/issues/19255233
                if (0 < spanEnd && spanEnd < tempObjectForCommitText.length) {
                    val spanEndChar = tempObjectForCommitText[spanEnd - 1]
                    val nextChar = tempObjectForCommitText[spanEnd]
                    if (UnicodeSurrogate.isLowSurrogate(spanEndChar)
                            && UnicodeSurrogate.isHighSurrogate(nextChar)) {
                        tempObjectForCommitText.setSpan(span, spanStart, spanEnd + 1, spanFlags)
                    }
                }
            }
            ic.commitText(tempObjectForCommitText, newCursorPosition)
        }
    }

    fun getSelectedText(flags: Int): CharSequence? {
        return ic?.getSelectedText(flags)
    }

    fun canDeleteCharacters(): Boolean {
        return expectedSelectionStart > 0
    }

    fun hasTextAfterCursor(): Boolean {
        val after = getTextAfterCursor(1, 0)
        return !TextUtils.isEmpty(after)
    }

    /**
     * Gets the caps modes we should be in after this specific string.
     * <p>
     * This returns a bit set of TextUtils#CAP_MODE_*, masked by the inputType argument.
     * This method also supports faking an additional space after the string passed in argument,
     * to support cases where a space will be added automatically, like in phantom space
     * state for example.
     * Note that for English, we are using American typography rules (which are not specific to
     * American English, it's just the most common set of rules for English).
     *
     * @param inputType a mask of the caps modes to test for.
     * @param spacingAndPunctuations the values of the settings to use for locale and separators.
     * @param hasSpaceBefore if we should consider there should be a space after the string.
     * @return the caps modes that should be on as a set of bits
     */
    fun getCursorCapsMode(inputType: Int,
            spacingAndPunctuations: SpacingAndPunctuations?, hasSpaceBefore: Boolean): Int {
        ic = parent.getCurrentInputConnection()
        if (!isConnected) {
            return Constants.TextUtils.CAP_MODE_OFF
        }
        if (!TextUtils.isEmpty(composingText)) {
            if (hasSpaceBefore) {
                // If we have some composing text and a space before, then we should have
                // MODE_CHARACTERS and MODE_WORDS on.
                return (TextUtils.CAP_MODE_CHARACTERS or TextUtils.CAP_MODE_WORDS) and inputType
            }
            // We have some composing text - we should be in MODE_CHARACTERS only.
            return TextUtils.CAP_MODE_CHARACTERS and inputType
        }
        // TODO: this will generally work, but there may be cases where the buffer contains SOME
        //  information but not enough to determine the caps mode accurately. This may happen after
        //  heavy pressing of delete, for example DEFAULT_TEXT_CACHE_SIZE - 5 times or so.
        //  getCapsMode should be updated to be able to return a "not enough info" result so that
        //  we can get more context only when needed.
        if (TextUtils.isEmpty(committedTextBeforeComposingText) && 0 != expectedSelectionStart) {
            if (!reloadTextCache()) {
                Log.w(TAG, "Unable to connect to the editor. "
                        + "Setting caps mode without knowing text.")
            }
        }
        // This never calls InputConnection#getCapsMode - in fact, it's a static method that
        // never blocks or initiates IPC.
        // TODO: don't call #toString() here. Instead, all accesses to
        //  committedTextBeforeComposingText should be done on the main thread.
        return CapsModeUtils.getCapsMode(committedTextBeforeComposingText.toString(), inputType,
                spacingAndPunctuations, hasSpaceBefore)
    }

    fun getCodePointBeforeCursor(): Int {
        val text: CharSequence = if (composingText.length == 0) committedTextBeforeComposingText else composingText
        val length = text.length
        if (length < 1) return Constants.NOT_A_CODE
        return Character.codePointBefore(text, length)
    }

    fun getCharBeforeBeforeCursor(): Int {
        if (composingText.length >= 2) return composingText[composingText.length - 2].code
        val length = committedTextBeforeComposingText.length
        if (composingText.length == 1) {
            if (length < 1) return Constants.NOT_A_CODE
            return committedTextBeforeComposingText[length - 1].code
        }
        if (length < 2) return Constants.NOT_A_CODE
        return committedTextBeforeComposingText[length - 2].code
    }

    fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val cachedLength = committedTextBeforeComposingText.length + composingText.length
        // If we have enough characters to satisfy the request, or if we have all characters in
        // the text field, then we can return the cached version right away.
        // However, if we don't have an expected cursor position, then we should always
        // go fetch the cache again (as it happens, INVALID_CURSOR_POSITION < 0, so we need to
        // test for this explicitly)
        if (INVALID_CURSOR_POSITION != expectedSelectionStart
                && (cachedLength >= n || cachedLength >= expectedSelectionStart)) {
            val s = StringBuilder(committedTextBeforeComposingText.toString())
            // We call #toString() here to create a temporary object.
            // In some situations, this method is called on a worker thread, and it's possible
            // the main thread touches the contents of composingText while this worker thread
            // is suspended, because composingText is a StringBuilder. This may lead to crashes,
            // so we call #toString() on it. That will result in the return value being strictly
            // speaking wrong, but since this is used for basing bigram probability off, and
            // it's only going to matter for one getSuggestions call, it's fine in the practice.
            s.append(composingText.toString())
            if (s.length > n) {
                s.delete(0, s.length - n)
            }
            return s
        }
        return getTextBeforeCursorAndDetectLaggyConnection(
                OPERATION_GET_TEXT_BEFORE_CURSOR,
                SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                n, flags)
    }

    private fun getTextBeforeCursorAndDetectLaggyConnection(
            operation: Int, timeout: Long, n: Int, flags: Int): CharSequence? {
        ic = parent.getCurrentInputConnection()
        val ic = ic ?: return null
        val startTime = SystemClock.uptimeMillis()
        val result = ic.getTextBeforeCursor(n, flags)
        detectLaggyConnection(operation, timeout, startTime)

        // only do the consistency check if we actually have text (i.e. we're not coming from some reload / reset)
        if ((committedTextBeforeComposingText.length > 0 || composingText.length > 0)
                && result != null && !checkTextBeforeCursorConsistency(result)) {
            // inconsistent state can occur for (at least) two reasons
            // 1. the app actively changes text field content, e.g. joplin when deleting list markers like "2."
            // 2. the app has outdated contents in the text field, e.g. com.farmerbb.notepad returns the
            //     just deleted char right after deletion, instead of the correct one
            //     todo: understand where this inconsistent state comes from, is it really the other app's fault, or is it HeliBoard?
            Log.w(TAG, "cached text out of sync, reloading")
            reloadCursorPosition()
            reloadTextCache()
        }
        return result
    }

    // checks whether the end of cached text before cursor is the same as end of the given CharSequence
    // this is done to find inconsistencies that arise in some text fields when characters are deleted, but also in some other cases
    // only checks the end for performance reasons
    // may need to check more than just the last character, because the text may end in e.g. rrrrr and even there a single car offset should be found
    private fun checkTextBeforeCursorConsistency(textField: CharSequence): Boolean {
        val lastIndex = textField.length - 1
        if (lastIndex == -1) return true
        val lastChar = textField[lastIndex]
        val composingLength = composingText.length
        for (i in 0..lastIndex) {
            // get last minus i character and compare
            val currentTextFieldChar = textField[lastIndex - i]
            val currentCachedChar: Char
            if (i < composingLength) {
                // take char from composing text
                currentCachedChar = composingText[composingLength - 1 - i]
            } else {
                // take last char from committedTextBeforeComposingText, consider composing length
                val index = committedTextBeforeComposingText.length - 1 - (i - composingLength)
                if (index < committedTextBeforeComposingText.length && index >= 0)
                    // todo: this can still cause an exception if committedTextBeforeComposingText changes just at the right time (different threads!)
                    currentCachedChar = committedTextBeforeComposingText[index]
                else return lastIndex > 100 // still let it pass if the same character is repeated many times, but cached text too short
            }

            if (currentTextFieldChar != currentCachedChar)
                // different character -> inconsistent
                return false

            if (lastChar != currentTextFieldChar)
                // not the same, and no inconsistency found so far -> unlikely there is one that won't be found later -> return early
                return true
        }
        return true // no inconsistency found after going through everything
    }

    fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        return getTextAfterCursorAndDetectLaggyConnection(
                OPERATION_GET_TEXT_AFTER_CURSOR,
                SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                n, flags)
    }

    private fun getTextAfterCursorAndDetectLaggyConnection(
            operation: Int, timeout: Long, n: Int, flags: Int): CharSequence? {
        ic = parent.getCurrentInputConnection()
        val ic = ic ?: return null
        val startTime = SystemClock.uptimeMillis()
        val result = ic.getTextAfterCursor(n, flags)
        detectLaggyConnection(operation, timeout, startTime)
        return result
    }

    private fun detectLaggyConnection(operation: Int, timeout: Long, startTime: Long) {
        val duration = SystemClock.uptimeMillis() - startTime
        if (duration >= timeout) {
            val operationName = OPERATION_NAMES[operation]
            Log.w(TAG, "Slow InputConnection: " + operationName + " took " + duration + " ms.")
            StatsUtils.onInputConnectionLaggy(operation, duration)
            lastSlowInputConnectionTime = SystemClock.uptimeMillis()
        } else if (duration < timeout / 5 && hasSlowInputConnection()) {
            // we have a fast connection now, maybe the slowness was just a hickup
            lastSlowInputConnectionTime -= SLOW_INPUTCONNECTION_PERSIST_MS / 2
            Log.d(TAG, "InputConnection: much faster now, reducing persist time")
        }
    }

    fun deleteTextBeforeCursor(beforeLength: Int) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        // TODO: the following is incorrect if the cursor is not immediately after the composition.
        //  Right now we never come here in this case because we reset the composing state before we
        //  come here in this case, but we need to fix this.
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "deleting "+beforeLength+" characters before cursor")
        val remainingChars = composingText.length - beforeLength
        if (remainingChars >= 0) {
            composingText.setLength(remainingChars)
        } else {
            composingText.setLength(0)
            // Never cut under 0
            val len = Math.max(committedTextBeforeComposingText.length
                    + remainingChars, 0)
            committedTextBeforeComposingText.setLength(len)
        }
        if (expectedSelectionStart > beforeLength) {
            expectedSelectionStart -= beforeLength
            expectedSelectionEnd -= beforeLength
        } else {
            // There are fewer characters before the cursor in the buffer than we are being asked to
            // delete. Only delete what is there, and update the end with the amount deleted.
            expectedSelectionEnd -= expectedSelectionStart
            expectedSelectionStart = 0
        }
        ic?.deleteSurroundingText(beforeLength, 0)
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
    }

    fun performEditorAction(actionId: Int) {
        ic = parent.getCurrentInputConnection()
        ic?.performEditorAction(actionId)
    }


    fun sendKeyEvent(keyEvent: KeyEvent) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DebugFlags.DEBUG_ENABLED) // no details, might be too sensitive
            Log.d(TAG, "key event with action "+keyEvent.getAction()+", is control: "+Character.isISOControl(keyEvent.getUnicodeChar()))
        if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
            if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
            // This method is only called for enter or backspace when speaking to old applications
            // (target SDK <= 15 (Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)), or for digits.
            // When talking to new applications we never use this method because it's inherently
            // racy and has unpredictable results, but for backward compatibility we continue
            // sending the key events for only Enter and Backspace because some applications
            // mistakenly catch them to do some stuff.
            when (keyEvent.getKeyCode()) {
                KeyEvent.KEYCODE_ENTER -> {
                    committedTextBeforeComposingText.append("\n")
                    expectedSelectionStart += 1
                    expectedSelectionEnd = expectedSelectionStart
                }
                KeyEvent.KEYCODE_DEL -> {
                    if (0 == composingText.length) {
                        if (committedTextBeforeComposingText.length > 0) {
                            committedTextBeforeComposingText.delete(
                                    committedTextBeforeComposingText.length - 1,
                                    committedTextBeforeComposingText.length)
                        }
                    } else {
                        composingText.delete(composingText.length - 1, composingText.length)
                    }
                    if (expectedSelectionStart > 0 && expectedSelectionStart == expectedSelectionEnd) {
                        // TODO: Handle surrogate pairs.
                        expectedSelectionStart -= 1
                    }
                    expectedSelectionEnd = expectedSelectionStart
                }
                KeyEvent.KEYCODE_UNKNOWN -> {
                    if (null != keyEvent.getCharacters()) {
                        committedTextBeforeComposingText.append(keyEvent.getCharacters())
                        expectedSelectionStart += keyEvent.getCharacters().length
                        expectedSelectionEnd = expectedSelectionStart
                    }
                }
                else -> {
                    val codePoint = keyEvent.getUnicodeChar()
                    if (!Character.isISOControl(codePoint)) { // don't append text if there is no actual text
                        val text = StringUtils.newSingleCodePointString(codePoint)
                        committedTextBeforeComposingText.append(text)
                        expectedSelectionStart += text.length
                        expectedSelectionEnd = expectedSelectionStart
                    }
                }
            }
        }
        ic?.sendKeyEvent(keyEvent)
    }

    fun setComposingRegion(start: Int, end: Int) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        val moveBy = expectedSelectionStart - start // determine now, as expectedSelectionStart may change in getTextBeforeCursor
        val textBeforeCursor =
                getTextBeforeCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE + (end - start), 0)
        committedTextBeforeComposingText.setLength(0)
        // also clear composing text, otherwise we may append existing text
        // this can happen when we're a little out of sync with the editor
        composingText.setLength(0)
        if (!textBeforeCursor.isNullOrEmpty()) {
            // The cursor is not necessarily at the end of the composing text, but we have its
            // position in expectedSelectionStart and expectedSelectionEnd. In this case we want the start
            // of the text, so we should use expectedSelectionStart. In other words, the composing
            // text starts (expectedSelectionStart - start) characters before the end of textBeforeCursor
            val indexOfStartOfComposingText = Math.max(textBeforeCursor.length - moveBy, 0)
            composingText.append(textBeforeCursor.subSequence(indexOfStartOfComposingText,
                    textBeforeCursor.length))
            committedTextBeforeComposingText.append(
                    textBeforeCursor.subSequence(0, indexOfStartOfComposingText))
        }
        ic?.setComposingRegion(start, end)
    }

    // return whether the text was (probably) set correctly
    // unfortunately this is necessary in some cases
    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        expectedSelectionStart += text.length - composingText.length
        expectedSelectionEnd = expectedSelectionStart
        composingText.setLength(0)
        composingText.append(text)
        // TODO: support values of newCursorPosition != 1. At this time, this is never called with
        //  newCursorPosition != 1.
        val ic = ic
        if (ic != null) {
            if (DebugFlags.DEBUG_ENABLED)
                Log.d(TAG, "setting composing text of length "+text.length) // don't log actual text
            ic.setComposingText(text, newCursorPosition)
            if (!Settings.getValues().mInputAttributes.mShouldShowSuggestions && text.length > 0) {
                // We have a field that disables suggestions, but still committed text is set.
                // This might lead to weird bugs (e.g. https://github.com/HeliBorg/HeliBoard/issues/225), so better do
                // a sanity check whether the wanted text has been set.
                // Note that the check may also fail because the text field is not yet updated, so we don't want to check everything!
                val lastChar = ic.getTextBeforeCursor(1, 0)
                if (lastChar == null || lastChar.length == 0 || text[text.length - 1] != lastChar[0]) {
                    Log.w(TAG, "did set " + text + ", but got " + ic.getTextBeforeCursor(text.length, 0) + " as last character")
                    return false
                }
            }
        }
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        return true
    }

    /**
     * Set the selection of the text editor.
     * <p>
     * Calls through to {@link InputConnection#setSelection(int, int)}.
     *
     * @param start the character index where the selection should start.
     * @param end the character index where the selection should end.
     * @return Returns true on success, false on failure: either the input connection is no longer
     * valid when setting the selection or when retrieving the text cache at that point, or
     * invalid arguments were passed.
     */
    fun setSelection(start: Int, end: Int): Boolean {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "setting selection from "+start+" to "+end)

        if (start < 0 || end < 0) {
            return false
        }
        if (start > end) {
            expectedSelectionStart = end
            expectedSelectionEnd = start
        } else {
            expectedSelectionStart = start
            expectedSelectionEnd = end
        }
        val ic = ic
        if (ic != null) {
            val isIcValid = ic.setSelection(start, end)
            if (!isIcValid) {
                return false
            }
        }
        return reloadTextCache()
    }

    fun selectAll() {
        if (!isConnected) return
        if (expectedSelectionStart != expectedSelectionEnd && expectedSelectionStart == 0 && !hasTextAfterCursor()) { // all text already selected
            ic!!.setSelection(expectedSelectionEnd, expectedSelectionEnd)
        } else ic!!.performContextMenuAction(android.R.id.selectAll)
    }

    fun selectWord(spacingAndPunctuations: SpacingAndPunctuations, script: String) {
        val ic = ic ?: return
        if (expectedSelectionStart != expectedSelectionEnd) { // already something selected
            ic.setSelection(expectedSelectionEnd, expectedSelectionEnd)
            return
        }
        val range = getWordRangeAtCursor(spacingAndPunctuations, script)
        if (range == null) return
        // getWordRangeAtCursor refreshed the connection and returns null when there is none,
        // so read the field again instead of using the one captured above
        this.ic?.setSelection(expectedSelectionStart - range.getNumberOfCharsInWordBeforeCursor(), expectedSelectionStart + range.getNumberOfCharsInWordAfterCursor())
    }

    fun copyText(getSelection: Boolean) {
        var text: CharSequence? = null
        if (getSelection) {
            // copy selected text, and if nothing is selected copy the whole text
            text = getSelectedText(InputConnection.GET_TEXT_WITH_STYLES)
        }
        if (text == null || text.length == 0) {
            // we have no selection, get the whole text
            val etr = ExtractedTextRequest()
            etr.flags = InputConnection.GET_TEXT_WITH_STYLES
            etr.hintMaxChars = Int.MAX_VALUE
            val et = ic!!.getExtractedText(etr, 0)
            if (et == null) return
            text = et.text
        }
        if (text == null || text.length == 0) return
        val cm = parent.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("copied text", text))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            KeyboardSwitcher.getInstance().showToast(parent.getString(R.string.toast_msg_clipboard_copy), true)
        }
    }

    fun commitCorrection(correctionInfo: CorrectionInfo) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        // This has no effect on the text field and does not change its content. It only makes
        // TextView flash the text for a second based on indices contained in the argument.
        ic?.commitCorrection(correctionInfo)
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
    }

    fun commitCompletion(completionInfo: CompletionInfo) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        var text = completionInfo.getText()
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "committing completion of length "+text.length) // don't log actual text
        // text should never be null, but just in case, it's better to insert nothing than to crash
        if (null == text) text = ""
        committedTextBeforeComposingText.append(text)
        expectedSelectionStart += text.length - composingText.length
        expectedSelectionEnd = expectedSelectionStart
        composingText.setLength(0)
        ic?.commitCompletion(completionInfo)
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
    }

    fun getNgramContextFromNthPreviousWord(
            spacingAndPunctuations: SpacingAndPunctuations, n: Int): NgramContext {
        ic = parent.getCurrentInputConnection()
        if (!isConnected) {
            return NgramContext.EMPTY_PREV_WORDS_INFO
        }
        val prev = getTextBeforeCursor(NUM_CHARS_TO_GET_BEFORE_CURSOR, 0)
        if (DEBUG_PREVIOUS_TEXT && null != prev) {
            val checkLength = NUM_CHARS_TO_GET_BEFORE_CURSOR - 1
            val reference = if (prev.length <= checkLength) prev.toString()
                    else prev.subSequence(prev.length - checkLength, prev.length).toString()
            // TODO: right now the following works because composingText holds the part of the
            //  composing text that is before the cursor, but this is very confusing. We should
            //  fix it.
            val internal = StringBuilder()
                    .append(committedTextBeforeComposingText).append(composingText)
            if (internal.length > checkLength) {
                internal.delete(0, internal.length - checkLength)
                if (!(reference == internal.toString())) {
                    val context = "Expected text = " + internal + "\nActual text = " + reference
                    (parent as LatinIME).debugDumpStateAndCrashWithException(context)
                }
            }
        }
        return NgramContextUtils.getNgramContextFromNthPreviousWord(prev, spacingAndPunctuations, n)
    }

    /**
     * Returns the text surrounding the cursor.
     *
     * @param spacingAndPunctuations the rules for spacing and punctuation
     * @param script the script we consider to be writing words, as one of ScriptUtils.SCRIPT_*
     * @return a range containing the text surrounding the cursor (does NOT include the current selection, if any)
     */
    fun getWordRangeAtCursor(spacingAndPunctuations: SpacingAndPunctuations,
            script: String): TextRange? {
        ic = parent.getCurrentInputConnection()
        if (!isConnected) {
            return null
        }
        val before = getTextBeforeCursorAndDetectLaggyConnection(
                OPERATION_GET_WORD_RANGE_AT_CURSOR,
                SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                NUM_CHARS_TO_GET_BEFORE_CURSOR,
                InputConnection.GET_TEXT_WITH_STYLES)
        val after = getTextAfterCursorAndDetectLaggyConnection(
                OPERATION_GET_WORD_RANGE_AT_CURSOR,
                SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                NUM_CHARS_TO_GET_AFTER_CURSOR,
                InputConnection.GET_TEXT_WITH_STYLES)
        if (before == null || after == null) {
            return null
        }
        return getTouchedWordRange(before, after, script, spacingAndPunctuations)
    }

    fun isCursorTouchingWord(spacingAndPunctuations: SpacingAndPunctuations,
            checkTextAfter: Boolean): Boolean {
        if (checkTextAfter && isCursorFollowedByWordCharacter(spacingAndPunctuations)) {
            // If what's after the cursor is a word character, then we're touching a word.
            return true
        }
        if (composingText.length > 0) {
            // a composing region should always count as a word
            return true
        }
        return endsWithWordCodepoint(committedTextBeforeComposingText.toString(), spacingAndPunctuations)
    }

    fun isCursorFollowedByWordCharacter(
            spacingAndPunctuations: SpacingAndPunctuations): Boolean {
        val after = getTextAfterCursor(1, 0)
        if (after.isNullOrEmpty()) {
            return false
        }
        val codePointAfterCursor = Character.codePointAt(after, 0)
        return !spacingAndPunctuations.isWordSeparator(codePointAfterCursor)
                && !spacingAndPunctuations.isWordConnector(codePointAfterCursor)
    }

    fun removeTrailingSpace() {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        val codePointBeforeCursor = getCodePointBeforeCursor()
        if (Constants.CODE_SPACE == codePointBeforeCursor) {
            deleteTextBeforeCursor(1)
        }
    }

    fun sameAsTextBeforeCursor(text: CharSequence): Boolean {
        val beforeText = getTextBeforeCursor(text.length, 0)
        return TextUtils.equals(text, beforeText)
    }

    fun revertDoubleSpacePeriod(spacingAndPunctuations: SpacingAndPunctuations): Boolean {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        // Here we test whether we indeed have a period and a space before us. This should not
        // be needed, but it's there just in case something went wrong.
        val textBeforeCursor = getTextBeforeCursor(2, 0)
        if (!TextUtils.equals(spacingAndPunctuations.mSentenceSeparatorAndSpace,
                textBeforeCursor)) {
            // Theoretically we should not be coming here if there isn't ". " before the
            // cursor, but the application may be changing the text while we are typing, so
            // anything goes. We should not crash.
            Log.d(TAG, "Tried to revert double-space combo but we didn't find \""
                    + spacingAndPunctuations.mSentenceSeparatorAndSpace
                    + "\" just before the cursor.")
            return false
        }
        // Double-space results in ". ". A backspace to cancel this should result in a single
        // space in the text field, so we replace ". " with a single space.
        deleteTextBeforeCursor(2)
        val singleSpace = " "
        commitText(singleSpace, 1)
        return true
    }

    fun revertSwapPunctuation(): Boolean {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        // Here we test whether we indeed have a space and something else before us. This should not
        // be needed, but it's there just in case something went wrong.
        val textBeforeCursor = getTextBeforeCursor(2, 0)
        // NOTE: This does not work with surrogate pairs. Hopefully when the keyboard is able to
        // enter surrogate pairs this code will have been removed.
        if (textBeforeCursor.isNullOrEmpty()
                || (Constants.CODE_SPACE != textBeforeCursor[1].code)) {
            // We may only come here if the application is changing the text while we are typing.
            // This is quite a broken case, but not logically impossible, so we shouldn't crash,
            // but some debugging log may be in order.
            Log.d(TAG, "Tried to revert a swap of punctuation but we didn't "
                    + "find a space just before the cursor.")
            return false
        }
        deleteTextBeforeCursor(2)
        val text = " " + textBeforeCursor.subSequence(0, 1)
        commitText(text, 1)
        return true
    }

    /**
     * Heuristic to determine if this is an expected update of the cursor.
     * <p>
     * Sometimes updates to the cursor position are late because of their asynchronous nature.
     * This method tries to determine if this update is one, based on the values of the cursor
     * position in the update, and the currently expected position of the cursor according to
     * LatinIME's internal accounting. If this is not a belated expected update, then it should
     * mean that the user moved the cursor explicitly.
     * This is quite robust, but of course it's not perfect. In particular, it will fail in the
     * case we get an update A, the user types in N characters so as to move the cursor to A+N but
     * we don't get those, and then the user places the cursor between A and A+N, and we get only
     * this update and not the ones in-between. This is almost impossible to achieve even trying
     * very very hard.
     *
     * @param oldSelStart The value of the old selection in the update.
     * @param newSelStart The value of the new selection in the update.
     * @param oldSelEnd The value of the old selection end in the update.
     * @param newSelEnd The value of the new selection end in the update.
     * @return whether this is a belated expected update or not.
     */
    fun isBelatedExpectedUpdate(oldSelStart: Int, newSelStart: Int,
            oldSelEnd: Int, newSelEnd: Int, composingSpanStart: Int, composingSpanEnd: Int): Boolean {
        // This update is "belated" if we are expecting it. That is, expectedSelectionStart and
        // expectedSelectionEnd match the new values that the TextView is updating TO.
        if (expectedSelectionStart == newSelStart && expectedSelectionEnd == newSelEnd) {
            if (composingSpanEnd - composingSpanStart < composingText.length) {
                // composing span is smaller than expected, maybe changed by the app (see #1141)
                // larger composing span is ok, because composingText only contains the word up to the cursor
                return false
            }
            return true
        }
        // This update is not belated if expectedSelectionStart and expectedSelectionEnd match the old
        // values, and one of newSelStart or newSelEnd is updated to a different value. In this
        // case, it is likely that something other than the IME has moved the selection endpoint
        // to the new value.
        if (expectedSelectionStart == oldSelStart && expectedSelectionEnd == oldSelEnd
                && (oldSelStart != newSelStart || oldSelEnd != newSelEnd)) return false
        // If neither of the above two cases hold, then the system may be having trouble keeping up
        // with updates. If 1) the selection is a cursor, 2) newSelStart is between oldSelStart
        // and expectedSelectionStart, and 3) newSelEnd is between oldSelEnd and expectedSelectionEnd, then
        // assume a belated update.
        return (newSelStart == newSelEnd)
                && (newSelStart - oldSelStart) * (expectedSelectionStart - newSelStart) >= 0
                && (newSelEnd - oldSelEnd) * (expectedSelectionEnd - newSelEnd) >= 0
    }

    /**
     * Looks at the text just before the cursor to find out if it looks like a URL.
     * <p>
     * The weakest point here is, if we don't have enough text bufferized, we may fail to realize
     * we are in URL situation, but other places in this class have the same limitation and it
     * does not matter too much in the practice.
     */
    fun textBeforeCursorLooksLikeURL(): Boolean {
        return StringUtils.lastPartLooksLikeURL(committedTextBeforeComposingText)
    }

    fun nonWordCodePointAndNoSpaceBeforeCursor(spacingAndPunctuations: SpacingAndPunctuations): Boolean {
        return app.caret.latin.common.nonWordCodePointAndNoSpaceBeforeCursor(committedTextBeforeComposingText, spacingAndPunctuations)
    }

    fun spaceBeforeCursor(): Boolean {
        return committedTextBeforeComposingText.indexOf(" ") != -1
    }

    fun getCharCountToDeleteBeforeCursor(): Int {
        val lastCodePoint = getCodePointBeforeCursor()
        if (StringUtils.mightBeEmoji(lastCodePoint) || Character.isSupplementaryCodePoint(lastCodePoint) || combiningRange.contains(lastCodePoint)) {
            val text = getTextBeforeCursor(NUM_CHARS_TO_GET_BEFORE_CURSOR, 0)
            if (text.isNullOrEmpty()) return 1
            return text.toString().lastGrapheme.length
        }
        return 1
    }

    fun hasLetterBeforeLastSpaceBeforeCursor(): Boolean {
        return app.caret.latin.common.hasLetterBeforeLastSpaceBeforeCursor(committedTextBeforeComposingText)
    }

    fun wordBeforeCursorMayBeEmail(): Boolean {
        return committedTextBeforeComposingText.lastIndexOf(" ") < committedTextBeforeComposingText.lastIndexOf("@")
    }

    fun textBeforeCursorUntilLastWhitespaceOrDoubleSlash(): CharSequence {
        var startIndex = 0
        var previousWasSlash = false
        for (i in committedTextBeforeComposingText.length - 1 downTo 0) {
            val c = committedTextBeforeComposingText[i]
            if (Character.isWhitespace(c)) {
                startIndex = i + 1
                break
            }
            if (c == '/') {
                if (previousWasSlash) {
                    startIndex = i + 2
                    break
                }
                previousWasSlash = true
            } else {
                previousWasSlash = false
            }
        }
        return committedTextBeforeComposingText.subSequence(startIndex, committedTextBeforeComposingText.length)
    }

    /**
     * Looks at the text just before the cursor to find out if we are inside a double quote.
     * <p>
     * As with #textBeforeCursorLooksLikeURL, this is dependent on how much text we have cached.
     * However this won't be a concrete problem in most situations, as the cache is almost always
     * long enough for this use.
     */
    fun isInsideDoubleQuoteOrAfterDigit(): Boolean {
        return StringUtils.isInsideDoubleQuoteOrAfterDigit(committedTextBeforeComposingText)
    }

    /**
     * Try to get the text from the editor to expose lies the framework may have been
     * telling us. Concretely, when the device rotates and when the keyboard reopens in the same
     * text field after having been closed with the back key, the frameworks tells us about where
     * the cursor used to be initially in the editor at the time it first received the focus; this
     * may be completely different from the place it is upon rotation. Since we don't have any
     * means to get the real value, try at least to ask the text view for some characters and
     * detect the most damaging cases: when the cursor position is declared to be much smaller
     * than it really is.
     * (renamed the method, because we clearly ask the editorInfo to provide initial selection, no reason to complain about it
     * being initial and thus possibly outdated)
     */
    fun tryFixIncorrectCursorPosition() {
        ic = parent.getCurrentInputConnection()
        val textBeforeCursor = getTextBeforeCursor(
                Constants.EDITOR_CONTENTS_CACHE_SIZE, 0)
        val selectedText = ic?.getSelectedText(0 /* flags */)
        if (null == textBeforeCursor ||
                (!TextUtils.isEmpty(selectedText) && expectedSelectionEnd == expectedSelectionStart)) {
            // If textBeforeCursor is null, we have no idea what kind of text field we have or if
            // thinking about the "cursor position" actually makes any sense. In this case we
            // remember a meaningless cursor position. Contrast this with an empty string, which is
            // valid and should mean the cursor is at the start of the text.
            // Also, if we expect we don't have a selection but we DO have non-empty selected text,
            // then the framework lied to us about the cursor position. In this case, we should just
            // revert to the most basic behavior possible for the next action (backspace in
            // particular comes to mind), so we remember a meaningless cursor position which should
            // result in degraded behavior from the next input.
            // Interestingly, in either case, chances are any action the user takes next will result
            // in a call to onUpdateSelection, which should set things right.
            expectedSelectionEnd = Constants.NOT_A_CURSOR_POSITION
            expectedSelectionStart = expectedSelectionEnd
        } else {
            val textLength = textBeforeCursor.length
            if (textLength < Constants.EDITOR_CONTENTS_CACHE_SIZE
                    && (textLength > expectedSelectionStart
                            ||  expectedSelectionStart < Constants.EDITOR_CONTENTS_CACHE_SIZE)) {
                // It should not be possible to have only one of those variables be
                // NOT_A_CURSOR_POSITION, so if they are equal, either the selection is zero-sized
                // (simple cursor, no selection) or there is no cursor/we don't know its pos
                val wasEqual = expectedSelectionStart == expectedSelectionEnd
                expectedSelectionStart = textLength
                // We can't figure out the value of mLastSelectionEnd :(
                // But at least if it's smaller than mLastSelectionStart something is wrong,
                // and if they used to be equal we also don't want to make it look like there is a
                // selection.
                if (wasEqual || expectedSelectionStart > expectedSelectionEnd) {
                    expectedSelectionEnd = expectedSelectionStart
                }
            } else {
                // better re-read the correct position instead of guessing from incomplete data
                reloadCursorPosition()
            }
        }
    }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
        ic = parent.getCurrentInputConnection()
        val ic = ic ?: return false
        return ic.performPrivateCommand(action, data)
    }



    /**
     * @return whether there is a selection currently active.
     */
    fun hasSelection(): Boolean {
        return expectedSelectionEnd != expectedSelectionStart
    }

    fun isCursorPositionKnown(): Boolean {
        return INVALID_CURSOR_POSITION != expectedSelectionStart
    }

    /**
     * Requests the editor to call back {@link InputMethodManager#updateCursorAnchorInfo}.
     * @param enableMonitor {@code true} to request the editor to call back the method whenever the
     * cursor/anchor position is changed.
     * @param requestImmediateCallback {@code true} to request the editor to call back the method
     * as soon as possible to notify the current cursor/anchor position to the input method.
     * @return {@code true} if the request is accepted. Returns {@code false} otherwise, which
     * includes "not implemented" or "rejected" or "temporarily unavailable" or whatever which
     * prevents the application from fulfilling the request. (TODO: Improve the API when it turns
     * out that we actually need more detailed error codes)
     */
    fun requestCursorUpdates(enableMonitor: Boolean, requestImmediateCallback: Boolean): Boolean {
        ic = parent.getCurrentInputConnection()
        val ic = ic ?: return false
        val cursorUpdateMode = (if (enableMonitor) InputConnection.CURSOR_UPDATE_MONITOR else 0) or
            (if (requestImmediateCallback) InputConnection.CURSOR_UPDATE_IMMEDIATE else 0)
        return ic.requestCursorUpdates(cursorUpdateMode)
    }

    // doesn't work in many apps that support normal clipboard pasting, possibly just because they don't have mime types in editorInfo
    fun commitContent(contentInfo: InputContentInfoCompat, editorInfo: EditorInfo) {
        ic = parent.getCurrentInputConnection()
        val ic = ic ?: return
        InputConnectionCompat.commitContent(ic, editorInfo, contentInfo, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
    }

    companion object {
        private const val TAG = "RichInputConnection"
        private const val DBG = false
        private const val DEBUG_PREVIOUS_TEXT = false
        private const val DEBUG_BATCH_NESTING = false
        private const val NUM_CHARS_TO_GET_BEFORE_CURSOR = 40
        private const val NUM_CHARS_TO_GET_AFTER_CURSOR = 40
        private const val INVALID_CURSOR_POSITION = -1

        /**
         * The amount of time a [reloadTextCache] call needs to take for the keyboard to enter
         * the [hasSlowInputConnection] state.
         */
        private const val SLOW_INPUT_CONNECTION_ON_FULL_RELOAD_MS: Long = 1000
        /**
         * The amount of time a [getTextBeforeCursor] or [getTextAfterCursor] call needs
         * to take for the keyboard to enter the [hasSlowInputConnection] state.
         */
        private const val SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS: Long = 200

        private const val OPERATION_GET_TEXT_BEFORE_CURSOR = 0
        private const val OPERATION_GET_TEXT_AFTER_CURSOR = 1
        private const val OPERATION_GET_WORD_RANGE_AT_CURSOR = 2
        private const val OPERATION_RELOAD_TEXT_CACHE = 3
        private val OPERATION_NAMES = arrayOf(
                "GET_TEXT_BEFORE_CURSOR",
                "GET_TEXT_AFTER_CURSOR",
                "GET_WORD_RANGE_AT_CURSOR",
                "RELOAD_TEXT_CACHE")

        /**
         * The amount of time the keyboard will persist in the [hasSlowInputConnection] state
         * after observing a slow InputConnection event.
         */
        private val SLOW_INPUTCONNECTION_PERSIST_MS = TimeUnit.MINUTES.toMillis(10)
    }
}
