/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.dictionary

import com.android.inputmethod.latin.BinaryDictionary

import app.caret.latin.NgramContext
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.ComposedData
import app.caret.latin.makedict.WordProperty
import app.caret.latin.settings.SettingsValuesForSuggestion

import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * This class provides binary dictionary reading operations with locking. An instance of this class
 * can be used by multiple threads. Note that different session IDs must be used when multiple
 * threads get suggestions using this class.
 */
class ReadOnlyBinaryDictionary(filename: String, offset: Long, length: Long,
        useFullEditDistance: Boolean, locale: Locale, dictType: String) : Dictionary(dictType, locale) {
    /**
     * A lock for accessing binary dictionary. Only closing binary dictionary is the operation
     * that change the state of dictionary.
     */
    private val lock = ReentrantReadWriteLock()

    private val binaryDictionary: BinaryDictionary

    init {
        binaryDictionary = BinaryDictionary(filename, offset, length, useFullEditDistance,
                locale, dictType, false /* isUpdatable */)
    }

    fun isValidDictionary(): Boolean {
        return binaryDictionary.isValidDictionary()
    }

    override fun getSuggestions(composedData: ComposedData?,
            ngramContext: NgramContext?, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion?,
            sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? {
        if (lock.readLock().tryLock()) {
            try {
                return binaryDictionary.getSuggestions(composedData, ngramContext,
                        proximityInfoHandle, settingsValuesForSuggestion, sessionId,
                        weightForLocale, inOutWeightOfLangModelVsSpatialModel)
            } finally {
                lock.readLock().unlock()
            }
        }
        return null
    }

    override fun isInDictionary(word: String?): Boolean {
        if (lock.readLock().tryLock()) {
            try {
                return binaryDictionary.isInDictionary(word)
            } finally {
                lock.readLock().unlock()
            }
        }
        return false
    }

    override fun shouldAutoCommit(candidate: SuggestedWordInfo?): Boolean {
        if (lock.readLock().tryLock()) {
            try {
                return binaryDictionary.shouldAutoCommit(candidate)
            } finally {
                lock.readLock().unlock()
            }
        }
        return false
    }

    override fun getFrequency(word: String?): Int {
        if (lock.readLock().tryLock()) {
            try {
                return binaryDictionary.getFrequency(word)
            } finally {
                lock.readLock().unlock()
            }
        }
        return NOT_A_PROBABILITY
    }

    override fun getMaxFrequencyOfExactMatches(word: String?): Int {
        if (lock.readLock().tryLock()) {
            try {
                return binaryDictionary.getMaxFrequencyOfExactMatches(word)
            } finally {
                lock.readLock().unlock()
            }
        }
        return NOT_A_PROBABILITY
    }

    override fun getWordProperty(word: String?, isBeginningOfSentence: Boolean): WordProperty? {
        if (lock.readLock().tryLock()) {
            try {
                return binaryDictionary.getWordProperty(word, isBeginningOfSentence)
            } finally {
                lock.readLock().unlock()
            }
        }
        return null
    }

    override fun close() {
        lock.writeLock().lock()
        try {
            binaryDictionary.close()
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun getHash(): String? {
        return binaryDictionary.getHash()
    }
}
