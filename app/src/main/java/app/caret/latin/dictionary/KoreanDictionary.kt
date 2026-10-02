// SPDX-License-Identifier: GPL-3.0-only

package app.caret.latin.dictionary

import app.caret.event.HangulCombiner
import app.caret.latin.NgramContext
import app.caret.latin.SuggestedWords
import app.caret.latin.common.ComposedData
import app.caret.latin.makedict.WordProperty
import app.caret.latin.settings.SettingsValuesForSuggestion

import java.text.Normalizer
import java.util.ArrayList

/*
 * For Korean dictionary, there are too many cases of characters to store on dictionary, which makes it slow.
 * To solve that, Unicode normalization is used to decompose Hangul syllables into Hangul jamos.
 */
class KoreanDictionary(dictionary: Dictionary) : Dictionary(dictionary.mDictType, dictionary.mLocale) {

    private val mDictionary: Dictionary = dictionary

    private fun processInput(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        val result = StringBuilder()
        for (c in normalized.toCharArray()) {
            val index = COMPAT_JAMO.indexOf(c)
            if (index == -1) result.append(c)
            else result.append(STANDARD_JAMO[index])
        }
        return result.toString()
    }

    private fun processOutput(output: String): String {
        return Normalizer.normalize(output, Normalizer.Form.NFC)
    }

    override fun getSuggestions(composedData: ComposedData?,
                  ngramContext: NgramContext?, proximityInfoHandle: Long, settingsValuesForSuggestion: SettingsValuesForSuggestion?,
                  sessionId: Int, weightForLocale: Float, inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWords.SuggestedWordInfo> {
        @Suppress("NAME_SHADOWING")
        val composedData = ComposedData(composedData!!.inputPointers,
                composedData!!.isBatchMode, processInput(composedData!!.typedWord))
        val suggestions: ArrayList<SuggestedWords.SuggestedWordInfo>? = mDictionary.getSuggestions(composedData,
                ngramContext, proximityInfoHandle, settingsValuesForSuggestion, sessionId,
                weightForLocale, inOutWeightOfLangModelVsSpatialModel)
        val result = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for (info in suggestions!!) {
            result.add(SuggestedWords.SuggestedWordInfo(processOutput(info.mWord), info.mPrevWordsContext,
                    info.mScore, info.mKindAndFlags, info.mSourceDict, info.mIndexOfTouchPointOfSecondWord, info.mAutoCommitFirstWordConfidence))
        }
        return result
    }

    override fun isInDictionary(word: String?): Boolean {
        return mDictionary.isInDictionary(processInput(word!!))
    }

    override fun getFrequency(word: String?): Int {
        return mDictionary.getFrequency(processInput(word!!))
    }

    override fun getMaxFrequencyOfExactMatches(word: String?): Int {
        return mDictionary.getMaxFrequencyOfExactMatches(processInput(word!!))
    }

    override fun getWordProperty(word: String?, isBeginningOfSentence: Boolean): WordProperty? {
        return mDictionary.getWordProperty(processInput(word!!), isBeginningOfSentence)
    }

    public override fun same(word: CharArray?, length: Int, typedWord: String?): Boolean {
        @Suppress("NAME_SHADOWING")
        val word = processInput(String(word!!)).toCharArray()
        @Suppress("NAME_SHADOWING")
        val typedWord = processInput(typedWord!!)
        return mDictionary.same(word, length, typedWord)
    }

    override fun close() {
        mDictionary.close()
    }

    override fun onFinishInput() {
        mDictionary.onFinishInput()
    }

    override fun isInitialized(): Boolean {
        return mDictionary.isInitialized()
    }

    override fun shouldAutoCommit(candidate: SuggestedWords.SuggestedWordInfo?): Boolean {
        return mDictionary.shouldAutoCommit(candidate)
    }

    override fun isUserSpecific(): Boolean {
        return mDictionary.isUserSpecific()
    }

    companion object {
        private const val COMPAT_JAMO = HangulCombiner.HangulJamo.COMPAT_CONSONANTS + HangulCombiner.HangulJamo.COMPAT_VOWELS
        private const val STANDARD_JAMO = HangulCombiner.HangulJamo.CONVERT_INITIALS + HangulCombiner.HangulJamo.CONVERT_MEDIALS
    }
}
