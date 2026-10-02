/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.content.Context
import app.caret.latin.utils.Log
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Cache for dictionary facilitators of multiple locales.
 * This class automatically creates and releases up to 3 facilitator instances using LRU policy.
 */
class DictionaryFacilitatorLruCache(context: Context, dictionaryNamePrefix: String) {
    private val context: Context
    private val dictionaryNamePrefix: String
    private val lock = Any()
    private val dictionaryFacilitator: DictionaryFacilitator
    private var useContactsDictionary = false
    private var useAppsDictionary = false
    private var locale: Locale? = null

    init {
        this.context = context
        this.dictionaryNamePrefix = dictionaryNamePrefix
        dictionaryFacilitator = DictionaryFacilitatorProvider.getDictionaryFacilitator(
                true /* isNeededForSpellChecking */)
    }

    private fun resetDictionariesForLocaleLocked() {
        // Nothing to do if the locale is null.  This would be the case before any get() calls.
        val locale = this.locale
        if (locale != null) {
          // Note: Given that personalized dictionaries are not used here; we can pass null account.
          dictionaryFacilitator.resetDictionaries(context, locale, useContactsDictionary,
                  useAppsDictionary, false, false, dictionaryNamePrefix, null)
        }
    }

    fun setUseContactsDictionary(useContactsDictionary: Boolean) {
        synchronized(lock) {
            if (this.useContactsDictionary == useContactsDictionary) {
                // The value has not been changed.
                return
            }
            this.useContactsDictionary = useContactsDictionary
            resetDictionariesForLocaleLocked()
            waitForLoadingMainDictionary(dictionaryFacilitator)
        }
    }

    fun setUseAppsDictionary(useAppsDictionary: Boolean) {
        synchronized(lock) {
            if (this.useAppsDictionary == useAppsDictionary) {
                // The value has not been changed.
                return
            }
            this.useAppsDictionary = useAppsDictionary
            resetDictionariesForLocaleLocked()
            waitForLoadingMainDictionary(dictionaryFacilitator)
        }
    }

    fun get(locale: Locale): DictionaryFacilitator {
        synchronized(lock) {
            if (!dictionaryFacilitator.isForLocale(locale)) {
                this.locale = locale
                resetDictionariesForLocaleLocked()
            }
            waitForLoadingMainDictionary(dictionaryFacilitator)
            return dictionaryFacilitator
        }
    }

    fun closeDictionaries() {
        synchronized(lock) {
            dictionaryFacilitator.closeDictionaries()
        }
    }

    companion object {
        private const val TAG = "DictFacilitatorLruCache"
        private const val WAIT_FOR_LOADING_MAIN_DICT_IN_MILLISECONDS = 1000
        private const val MAX_RETRY_COUNT_FOR_WAITING_FOR_LOADING_DICT = 5

        private fun waitForLoadingMainDictionary(
                dictionaryFacilitator: DictionaryFacilitator) {
            for (i in 0 until MAX_RETRY_COUNT_FOR_WAITING_FOR_LOADING_DICT) {
                try {
                    dictionaryFacilitator.waitForLoadingMainDictionaries(
                            WAIT_FOR_LOADING_MAIN_DICT_IN_MILLISECONDS.toLong(), TimeUnit.MILLISECONDS)
                    return
                } catch (e: InterruptedException) {
                    Log.i(TAG, "Interrupted during waiting for loading main dictionary.", e)
                    if (i < MAX_RETRY_COUNT_FOR_WAITING_FOR_LOADING_DICT - 1) {
                        Log.i(TAG, "Retry", e)
                    } else {
                        Log.w(TAG, "Give up retrying. Retried "
                                + MAX_RETRY_COUNT_FOR_WAITING_FOR_LOADING_DICT + " times.", e)
                    }
                }
            }
        }
    }
}
