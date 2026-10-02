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
    private val mContext: Context
    private val mDictionaryNamePrefix: String
    private val lock = Any()
    private val mDictionaryFacilitator: DictionaryFacilitator
    private var mUseContactsDictionary = false
    private var mUseAppsDictionary = false
    private var mLocale: Locale? = null

    init {
        mContext = context
        mDictionaryNamePrefix = dictionaryNamePrefix
        mDictionaryFacilitator = DictionaryFacilitatorProvider.getDictionaryFacilitator(
                true /* isNeededForSpellChecking */)
    }

    private fun resetDictionariesForLocaleLocked() {
        // Nothing to do if the locale is null.  This would be the case before any get() calls.
        val locale = mLocale
        if (locale != null) {
          // Note: Given that personalized dictionaries are not used here; we can pass null account.
          mDictionaryFacilitator.resetDictionaries(mContext, locale, mUseContactsDictionary,
                  mUseAppsDictionary, false, false, mDictionaryNamePrefix, null)
        }
    }

    fun setUseContactsDictionary(useContactsDictionary: Boolean) {
        synchronized(lock) {
            if (mUseContactsDictionary == useContactsDictionary) {
                // The value has not been changed.
                return
            }
            mUseContactsDictionary = useContactsDictionary
            resetDictionariesForLocaleLocked()
            waitForLoadingMainDictionary(mDictionaryFacilitator)
        }
    }

    fun setUseAppsDictionary(useAppsDictionary: Boolean) {
        synchronized(lock) {
            if (mUseAppsDictionary == useAppsDictionary) {
                // The value has not been changed.
                return
            }
            mUseAppsDictionary = useAppsDictionary
            resetDictionariesForLocaleLocked()
            waitForLoadingMainDictionary(mDictionaryFacilitator)
        }
    }

    fun get(locale: Locale): DictionaryFacilitator {
        synchronized(lock) {
            if (!mDictionaryFacilitator.isForLocale(locale)) {
                mLocale = locale
                resetDictionariesForLocaleLocked()
            }
            waitForLoadingMainDictionary(mDictionaryFacilitator)
            return mDictionaryFacilitator
        }
    }

    fun closeDictionaries() {
        synchronized(lock) {
            mDictionaryFacilitator.closeDictionaries()
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
