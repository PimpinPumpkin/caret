/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.spellcheck

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.textservice.SpellCheckerService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import android.view.textservice.SuggestionsInfo
import app.caret.keyboard.Keyboard
import app.caret.keyboard.KeyboardElement
import app.caret.keyboard.KeyboardLayoutSet
import app.caret.latin.DictionaryFacilitatorLruCache
import app.caret.latin.InputAttributes
import app.caret.latin.NgramContext
import app.caret.latin.R
import app.caret.latin.RichInputMethodSubtype
import app.caret.latin.SuggestedWords
import app.caret.latin.common.ComposedData
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.settings.SettingsValuesForSuggestion
import app.caret.latin.utils.SubtypeSettings
import app.caret.latin.utils.SubtypeUtilsAdditional
import app.caret.latin.utils.SuggestionResults
import app.caret.latin.utils.prefs
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

/**
 * Service for spell checking, using LatinIME's dictionaries and mechanisms.
 */
class AndroidSpellCheckerService : SpellCheckerService(),
        SharedPreferences.OnSharedPreferenceChangeListener {

    private val MAX_NUM_OF_THREADS_READ_DICTIONARY = 2
    private val semaphore = Semaphore(MAX_NUM_OF_THREADS_READ_DICTIONARY, true)
    // TODO: Make each spell checker session has its own session id.
    private val sessionIdPool = ConcurrentLinkedQueue<Int>()

    private val dictionaryFacilitatorCache =
            DictionaryFacilitatorLruCache(this, DICTIONARY_NAME_PREFIX)
    private val keyboardCache = ConcurrentHashMap<Locale, Keyboard>()

    // The threshold for a suggestion to be considered "recommended".
    private var mRecommendedThreshold = 0f
    private lateinit var settingsValuesForSuggestion: SettingsValuesForSuggestion

    init {
        for (i in 0 until MAX_NUM_OF_THREADS_READ_DICTIONARY) {
            sessionIdPool.add(i)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mRecommendedThreshold = getString(R.string.spellchecker_recommended_threshold_value).toFloat()
        val prefs = prefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
        onSharedPreferenceChanged(prefs, Settings.PREF_USE_CONTACTS)
        onSharedPreferenceChanged(prefs, Settings.PREF_USE_APPS)
        val blockOffensive = prefs.getBoolean(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Defaults.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
        settingsValuesForSuggestion = SettingsValuesForSuggestion(blockOffensive, false)
    }

    fun getRecommendedThreshold(): Float {
        return mRecommendedThreshold
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key != null) when (key) {
            Settings.PREF_USE_CONTACTS -> {
                val useContactsDictionary = prefs!!.getBoolean(Settings.PREF_USE_CONTACTS, Defaults.PREF_USE_CONTACTS)
                dictionaryFacilitatorCache.setUseContactsDictionary(useContactsDictionary)
            }
            Settings.PREF_USE_APPS -> {
                val useAppsDictionary = prefs!!.getBoolean(Settings.PREF_USE_APPS, Defaults.PREF_USE_APPS)
                dictionaryFacilitatorCache.setUseAppsDictionary(useAppsDictionary)
            }
            Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE -> {
                val blockOffensive = prefs!!.getBoolean(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Defaults.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
                settingsValuesForSuggestion = SettingsValuesForSuggestion(blockOffensive, false)
            }
        }
    }

    override fun createSession(): SpellCheckerService.Session {
        // Should not refer to AndroidSpellCheckerSession directly considering
        // that AndroidSpellCheckerSession may be overlaid.
        return AndroidSpellCheckerSessionFactory.newInstance(this)
    }

    fun isValidWord(locale: Locale, word: String): Boolean {
        semaphore.acquireUninterruptibly()
        try {
            val dictionaryFacilitatorForLocale = dictionaryFacilitatorCache.get(locale)
            return dictionaryFacilitatorForLocale.isValidSpellingWord(word)
        } finally {
            semaphore.release()
        }
    }

    fun getSuggestionResults(locale: Locale,
            composedData: ComposedData, ngramContext: NgramContext,
            keyboard: Keyboard): SuggestionResults {
        var sessionId: Int? = null
        semaphore.acquireUninterruptibly()
        try {
            sessionId = sessionIdPool.poll()
            val dictionaryFacilitatorForLocale = dictionaryFacilitatorCache.get(locale)
            return dictionaryFacilitatorForLocale.getSuggestionResults(composedData, ngramContext,
                    keyboard, settingsValuesForSuggestion,
                    sessionId!!, SuggestedWords.INPUT_STYLE_TYPING)
        } finally {
            if (sessionId != null) {
                sessionIdPool.add(sessionId)
            }
            semaphore.release()
        }
    }

    fun hasMainDictionaryForLocale(locale: Locale): Boolean {
        semaphore.acquireUninterruptibly()
        try {
            val dictionaryFacilitator =
                    dictionaryFacilitatorCache.get(locale)
            return dictionaryFacilitator.hasAtLeastOneInitializedMainDictionary()
        } finally {
            semaphore.release()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        semaphore.acquireUninterruptibly(MAX_NUM_OF_THREADS_READ_DICTIONARY)
        try {
            dictionaryFacilitatorCache.closeDictionaries()
        } finally {
            semaphore.release(MAX_NUM_OF_THREADS_READ_DICTIONARY)
        }
        keyboardCache.clear()
        return false
    }

    fun getKeyboardForLocale(locale: Locale): Keyboard {
        var keyboard = keyboardCache[locale]
        if (keyboard == null) {
            keyboard = createKeyboardForLocale(locale)
            keyboardCache.put(locale, keyboard)
        }
        return keyboard
    }

    private fun createKeyboardForLocale(locale: Locale): Keyboard {
        if (Settings.getValues() == null) {
            // creating a keyboard reads SettingsValues from Settings instance
            // maybe it would be "more correct" to create an instance of SettingsValues and use that one instead
            // but creating a global one if not existing should be fine too
            val editorInfo = EditorInfo()
            editorInfo.inputType = InputType.TYPE_CLASS_TEXT
            Settings.getInstance().loadSettings(this, locale, InputAttributes(editorInfo, false, getPackageName()))
        }
        val mainLayoutName = SubtypeSettings.getMatchingMainLayoutNameForLocale(locale)
        val subtype = SubtypeUtilsAdditional.createDummyAdditionalSubtype(locale, mainLayoutName)
        val keyboardLayoutSet = createKeyboardSetForSpellChecker(subtype)
        return keyboardLayoutSet.getKeyboard(KeyboardElement.ALPHABET)
    }

    private fun createKeyboardSetForSpellChecker(subtype: InputMethodSubtype): KeyboardLayoutSet {
        val editorInfo = EditorInfo()
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT
        val builder = KeyboardLayoutSet.Builder(this, editorInfo, null)
        return builder
                .setKeyboardGeometry(SPELLCHECKER_DUMMY_KEYBOARD_WIDTH, SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT)
                .setSubtype(RichInputMethodSubtype.get(subtype))
                .setIsSpellChecker(true)
                .disableTouchPositionCorrectionData()
                .build()
    }

    companion object {
        const val SPELLCHECKER_DUMMY_KEYBOARD_WIDTH = 480
        const val SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT = 301

        private const val DICTIONARY_NAME_PREFIX = "spellcheck_"

        private val EMPTY_STRING_ARRAY = arrayOf<String>()

        const val SINGLE_QUOTE = "'"
        const val APOSTROPHE = "’"

        /**
         * Returns an empty SuggestionsInfo with flags signaling the word is not in the dictionary.
         * @param reportAsTypo whether this should include the flag LOOKS_LIKE_TYPO, for red underline.
         * @return the empty SuggestionsInfo with the appropriate flags set.
         */
        @JvmStatic
        fun getNotInDictEmptySuggestions(reportAsTypo: Boolean): SuggestionsInfo {
            return SuggestionsInfo(if (reportAsTypo) SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO else 0,
                    EMPTY_STRING_ARRAY)
        }
        @JvmStatic
        fun getTypoNoUiSuggestions(): SuggestionsInfo {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS,
                    EMPTY_STRING_ARRAY)
            else SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, EMPTY_STRING_ARRAY) // should not happen unless user transfers settings to old phone
        }
        /**
         * Returns an empty suggestionInfo with flags signaling the word is in the dictionary.
         * @return the empty SuggestionsInfo with the appropriate flags set.
         */
        @JvmStatic
        fun getInDictEmptySuggestions(): SuggestionsInfo {
            return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, EMPTY_STRING_ARRAY)
        }
    }
}
