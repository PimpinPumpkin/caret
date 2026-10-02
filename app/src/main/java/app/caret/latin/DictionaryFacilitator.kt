/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.content.Context
import android.util.LruCache

import app.caret.keyboard.Keyboard
import app.caret.latin.common.ComposedData
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.dictionary.DictionaryStats
import app.caret.latin.settings.SettingsValuesForSuggestion
import app.caret.latin.utils.SuggestionResults

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Interface that facilitates interaction with different kinds of dictionaries. Provides APIs to
 * instantiate and select the correct dictionaries (based on language and settings), update entries
 * and fetch suggestions. Currently AndroidSpellCheckerService and LatinIME both use
 * DictionaryFacilitator as a client for interacting with dictionaries.
 */
interface DictionaryFacilitator {

    /** The facilitator will put words into the cache whenever it decodes them. */
    fun setValidSpellingWordReadCache(cache: LruCache<String, Boolean>)

    /** The facilitator will get words from the cache whenever it needs to check their spelling. */
    fun setValidSpellingWordWriteCache(cache: LruCache<String, Boolean>)

    /**
     * Returns whether this facilitator is exactly for this locale.
     *
     * @param locale the locale to test against
     */
    fun isForLocale(locale: Locale?): Boolean

    fun interface DictionaryInitializationListener {
        fun onUpdateMainDictionaryAvailability(isMainDictionaryAvailable: Boolean)
    }

    /**
     * Called every time {@link LatinIME} starts on a new text field.
     * <p>
     * WARNING: The service methods that call start/finish are very spammy.
     */
    fun onStartInput()

    /**
     * Called every time the {@link LatinIME} finishes with the current text field.
     * May be followed by {@link #onStartInput} again in another text field,
     * or it may be done for a while.
     * <p>
     * WARNING: The service methods that call start/finish are very spammy.
     */
    fun onFinishInput()

    /** whether a dictionary is set */
    fun isActive(): Boolean

    /** the locale provided in resetDictionaries */
    fun getMainLocale(): Locale

    /** the most "trusted" locale, differs from getMainLocale only if multilingual typing is used */
    fun getCurrentLocale(): Locale

    fun usesSameSettings(
            locales: List<Locale>,
            contacts: Boolean,
            apps: Boolean,
            personalization: Boolean
    ): Boolean

    /** switches to newLocale, gets secondary locales from current settings, and sets secondary dictionaries */
    fun resetDictionaries(
            context: Context,
            newLocale: Locale,
            useContactsDict: Boolean,
            useAppsDict: Boolean,
            usePersonalizedDicts: Boolean,
            forceReloadMainDictionary: Boolean,
            dictNamePrefix: String,
            listener: DictionaryInitializationListener?)

    /** removes the word from all editable dictionaries, and adds it to a blacklist in case it's in a read-only dictionary */
    fun removeWord(word: String)

    fun closeDictionaries()

    /** main dictionaries are loaded asynchronously after resetDictionaries */
    fun hasAtLeastOneInitializedMainDictionary(): Boolean

    /** main dictionaries are loaded asynchronously after resetDictionaries */
    fun hasAtLeastOneUninitializedMainDictionary(): Boolean

    /** main dictionaries are loaded asynchronously after resetDictionaries */
    @Throws(InterruptedException::class)
    fun waitForLoadingMainDictionaries(timeout: Long, unit: TimeUnit)

    /** adds the word to user history dictionary, calls adjustConfindences, and might add it to personal dictionary if the setting is enabled */
    fun addToUserHistory(suggestion: String, wasAutoCapitalized: Boolean,
            ngramContext: NgramContext, timeStampInSeconds: Long,
            blockPotentiallyOffensive: Boolean)

    /** adjust confidences for multilingual typing */
    fun adjustConfidences(word: String, wasAutoCapitalized: Boolean)

    /** a string with all used locales and their current confidences, null if multilingual typing is not used */
    fun localesAndConfidences(): String?

    /** completely removes the word from user history (currently not if event is a backspace event) */
    fun unlearnFromUserHistory(word: String, ngramContext: NgramContext, timeStampInSeconds: Long, event: UnlearnEvent)

    fun getSuggestionResults(composedData: ComposedData,
            ngramContext: NgramContext, keyboard: Keyboard,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int,
            inputStyle: Int): SuggestionResults

    fun isValidSpellingWord(word: String): Boolean

    fun isValidSuggestionWord(word: String): Boolean

    fun clearUserHistoryDictionary(context: Context)

    fun dump(context: Context): String

    fun dumpDictionaryForDebug(dictName: String)

    fun getDictionaryStats(context: Context): List<DictionaryStats>

    enum class UnlearnEvent { BACKSPACE, REJECTION, REVERT }

    companion object {
        @JvmField
        val ALL_DICTIONARY_TYPES = arrayOf(
                Dictionary.TYPE_MAIN,
                Dictionary.TYPE_CONTACTS,
                Dictionary.TYPE_APPS,
                Dictionary.TYPE_USER_HISTORY,
                Dictionary.TYPE_USER)

        @JvmField
        val DYNAMIC_DICTIONARY_TYPES = arrayOf(
                Dictionary.TYPE_CONTACTS,
                Dictionary.TYPE_APPS,
                Dictionary.TYPE_USER_HISTORY,
                Dictionary.TYPE_USER)
    }
}
