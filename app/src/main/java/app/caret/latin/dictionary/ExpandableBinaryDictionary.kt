/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.dictionary

import android.content.Context
import app.caret.latin.NgramContext
import app.caret.latin.utils.Log

import com.android.inputmethod.latin.BinaryDictionary

import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.ComposedData
import app.caret.latin.common.FileUtils
import app.caret.latin.define.DecoderSpecificConstants
import app.caret.latin.makedict.DictionaryHeader
import app.caret.latin.makedict.FormatSpec
import app.caret.latin.makedict.UnsupportedFormatException
import app.caret.latin.makedict.WordProperty
import app.caret.latin.settings.SettingsValuesForSuggestion
import app.caret.latin.utils.AsyncResultHolder
import app.caret.latin.utils.CombinedFormatUtils
import app.caret.latin.utils.ExecutorUtils

import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Abstract base class for an expandable dictionary that can be created and updated dynamically
 * during runtime. When updated it automatically generates a new binary dictionary to handle future
 * queries in native code. This binary dictionary is written to internal storage.
 * <p>
 * A class that extends this abstract class must have a static factory method named
 *   getDictionary(Context context, Locale locale, File dictFile, String dictNamePrefix)
 */
abstract class ExpandableBinaryDictionary : Dictionary {

    /** The application context. */
    @JvmField
    val context: Context

    /**
     * The binary dictionary generated dynamically from the fusion dictionary. This is used to
     * answer unigram and bigram queries.
     */
    var binaryDictionary: BinaryDictionary?
        private set

    /**
     * The name of this dictionary, used as a part of the filename for storing the binary
     * dictionary.
     */
    private val dictName: String

    /** Dictionary file */
    private val dictFile: File

    /** Indicates whether a task for reloading the dictionary has been scheduled. */
    private val isReloading: AtomicBoolean

    /** Indicates whether the current dictionary needs to be recreated. */
    private var needsToRecreate: Boolean

    private val lock: ReentrantReadWriteLock

    /**
     * Abstract method for loading initial contents of a given dictionary.
     */
    protected abstract fun loadInitialContentsLocked()

    fun isValidDictionaryLocked(): Boolean {
        return binaryDictionary!!.isValidDictionary()
    }

    /**
     * Creates a new expandable binary dictionary.
     *
     * @param context The application context of the parent.
     * @param dictName The name of the dictionary. Multiple instances with the same
     *        name is supported.
     * @param locale the dictionary locale.
     * @param dictType the dictionary type, as a human-readable string
     * @param dictFile dictionary file path. if null, use default dictionary path based on
     *        dictionary type.
     */
    constructor(context: Context, dictName: String,
            locale: Locale, dictType: String, dictFile: File?) : super(dictType, locale) {
        this.dictName = dictName
        this.context = context
        this.dictFile = getDictFile(context, dictName, dictFile)
        binaryDictionary = null
        isReloading = AtomicBoolean()
        needsToRecreate = false
        lock = ReentrantReadWriteLock()
    }

    private fun asyncExecuteTaskWithWriteLock(task: Runnable) {
        asyncExecuteTaskWithLock(lock.writeLock(), task)
    }

    override fun getFrequency(word: String?): Int {
        if (lock.readLock().tryLock()) {
            try {
                return binaryDictionary!!.getFrequency(word)
            } finally {
                lock.readLock().unlock()
            }
        }
        return Dictionary.NOT_A_PROBABILITY
    }

    fun closeBinaryDictionary() {
        binaryDictionary?.let {
            it.close()
            binaryDictionary = null
        }
    }

    /**
     * Closes and cleans up the binary dictionary.
     */
    override fun close() {
        asyncExecuteTaskWithWriteLock(Runnable { closeBinaryDictionary() })
    }

    protected open fun getHeaderAttributeMap(): MutableMap<String, String> {
        val attributeMap = HashMap<String, String>()
        attributeMap.put(DictionaryHeader.DICTIONARY_ID_KEY, dictName)
        attributeMap.put(DictionaryHeader.DICTIONARY_LOCALE_KEY, this.locale.toString())
        attributeMap.put(DictionaryHeader.DICTIONARY_VERSION_KEY,
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()).toString())
        return attributeMap
    }

    private fun removeBinaryDictionary() {
        asyncExecuteTaskWithWriteLock(Runnable { removeBinaryDictionaryLocked() })
    }

    fun removeBinaryDictionaryLocked() {
        closeBinaryDictionary()
        if (dictFile.exists() && !FileUtils.deleteRecursively(dictFile)) {
            Log.e(TAG, "Can't remove a file: " + dictFile.getName())
        }
    }

    private fun openBinaryDictionaryLocked() {
        binaryDictionary = BinaryDictionary(
                dictFile.getAbsolutePath(), 0 /* offset */, dictFile.length(),
                true /* useFullEditDistance */, this.locale, this.dictType, true /* isUpdatable */)
    }

    fun createOnMemoryBinaryDictionaryLocked() {
        binaryDictionary = BinaryDictionary(
                dictFile.getAbsolutePath(), true /* useFullEditDistance */, this.locale, this.dictType,
                DICTIONARY_FORMAT_VERSION.toLong(), getHeaderAttributeMap())
    }

    fun clear() {
        asyncExecuteTaskWithWriteLock(Runnable {
            removeBinaryDictionaryLocked()
            createOnMemoryBinaryDictionaryLocked()
        })
    }

    /**
     * Check whether GC is needed and run GC if required.
     */
    fun runGCIfRequired(mindsBlockByGC: Boolean) {
        asyncExecuteTaskWithWriteLock(Runnable {
            if (binaryDictionary == null) {
                return@Runnable
            }
            runGCIfRequiredLocked(mindsBlockByGC)
        })
    }

    protected fun runGCIfRequiredLocked(mindsBlockByGC: Boolean) {
        val dict = binaryDictionary!!
        if (dict.needsToRunGC(mindsBlockByGC)) {
            dict.flushWithGC()
        }
    }

    private fun updateDictionaryWithWriteLock(updateTask: Runnable) {
        reloadDictionaryIfRequired()
        asyncExecuteTaskWithWriteLock(Runnable {
            if (binaryDictionary == null) {
                return@Runnable
            }
            runGCIfRequiredLocked(true /* mindsBlockByGC */)
            updateTask.run()
        })
    }

    /**
     * Adds unigram information of a word to the dictionary. May overwrite an existing entry.
     */
    fun addUnigramEntry(word: String, frequency: Int,
            shortcutTarget: String?, shortcutFreq: Int, isNotAWord: Boolean,
            isPossiblyOffensive: Boolean, timestamp: Int) {
        updateDictionaryWithWriteLock(Runnable { addUnigramLocked(word, frequency, shortcutTarget,
                shortcutFreq, isNotAWord, isPossiblyOffensive, timestamp) })
    }

    protected fun addUnigramLocked(word: String, frequency: Int,
            shortcutTarget: String?, shortcutFreq: Int, isNotAWord: Boolean,
            isPossiblyOffensive: Boolean, timestamp: Int) {
        if (!binaryDictionary!!.addUnigramEntry(word, frequency, shortcutTarget, shortcutFreq,
                false /* isBeginningOfSentence */, isNotAWord, isPossiblyOffensive, timestamp)) {
            Log.e(TAG, "Cannot add unigram entry. word: " + word)
        }
    }

    /**
     * Dynamically remove the unigram entry from the dictionary.
     */
    fun removeUnigramEntryDynamically(word: String) {
        reloadDictionaryIfRequired()
        asyncExecuteTaskWithWriteLock(Runnable {
            val binaryDictionary = this.binaryDictionary
            if (binaryDictionary == null) {
                return@Runnable
            }
            runGCIfRequiredLocked(true /* mindsBlockByGC */)
            if (!binaryDictionary.removeUnigramEntry(word)) {
                if (DEBUG) {
                    Log.i(TAG, "Cannot remove unigram entry: " + word)
                }
            }
        })
    }

    /**
     * Adds n-gram information of a word to the dictionary. May overwrite an existing entry.
     */
    fun addNgramEntry(ngramContext: NgramContext, word: String,
            frequency: Int, timestamp: Int) {
        reloadDictionaryIfRequired()
        asyncExecuteTaskWithWriteLock(Runnable {
            if (binaryDictionary == null) {
                return@Runnable
            }
            runGCIfRequiredLocked(true /* mindsBlockByGC */)
            addNgramEntryLocked(ngramContext, word, frequency, timestamp)
        })
    }

    protected fun addNgramEntryLocked(ngramContext: NgramContext, word: String,
            frequency: Int, timestamp: Int) {
        if (!binaryDictionary!!.addNgramEntry(ngramContext, word, frequency, timestamp)) {
            if (DEBUG) {
                Log.i(TAG, "Cannot add n-gram entry.")
                Log.i(TAG, "  NgramContext: " + ngramContext + ", word: " + word)
            }
        }
    }

    /**
     * Update dictionary for the word with the ngramContext.
     */
    fun updateEntriesForWord(ngramContext: NgramContext,
            word: String, isValidWord: Boolean, count: Int, timestamp: Int) {
        updateDictionaryWithWriteLock(Runnable {
            val binaryDictionary = this.binaryDictionary
            if (binaryDictionary == null) {
                return@Runnable
            }
            if (!binaryDictionary.updateEntriesForWordWithNgramContext(ngramContext, word,
                    isValidWord, count, timestamp)) {
                if (DEBUG) {
                    Log.e(TAG, "Cannot update counter. word: " + word
                            + " context: " + ngramContext)
                }
            }
        })
    }

    override fun getSuggestions(composedData: ComposedData?,
            ngramContext: NgramContext?, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion?, sessionId: Int,
            weightForLocale: Float, inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? {
        reloadDictionaryIfRequired()
        var lockAcquired = false
        try {
            lockAcquired = lock.readLock().tryLock(
                    TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS.toLong(), TimeUnit.MILLISECONDS)
            if (lockAcquired) {
                val dict = binaryDictionary ?: return null
                val suggestions: ArrayList<SuggestedWordInfo>? =
                        dict.getSuggestions(composedData, ngramContext,
                                proximityInfoHandle, settingsValuesForSuggestion, sessionId,
                                weightForLocale, inOutWeightOfLangModelVsSpatialModel)
                if (dict.isCorrupted()) {
                    Log.i(TAG, "Dictionary (" + dictName + ") is corrupted. "
                            + "Remove and regenerate it.")
                    removeBinaryDictionary()
                }
                return suggestions
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted tryLock() in getSuggestionsWithSessionId().", e)
        } finally {
            if (lockAcquired) {
                lock.readLock().unlock()
            }
        }
        return null
    }

    override fun isInDictionary(word: String?): Boolean {
        reloadDictionaryIfRequired()
        var lockAcquired = false
        try {
            lockAcquired = lock.readLock().tryLock(
                    TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS.toLong(), TimeUnit.MILLISECONDS)
            if (lockAcquired) {
                if (binaryDictionary == null) {
                    return false
                }
                return isInDictionaryLocked(word!!)
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted tryLock() in isInDictionary().", e)
        } finally {
            if (lockAcquired) {
                lock.readLock().unlock()
            }
        }
        return false
    }

    protected fun isInDictionaryLocked(word: String): Boolean {
        val dict = binaryDictionary ?: return false
        return dict.isInDictionary(word)
    }

    override fun getMaxFrequencyOfExactMatches(word: String?): Int {
        reloadDictionaryIfRequired()
        var lockAcquired = false
        try {
            lockAcquired = lock.readLock().tryLock(
                    TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS.toLong(), TimeUnit.MILLISECONDS)
            if (lockAcquired) {
                val dict = binaryDictionary ?: return Dictionary.NOT_A_PROBABILITY
                return dict.getMaxFrequencyOfExactMatches(word)
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted tryLock() in getMaxFrequencyOfExactMatches().", e)
        } finally {
            if (lockAcquired) {
                lock.readLock().unlock()
            }
        }
        return Dictionary.NOT_A_PROBABILITY
    }


    /**
     * Loads the current binary dictionary from internal storage. Assumes the dictionary file
     * exists.
     */
    fun loadBinaryDictionaryLocked() {
        if (DBG_STRESS_TEST) {
            // Test if this class does not cause problems when it takes long time to load binary
            // dictionary.
            try {
                Log.w(TAG, "Start stress in loading: " + dictName)
                Thread.sleep(15000)
                Log.w(TAG, "End stress in loading")
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while loading: " + dictName, e)
            }
        }
        val oldBinaryDictionary = binaryDictionary
        openBinaryDictionaryLocked()
        if (oldBinaryDictionary != null) {
            oldBinaryDictionary.close()
        }
        val dict = binaryDictionary!!
        if (dict.isValidDictionary()
                && needsToMigrateDictionary(dict.getFormatVersion())) {
            if (!dict.migrateTo(DICTIONARY_FORMAT_VERSION)) {
                Log.e(TAG, "Dictionary migration failed: " + dictName)
                removeBinaryDictionaryLocked()
            }
        }
    }

    /**
     * Create a new binary dictionary and load initial contents.
     */
    fun createNewDictionaryLocked() {
        removeBinaryDictionaryLocked()
        createOnMemoryBinaryDictionaryLocked()
        loadInitialContentsLocked()
        // Run GC and flush to file when initial contents have been loaded.
        binaryDictionary!!.flushWithGCIfHasUpdated()
    }

    /**
     * Marks that the dictionary needs to be recreated.
     *
     */
    protected fun setNeedsToRecreate() {
        needsToRecreate = true
    }

    fun clearNeedsToRecreate() {
        needsToRecreate = false
    }

    val isNeededToRecreate: Boolean
        get() = needsToRecreate

    /**
     * Load the current binary dictionary from internal storage. If the dictionary file doesn't
     * exists or needs to be regenerated, the new dictionary file will be asynchronously generated.
     * However, the dictionary itself is accessible even before the new dictionary file is actually
     * generated. It may return a null result for getSuggestions() in that case by design.
     */
    fun reloadDictionaryIfRequired() {
        if (!isReloadRequired()) return
        asyncReloadDictionary()
    }

    /**
     * Returns whether a dictionary reload is required.
     */
    private fun isReloadRequired(): Boolean {
        return binaryDictionary == null || needsToRecreate
    }

    /**
     * Reloads the dictionary. Access is controlled on a per dictionary file basis.
     */
    private fun asyncReloadDictionary() {
        if (!isReloading.compareAndSet(false, true)) {
            return
        }
        asyncExecuteTaskWithWriteLock(Runnable {
            try {
                if (!dictFile.exists() || isNeededToRecreate) {
                    // If the dictionary file does not exist or contents have been updated,
                    // generate a new one.
                    createNewDictionaryLocked()
                } else if (binaryDictionary == null) {
                    // Otherwise, load the existing dictionary.
                    loadBinaryDictionaryLocked()
                    val binaryDictionary = this.binaryDictionary
                    if (binaryDictionary != null && !(isValidDictionaryLocked()
                            // TODO: remove the check below
                            && matchesExpectedBinaryDictFormatVersionForThisType(binaryDictionary.getFormatVersion()))) {
                        // Binary dictionary or its format version is not valid. Regenerate
                        // the dictionary file. createNewDictionaryLocked will remove the
                        // existing files if appropriate.
                        createNewDictionaryLocked()
                    }
                }
                clearNeedsToRecreate()
            } finally {
                isReloading.set(false)
            }
        })
    }

    /**
     * Flush binary dictionary to dictionary file.
     */
    override fun onFinishInput() {
        asyncExecuteTaskWithWriteLock(Runnable {
            val binaryDictionary = this.binaryDictionary
            if (binaryDictionary == null) {
                return@Runnable
            }
            if (binaryDictionary.needsToRunGC(false /* mindsBlockByGC */)) {
                binaryDictionary.flushWithGCIfHasUpdated()
            } else {
                binaryDictionary.flush()
            }
        })
    }

    fun getDictionaryStats(): DictionaryStats? {
        reloadDictionaryIfRequired()
        val result =
                AsyncResultHolder<DictionaryStats?>("DictionaryStats")
        asyncExecuteTaskWithLock(lock.readLock(), object : Runnable {
            override fun run() {
                result.set(DictionaryStats(this@ExpandableBinaryDictionary.locale!!, dictName, dictName, dictFile, 0))
            }
        })
        return result.get(null /* defaultValue */, TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS.toLong())
    }

    fun dumpAllWordsForDebug() {
        reloadDictionaryIfRequired()
        val tag = TAG
        asyncExecuteTaskWithLock(lock.readLock(), Runnable {
            Log.d(tag, "Dump dictionary: " + dictName + " for " + this.locale)
            val binaryDictionary = this.binaryDictionary
            if (binaryDictionary == null) {
                return@Runnable
            }
            try {
                val header = binaryDictionary.getHeader()
                Log.d(tag, "Format version: " + binaryDictionary.getFormatVersion())
                Log.d(tag, CombinedFormatUtils.formatAttributeMap(header.dictionaryOptions.attributes))
            } catch (e: UnsupportedFormatException) {
                Log.d(tag, "Cannot fetch header information.", e)
            }
            var token = 0
            do {
                val result =
                        binaryDictionary.getNextWordProperty(token)
                val wordProperty = result.mWordProperty
                if (wordProperty == null) {
                    Log.d(tag, " dictionary is empty.")
                    break
                }
                Log.d(tag, wordProperty.toString())
                token = result.mNextToken
            } while (token != 0)
        })
    }

    /**
     * Returns dictionary content required for syncing.
     */
    fun getWordPropertiesForSyncing(): Array<WordProperty> {
        reloadDictionaryIfRequired()
        val result =
                AsyncResultHolder<Array<WordProperty>>("WordPropertiesForSync")
        asyncExecuteTaskWithLock(lock.readLock(), Runnable {
            val wordPropertyList = ArrayList<WordProperty>()
            val binaryDictionary = this.binaryDictionary
            if (binaryDictionary == null) {
                return@Runnable
            }
            var token = 0
            do {
                // TODO: We need a new API that returns *new* un-synced data.
                val nextWordPropertyResult =
                        binaryDictionary.getNextWordProperty(token)
                val wordProperty = nextWordPropertyResult.mWordProperty
                if (wordProperty == null) {
                    break
                }
                wordPropertyList.add(wordProperty)
                token = nextWordPropertyResult.mNextToken
            } while (token != 0)
            result.set(wordPropertyList.toTypedArray())
        })
        // TODO: Figure out the best timeout duration for this API.
        return result.get(DEFAULT_WORD_PROPERTIES_FOR_SYNC, TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS.toLong())!!
    }

    companion object {
        private const val DEBUG = false

        /** Used for Log actions from this class */
        private val TAG = ExpandableBinaryDictionary::class.java.getSimpleName()

        /** Whether to print debug output to log */
        private const val DBG_STRESS_TEST = false

        private const val TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS = 100

        /**
         * The maximum length of a word in this dictionary.
         */
        protected const val MAX_WORD_LENGTH =
                DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH

        private const val DICTIONARY_FORMAT_VERSION = FormatSpec.VERSION4

        private val DEFAULT_WORD_PROPERTIES_FOR_SYNC =
                emptyArray<WordProperty>() /* default */

        /* A extension for a binary dictionary file. */
        protected const val DICT_FILE_EXTENSION = ".dict"

        @JvmStatic
        fun matchesExpectedBinaryDictFormatVersionForThisType(formatVersion: Int): Boolean {
            return formatVersion == FormatSpec.VERSION4
        }

        private fun needsToMigrateDictionary(formatVersion: Int): Boolean {
            // When we bump up the dictionary format version, the old version should be added to here
            // for supporting migration. Note that native code has to support reading such formats.
            return formatVersion == FormatSpec.VERSION402
        }

        @JvmStatic
        fun getDictFile(context: Context, dictName: String,
                dictFile: File?): File {
            return if (dictFile != null) dictFile
                    else File(context.getFilesDir(), dictName + DICT_FILE_EXTENSION)
        }

        @JvmStatic
        fun getDictName(name: String, locale: Locale,
                dictFile: File?): String {
            return if (dictFile != null) dictFile.getName() else name + "." + locale.toLanguageTag()
        }

        private fun asyncExecuteTaskWithLock(lock: Lock, task: Runnable) {
            ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
                lock.lock()
                try {
                    task.run()
                } finally {
                    lock.unlock()
                }
            }
        }
    }
}
