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
class KoreanDictionary(private val dictionary: Dictionary) : Dictionary(dictionary.dictType, dictionary.locale) {

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
        val original = composedData!!
        val decomposed = ComposedData(original.inputPointers,
                original.isBatchMode, processInput(original.typedWord))
        val suggestions: ArrayList<SuggestedWords.SuggestedWordInfo>? = dictionary.getSuggestions(decomposed,
                ngramContext, proximityInfoHandle, settingsValuesForSuggestion, sessionId,
                weightForLocale, inOutWeightOfLangModelVsSpatialModel)
        val result = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for (info in suggestions!!) {
            result.add(SuggestedWords.SuggestedWordInfo(processOutput(info.word), info.prevWordsContext,
                    info.score, info.kindAndFlags, info.sourceDict, info.indexOfTouchPointOfSecondWord, info.autoCommitFirstWordConfidence))
        }
        return result
    }

    override fun isInDictionary(word: String?): Boolean {
        return dictionary.isInDictionary(processInput(word!!))
    }

    override fun getFrequency(word: String?): Int {
        return dictionary.getFrequency(processInput(word!!))
    }

    override fun getMaxFrequencyOfExactMatches(word: String?): Int {
        return dictionary.getMaxFrequencyOfExactMatches(processInput(word!!))
    }

    override fun getWordProperty(word: String?, isBeginningOfSentence: Boolean): WordProperty? {
        return dictionary.getWordProperty(processInput(word!!), isBeginningOfSentence)
    }

    public override fun same(word: CharArray?, length: Int, typedWord: String?): Boolean {
        @Suppress("NAME_SHADOWING")
        val word = processInput(String(word!!)).toCharArray()
        @Suppress("NAME_SHADOWING")
        val typedWord = processInput(typedWord!!)
        return dictionary.same(word, length, typedWord)
    }

    override fun close() {
        dictionary.close()
    }

    override fun onFinishInput() {
        dictionary.onFinishInput()
    }

    override fun isInitialized(): Boolean {
        return dictionary.isInitialized()
    }

    override fun shouldAutoCommit(candidate: SuggestedWords.SuggestedWordInfo?): Boolean {
        return dictionary.shouldAutoCommit(candidate)
    }

    override fun isUserSpecific(): Boolean {
        return dictionary.isUserSpecific()
    }

    companion object {
        private const val COMPAT_JAMO = HangulCombiner.HangulJamo.COMPAT_CONSONANTS + HangulCombiner.HangulJamo.COMPAT_VOWELS
        private const val STANDARD_JAMO = HangulCombiner.HangulJamo.CONVERT_INITIALS + HangulCombiner.HangulJamo.CONVERT_MEDIALS
    }
}
