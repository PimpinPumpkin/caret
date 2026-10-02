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
    private var mCombinerChain: CombinerChain? = null
    private var mCombiningSpec: String? = null // Memory so that we don't uselessly recreate the combiner chain

    // The list of events that served to compose this string.
    private val mEvents: ArrayList<Event>?
    private val mInputPointers: InputPointers?
    private var mAutoCorrection: SuggestedWordInfo? = null
    private var mIsResumed = false
    private var mIsBatchMode = false
    // A memory of the last rejected batch mode suggestion, if any. This goes like this: the user
    // gestures a word, is displeased with the results and hits backspace, then gestures again.
    // At the very least we should avoid re-suggesting the same thing, and to do that we memorize
    // the rejected suggestion in this variable.
    // TODO: this should be done in a comprehensive way by the User History feature instead of
    // as an ad-hockery here.
    private var mRejectedBatchModeSuggestion: String? = null

    // Cache these values for performance
    private var mTypedWordCache: CharSequence? = null
    private var mCapsCount = 0
    private var mDigitsCount = 0
    private var mCapitalizedMode: CapsMode? = null
    // This is the number of code points entered so far. This is not limited to MAX_WORD_LENGTH.
    // In general, this contains the size of mPrimaryKeyCodes, except when this is greater than
    // MAX_WORD_LENGTH in which case mPrimaryKeyCodes only contain the first MAX_WORD_LENGTH
    // code points.
    private var mCodePointSize = 0
    private var mCursorPositionWithinWord = 0

    /**
     * Whether the composing word has the only first char capitalized.
     */
    private var mIsOnlyFirstCharCapitalized = false

    constructor() {
        mCombinerChain = CombinerChain("", "")
        mEvents = ArrayList()
        mInputPointers = InputPointers(MAX_WORD_LENGTH)
        mAutoCorrection = null
        mIsResumed = false
        mIsBatchMode = false
        mCursorPositionWithinWord = 0
        mRejectedBatchModeSuggestion = null
        refreshTypedWordCache()
    }

    /** creates a copy of the internal state (currently reuses mInputPointers though, so don't use this for other purpose than getting suggestions) */
    fun copy(): WordComposer {
        return WordComposer(this)
    }

    @Suppress("CopyConstructorMissesField")
    private constructor(other: WordComposer) {
        mEvents = null
        mInputPointers = other.mInputPointers // ideally we would have an actual copy, but for current use it should be ok
        mAutoCorrection = other.mAutoCorrection
        mIsResumed = other.mIsResumed
        mIsBatchMode = other.mIsBatchMode
        mRejectedBatchModeSuggestion = other.mRejectedBatchModeSuggestion
        mTypedWordCache = other.mTypedWordCache
        mCapsCount = other.mCapsCount
        mDigitsCount = other.mDigitsCount
        mCapitalizedMode = other.mCapitalizedMode
        mCodePointSize = other.mCodePointSize
        mCursorPositionWithinWord = other.mCursorPositionWithinWord
        mIsOnlyFirstCharCapitalized = other.mIsOnlyFirstCharCapitalized
    }

    fun getComposedDataSnapshot(): ComposedData {
        return ComposedData(getInputPointers()!!, isBatchMode(), mTypedWordCache!!.toString())
    }

    /**
     * Restart the combiners, possibly with a new spec.
     * @param combiningSpec The spec string for combining. This is found in the extra value.
     */
    fun restartCombining(combiningSpec: String?) {
        val nonNullCombiningSpec = if (null == combiningSpec) "" else combiningSpec
        if (nonNullCombiningSpec != mCombiningSpec) {
            mCombinerChain = CombinerChain(mCombinerChain!!.composingWordWithCombiningFeedback.toString(), nonNullCombiningSpec)
            mCombiningSpec = nonNullCombiningSpec
        }
    }

    /**
     * Clear out the keys registered so far.
     */
    fun reset() {
        mCombinerChain!!.reset()
        mEvents!!.clear()
        mAutoCorrection = null
        mCapsCount = 0
        mDigitsCount = 0
        mIsOnlyFirstCharCapitalized = false
        mIsResumed = false
        mIsBatchMode = false
        mRejectedBatchModeSuggestion = null
        refreshTypedWordCache()
        mCursorPositionWithinWord = 0
    }

    private fun refreshTypedWordCache() {
        val oldSize = mCodePointSize
        mTypedWordCache = mCombinerChain!!.composingWordWithCombiningFeedback
        mCodePointSize = Character.codePointCount(mTypedWordCache!!, 0, mTypedWordCache!!.length)
        mCursorPositionWithinWord += mCodePointSize - oldSize
        // should not be necessary after setting mCursorPositionWithinWord at the end of reset, but still better not crash
        if (mCursorPositionWithinWord < 0) mCursorPositionWithinWord = 0
        else if (mCursorPositionWithinWord > mCodePointSize) mCursorPositionWithinWord = mCodePointSize
    }

    /**
     * Number of keystrokes in the composing word.
     * @return the number of keystrokes
     */
    fun size(): Int {
        return mCodePointSize
    }

    fun isSingleLetter(): Boolean {
        return size() == 1
    }

    fun isComposingWord(): Boolean {
        return size() > 0
    }

    fun getInputPointers(): InputPointers? {
        return mInputPointers
    }

    /**
     * Process an event and return an event, and return a processed event to apply.
     * @param event the unprocessed event.
     * @return the processed event. Never null, but may be marked as consumed.
     */
    fun processEvent(event: Event): Event {
        val processedEvent = mCombinerChain!!.processEvent(mEvents, event)
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
        mCombinerChain!!.applyProcessedEvent(event)
        val primaryCode = event.codePoint
        val keyX = event.x
        val keyY = event.y
        val newIndex = size()
        refreshTypedWordCache()
        if (!keepCursorPosition || newIndex == mCodePointSize)
            mCursorPositionWithinWord = mCodePointSize
        // We may have deleted the last one.
        if (0 == mCodePointSize) {
            mIsOnlyFirstCharCapitalized = false
        }
        if (KeyCode.DELETE != event.keyCode) {
            if (newIndex < MAX_WORD_LENGTH) {
                // In the batch input mode, the {@code mInputPointers} holds batch input points and
                // shouldn't be overridden by the "typed key" coordinates
                // (See {@link #setBatchInputWord}).
                if (!mIsBatchMode) {
                    // TODO: Set correct pointer id and time
                    mInputPointers!!.addPointerAt(newIndex, keyX, keyY, 0, 0)
                }
            }
            if (0 == newIndex) {
                mIsOnlyFirstCharCapitalized = Character.isUpperCase(primaryCode)
            } else {
                mIsOnlyFirstCharCapitalized = mIsOnlyFirstCharCapitalized
                        && !Character.isUpperCase(primaryCode)
            }
            if (Character.isUpperCase(primaryCode)) mCapsCount++
            if (Character.isDigit(primaryCode)) mDigitsCount++
        }
        mAutoCorrection = null
    }

    fun setCursorPositionWithinWord(posWithinWord: Int) {
        mCursorPositionWithinWord = posWithinWord
        // TODO: compute where that puts us inside the events
    }

    fun isCursorFrontOrMiddleOfComposingWord(): Boolean {
        if (DebugFlags.DEBUG_ENABLED && mCursorPositionWithinWord > mCodePointSize) {
            throw RuntimeException("Wrong cursor position : " + mCursorPositionWithinWord
                    + "in a word of size " + mCodePointSize)
        }
        return mCursorPositionWithinWord != mCodePointSize
    }

    fun isCursorInFrontOfComposingWord(): Boolean {
        return isComposingWord() && mCursorPositionWithinWord == 0
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
        var cursorPos = mCursorPositionWithinWord
        // TODO: Don't make that copy. We can do this directly from mTypedWordCache.
        val codePoints = StringUtils.toCodePointArray(mTypedWordCache!!)
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
        mCursorPositionWithinWord = cursorPos
        mCombinerChain!!.applyProcessedEvent(mCombinerChain!!.processEvent(
                mEvents, Event.createCursorMovedEvent(cursorPos)))
        return true
    }

    fun setBatchInputPointers(batchPointers: InputPointers) {
        mInputPointers!!.set(batchPointers)
        mIsBatchMode = true
    }

    fun setBatchInputWord(word: String) {
        reset()
        mIsBatchMode = true
        val length = word.length
        var i = 0
        while (i < length) {
            val codePoint = Character.codePointAt(word, i)
            // We don't want to override the batch input points that are held in mInputPointers
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
        return mTypedWordCache!!.toString()
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
        return if (isComposingWord()) mIsOnlyFirstCharCapitalized else (mCapitalizedMode != CapsMode.OFF)
    }

    /**
     * Whether or not all of the user typed chars are upper case
     * @return true if all user typed chars are upper case, false otherwise
     */
    fun isAllUpperCase(): Boolean {
        if (size() <= 1) {
            return mCapitalizedMode == CapsMode.AUTO_LOCKED || mCapitalizedMode == CapsMode.MANUAL_LOCKED
        }
        return mCapsCount == size()
    }

    fun wasShiftedNoLock(): Boolean {
        return mCapitalizedMode == CapsMode.AUTO || mCapitalizedMode == CapsMode.MANUAL
    }

    fun lastChar(): Char {
        if (!isComposingWord()) return '\u0000'
        return mTypedWordCache!![mTypedWordCache!!.length - 1]
    }

    /**
     * Returns true if more than one character is upper case, otherwise returns false.
     */
    fun isMostlyCaps(): Boolean {
        return mCapsCount > 1
    }

    /**
     * Returns true if we have digits in the composing word.
     */
    fun hasDigits(): Boolean {
        return mDigitsCount > 0
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
        mCapitalizedMode = mode
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
            mCapitalizedMode = mode
        }
    }

    /**
     * Returns whether the word was automatically capitalized.
     * @return whether the word was automatically capitalized
     */
    fun wasAutoCapitalized(): Boolean {
        return mCapitalizedMode == CapsMode.AUTO_LOCKED || mCapitalizedMode == CapsMode.AUTO
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
                mInputPointers, mTypedWordCache!!.toString(), committedWord, separatorString,
                ngramContext, mCapitalizedMode)
        mInputPointers!!.reset()
        if (type != LastComposedWord.COMMIT_TYPE_DECIDED_WORD
                && type != LastComposedWord.COMMIT_TYPE_MANUAL_PICK) {
            lastComposedWord.deactivate()
        }
        mCapsCount = 0
        mDigitsCount = 0
        mIsBatchMode = false
        mCombinerChain!!.reset()
        mEvents!!.clear()
        mCodePointSize = 0
        mIsOnlyFirstCharCapitalized = false
        mCapitalizedMode = CapsMode.OFF
        refreshTypedWordCache()
        mAutoCorrection = null
        mCursorPositionWithinWord = 0
        mIsResumed = false
        mRejectedBatchModeSuggestion = null
        return lastComposedWord
    }

    fun resumeSuggestionOnLastComposedWord(lastComposedWord: LastComposedWord) {
        mEvents!!.clear()
        Collections.copy(mEvents, lastComposedWord.mEvents)
        mInputPointers!!.set(lastComposedWord.mInputPointers)
        mCombinerChain!!.reset()
        refreshTypedWordCache()
        mCapitalizedMode = lastComposedWord.mCapitalizedMode
        mAutoCorrection = null // This will be filled by the next call to updateSuggestion.
        mCursorPositionWithinWord = mCodePointSize
        mRejectedBatchModeSuggestion = null
        mIsResumed = true
    }

    fun isBatchMode(): Boolean {
        return mIsBatchMode
    }

    fun unsetBatchMode() {
        mIsBatchMode = false
    }

    fun setRejectedBatchModeSuggestion(rejectedSuggestion: String?) {
        mRejectedBatchModeSuggestion = rejectedSuggestion
    }

    fun getRejectedBatchModeSuggestion(): String? {
        return mRejectedBatchModeSuggestion
    }

    /**
     * Get the current combining spec.
     * @return the combining spec string, or null if none is set.
     */
    fun getCombiningSpec(): String? {
        return mCombiningSpec
    }

    fun addInputPointerForTest(index: Int, keyX: Int, keyY: Int) {
        mInputPointers!!.addPointerAt(index, keyX, keyY, 0, 0)
    }

    fun setTypedWordCacheForTests(typedWordCacheForTests: String?) {
        mTypedWordCache = typedWordCacheForTests
    }

    private constructor(isEmpty: Boolean) {
        mCodePointSize = if (isEmpty) 0 else 1
        mEvents = null
        mInputPointers = null
    }

    companion object {
        private const val MAX_WORD_LENGTH = DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH

        @JvmStatic
        fun getComposerForTest(isEmpty: Boolean): WordComposer {
            return WordComposer(isEmpty)
        }
    }
}
