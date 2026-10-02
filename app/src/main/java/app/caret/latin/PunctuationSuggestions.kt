/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import app.caret.keyboard.internal.KeySpecParser
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.StringUtils
import app.caret.latin.dictionary.Dictionary

import java.util.ArrayList
import java.util.Arrays

/**
 * The extended {@link SuggestedWords} class to represent punctuation suggestions.
 * <p>
 * Each punctuation specification string is the key specification that can be parsed by
 * {@link KeySpecParser}.
 */
class PunctuationSuggestions private constructor(punctuationsList: ArrayList<SuggestedWordInfo>) :
        SuggestedWords(punctuationsList,
                null /* rawSuggestions */,
                null /* typedWord */,
                false /* typedWordValid */,
                false /* hasAutoCorrectionCandidate */,
                false /* isObsoleteSuggestions */,
                SuggestedWords.INPUT_STYLE_NONE /* inputStyle */,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER) {

    /**
     * {@inheritDoc}
     * Note that {@link SuggestedWords#getWord(int)} returns a punctuation key specification text.
     * The suggested punctuation should be gotten by parsing the key specification.
     */
    override fun getWord(index: Int): String {
        val keySpec = super.getWord(index)
        val code = KeySpecParser.getCode(keySpec)
        return if (code == KeyCode.MULTIPLE_CODE_POINTS)
            KeySpecParser.getOutputText(keySpec, code)!!
        else
            StringUtils.newSingleCodePointString(code)
    }

    /**
     * {@inheritDoc}
     * Note that {@link SuggestedWords#getWord(int)} returns a punctuation key specification text.
     * The displayed text should be gotten by parsing the key specification.
     */
    override fun getLabel(index: Int): String {
        val keySpec = super.getWord(index)
        return KeySpecParser.getLabel(keySpec)!!
    }

    /**
     * {@inheritDoc}
     * Note that {@link #getWord(int)} returns a suggested punctuation. We should create a
     * {@link SuggestedWords.SuggestedWordInfo} object that represents a hard coded word.
     */
    override fun getInfo(index: Int): SuggestedWordInfo {
        return newHardCodedWordInfo(getWord(index))
    }

    /**
     * The predicator to tell whether this object represents punctuation suggestions.
     * @return true if this object represents punctuation suggestions.
     */
    override fun isPunctuationSuggestions(): Boolean {
        return true
    }

    override fun toString(): String {
        return ("PunctuationSuggestions: "
                + " words=" + Arrays.toString(this.suggestedWordInfoList.toTypedArray()))
    }

    companion object {
        /**
         * Create new instance of {@link PunctuationSuggestions} from the array of punctuation key
         * specifications.
         *
         * @param punctuationSpecs The array of punctuation key specifications.
         * @return The {@link PunctuationSuggestions} object.
         */
        @JvmStatic
        fun newPunctuationSuggestions(
                punctuationSpecs: Array<String>?): PunctuationSuggestions {
            if (punctuationSpecs == null || punctuationSpecs.size == 0) {
                return PunctuationSuggestions(ArrayList(0))
            }
            val punctuationList =
                    ArrayList<SuggestedWordInfo>(punctuationSpecs.size)
            for (spec in punctuationSpecs) {
                punctuationList.add(newHardCodedWordInfo(spec))
            }
            return PunctuationSuggestions(punctuationList)
        }

        private fun newHardCodedWordInfo(keySpec: String): SuggestedWordInfo {
            return SuggestedWordInfo(keySpec, "" /* prevWordsContext */,
                    SuggestedWordInfo.MAX_SCORE,
                    SuggestedWordInfo.KIND_HARDCODED,
                    Dictionary.DICTIONARY_HARDCODED,
                    SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
                    SuggestedWordInfo.NOT_A_CONFIDENCE /* autoCommitFirstWordConfidence */)
        }
    }
}
