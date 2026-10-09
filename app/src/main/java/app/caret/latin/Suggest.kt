/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package app.caret.latin

import android.text.TextUtils
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import app.caret.keyboard.Keyboard
import app.caret.keyboard.internal.keyboard_parser.getEmojiDefaultVersion
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.ComposedData
import app.caret.latin.common.Constants
import app.caret.latin.common.InputPointers
import app.caret.latin.common.StringUtils
import app.caret.latin.define.DebugFlags
import app.caret.latin.define.DecoderSpecificConstants.SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION
import app.caret.latin.define.DecoderSpecificConstants.SHOULD_REMOVE_PREVIOUSLY_REJECTED_SUGGESTION
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.settings.Settings
import app.caret.latin.settings.SettingsValuesForSuggestion
import app.caret.latin.suggestions.SuggestionStripView
import app.caret.latin.lm.TapEvidence
import app.caret.latin.lm.TypingModel
import app.caret.latin.swipe.FutoSwipe
import app.caret.latin.swipe.SwipeRecorder
import app.caret.latin.utils.AutoCorrectionUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.prefs
import app.caret.latin.utils.BackgroundGatheringCache
import app.caret.latin.utils.SuggestionResults
import app.caret.latin.utils.WordData
import app.caret.latin.utils.useBackgroundGathering
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * This class loads a dictionary and provides a list of suggestions for a given sequence of
 * characters. This includes corrections and completions.
 */
class Suggest(private val dictionaryFacilitator: DictionaryFacilitator) {
    private var autoCorrectionThreshold = 0f
    private val plausibilityThreshold = 0f
    private val nextWordSuggestionsCache = HashMap<NgramContext, SuggestionResults>()

    // cache cleared whenever LatinIME.loadSettings is called, notably on changing layout and switching input fields
    fun clearNextWordSuggestionsCache() = nextWordSuggestionsCache.clear()

    /**
     * Set the normalized-score threshold for a suggestion to be considered strong enough that we
     * will auto-correct to this.
     * @param threshold the threshold
     */
    fun setAutoCorrectionThreshold(threshold: Float) {
        autoCorrectionThreshold = threshold
    }

    // todo: remove when InputLogic is ready
    interface OnGetSuggestedWordsCallback {
        fun onGetSuggestedWords(suggestedWords: SuggestedWords)
    }

    fun getSuggestedWords(wordComposer: WordComposer, ngramContext: NgramContext, keyboard: Keyboard,
                          settingsValuesForSuggestion: SettingsValuesForSuggestion, isCorrectionEnabled: Boolean,
                          inputStyle: Int, sequenceNumber: Int): SuggestedWords =
        if (wordComposer.isBatchMode) {
            getSuggestedWordsForBatchInput(wordComposer, ngramContext, keyboard, settingsValuesForSuggestion,
                inputStyle, isCorrectionEnabled, sequenceNumber)
        } else {
            getSuggestedWordsForNonBatchInput(wordComposer, ngramContext, keyboard, settingsValuesForSuggestion,
                inputStyle, isCorrectionEnabled, sequenceNumber)
        }

    // Retrieves suggestions for non-batch input (typing, recorrection, predictions...)
    // and calls the callback function with the suggestions.
    private fun getSuggestedWordsForNonBatchInput(wordComposer: WordComposer, ngramContext: NgramContext, keyboard: Keyboard,
                      settingsValuesForSuggestion: SettingsValuesForSuggestion, inputStyleIfNotPrediction: Int,
                      isCorrectionEnabled: Boolean, sequenceNumber: Int): SuggestedWords {
        val typedWordString = wordComposer.typedWord
        val resultsArePredictions = !wordComposer.isComposingWord
        val suggestionResults = if (typedWordString.isEmpty())
                getNextWordSuggestions(ngramContext, keyboard, inputStyleIfNotPrediction, settingsValuesForSuggestion)
            else dictionaryFacilitator.getSuggestionResults(wordComposer.getComposedDataSnapshot(), ngramContext, keyboard,
                settingsValuesForSuggestion, SESSION_ID_TYPING, inputStyleIfNotPrediction)
        val trailingSingleQuotesCount = StringUtils.getTrailingSingleQuotesCount(typedWordString)
        val capsMode = getCapsModeForTyping(wordComposer, keyboard)
        val suggestionsContainer = ArrayList(suggestionResults)
        capitalizeAndAddTrailingSingleQuotes(suggestionsContainer, capsMode, trailingSingleQuotesCount, dictionaryFacilitator.getMainLocale())
        val capitalizedTypedWord = capitalize(typedWordString, capsMode, dictionaryFacilitator.getMainLocale())

        // store the original SuggestedWordInfo for typed word, as it will be removed
        // we may want to re-add it in case auto-correction happens, so that the original word can at least be selected
        // we check against the capitalizedTypedWord because getTransformedSuggestedWordInfoList adjusts for capsMode
        val typedWordFirstOccurrenceWordInfo = suggestionsContainer.firstOrNull { it.word == capitalizedTypedWord }
        val firstOccurrenceOfTypedWordInSuggestions = SuggestedWordInfo.removeDupsAndTypedWord(capitalizedTypedWord, suggestionsContainer)
        makeFirstTwoSuggestionsNonEmoji(suggestionsContainer)
        val fit = if (isCorrectionEnabled && !resultsArePredictions && typedWordString.isNotEmpty())
                fitToTaps(typedWordString, suggestionsContainer, wordComposer, keyboard)
                    ?: fitToSentence(typedWordString, suggestionsContainer, ngramContext)
            else null

        val (allowsToBeAutoCorrected, dictionaryAutoCorrection) = shouldBeAutoCorrected(
            trailingSingleQuotesCount,
            capitalizedTypedWord,
            suggestionsContainer.firstOrNull(),
            {
                val first = suggestionsContainer.firstOrNull() ?: suggestionResults.first()
                val suggestions = getNextWordSuggestions(ngramContext, keyboard, inputStyleIfNotPrediction, settingsValuesForSuggestion)
                val suggestionForFirstInContainer = suggestions.firstOrNull { it.word == first.word }
                val suggestionForTypedWord = suggestions.firstOrNull { it.word == capitalizedTypedWord }
                suggestionForFirstInContainer to suggestionForTypedWord
            },
            isCorrectionEnabled,
            wordComposer,
            suggestionResults,
            firstOccurrenceOfTypedWordInSuggestions,
            typedWordFirstOccurrenceWordInfo
        )
        val bySentence = if (fit?.readFromKeys == true)
                correctByTaps(dictionaryAutoCorrection, fit, typedWordFirstOccurrenceWordInfo != null,
                    typedWordString, suggestionsContainer, isCorrectionEnabled, wordComposer)
            else correctBySentence(dictionaryAutoCorrection, fit, typedWordFirstOccurrenceWordInfo != null,
                typedWordString, suggestionsContainer, isCorrectionEnabled, wordComposer)
        val hasAutoCorrection = correctByHabit(bySentence, typedWordString, suggestionsContainer, capsMode,
            isCorrectionEnabled && !resultsArePredictions && !wordComposer.isResumed)
        val typedWordInfo = SuggestedWordInfo(typedWordString, "", SuggestedWordInfo.MAX_SCORE,
            SuggestedWordInfo.KIND_TYPED, typedWordFirstOccurrenceWordInfo?.sourceDict ?: Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX , SuggestedWordInfo.NOT_A_CONFIDENCE)
        if (typedWordString.isNotEmpty()) {
            suggestionsContainer.add(0, typedWordInfo)
        }
        val suggestionsList = if (SuggestionStripView.DEBUG_SUGGESTIONS && suggestionsContainer.isNotEmpty())
                getSuggestionsInfoListWithDebugInfo(capitalizedTypedWord, suggestionsContainer)
            else suggestionsContainer

        val inputStyle = if (resultsArePredictions) {
            if (suggestionResults.isBeginningOfSentence) SuggestedWords.INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION
            else SuggestedWords.INPUT_STYLE_PREDICTION
        } else {
            inputStyleIfNotPrediction
        }

        useDefaultEmojiSkinTone(suggestionsList)

        // If there is an incoming autocorrection, make sure typed word is shown, so user is able to override it.
        // Otherwise, if the relevant setting is enabled, show the typed word in the middle.
        val typedWordWasCapitalized = capitalizedTypedWord != typedWordString
        val correctToCapitalizedWord = typedWordWasCapitalized && isCorrectionEnabled && Settings.values.autoCorrectCapitalizedSuggestion
            && !wordComposer.isCursorFrontOrMiddleOfComposingWord() && typedWordString.drop(1).none { it.isUpperCase() }
        val indexOfTypedWord = 1 + if (hasAutoCorrection) SuggestedWords.INDEX_OF_AUTO_CORRECTION else SuggestedWords.INDEX_OF_TYPED_WORD
        if (
            (hasAutoCorrection
                || (Settings.values.centerSuggestionTextToEnter && !wordComposer.isResumed)
                || typedWordWasCapitalized
            ) && suggestionsList.size >= indexOfTypedWord && capitalizedTypedWord.isNotEmpty()) {
            if (typedWordFirstOccurrenceWordInfo != null) {
                addDebugInfo(typedWordFirstOccurrenceWordInfo, capitalizedTypedWord)
                suggestionsList.add(indexOfTypedWord, typedWordFirstOccurrenceWordInfo)
            } else {
                suggestionsList.add(indexOfTypedWord,
                    SuggestedWordInfo(capitalizedTypedWord, "", 0, SuggestedWordInfo.KIND_TYPED,
                        Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
                )
            }
        }
        val isTypedWordValid = firstOccurrenceOfTypedWordInSuggestions > -1 || (!resultsArePredictions && !allowsToBeAutoCorrected)
        return SuggestedWords(suggestionsList, suggestionResults.rawSuggestions, typedWordInfo,
            isTypedWordValid, hasAutoCorrection || correctToCapitalizedWord, false, inputStyle, sequenceNumber)
    }

    /** What the language model says about the typed word and the first suggestions. */
    class SentenceFit(
        val typed: Float, val scores: Map<SuggestedWordInfo, Float>, val best: SuggestedWordInfo?,
        /** The scores are sentence and taps together (see fitToTaps), not only how a word fits the sentence. */
        val readFromKeys: Boolean = false,
        /** Debug builds: what went into the scores, for scripts/dev/taps-replay.py to try other settings on. */
        val trace: String? = null,
    )

    /**
     * Caret: which word was meant, from two things that do not depend on each other: how well each word
     * fits the sentence (the typing model's language model) and how well the taps fit its letters
     * ([TapEvidence.fit]). A rare word tapped cleanly beats a common one a key away; a tap on the border
     * between two keys leaves it to the sentence. Null without a typing model or without the taps.
     */
    private fun fitToTaps(
        typedWord: String, suggestions: List<SuggestedWordInfo>, wordComposer: WordComposer, keyboard: Keyboard,
    ): SentenceFit? {
        if (dictionaryFacilitator.getMainLocale().language != "en") return null
        val head = suggestions.take(TAPS_RANKED_SUGGESTIONS).filter { info ->
            info.word.all { it.isLetter() || it == '\'' } && !info.word.equals(typedWord, ignoreCase = true)
        }
        if (head.isEmpty()) return null
        // every key tapped once, in order, nothing deleted in between: otherwise the taps do not tell the word
        val pointers = wordComposer.getInputPointers() ?: return null
        if (pointers.pointerSize != typedWord.length || !typedWord.all { it.isLetter() }) return null
        val centers = ('a'..'z').mapNotNull { letter ->
            keyboard.getKey(letter.code)?.let { letter to ((it.getDrawX() + it.getDrawWidth() / 2f) to (it.getY() + it.height / 2f)) }
        }.toMap()
        if (centers.size < 26) return null
        val words = listOf(typedWord) + head.map { it.word }
        val sentence = TypingModel.scoreWords(FutoSwipe.textBeforeWord, words) ?: return null
        val tapX = pointers.xCoordinates.copyOf(typedWord.length)
        val tapY = pointers.yCoordinates.copyOf(typedWord.length)
        val width = keyboard.mostCommonKeyWidth.toFloat()
        val total = words.indices.map { i ->
            maxOf(sentence[i], TAPS_SENTENCE_FLOOR) + TapEvidence.fit(words[i], tapX, tapY, centers, width, TAPS_SHARPNESS, TAPS_SLIP)
        }
        val scores = head.withIndex().associate { (i, info) -> info to total[i + 1] }
        if (BuildConfig.DEBUG) Log.d(TAG, "taps $typedWord (${sentence[0]} -> ${total[0]}): " +
            head.withIndex().joinToString { (i, it) -> "${it.word} ${sentence[i + 1]} -> ${total[i + 1]}" })
        val trace = if (!BuildConfig.DEBUG) null else {
            if (tracedCenters !== keyboard) {
                tracedCenters = keyboard
                Log.d(TAG, "tapskeys $width " + centers.entries.joinToString(" ") { "${it.key}:${it.value.first},${it.value.second}" })
            }
            "$typedWord|${sentence[0]}|${tapX.joinToString(",")}|${tapY.joinToString(",")}|" +
                head.withIndex().joinToString(";") { (i, it) -> "${it.word}:${sentence[i + 1]}:${isAllowedByAutoCorrectionWithSpaceFilter(it)}" }
        }
        return SentenceFit(total[0], scores, head.maxByOrNull { scores.getValue(it) }, readFromKeys = true, trace = trace)
    }

    private var tracedCenters: Keyboard? = null

    /**
     * Caret: autocorrecting by sentence and taps together (see [fitToTaps]). The likeliest word goes to
     * the front of [suggestions] when it is to replace what was typed:
     * - a typed word that is a word stays unless another one is likelier by a clear margin;
     * - typed letters that are no word are replaced by the likeliest word when the dictionary would
     *   correct them too, or when that word is far likelier than the letters as they stand.
     */
    private fun correctByTaps(
        dictionarySays: Boolean, fit: SentenceFit, typedIsWord: Boolean, typedWord: String,
        suggestions: ArrayList<SuggestedWordInfo>, isCorrectionEnabled: Boolean, wordComposer: WordComposer,
    ): Boolean {
        val best = fit.best ?: return dictionarySays
        val first = suggestions.firstOrNull() ?: return dictionarySays
        if (fit.trace != null) Log.d(TAG, "tapsdecide $dictionarySays|$typedIsWord|${first.word}|" +
            "${isCorrectionEnabled && !wordComposer.hasDigits() && !wordComposer.isResumed && !(wordComposer.isMostlyCaps && !wordComposer.isAllUpperCase())}|${fit.trace}")
        if (dictionarySays && first.word.equals(typedWord, ignoreCase = true)) return true // a change of capitals only
        if (!isCorrectionEnabled || wordComposer.hasDigits() || wordComposer.isResumed
            || (wordComposer.isMostlyCaps && !wordComposer.isAllUpperCase()) || !isAllowedByAutoCorrectionWithSpaceFilter(best))
            return dictionarySays
        val lead = fit.scores.getValue(best) - fit.typed
        val replace = when {
            typedIsWord -> lead >= TAPS_WORD_MARGIN
            // letters that are no word: corrected when the dictionary would, or when another word is far likelier
            else -> dictionarySays || lead >= TAPS_UNKNOWN_MARGIN
        }
        if (!replace) return false
        suggestions.remove(best)
        suggestions.add(0, best)
        return true
    }

    /**
     * Caret: scores the first suggestions by how well they fit the sentence so far, with the
     * typing model, or the context language model that comes with the swipe models. The dictionary ranks by spelling
     * and frequency alone, so "if" and "of" or "go" and "to" look alike to it. The typed word in
     * other capitals counts as the typed word, not as a correction.
     */
    private fun fitToSentence(typedWord: String, suggestions: List<SuggestedWordInfo>, ngramContext: NgramContext): SentenceFit? {
        val head = suggestions.take(SENTENCE_RANKED_SUGGESTIONS).filter { info ->
            info.word.all { it.isLetter() || it == '\'' } && !info.word.equals(typedWord, ignoreCase = true)
        }
        if (head.isEmpty()) return null
        val words = listOf(typedWord) + head.map { it.word }
        val locale = dictionaryFacilitator.getMainLocale()
        // the typing model reads the whole line; without one, the smaller model of the swipe models
        val lm = (if (locale.language == "en") TypingModel.scoreWords(FutoSwipe.textBeforeWord, words) else null)
            ?: FutoSwipe.scoreWords(ngramContext, locale, words)
            ?: return null
        val scores = head.withIndex().associate { (i, info) -> info to lm[i + 1] }
        val weight = SENTENCE_WEIGHT
        val best = head.maxByOrNull { BinaryDictionaryUtils.calcNormalizedScore(typedWord, it.word, it.score) + weight * scores.getValue(it) }
        if (BuildConfig.DEBUG) Log.d(TAG, "fit for $typedWord (${lm[0]}): " +
            head.joinToString { "${it.word} ${BinaryDictionaryUtils.calcNormalizedScore(typedWord, it.word, it.score)} ${scores[it]}" })
        return SentenceFit(lm[0], scores, best)
    }

    /**
     * Caret: what the language model changes about autocorrecting, with the word it settles on
     * moved to the front of [suggestions]:
     * - a correction goes to the suggestion that fits the sentence better, when its spelling is about as close;
     * - a correction is dropped when the typed word, a word itself, fits the sentence clearly better;
     * - a typed word that is a word itself ("if", "go") is replaced when a close suggestion fits
     *   the sentence far better ("of", "to").
     */
    private fun correctBySentence(
        dictionarySays: Boolean, fit: SentenceFit?, typedIsWord: Boolean, typedWord: String,
        suggestions: ArrayList<SuggestedWordInfo>, isCorrectionEnabled: Boolean, wordComposer: WordComposer,
    ): Boolean {
        val best = fit?.best ?: return dictionarySays
        val first = suggestions.firstOrNull() ?: return dictionarySays
        if (SENTENCE_WEIGHT < 0) return dictionarySays // switched off, for comparing
        val bestFit = fit.scores.getValue(best)
        fun promoteBest() {
            suggestions.remove(best)
            suggestions.add(0, best)
        }
        if (dictionarySays) {
            if (first.word.equals(typedWord, ignoreCase = true)) return true // a change of capitals only
            if (typedIsWord && fit.typed - (fit.scores[first] ?: bestFit) >= SENTENCE_KEEP_MARGIN) return false
            if (best !== first && fit.scores[first] != null && isAllowedByAutoCorrectionWithSpaceFilter(best)) promoteBest()
            return true
        }
        if (!isCorrectionEnabled || !typedIsWord || wordComposer.hasDigits() || wordComposer.isResumed
            || (wordComposer.isMostlyCaps && !wordComposer.isAllUpperCase()) || !isAllowedByAutoCorrectionWithSpaceFilter(best))
            return false
        val spelling = BinaryDictionaryUtils.calcNormalizedScore(typedWord, best.word, best.score)
        // a rare word reads as unlikely whatever the sentence ("hooted" against "hosted"): that alone is no reason
        if (bestFit - maxOf(fit.typed, SENTENCE_TYPED_FLOOR) < SENTENCE_REPLACE_MARGIN || spelling < SENTENCE_REPLACE_SPELLING) return false
        promoteBest()
        return true
    }

    /**
     * Caret: what the person has taught the keyboard about [typedWord] goes before everything else
     * (see [PersonalCorrections]): a word they keep taking back is left as typed, and a fix they keep
     * making is made for them, with the word they mean at the front of [suggestions].
     */
    private fun correctByHabit(
        otherwise: Boolean, typedWord: String, suggestions: ArrayList<SuggestedWordInfo>, capsMode: CapsMode, enabled: Boolean,
    ): Boolean {
        if (!enabled || typedWord.isEmpty() || !Settings.values.usePersonalizedDicts) return otherwise
        val meant = PersonalCorrections.meantBy(typedWord) ?: return otherwise
        if (meant == typedWord) return false
        val word = capitalize(meant, capsMode, dictionaryFacilitator.getMainLocale())
        val known = suggestions.firstOrNull { it.word == word }
        if (known != null) suggestions.remove(known)
        suggestions.add(0, known ?: SuggestedWordInfo(word, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_WHITELIST,
            Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
        return true
    }

    // returns [allowsToBeAutoCorrected, hasAutoCorrection]
    // public for testing
    // todo: now we can do better tests, maybe make it private again and test via getSuggestedWords (and simplify if possible)
    fun shouldBeAutoCorrected(
        trailingSingleQuotesCount: Int,
        typedWordString: String,
        firstSuggestionInContainer: SuggestedWordInfo?,
        getEmptyWordSuggestions: () -> Pair<SuggestedWordInfo?, SuggestedWordInfo?>,
        isCorrectionEnabled: Boolean,
        wordComposer: WordComposer,
        suggestionResults: SuggestionResults,
        firstOccurrenceOfTypedWordInSuggestions: Int,
        typedWordInfo: SuggestedWordInfo?
    ): Pair<Boolean, Boolean> {
        val consideredWord = typedWordString.dropLast(trailingSingleQuotesCount)
        val firstAndTypedEmptyInfos by lazy { getEmptyWordSuggestions() }

        val scoreLimit = Settings.values.scoreLimitForAutocorrect
        // We allow auto-correction if whitelisting is not required or the word is whitelisted,
        // or if the word had more than one char and was not suggested.
        val allowsToBeAutoCorrected: Boolean
        if (SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION
                || firstSuggestionInContainer?.isKindOf(SuggestedWordInfo.KIND_WHITELIST) == true
                || (consideredWord.length > 1
                    && typedWordInfo?.sourceDict == null // more than 1 letter and not in dictionary
                    // if the typed word contains @ or ., the suggestion also needs to contain it
                    // (avoid autocorrecting mail addresses, URLs & similar to something different)
                    && (!typedWordString.contains('@') || firstSuggestionInContainer?.word?.contains('@') == true)
                    && (!typedWordString.contains('.') || firstSuggestionInContainer?.word?.contains('.') == true)
                    )
            ) {
            allowsToBeAutoCorrected = true
        } else if (firstSuggestionInContainer != null && typedWordString.isNotEmpty()) {
            // maybe allow autocorrect, depending on scores and emptyWordSuggestions
            val first = firstAndTypedEmptyInfos.first
            val typed = firstAndTypedEmptyInfos.second
            allowsToBeAutoCorrected = when {
                firstSuggestionInContainer.score > scoreLimit -> true // suggestion has good score, allow
                first == null -> false // no autocorrect if first suggestion unknown in this ngram context
                typed == null -> true // allow autocorrect if typed word not known in this ngram context, todo: this may be too aggressive
                else -> first.score - typed.score > 20 // autocorrect if suggested word has clearly higher score for empty word suggestions
            }
        } else {
            allowsToBeAutoCorrected = false
        }
        // If correction is not enabled, we never auto-correct. This is for example for when
        // the setting "Auto-correction" is "off": we still suggest, but we don't auto-correct.
        val hasAutoCorrection: Boolean
        if (!isCorrectionEnabled
            || !allowsToBeAutoCorrected // If the word does not allow to be auto-corrected, then we don't auto-correct.
            || !wordComposer.isComposingWord // If we are doing prediction, then we never auto-correct of course
            || suggestionResults.isEmpty() // If we don't have suggestion results, we can't evaluate the first suggestion for auto-correction
            || wordComposer.hasDigits() // If the word has digits, we never auto-correct because it's likely the word was type with a lot of care // todo: but what if user touched the number row?
            || (wordComposer.isMostlyCaps && !wordComposer.isAllUpperCase()) // If the word is mostly caps, we never auto-correct because this is almost certainly intentional
            || wordComposer.isResumed // We never auto-correct when suggestions are resumed because it would be unexpected
            // If we don't have a main dictionary, we never want to auto-correct. The reason
            // for this is, the user may have a contact whose name happens to match a valid
            // word in their language, and it will unexpectedly auto-correct. For example, if
            // the user types in English with no dictionary and has a "Will" in their contact
            // list, "will" would always auto-correct to "Will" which is unwanted. Hence, no
            // main dict => no auto-correct. Also, it would probably get obnoxious quickly.
            // TODO: now that we have personalization, we may want to re-evaluate this decision
            || !dictionaryFacilitator.hasAtLeastOneInitializedMainDictionary()
        ) {
            hasAutoCorrection = false
        } else {
            val firstSuggestion = firstSuggestionInContainer ?: suggestionResults.first()
            if (suggestionResults.firstSuggestionExceedsConfidenceThreshold && firstOccurrenceOfTypedWordInSuggestions != 0) {
                // mFirstSuggestionExceedsConfidenceThreshold is always set to false, so currently this branch is useless
                return true to true
            }
            if (!AutoCorrectionUtils.suggestionExceedsThreshold(firstSuggestion, consideredWord, autoCorrectionThreshold)) {
                // Score is too low for autocorrect
                // todo: maybe also do something here depending on ngram context?
                return true to false
            }
            // We have a high score, so we need to check if this suggestion is in the correct
            // form to allow auto-correcting to it in this language. For details of how this
            // is determined, see #isAllowedByAutoCorrectionWithSpaceFilter.
            val allowed = isAllowedByAutoCorrectionWithSpaceFilter(firstSuggestion)
            if (allowed && typedWordInfo != null && typedWordInfo.score > scoreLimit) {
                // typed word is valid and has good score
                // do not auto-correct if typed word is better match than first suggestion
                val dictLocale = dictionaryFacilitator.getCurrentLocale()
                if (firstSuggestion.score < scoreLimit) {
                    // don't allow if suggestion has too low score
                    return true to false
                }
                val firstSuggestionLocale = firstSuggestion.sourceDict!!.locale
                if (firstSuggestionLocale !== typedWordInfo.sourceDict!!.locale) {
                    // dict locale different -> return the better match
                    return true to (dictLocale == firstSuggestionLocale)
                }
                // the score difference may need tuning, but so far it seems alright
                val firstWordBonusScore =
                    ((if (firstSuggestion.isKindOf(SuggestedWordInfo.KIND_WHITELIST)) 20 else 0) // large bonus because it's wanted by dictionary
                            + (if (StringUtils.isLowerCaseAscii(typedWordString)) 5 else 0) // small bonus because typically only lower case ascii is typed (applies to latin keyboards only)
                            + if (firstSuggestion.score > typedWordInfo.score) 5 else 0) // small bonus if score is higher
                val firstScoreForEmpty = firstAndTypedEmptyInfos.first?.score ?: 0
                val typedScoreForEmpty = firstAndTypedEmptyInfos.second?.score ?: 0
                if (firstScoreForEmpty + firstWordBonusScore >= typedScoreForEmpty + 20) {
                    // first word is clearly better match for this ngram context
                    return true to true
                }
                hasAutoCorrection = false
            } else {
                hasAutoCorrection = allowed
            }
        }
        return allowsToBeAutoCorrected to hasAutoCorrection
    }

    // Retrieves suggestions for the batch input
    // and calls the callback function with the suggestions.
    private fun getSuggestedWordsForBatchInput(
        wordComposer: WordComposer,
        ngramContext: NgramContext, keyboard: Keyboard,
        settingsValuesForSuggestion: SettingsValuesForSuggestion,
        inputStyle: Int, isCorrectionEnabled: Boolean, sequenceNumber: Int
    ): SuggestedWords {
        val suggestionResults = dictionaryFacilitator.getSuggestionResults(
            wordComposer.getComposedDataSnapshot(), ngramContext, keyboard,
            settingsValuesForSuggestion, SESSION_ID_GESTURE, inputStyle
        )

        // For transforming words that don't come from a dictionary, because it's our best bet
        val locale = dictionaryFacilitator.getMainLocale()
        val capsMode = getCapsModeForGesture(wordComposer, keyboard)
        val suggestionsContainer = ArrayList(suggestionResults)
        replaceSingleLetterFirstSuggestion(suggestionsContainer)

        val rejected: SuggestedWordInfo?
        if (SHOULD_REMOVE_PREVIOUSLY_REJECTED_SUGGESTION && suggestionsContainer.size > 1 && TextUtils.equals(
                suggestionsContainer[0].word,
                wordComposer.rejectedBatchModeSuggestion
            )
        ) {
            rejected = suggestionsContainer.removeAt(0)
            suggestionsContainer.add(1, rejected)
        } else {
            rejected = null
        }
        SuggestedWordInfo.removeDupsAndTypedWord(null, suggestionsContainer)
        makeFirstTwoSuggestionsNonEmoji(suggestionsContainer)
        val pseudoTypedWord = suggestionsContainer.firstOrNull() // unchanged first suggestion, but considering adjusted order
        capitalizeAndAddTrailingSingleQuotes(suggestionsContainer, capsMode, 0, locale)

        // For some reason some suggestions with MIN_VALUE are making their way here.
        // TODO: Find a more robust way to detect distracters.
        for (i in suggestionsContainer.indices.reversed()) {
            if (suggestionsContainer[i].score < SUPPRESS_SUGGEST_THRESHOLD) {
                suggestionsContainer.removeAt(i)
            }
        }

        val capitalizedTypedWord = capitalize(wordComposer.typedWord, capsMode, locale)
        val addCapitalizedSuggestion = capitalizedTypedWord != wordComposer.typedWord && suggestionsContainer.drop(1).none { it.word == capitalizedTypedWord }
        if (addCapitalizedSuggestion) {
            suggestionsContainer.add(min(1, suggestionsContainer.size),
                SuggestedWordInfo(capitalizedTypedWord, "", 0, SuggestedWordInfo.KIND_TYPED,
                    Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
            )
        }

        useDefaultEmojiSkinTone(suggestionsContainer)

        // In the batch input mode, the most relevant suggested word should act as a "typed word"
        // (typedWordValid=true), not as an "auto correct word" (willAutoCorrect=false).
        // Exception is when using shift to change capitalization of suggestions.
        // Note that because this method is never used to get predictions, there is no need to
        // modify inputType such in getSuggestedWordsForNonBatchInput.
        val pseudoTypedWordInfo = preferNextWordSuggestion(
            pseudoTypedWord, suggestionsContainer,
            getNextWordSuggestions(ngramContext, keyboard, inputStyle, settingsValuesForSuggestion), rejected
        )
        val suggestionsList = if (SuggestionStripView.DEBUG_SUGGESTIONS && suggestionsContainer.isNotEmpty()) {
            getSuggestionsInfoListWithDebugInfo(suggestionResults.first().word, suggestionsContainer)
        } else {
            suggestionsContainer
        }

        if (inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH) SwipeRecorder.onCommitted(pseudoTypedWordInfo?.word)
        if (useBackgroundGathering && inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH) {
            val wordData = WordData(null, suggestionResults, wordComposer.getComposedDataSnapshot(),
                ngramContext, keyboard, inputStyle, false, pseudoTypedWordInfo)
            BackgroundGatheringCache.addWord(wordData)
        }

        val autocorrectCapitalization = addCapitalizedSuggestion && Settings.values.autoCorrectCapitalizedSuggestion && isCorrectionEnabled && !wordComposer.isCursorFrontOrMiddleOfComposingWord()
        return SuggestedWords(suggestionsList, suggestionResults.rawSuggestions, pseudoTypedWordInfo, true,
            autocorrectCapitalization, false, inputStyle, sequenceNumber)
    }

    private fun useDefaultEmojiSkinTone(suggestionsList: ArrayList<SuggestedWordInfo>) {
        for (i in suggestionsList.indices) {
            suggestionsList[i] = useDefaultEmojiSkinTone(suggestionsList[i])
        }
    }

    /** get suggestions based on the current ngram context, with an empty typed word (that's what next word suggestions do)  */
    private fun getNextWordSuggestions(ngramContext: NgramContext, keyboard: Keyboard, inputStyle: Int,
                                       settingsValuesForSuggestion: SettingsValuesForSuggestion): SuggestionResults {
        val cachedResults = nextWordSuggestionsCache[ngramContext]
        if (cachedResults != null) return cachedResults
        val newResults = dictionaryFacilitator.getSuggestionResults(ComposedData(InputPointers(1),
            false, ""), ngramContext, keyboard, settingsValuesForSuggestion, SESSION_ID_TYPING, inputStyle)
        nextWordSuggestionsCache[ngramContext] = newResults
        return newResults
    }

    companion object {
        private val TAG: String = Suggest::class.java.simpleName

        // Session id for {@link #getSuggestedWords(WordComposer,String,ProximityInfo,boolean,int)}.
        // We are sharing the same ID between typing and gesture to save RAM footprint.
        const val SESSION_ID_TYPING = 0
        const val SESSION_ID_GESTURE = 0

        /** How many of the first suggestions the language model reorders. */
        private const val SENTENCE_RANKED_SUGGESTIONS = 6
        /** Weight of the language model score against the spelling score when reordering. */
        private val SENTENCE_WEIGHT get() = tuning("sentence_weight", 0.06f)
        /** How much better a suggestion has to fit the sentence to replace a typed word that is a word itself. */
        private val SENTENCE_REPLACE_MARGIN get() = tuning("sentence_replace_margin", 8f)
        /** ...and how close it has to be to what was typed. */
        private val SENTENCE_REPLACE_SPELLING get() = tuning("sentence_replace_spelling", 0.15f)
        /** How much better a typed word has to fit the sentence to be kept although a correction is ready. */
        /** How much likelier (sentence and taps together, log probability) another word has to be than a typed word. */
        private val TAPS_WORD_MARGIN get() = tuning("taps_word_margin", 4.5f)
        /** The same for typed letters that are no word, when the dictionary would leave them. */
        private val TAPS_UNKNOWN_MARGIN get() = tuning("taps_unknown_margin", 5f)
        // These numbers were chosen with scripts/dev/taps-replay.py on logged benchmark decisions: the best on
        // sloppy sentences among the settings that changed no cleanly tapped rare word and no more of the rare
        // words tapped a little off than the dictionary alone does.
        /** How tightly taps are taken to land around the middle of a key: 1 / (2 * s * s) for a spread of s = 0.35 key widths. */
        private val TAPS_SHARPNESS get() = tuning("taps_sharpness", 4f)
        /** The price of a skipped, doubled or swapped letter: about one in four hundred letters. */
        private val TAPS_SLIP get() = tuning("taps_slip", 6f)
        /** No word is taken to fit the sentence worse than this: the model's odds for very rare words are not that exact. */
        private val TAPS_SENTENCE_FLOOR get() = tuning("taps_sentence_floor", -16f)
        /** How many of the first suggestions are weighed by sentence and taps. */
        private const val TAPS_RANKED_SUGGESTIONS = 8
        private val SENTENCE_TYPED_FLOOR get() = tuning("sentence_typed_floor", -15f)
        private val SENTENCE_KEEP_MARGIN get() = tuning("sentence_keep_margin", 4f)

        // Debug builds read these from tuning.properties in the app's external files folder, to tune
        // them with scripts/typing-benchmark.py: adb push the file, then restart the keyboard.
        private val tuningValues: java.util.Properties by lazy {
            java.util.Properties().apply {
                if (BuildConfig.DEBUG) try {
                    val file = java.io.File(Settings.currentContext.getExternalFilesDir(null), "tuning.properties")
                    if (file.isFile) file.inputStream().use { load(it) }
                    Log.i(TAG, "tuning: $this")
                } catch (e: Exception) {
                    Log.w(TAG, "no tuning values", e)
                }
            }
        }

        private fun tuning(name: String, default: Float) = tuningValues.getProperty(name)?.toFloatOrNull() ?: default

        // Close to -2**31
        private const val SUPPRESS_SUGGEST_THRESHOLD = -2000000000

        private const val MAXIMUM_AUTO_CORRECT_LENGTH_FOR_GERMAN = 12
        // TODO: should we add Finnish here?
        private val languageToMaximumAutoCorrectionWithSpaceLength = hashMapOf(Locale.GERMAN.language to MAXIMUM_AUTO_CORRECT_LENGTH_FOR_GERMAN)

        private fun capitalizeAndAddTrailingSingleQuotes(
            suggestions: ArrayList<SuggestedWordInfo>, capsMode: CapsMode, trailingSingleQuotesCount: Int, defaultLocale: Locale
        ) {
            val suggestionsCount = suggestions.size
            if (capsMode != CapsMode.OFF || 0 != trailingSingleQuotesCount) {
                for (i in 0 until suggestionsCount) {
                    val wordInfo = suggestions[i]
                    val wordLocale = wordInfo.sourceDict!!.locale
                    val transformedWordInfo = capitalizeAndAddTrailingSingleQuotes(
                        wordInfo, wordLocale ?: defaultLocale, capsMode, trailingSingleQuotesCount
                    )
                    suggestions[i] = transformedWordInfo
                }
            }
        }

        private fun capitalizeAndAddTrailingSingleQuotes(
            wordInfo: SuggestedWordInfo, locale: Locale, capsMode: CapsMode, trailingSingleQuotesCount: Int
        ): SuggestedWordInfo {
            var capitalizedWord = capitalize(wordInfo.word, capsMode, locale)
            // Appending quotes is here to help people quote words. However, it's not helpful
            // when they type words with quotes toward the end like "it's" or "didn't", where
            // it's more likely the user missed the last character (or didn't type it yet).
            val quotesToAppend = trailingSingleQuotesCount - if (wordInfo.word.contains('\'')) 1 else 0
            repeat(max(0, quotesToAppend)) { capitalizedWord += '\'' }
            return SuggestedWordInfo(
                capitalizedWord, wordInfo.prevWordsContext,
                wordInfo.score, wordInfo.kindAndFlags,
                wordInfo.sourceDict, wordInfo.indexOfTouchPointOfSecondWord,
                wordInfo.autoCommitFirstWordConfidence
            )
        }

        private fun getSuggestionsInfoListWithDebugInfo(
            typedWord: String, suggestions: ArrayList<SuggestedWordInfo>
        ): ArrayList<SuggestedWordInfo> {
            val suggestionsSize = suggestions.size
            val suggestionsList = ArrayList<SuggestedWordInfo>(suggestionsSize)
            for (cur in suggestions) {
                addDebugInfo(cur, typedWord)
                suggestionsList.add(cur)
            }
            return suggestionsList
        }

        @JvmStatic
        fun addDebugInfo(wordInfo: SuggestedWordInfo?, typedWord: String) {
            if (!SuggestionStripView.DEBUG_SUGGESTIONS)
                return
            val normalizedScore = BinaryDictionaryUtils.calcNormalizedScore(typedWord, wordInfo.toString(), wordInfo!!.score)
            val scoreInfoString: String
            val sourceDict = wordInfo.sourceDict!!
            val dict = sourceDict.dictType + ":" + sourceDict.locale
            scoreInfoString = if (normalizedScore > 0) {
                String.format(Locale.ROOT, "%d (%4.2f), %s", wordInfo.score, normalizedScore, dict)
            } else {
                String.format(Locale.ROOT, "%d, %s", wordInfo.score, dict)
            }
            wordInfo.setDebugString(scoreInfoString)
        }

        @JvmStatic
        fun useDefaultEmojiSkinTone(suggestion: SuggestedWordInfo): SuggestedWordInfo {
            val defaultVersion = getEmojiDefaultVersion(suggestion.word)
            if (defaultVersion == suggestion.word) {
                return suggestion
            }

            return SuggestedWordInfo(defaultVersion, suggestion.prevWordsContext, suggestion.score, suggestion.kindAndFlags,
                suggestion.sourceDict, suggestion.indexOfTouchPointOfSecondWord, suggestion.autoCommitFirstWordConfidence)
        }

        /**
         * Computes whether this suggestion should be blocked or not in this language
         *
         * This function implements a filter that avoids auto-correcting to suggestions that contain
         * spaces that are above a certain language-dependent character limit. In languages like German
         * where it's possible to concatenate many words, it often happens our dictionary does not
         * have the longer words. In this case, we offer a lot of unhelpful suggestions that contain
         * one or several spaces. Ideally we should understand what the user wants and display useful
         * suggestions by improving the dictionary and possibly having some specific logic. Until
         * that's possible we should avoid displaying unhelpful suggestions. But it's hard to tell
         * whether a suggestion is useful or not. So at least for the time being we block
         * auto-correction when the suggestion is long and contains a space, which should avoid the
         * worst damage.
         * This function is implementing that filter. If the language enforces no such limit, then it
         * always returns true. If the suggestion contains no space, it also returns true. Otherwise,
         * it checks the length against the language-specific limit.
         *
         * @param info the suggestion info
         * @return whether it's fine to auto-correct to this.
         */
        private fun isAllowedByAutoCorrectionWithSpaceFilter(info: SuggestedWordInfo): Boolean {
            val locale = info.sourceDict!!.locale ?: return true
            val maximumLengthForThisLanguage = languageToMaximumAutoCorrectionWithSpaceLength[locale.language]
                ?: return true // This language does not enforce a maximum length to auto-correction
            return (info.word.length <= maximumLengthForThisLanguage
                    || -1 == info.word.indexOf(Constants.CODE_SPACE.toChar()))
        }

        /** returns CapsMode.MANUAL, CapsMode.MANUAL_LOCKED, or CAPS_MODE_OFF */
        private fun getCapsModeForTyping(wordComposer: WordComposer, keyboard: Keyboard): CapsMode {
            val capsMode = keyboard.id.element.capsMode
            if (capsMode == CapsMode.MANUAL || capsMode == CapsMode.MANUAL_LOCKED)
                return capsMode
            // we have some auto-mode which we ignore
            // instead we determine mode from the typed word (that's how it was done for a long time, todo: maybe adjust if necessary?)
            if (wordComposer.isAllUpperCase() && !wordComposer.isResumed) return CapsMode.MANUAL_LOCKED
            if (wordComposer.isOrWillBeOnlyFirstCharCapitalized) return CapsMode.MANUAL
            return CapsMode.OFF
        }

        /** returns CapsMode.MANUAL, CapsMode.MANUAL_LOCKED, or CAPS_MODE_OFF */
        // maybe could check the details in differences to getCapsModeForTyping and unify?
        private fun getCapsModeForGesture(wordComposer: WordComposer, keyboard: Keyboard): CapsMode {
            val capsMode = keyboard.id.element.capsMode
            if (capsMode == CapsMode.MANUAL || capsMode == CapsMode.MANUAL_LOCKED)
                return capsMode
            if (wordComposer.isAllUpperCase()) return CapsMode.MANUAL_LOCKED
            if (wordComposer.wasShiftedNoLock()) return CapsMode.MANUAL
            return CapsMode.OFF
        }

        private fun capitalize(word: String, capsMode: CapsMode, locale: Locale) = when (capsMode) {
            CapsMode.MANUAL_LOCKED -> word.uppercase(locale)
            CapsMode.MANUAL -> StringUtils.capitalizeFirstCodePoint(word, locale)
            else -> word
        }

        private fun makeFirstTwoSuggestionsNonEmoji(words: MutableList<SuggestedWordInfo>) {
            for (i in 0..1) {
                if (words.size > 2 && words[i].isEmoji()) {
                    val relativeIndex = words.subList(2, words.size).indexOfFirst { !it.isEmoji() }
                    if (relativeIndex < 0) break
                    val firstNonEmojiIndex = relativeIndex + 2
                    if (firstNonEmojiIndex > i) {
                        words.add(i, words.removeAt(firstNonEmojiIndex))
                    }
                }
            }
        }

        /** reduces score of the first suggestion if next one is close and has more than a single letter */
        private fun replaceSingleLetterFirstSuggestion(suggestionResults: MutableList<SuggestedWordInfo>) {
            if (suggestionResults.size < 2 || suggestionResults.first().word.length != 1) return
            // suppress single letter suggestions if next suggestion is close and has more than one letter
            val first = suggestionResults[0]
            val second = suggestionResults[1]
            if (second.word.length > 1 && second.score > 0.94 * first.score) {
                suggestionResults.remove(first) // remove and re-add with lower score
                val modifiedFirst = SuggestedWordInfo(
                    first.word, first.prevWordsContext, (first.score * 0.93).toInt(),
                    first.kindAndFlags, first.sourceDict, first.indexOfTouchPointOfSecondWord, first.autoCommitFirstWordConfidence
                )
                val insertIndex = suggestionResults.indexOfFirst { it.score < modifiedFirst.score }
                if (insertIndex == -1) suggestionResults.add(modifiedFirst)
                else suggestionResults.add(insertIndex, modifiedFirst)

                if (DebugFlags.DEBUG_ENABLED)
                    Log.d(TAG, "reduced score of ${first.word} from ${first.score}, new first: ${suggestionResults.first().word} (${suggestionResults.first().score})")
            }
        }

        /** returns new pseudoTypedWordInfo, puts it in suggestionsContainer, modifies nextWordSuggestions */
        private fun preferNextWordSuggestion(
            pseudoTypedWordInfo: SuggestedWordInfo?,
            suggestionsContainer: ArrayList<SuggestedWordInfo>,
            nextWordSuggestions: SuggestionResults, rejected: SuggestedWordInfo?
        ): SuggestedWordInfo? {
            if (pseudoTypedWordInfo == null || !Settings.values.usePersonalizedDicts
                || pseudoTypedWordInfo.sourceDict!!.dictType != Dictionary.TYPE_MAIN || suggestionsContainer.size < 2
            ) return pseudoTypedWordInfo
            val goodNextSuggestions = nextWordSuggestions.filter { it.score >= 170 } // we only want reasonably often typed words, value may require tuning
            if (goodNextSuggestions.isEmpty()) return pseudoTypedWordInfo

            // for each suggestion, check whether the word was already typed in this ngram context (i.e. is nextWordSuggestion)
            for (suggestion in suggestionsContainer) {
                if (suggestion.score < pseudoTypedWordInfo.score * 0.93) break // we only want reasonably good suggestions, value may require tuning
                if (suggestion === rejected) continue  // ignore rejected suggestions
                for (nextWordSuggestion in goodNextSuggestions) {
                    if (nextWordSuggestion.word != suggestion.word) continue
                    // if we have a high scoring suggestion in next word suggestions, take it (because it's expected that user might want to type it again)
                    suggestionsContainer.remove(suggestion)
                    suggestionsContainer.add(0, suggestion)
                    if (DebugFlags.DEBUG_ENABLED)
                        Log.d(TAG, "replaced batch word $pseudoTypedWordInfo with $suggestion")
                    return suggestion
                }
            }
            return pseudoTypedWordInfo
        }
    }
}
