// SPDX-License-Identifier: GPL-3.0-only

package app.caret.latin.dictionary

import android.content.Context
import com.android.inputmethod.latin.BinaryDictionary
import java.io.File
import java.util.Locale
import app.caret.latin.AppsManager
import app.caret.latin.NgramContext
import app.caret.latin.common.StringUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.SpacedTokens

// todo: actually we only need a single AppsBinaryDictionary, but currently
//  have one for each language, and may even have multiple instances in multilingual typing
class AppsBinaryDictionary protected constructor(ctx: Context, locale: Locale,
        dictFile: File?, name: String) :
        ExpandableBinaryDictionary(ctx, ExpandableBinaryDictionary.getDictName(name, locale, dictFile), locale, Dictionary.TYPE_APPS, dictFile),
        AppsManager.AppsChangedListener {

    private val appsManager: AppsManager

    init {
        appsManager = AppsManager(ctx)
        appsManager.registerForUpdates(this)
        reloadDictionaryIfRequired()
    }

    @Synchronized
    override fun close() {
        appsManager.close()
        super.close()
    }

    override fun onAppsChanged() {
        setNeedsToRecreate()
    }

    /**
     * Typically called whenever the dictionary is created for the first time or recreated when we
     * think that there are updates to the dictionary. This is called asynchronously.
     */
    public override fun loadInitialContentsLocked() {
        loadDictionaryLocked()
    }

    /**
     * Loads app names to the dictionary.
     */
    private fun loadDictionaryLocked() {
        for (name in appsManager.getNames()) {
            addNameLocked(name)
        }
    }

    /**
     * Adds the words in an app label to the binary dictionary along with their n-grams.
     */
    private fun addNameLocked(appLabel: String) {
        var ngramContext = NgramContext.getEmptyPrevWordsContext(
                BinaryDictionary.MAX_PREV_WORD_COUNT_FOR_N_GRAM)
        // TODO: Better tokenization for non-Latin writing systems
        for (word in SpacedTokens(appLabel)) {
            if (DEBUG_DUMP) {
                Log.d(TAG, "addName word = " + word)
            }
            val wordLen = StringUtils.codePointCount(word)
            // Don't add single letter words, possibly confuses capitalization of i.
            if (1 < wordLen && wordLen <= MAX_WORD_LENGTH) {
                if (DEBUG) {
                    Log.d(TAG, "addName " + appLabel + ", " + word + ", "  + ngramContext)
                }
                runGCIfRequiredLocked(true /* mindsBlockByGC */)
                addUnigramLocked(word, FREQUENCY_FOR_APPS,
                        null /* shortcut */, 0 /* shortcutFreq */, false /* isNotAWord */,
                        false /* isPossiblyOffensive */,
                        BinaryDictionary.NOT_A_VALID_TIMESTAMP)
                if (ngramContext.isValid) {
                    runGCIfRequiredLocked(true /* mindsBlockByGC */)
                    addNgramEntryLocked(ngramContext,
                            word,
                            FREQUENCY_FOR_APPS_BIGRAM,
                            BinaryDictionary.NOT_A_VALID_TIMESTAMP)
                }
                ngramContext = ngramContext.getNextNgramContext(
                        NgramContext.WordInfo(word))
            }
        }
    }

    companion object {
        private val TAG = AppsBinaryDictionary::class.java.simpleName
        private const val NAME = "apps"

        private const val FREQUENCY_FOR_APPS = 100
        private const val FREQUENCY_FOR_APPS_BIGRAM = 200

        private const val DEBUG = false
        private const val DEBUG_DUMP = false

        @JvmStatic
        fun getDictionary(context: Context, locale: Locale,
                dictFile: File?, dictNamePrefix: String): AppsBinaryDictionary {
            return AppsBinaryDictionary(context, locale, dictFile, dictNamePrefix + NAME)
        }
    }
}
