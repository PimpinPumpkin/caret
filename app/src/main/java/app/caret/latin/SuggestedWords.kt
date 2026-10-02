/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.text.TextUtils
import android.view.inputmethod.CompletionInfo
import app.caret.latin.common.StringUtils
import app.caret.latin.common.isEmoji
import app.caret.latin.define.DebugFlags
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.settings.Settings
import java.util.ArrayList
import java.util.Arrays
import java.util.HashSet

open class SuggestedWords(
        suggestedWordInfoList: ArrayList<SuggestedWordInfo>,
        rawSuggestions: ArrayList<SuggestedWordInfo>?,
        typedWordInfo: SuggestedWordInfo?,
        typedWordValid: Boolean,
        willAutoCorrect: Boolean,
        isObsoleteSuggestions: Boolean,
        inputStyle: Int,
        sequenceNumber: Int) {
    /**
     * Get {@link SuggestedWordInfo} object for the typed word.
     * @return The {@link SuggestedWordInfo} object for the typed word.
     */
    @JvmField
    val typedWordInfo: SuggestedWordInfo?
    @JvmField
    val typedWordValid: Boolean
    // Note: this INCLUDES cases where the word will auto-correct to itself. A good definition
    // of what this flag means would be "the top suggestion is strong enough to auto-correct",
    // whether this exactly matches the user entry or not.
    @JvmField
    val willAutoCorrect: Boolean
    @JvmField
    val isObsoleteSuggestions: Boolean
    // How the input for these suggested words was done by the user. Must be one of the
    // INPUT_STYLE_* constants above.
    @JvmField
    val inputStyle: Int
    @JvmField
    val sequenceNumber: Int // Sequence number for auto-commit.
    @JvmField
    val suggestedWordInfoList: ArrayList<SuggestedWordInfo>
    @JvmField
    val rawSuggestions: ArrayList<SuggestedWordInfo>?

    init {
        this.suggestedWordInfoList = suggestedWordInfoList
        this.rawSuggestions = rawSuggestions
        this.typedWordValid = typedWordValid
        this.willAutoCorrect = willAutoCorrect
        this.isObsoleteSuggestions = isObsoleteSuggestions
        this.inputStyle = inputStyle
        this.sequenceNumber = sequenceNumber
        this.typedWordInfo = typedWordInfo
    }

    val isEmpty: Boolean
        get() = suggestedWordInfoList.isEmpty()

    fun size(): Int {
        return suggestedWordInfoList.size
    }

    /**
     * Get suggested word to show as suggestions to UI.
     *
     * @return the count of suggested word to show as suggestions to UI.
     */
    fun getWordCountToShow(): Int {
        if (isPrediction) {
            return size()
        }
        return size() - /* typed word */ 1
    }

    /**
     * Get suggested word at <code>index</code>.
     * @param index The index of the suggested word.
     * @return The suggested word.
     */
    open fun getWord(index: Int): String {
        return suggestedWordInfoList.get(index).word
    }

    /**
     * Get displayed text at <code>index</code>.
     * In RTL languages, the displayed text on the suggestion strip may be different from the
     * suggested word that is returned from {@link #getWord(int)}. For example the displayed text
     * of punctuation suggestion "(" should be ")".
     * @param index The index of the text to display.
     * @return The text to be displayed.
     */
    open fun getLabel(index: Int): String {
        return suggestedWordInfoList.get(index).word
    }

    /**
     * Get {@link SuggestedWordInfo} object at <code>index</code>.
     * @param index The index of the {@link SuggestedWordInfo}.
     * @return The {@link SuggestedWordInfo} object.
     */
    open fun getInfo(index: Int): SuggestedWordInfo {
        return suggestedWordInfoList.get(index)
    }

    /**
     * Gets the suggestion index from the suggestions list.
     * @param suggestedWordInfo The {@link SuggestedWordInfo} to find the index.
     * @return The position of the suggestion in the suggestion list.
     */
    fun indexOf(suggestedWordInfo: SuggestedWordInfo?): Int {
        return suggestedWordInfoList.indexOf(suggestedWordInfo)
    }

    @Suppress("SENSELESS_COMPARISON")
    fun getDebugString(pos: Int): String? {
        if (!DebugFlags.DEBUG_ENABLED) {
            return null
        }
        val wordInfo = getInfo(pos)
        if (wordInfo == null) {
            return null
        }
        val debugString = wordInfo.debugString
        if (TextUtils.isEmpty(debugString)) {
            return null
        }
        return debugString
    }

    /**
     * The predicator to tell whether this object represents punctuation suggestions.
     * @return false if this object desn't represent punctuation suggestions.
     */
    open fun isPunctuationSuggestions(): Boolean {
        return false
    }

    override fun toString(): String {
        // Pretty-print method to help debug
        return ("SuggestedWords:"
                + " mTypedWordValid=" + typedWordValid
                + " mWillAutoCorrect=" + willAutoCorrect
                + " mInputStyle=" + inputStyle
                + " words=" + Arrays.toString(suggestedWordInfoList.toTypedArray()))
    }

    fun getAutoCommitCandidate(): SuggestedWordInfo? {
        if (suggestedWordInfoList.size <= 0) return null
        val candidate = suggestedWordInfoList.get(0)
        return if (candidate.isEligibleForAutoCommit) candidate else null
    }

    // non-final for testability.
    class SuggestedWordInfo {
        @JvmField
        val word: String
        @JvmField
        val prevWordsContext: String
        // The completion info from the application. Null for suggestions that don't come from
        // the application (including keyboard-computed ones, so this is almost always null)
        @JvmField
        val applicationSpecifiedCompletionInfo: CompletionInfo?
        @JvmField
        val score: Int
        @JvmField
        var originalScore: Int = 0 // relevant only for background data gathering, todo: remove when gathering period is over
        @JvmField
        val kindAndFlags: Int
        @JvmField
        val codePointCount: Int
        @JvmField
        val sourceDict: Dictionary?
        // For auto-commit. This keeps track of the index inside the touch coordinates array
        // passed to native code to get suggestions for a gesture that corresponds to the first
        // letter of the second word.
        @JvmField
        val indexOfTouchPointOfSecondWord: Int
        // For auto-commit. This is a measure of how confident we are that we can commit the
        // first word of this suggestion.
        @JvmField
        val autoCommitFirstWordConfidence: Int
        private var debugInfo: String = ""
        private var isEmojiCache: Boolean? = null

        /**
         * Create a new suggested word info.
         * @param word The string to suggest.
         * @param prevWordsContext previous words context.
         * @param score A measure of how likely this suggestion is.
         * @param kindAndFlags The kind of suggestion, as one of the above KIND_* constants with
         * flags.
         * @param sourceDict What instance of Dictionary produced this suggestion.
         * @param indexOfTouchPointOfSecondWord See indexOfTouchPointOfSecondWord.
         * @param autoCommitFirstWordConfidence See autoCommitFirstWordConfidence.
         */
        constructor(word: String, prevWordsContext: String,
                score: Int, kindAndFlags: Int,
                sourceDict: Dictionary?, indexOfTouchPointOfSecondWord: Int,
                autoCommitFirstWordConfidence: Int) {
            this.word = word
            this.prevWordsContext = prevWordsContext
            applicationSpecifiedCompletionInfo = null
            this.score = score
            this.kindAndFlags = kindAndFlags
            this.sourceDict = sourceDict
            codePointCount = StringUtils.codePointCount(word)
            this.indexOfTouchPointOfSecondWord = indexOfTouchPointOfSecondWord
            this.autoCommitFirstWordConfidence = autoCommitFirstWordConfidence
        }

        /**
         * Create a new suggested word info from an application-specified completion.
         * If the passed argument or its contained text is null, this throws a NPE.
         * @param applicationSpecifiedCompletion The application-specified completion info.
         */
        constructor(applicationSpecifiedCompletion: CompletionInfo?) {
            word = applicationSpecifiedCompletion!!.text!!.toString()
            prevWordsContext = ""
            applicationSpecifiedCompletionInfo = applicationSpecifiedCompletion
            score = MAX_SCORE
            kindAndFlags = KIND_APP_DEFINED
            sourceDict = Dictionary.DICTIONARY_APPLICATION_DEFINED
            codePointCount = StringUtils.codePointCount(word)
            indexOfTouchPointOfSecondWord = NOT_AN_INDEX
            autoCommitFirstWordConfidence = NOT_A_CONFIDENCE
        }

        val isEligibleForAutoCommit: Boolean
            get() = (isKindOf(KIND_CORRECTION) && NOT_AN_INDEX != indexOfTouchPointOfSecondWord)

        val kind: Int
            get() = (kindAndFlags and KIND_MASK_KIND)

        fun isKindOf(kind: Int): Boolean {
            return this.kind == kind
        }

        val isPossiblyOffensive: Boolean
            get() = (kindAndFlags and KIND_FLAG_POSSIBLY_OFFENSIVE) != 0

        val isExactMatch: Boolean
            get() = (kindAndFlags and KIND_FLAG_EXACT_MATCH) != 0

        val isExactMatchWithIntentionalOmission: Boolean
            get() = (kindAndFlags and KIND_FLAG_EXACT_MATCH_WITH_INTENTIONAL_OMISSION) != 0

        fun isAppropriateForAutoCorrection(): Boolean {
            return (kindAndFlags and KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION) != 0
                    || (isKindOf(KIND_SHORTCUT) && Settings.values.autoCorrectShortcuts)
        }

        fun setDebugString(str: String?) {
            if (null == str) throw NullPointerException("Debug info is null")
            debugInfo = str
        }

        val debugString: String
            get() = debugInfo

        fun isEmoji(): Boolean {
            return isEmojiCache ?: isEmoji(word).also { isEmojiCache = it }
        }

        @Deprecated("")
        val sourceDictionary: Dictionary?
            get() = sourceDict

        fun codePointAt(i: Int): Int {
            return word.codePointAt(i)
        }

        override fun toString(): String {
            if (TextUtils.isEmpty(debugInfo)) {
                return word
            }
            return word + " (" + debugInfo + ")"
        }

        companion object {
            const val NOT_AN_INDEX = -1
            const val NOT_A_CONFIDENCE = -1
            const val MAX_SCORE = Int.MAX_VALUE

            private const val KIND_MASK_KIND = 0xFF // Mask to get only the kind
            const val KIND_TYPED = 0 // What user typed
            const val KIND_CORRECTION = 1 // Simple correction/suggestion
            const val KIND_COMPLETION = 2 // Completion (suggestion with appended chars)
            const val KIND_WHITELIST = 3 // Whitelisted word
            const val KIND_BLACKLIST = 4 // Blacklisted word
            const val KIND_HARDCODED = 5 // Hardcoded suggestion, e.g. punctuation
            const val KIND_APP_DEFINED = 6 // Suggested by the application
            const val KIND_SHORTCUT = 7 // A shortcut
            const val KIND_PREDICTION = 8 // A prediction (== a suggestion with no input)
            // KIND_RESUMED: A resumed suggestion (comes from a span, currently this type is used only
            // in java for re-correction)
            const val KIND_RESUMED = 9
            const val KIND_OOV_CORRECTION = 10 // Most probable string correction

            const val KIND_FLAG_POSSIBLY_OFFENSIVE = 0x80000000.toInt()
            const val KIND_FLAG_EXACT_MATCH = 0x40000000
            const val KIND_FLAG_EXACT_MATCH_WITH_INTENTIONAL_OMISSION = 0x20000000
            const val KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION = 0x10000000

            /**
             * This will always remove the higher index if a duplicate is found.
             * Will also remove all occurrences of the typed word.
             *
             * @return position of typed word in the candidate list
             */
            @JvmStatic
            fun removeDupsAndTypedWord(
                    typedWord: String?,
                    candidates: ArrayList<SuggestedWordInfo>): Int {
                if (candidates.isEmpty()) {
                    return -1
                }
                var firstOccurrenceOfWord = -1
                if (typedWord != null && typedWord.length > 0) {
                    firstOccurrenceOfWord = removeSuggestedWordInfoFromList(
                            typedWord, candidates, -1 /* startIndexExclusive */)
                }
                var i = 0
                while (i < candidates.size) {
                    removeSuggestedWordInfoFromList(
                            candidates.get(i).word, candidates, i /* startIndexExclusive */)
                    ++i
                }
                return firstOccurrenceOfWord
            }

            private fun removeSuggestedWordInfoFromList(
                    word: String,
                    candidates: ArrayList<SuggestedWordInfo>,
                    startIndexExclusive: Int): Int {
                var firstOccurrenceOfWord = -1
                var i = startIndexExclusive + 1
                while (i < candidates.size) {
                    val previous = candidates.get(i)
                    if (word == previous.word) {
                        if (firstOccurrenceOfWord == -1) {
                            firstOccurrenceOfWord = i
                        }
                        candidates.removeAt(i)
                        --i
                    }
                    ++i
                }
                return firstOccurrenceOfWord
            }
        }
    }

    val isPrediction: Boolean
        get() = isPrediction(inputStyle)

    /**
     * @return the {@link SuggestedWordInfo} which corresponds to the word that is originally
     * typed by the user. Otherwise returns {@code null}. Note that gesture input is not
     * considered to be a typed word.
     */
    fun getTypedWordInfoOrNull(): SuggestedWordInfo? {
        if (INDEX_OF_TYPED_WORD >= size()) {
            return null
        }
        val info = getInfo(INDEX_OF_TYPED_WORD)
        return if (info.kind == SuggestedWordInfo.KIND_TYPED) info else null
    }

    companion object {
        const val INDEX_OF_TYPED_WORD = 0
        const val INDEX_OF_AUTO_CORRECTION = 1
        const val NOT_A_SEQUENCE_NUMBER = -1

        const val INPUT_STYLE_NONE = 0
        const val INPUT_STYLE_TYPING = 1
        const val INPUT_STYLE_UPDATE_BATCH = 2
        const val INPUT_STYLE_TAIL_BATCH = 3
        const val INPUT_STYLE_APPLICATION_SPECIFIED = 4
        const val INPUT_STYLE_RECORRECTION = 5
        const val INPUT_STYLE_PREDICTION = 6
        const val INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION = 7

        // The maximum number of suggestions available.
        const val MAX_SUGGESTIONS = 18

        private val EMPTY_WORD_INFO_LIST = ArrayList<SuggestedWordInfo>(0)
        private val EMPTY = SuggestedWords(
                EMPTY_WORD_INFO_LIST, null, null, false,
                false, false, INPUT_STYLE_NONE, NOT_A_SEQUENCE_NUMBER)

        private val EMPTY_BATCH = SuggestedWords(
                EMPTY_WORD_INFO_LIST, null, null, false,
                false, false, INPUT_STYLE_UPDATE_BATCH, NOT_A_SEQUENCE_NUMBER)

        @JvmStatic
        fun getFromApplicationSpecifiedCompletions(
                infos: Array<out CompletionInfo?>): ArrayList<SuggestedWordInfo> {
            val result = ArrayList<SuggestedWordInfo>()
            for (info in infos) {
                if (null == info || null == info.text) {
                    continue
                }
                result.add(SuggestedWordInfo(info))
            }
            return result
        }

        @JvmStatic
        val emptyInstance: SuggestedWords
            get() = EMPTY

        @JvmStatic
        val emptyBatchInstance: SuggestedWords
            get() = EMPTY_BATCH

        // Should get rid of the first one (what the user typed previously) from suggestions
        // and replace it with what the user currently typed.
        @JvmStatic
        fun getTypedWordAndPreviousSuggestions(
                typedWordInfo: SuggestedWordInfo,
                previousSuggestions: SuggestedWords): ArrayList<SuggestedWordInfo> {
            val suggestionsList = ArrayList<SuggestedWordInfo>()
            val alreadySeen = HashSet<String>()
            suggestionsList.add(typedWordInfo)
            alreadySeen.add(typedWordInfo.word)
            val previousSize = previousSuggestions.size()
            for (index in 1 until previousSize) {
                val prevWordInfo = previousSuggestions.getInfo(index)
                val prevWord = prevWordInfo.word
                // Filter out duplicate suggestions.
                if (!alreadySeen.contains(prevWord)) {
                    suggestionsList.add(prevWordInfo)
                    alreadySeen.add(prevWord)
                }
            }
            return suggestionsList
        }

        private fun isPrediction(inputStyle: Int): Boolean {
            return INPUT_STYLE_PREDICTION == inputStyle
                    || INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION == inputStyle
        }
    }
}
