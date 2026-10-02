// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.dictionary.Dictionary
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PredictionOrderTest {
    private class FakeDictionary(type: String) : Dictionary(type, Locale.ENGLISH) {
        override fun getSuggestions(composedData: app.caret.latin.common.ComposedData?, ngramContext: NgramContext?,
            proximityInfoHandle: Long, settingsValuesForSuggestion: app.caret.latin.settings.SettingsValuesForSuggestion?,
            sessionId: Int, weightForLocale: Float, inOutWeightOfLangModelVsSpatialModel: FloatArray?
        ): ArrayList<SuggestedWordInfo>? = null
        override fun isInDictionary(word: String?) = false
    }
    private val history = FakeDictionary(Dictionary.TYPE_USER_HISTORY)
    private val main = FakeDictionary(Dictionary.TYPE_MAIN)

    private fun info(word: String, score: Int, dict: Dictionary) = SuggestedWordInfo(word, "", score,
        SuggestedWordInfo.KIND_PREDICTION, dict, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    private fun order(existing: List<SuggestedWordInfo>, model: List<String>) =
        DictionaryFacilitatorImpl.orderPredictions(existing, model, Locale.ENGLISH, main).map { it.word }

    @Test fun `the model's words come after two personal predictions and before the dictionary's`() {
        val existing = listOf(info("mine", 90, history), info("also", 80, history), info("third", 70, history),
            info("dull", 60, main))
        assertEquals(listOf("mine", "also", "good", "better", "third", "dull"), order(existing, listOf("good", "better")))
    }

    @Test fun `a word is listed once, where it first appears`() {
        val existing = listOf(info("Good", 90, history), info("dull", 60, main), info("better", 50, main))
        assertEquals(listOf("Good", "better", "new", "dull"), order(existing, listOf("good", "better", "new")))
    }

    @Test fun `without personal predictions the model leads`() {
        assertEquals(listOf("a", "b", "dull"), order(listOf(info("dull", 60, main)), listOf("a", "b")))
    }
}
