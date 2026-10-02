/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import app.caret.event.CombinerChain
import app.caret.event.Event
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.ComposedData
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.common.InputPointers
import app.caret.latin.common.StringUtils
import app.caret.latin.define.DebugFlags
import app.caret.latin.define.DecoderSpecificConstants

import java.util.ArrayList
import java.util.Collections

/**
 * A place to store the currently composing word with information such as adjacent key codes as well
 */
class WordComposer {
    private var combinerChain: CombinerChain? = null
    private var mCombiningSpec: String? = null // Memory so that we don't uselessly recreate the combiner chain

    // The list of events that served to compose this string.
    private val mEvents: ArrayList<Event>?
    private val inputPointers: InputPointers?
    private var mAutoCorrection: SuggestedWordInfo? = null
    private var mIsResumed = false
    private var mIsBatchMode = false
    // A memory of the last rejected batch mode suggestion, if any. This goes like this: the user
    // gestures a word, is displeased with the results and hits backspace, then gestures again.
    // At the very least we should avoid re-suggesting the same thing, and to do that we memorize
    // the rejected suggestion in this variable.
    // TODO: this should be done in a comprehensive way by the User History feature instead of
    // as an ad-hockery here.
    private var rejectedBatchModeSuggestion: String? = null

    // Cache these values for performance
    private var typedWordCache: CharSequence? = null
    private var capsCount = 0
    private var digitsCount = 0
    private var capitalizedMode: CapsMode? = null
    // This is the number of code points entered so far. This is not limited to MAX_WORD_LENGTH.
    // In general, this contains the size of mPrimaryKeyCodes, except when this is greater than
    // MAX_WORD_LENGTH in which case mPrimaryKeyCodes only contain the first MAX_WORD_LENGTH
    // code points.
    private var codePointSize = 0
    private var cursorPositionWithinWord = 0

    /**
     * Whether the composing word has the only first char capitalized.
     */
    private var isOnlyFirstCharCapitalized = false

    constructor() {
        combinerChain = CombinerChain("", "")
        mEvents = ArrayList()
        inputPointers = InputPointers(MAX_WORD_LENGTH)
        mAutoCorrection = null
        mIsResumed = false
        mIsBatchMode = false
        cursorPositionWithinWord = 0
        rejectedBatchModeSuggestion = null
        refreshTypedWordCache()
    }

    /** creates a copy of the internal state (currently reuses inputPointers though, so don't use this for other purpose than getting suggestions) */
    fun copy(): WordComposer {
        return WordComposer(this)
    }

    @Suppress("CopyConstructorMissesField")
    private constructor(other: WordComposer) {
        mEvents = null
        inputPointers = other.inputPointers // ideally we would have an actual copy, but for current use it should be ok
        mAutoCorrection = other.mAutoCorrection
        mIsResumed = other.mIsResumed
        mIsBatchMode = other.mIsBatchMode
        rejectedBatchModeSuggestion = other.rejectedBatchModeSuggestion
        typedWordCache = other.typedWordCache
        capsCount = other.capsCount
        digitsCount = other.digitsCount
        capitalizedMode = other.capitalizedMode
        codePointSize = other.codePointSize
        cursorPositionWithinWord = other.cursorPositionWithinWord
        isOnlyFirstCharCapitalized = other.isOnlyFirstCharCapitalized
    }

    fun getComposedDataSnapshot(): ComposedData {
        return ComposedData(getInputPointers()!!, isBatchMode(), typedWordCache!!.toString())
    }

    /**
     * Restart the combiners, possibly with a new spec.
     * @param combiningSpec The spec string for combining. This is found in the extra value.
     */
    fun restartCombining(combiningSpec: String?) {
        val nonNullCombiningSpec = if (null == combiningSpec) "" else combiningSpec
        if (nonNullCombiningSpec != mCombiningSpec) {
            combinerChain = CombinerChain(combinerChain!!.composingWordWithCombiningFeedback.toString(), nonNullCombiningSpec)
            mCombiningSpec = nonNullCombiningSpec
        }
    }

    /**
     * Clear out the keys registered so far.
     */
    fun reset() {
        combinerChain!!.reset()
        mEvents!!.clear()
        mAutoCorrection = null
        capsCount = 0
        digitsCount = 0
        isOnlyFirstCharCapitalized = false
        mIsResumed = false
        mIsBatchMode = false
        rejectedBatchModeSuggestion = null
        refreshTypedWordCache()
        cursorPositionWithinWord = 0
    }

    private fun refreshTypedWordCache() {
        val oldSize = codePointSize
        typedWordCache = combinerChain!!.composingWordWithCombiningFeedback
        codePointSize = Character.codePointCount(typedWordCache!!, 0, typedWordCache!!.length)
        cursorPositionWithinWord += codePointSize - oldSize
        // should not be necessary after setting cursorPositionWithinWord at the end of reset, but still better not crash
        if (cursorPositionWithinWord < 0) cursorPositionWithinWord = 0
        else if (cursorPositionWithinWord > codePointSize) cursorPositionWithinWord = codePointSize
    }

    /**
     * Number of keystrokes in the composing word.
     * @return the number of keystrokes
     */
    fun size(): Int {
        return codePointSize
    }

    fun isSingleLetter(): Boolean {
        return size() == 1
    }

    fun isComposingWord(): Boolean {
        return size() > 0
    }

    fun getInputPointers(): InputPointers? {
        return inputPointers
    }

    /**
     * Process an event and return an event, and return a processed event to apply.
     * @param event the unprocessed event.
     * @return the processed event. Never null, but may be marked as consumed.
     */
    fun processEvent(event: Event): Event {
        val processedEvent = combinerChain!!.processEvent(mEvents, event)
        // The retained state of the combiner chain may have changed while processing the event,
        // so we need to update our cache.
        refreshTypedWordCache()
        mEvents!!.add(event)
        return processedEvent
    }

    /**
     * Apply a processed input event.
     *
     * All input events should be supported, including software/hardware events, characters as well
     * as deletions, multiple inputs and gestures.
     *
     * @param event the event to apply. Must not be null.
     */
    fun applyProcessedEvent(event: Event) {
        applyProcessedEvent(event, false)
    }

    // specifically for that KeyCode.MULTIPLE_CODE_POINTS Hangul event: try keeping cursor position
    // because typically nothing changes, todo: if really nothing changes maybe there is a better way to do it
    fun applyProcessedEvent(event: Event, keepCursorPosition: Boolean) {
        combinerChain!!.applyProcessedEvent(event)
        val primaryCode = event.codePoint
        val keyX = event.x
        val keyY = event.y
        val newIndex = size()
        refreshTypedWordCache()
        if (!keepCursorPosition || newIndex == codePointSize)
            cursorPositionWithinWord = codePointSize
        // We may have deleted the last one.
        if (0 == codePointSize) {
            isOnlyFirstCharCapitalized = false
        }
        if (KeyCode.DELETE != event.keyCode) {
            if (newIndex < MAX_WORD_LENGTH) {
                // In the batch input mode, the {@code inputPointers} holds batch input points and
                // shouldn't be overridden by the "typed key" coordinates
                // (See {@link #setBatchInputWord}).
                if (!mIsBatchMode) {
                    // TODO: Set correct pointer id and time
                    inputPointers!!.addPointerAt(newIndex, keyX, keyY, 0, 0)
                }
            }
            if (0 == newIndex) {
                isOnlyFirstCharCapitalized = Character.isUpperCase(primaryCode)
            } else {
                isOnlyFirstCharCapitalized = isOnlyFirstCharCapitalized
                        && !Character.isUpperCase(primaryCode)
            }
            if (Character.isUpperCase(primaryCode)) capsCount++
            if (Character.isDigit(primaryCode)) digitsCount++
        }
        mAutoCorrection = null
    }

    fun setCursorPositionWithinWord(posWithinWord: Int) {
        cursorPositionWithinWord = posWithinWord
        // TODO: compute where that puts us inside the events
    }

    fun isCursorFrontOrMiddleOfComposingWord(): Boolean {
        if (DebugFlags.DEBUG_ENABLED && cursorPositionWithinWord > codePointSize) {
            throw RuntimeException("Wrong cursor position : " + cursorPositionWithinWord
                    + "in a word of size " + codePointSize)
        }
        return cursorPositionWithinWord != codePointSize
    }

    fun isCursorInFrontOfComposingWord(): Boolean {
        return isComposingWord() && cursorPositionWithinWord == 0
    }

    /**
     * When the cursor is moved by the user, we need to update its position.
     * If it falls inside the currently composing word, we don't reset the composition, and
     * only update the cursor position.
     *
     * @param expectedMoveAmount How many java chars to move the cursor. Negative values move
     * the cursor backward, positive values move the cursor forward.
     * @return true if the cursor is still inside the composing word, false otherwise.
     */
    fun moveCursorByAndReturnIfInsideComposingWord(expectedMoveAmount: Int): Boolean {
        var actualMoveAmount = 0
        var cursorPos = cursorPositionWithinWord
        // TODO: Don't make that copy. We can do this directly from typedWordCache.
        val codePoints = StringUtils.toCodePointArray(typedWordCache!!)
        if (expectedMoveAmount >= 0) {
            // Moving the cursor forward for the expected amount or until the end of the word has
            // been reached, whichever comes first.
            while (actualMoveAmount < expectedMoveAmount && cursorPos < codePoints.size) {
                actualMoveAmount += Character.charCount(codePoints[cursorPos])
                ++cursorPos
            }
        } else {
            // Moving the cursor backward for the expected amount or until the start of the word
            // has been reached, whichever comes first.
            while (actualMoveAmount > expectedMoveAmount && cursorPos > 0) {
                --cursorPos
                actualMoveAmount -= Character.charCount(codePoints[cursorPos])
            }
        }
        // If the actual and expected amounts differ, we crossed the start or the end of the word
        // so the result would not be inside the composing word.
        if (actualMoveAmount != expectedMoveAmount) {
            return false
        }
        cursorPositionWithinWord = cursorPos
        combinerChain!!.applyProcessedEvent(combinerChain!!.processEvent(
                mEvents, Event.createCursorMovedEvent(cursorPos)))
        return true
    }

    fun setBatchInputPointers(batchPointers: InputPointers) {
        inputPointers!!.set(batchPointers)
        mIsBatchMode = true
    }

    fun setBatchInputWord(word: String) {
        reset()
        mIsBatchMode = true
        val length = word.length
        var i = 0
        while (i < length) {
            val codePoint = Character.codePointAt(word, i)
            // We don't want to override the batch input points that are held in inputPointers
            // (See {@link #add(int,int,int)}).
            val processedEvent = processEvent(Event.createEventForCodePointFromUnknownSource(codePoint))
            applyProcessedEvent(processedEvent)
            i = Character.offsetByCodePoints(word, i, 1)
        }
    }

    /**
     * Set the currently composing word to the one passed as an argument.
     * This will register NOT_A_COORDINATE for X and Ys, and use the passed keyboard for proximity.
     * @param codePoints the code points to set as the composing word.
     * @param coordinates the x, y coordinates of the key in the CoordinateUtils format
     */
    fun setComposingWord(codePoints: IntArray, coordinates: IntArray) {
        reset()
        val length = codePoints.size
        for (i in 0 until length) {
            val processedEvent =
                    processEvent(Event.createEventForCodePointFromAlreadyTypedText(codePoints[i],
                        CoordinateUtils.xFromArray(coordinates, i),
                        CoordinateUtils.yFromArray(coordinates, i))
                    )
            applyProcessedEvent(processedEvent)
        }
        mIsResumed = true
    }

    /**
     * Returns the word as it was typed, without any correction applied.
     * @return the word that was typed so far. Never returns null.
     */
    fun getTypedWord(): String {
        return typedWordCache!!.toString()
    }

    /**
     * Whether this composer is composing or about to compose a word in which only the first letter
     * is a capital.
     *
     * If we do have a composing word, we just return whether the word has indeed only its first
     * character capitalized. If we don't, then we return a value based on the capitalized mode,
     * which tell us what is likely to happen for the next composing word.
     *
     * @return capitalization preference
     */
    fun isOrWillBeOnlyFirstCharCapitalized(): Boolean {
        return if (isComposingWord()) isOnlyFirstCharCapitalized else (capitalizedMode != CapsMode.OFF)
    }

    /**
     * Whether or not all of the user typed chars are upper case
     * @return true if all user typed chars are upper case, false otherwise
     */
    fun isAllUpperCase(): Boolean {
        if (size() <= 1) {
            return capitalizedMode == CapsMode.AUTO_LOCKED || capitalizedMode == CapsMode.MANUAL_LOCKED
        }
        return capsCount == size()
    }

    fun wasShiftedNoLock(): Boolean {
        return capitalizedMode == CapsMode.AUTO || capitalizedMode == CapsMode.MANUAL
    }

    fun lastChar(): Char {
        if (!isComposingWord()) return '\u0000'
        return typedWordCache!![typedWordCache!!.length - 1]
    }

    /**
     * Returns true if more than one character is upper case, otherwise returns false.
     */
    fun isMostlyCaps(): Boolean {
        return capsCount > 1
    }

    /**
     * Returns true if we have digits in the composing word.
     */
    fun hasDigits(): Boolean {
        return digitsCount > 0
    }

    /**
     * Saves the caps mode at the start of composing.
     *
     * WordComposer needs to know about the caps mode for several reasons. The first is, we need
     * to know after the fact what the reason was, to register the correct form into the user
     * history dictionary: if the word was automatically capitalized, we should insert it in
     * all-lower case but if it's a manual pressing of shift, then it should be inserted as is.
     * Also, batch input needs to know about the current caps mode to display correctly
     * capitalized suggestions.
     * @param mode the mode at the time of start
     */
    fun setCapitalizedModeAtStartComposingTime(mode: CapsMode) {
        capitalizedMode = mode
    }

    /**
     * Before fetching suggestions, we don't necessarily know about the capitalized mode yet.
     *
     * If we don't have a composing word yet, we take a note of this mode so that we can then
     * supply this information to the suggestion process. If we have a composing word, then
     * the previous mode has priority over this.
     * @param mode the mode just before fetching suggestions
     */
    fun adviseCapitalizedModeBeforeFetchingSuggestions(mode: CapsMode) {
        if (!isComposingWord()) {
            capitalizedMode = mode
        }
    }

    /**
     * Returns whether the word was automatically capitalized.
     * @return whether the word was automatically capitalized
     */
    fun wasAutoCapitalized(): Boolean {
        return capitalizedMode == CapsMode.AUTO_LOCKED || capitalizedMode == CapsMode.AUTO
    }

    /**
     * Sets the auto-correction for this word.
     */
    fun setAutoCorrection(autoCorrection: SuggestedWordInfo?) {
        mAutoCorrection = autoCorrection
    }

    /**
     * @return the auto-correction for this word, or null if none.
     */
    fun getAutoCorrectionOrNull(): SuggestedWordInfo? {
        return mAutoCorrection
    }

    /**
     * @return whether we started composing this word by resuming suggestion on an existing string
     */
    fun isResumed(): Boolean {
        return mIsResumed
    }

    // `type' should be one of the LastComposedWord.COMMIT_TYPE_* constants above.
    // committedWord should contain suggestion spans if applicable.
    fun commitWord(type: Int, committedWord: CharSequence,
            separatorString: String, ngramContext: NgramContext): LastComposedWord {
        // Note: currently, we come here whenever we commit a word. If it's a MANUAL_PICK
        // or a DECIDED_WORD we may cancel the commit later; otherwise, we should deactivate
        // the last composed word to ensure this does not happen.
        val lastComposedWord = LastComposedWord(mEvents,
                inputPointers, typedWordCache!!.toString(), committedWord, separatorString,
                ngramContext, capitalizedMode)
        inputPointers!!.reset()
        if (type != LastComposedWord.COMMIT_TYPE_DECIDED_WORD
                && type != LastComposedWord.COMMIT_TYPE_MANUAL_PICK) {
            lastComposedWord.deactivate()
        }
        capsCount = 0
        digitsCount = 0
        mIsBatchMode = false
        combinerChain!!.reset()
        mEvents!!.clear()
        codePointSize = 0
        isOnlyFirstCharCapitalized = false
        capitalizedMode = CapsMode.OFF
        refreshTypedWordCache()
        mAutoCorrection = null
        cursorPositionWithinWord = 0
        mIsResumed = false
        rejectedBatchModeSuggestion = null
        return lastComposedWord
    }

    fun resumeSuggestionOnLastComposedWord(lastComposedWord: LastComposedWord) {
        mEvents!!.clear()
        Collections.copy(mEvents, lastComposedWord.mEvents)
        inputPointers!!.set(lastComposedWord.mInputPointers)
        combinerChain!!.reset()
        refreshTypedWordCache()
        capitalizedMode = lastComposedWord.mCapitalizedMode
        mAutoCorrection = null // This will be filled by the next call to updateSuggestion.
        cursorPositionWithinWord = codePointSize
        rejectedBatchModeSuggestion = null
        mIsResumed = true
    }

    fun isBatchMode(): Boolean {
        return mIsBatchMode
    }

    fun unsetBatchMode() {
        mIsBatchMode = false
    }

    fun setRejectedBatchModeSuggestion(rejectedSuggestion: String?) {
        rejectedBatchModeSuggestion = rejectedSuggestion
    }

    fun getRejectedBatchModeSuggestion(): String? {
        return rejectedBatchModeSuggestion
    }

    /**
     * Get the current combining spec.
     * @return the combining spec string, or null if none is set.
     */
    fun getCombiningSpec(): String? {
        return mCombiningSpec
    }

    fun addInputPointerForTest(index: Int, keyX: Int, keyY: Int) {
        inputPointers!!.addPointerAt(index, keyX, keyY, 0, 0)
    }

    fun setTypedWordCacheForTests(typedWordCacheForTests: String?) {
        typedWordCache = typedWordCacheForTests
    }

    private constructor(isEmpty: Boolean) {
        codePointSize = if (isEmpty) 0 else 1
        mEvents = null
        inputPointers = null
    }

    companion object {
        private const val MAX_WORD_LENGTH = DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH

        @JvmStatic
        fun getComposerForTest(isEmpty: Boolean): WordComposer {
            return WordComposer(isEmpty)
        }
    }
}
