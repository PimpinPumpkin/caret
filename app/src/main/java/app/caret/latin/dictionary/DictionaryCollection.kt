/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.dictionary

import app.caret.latin.NgramContext
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.ComposedData
import app.caret.latin.settings.SettingsValuesForSuggestion
import app.caret.latin.utils.Log

import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Locale

/**
 * Class for a collection of dictionaries that behave like one dictionary.
 */
class DictionaryCollection(dictType: String, locale: Locale?,
        dictionaries: Collection<Dictionary?>, weights: FloatArray) : Dictionary(dictType, locale) {
    private val TAG = DictionaryCollection::class.java.simpleName
    private val mDictionaries: ArrayList<Dictionary>
    private val mWeights: FloatArray

    init {
        val list = ArrayList<Dictionary?>(dictionaries)
        list.removeAll(Collections.singleton<Dictionary?>(null))
        // no null elements are left at this point
        @Suppress("UNCHECKED_CAST")
        mDictionaries = list as ArrayList<Dictionary>
        if (mDictionaries.size > weights.size) {
            mWeights = FloatArray(mDictionaries.size)
            Arrays.fill(mWeights, 1f)
            Log.w(TAG, "got weights array of length " + weights.size + ", expected "+mDictionaries.size)
        } else mWeights = weights
    }

    override fun getSuggestions(composedData: ComposedData?,
            ngramContext: NgramContext?, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion?,
            sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? {
        val dictionaries = mDictionaries
        if (dictionaries.isEmpty()) return null
        // To avoid creating unnecessary objects, we get the list out of the first
        // dictionary and add the rest to it if not null, hence the get(0)
        var suggestions = dictionaries[0].getSuggestions(composedData,
                ngramContext, proximityInfoHandle, settingsValuesForSuggestion, sessionId,
                weightForLocale * mWeights[0], inOutWeightOfLangModelVsSpatialModel)
        if (null == suggestions) suggestions = ArrayList()
        val length = dictionaries.size
        for (i in 1 until length) {
            val sugg = dictionaries[i].getSuggestions(
                    composedData, ngramContext, proximityInfoHandle, settingsValuesForSuggestion,
                    sessionId, weightForLocale * mWeights[i], inOutWeightOfLangModelVsSpatialModel)
            if (null != sugg) suggestions.addAll(sugg)
        }
        return suggestions
    }

    override fun isInDictionary(word: String?): Boolean {
        for (i in mDictionaries.size - 1 downTo 0)
            if (mDictionaries[i].isInDictionary(word)) return true
        return false
    }

    override fun getFrequency(word: String?): Int {
        var maxFreq = -1
        for (i in mDictionaries.size - 1 downTo 0) {
            val tempFreq = mDictionaries[i].getFrequency(word)
            maxFreq = Math.max(tempFreq, maxFreq)
        }
        return maxFreq
    }

    override fun getMaxFrequencyOfExactMatches(word: String?): Int {
        var maxFreq = -1
        for (i in mDictionaries.size - 1 downTo 0) {
            val tempFreq = mDictionaries[i].getMaxFrequencyOfExactMatches(word)
            maxFreq = Math.max(tempFreq, maxFreq)
        }
        return maxFreq
    }

    override fun isInitialized(): Boolean {
        return !mDictionaries.isEmpty()
    }

    override fun close() {
        for (dict in mDictionaries)
            dict.close()
    }
}
