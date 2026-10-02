/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.personalization

import android.content.Context
import app.caret.latin.utils.Log

import app.caret.latin.common.FileUtils

import java.io.File
import java.io.FilenameFilter
import java.lang.ref.SoftReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Helps handle and manage personalized dictionaries such as {@link UserHistoryDictionary}.
 */
class PersonalizationHelper {
    private class DictFilter(name: String) : FilenameFilter {
        private val name: String

        init {
            this.name = name
        }

        override fun accept(dir: File?, name: String?): Boolean {
            return name!!.startsWith(this.name)
        }
    }

    companion object {
        private val TAG = PersonalizationHelper::class.java.simpleName
        private const val DEBUG = false

        private val langUserHistoryDictCache =
                ConcurrentHashMap<String, SoftReference<UserHistoryDictionary>>()

        @JvmStatic
        fun getUserHistoryDictionary(context: Context, locale: Locale): UserHistoryDictionary {
            val lookupStr = locale.toString()
            synchronized(langUserHistoryDictCache) {
                if (langUserHistoryDictCache.containsKey(lookupStr)) {
                    val ref = langUserHistoryDictCache[lookupStr]
                    val dict = if (ref == null) null else ref.get()
                    if (dict != null) {
                        if (DEBUG) {
                            Log.d(TAG, "Use cached UserHistoryDictionary with lookup: $lookupStr")
                        }
                        dict.reloadDictionaryIfRequired()
                        return dict
                    }
                }
                val dict = UserHistoryDictionary(context, locale)
                langUserHistoryDictCache[lookupStr] = SoftReference(dict)
                return dict
            }
        }

        @JvmStatic
        fun removeAllUserHistoryDictionaries(context: Context) {
            synchronized(langUserHistoryDictCache) {
                for (entry in langUserHistoryDictCache.entries) {
                    if (entry.value != null) {
                        val dict = entry.value.get()
                        if (dict != null) {
                            dict.clear()
                        }
                    }
                }
                langUserHistoryDictCache.clear()
                val filesDir = context.filesDir
                if (filesDir == null) {
                    Log.e(TAG, "context.getFilesDir() returned null.")
                    return
                }
                val filesDeleted = FileUtils.deleteFilteredFiles(
                        filesDir, DictFilter(UserHistoryDictionary.NAME))
                if (!filesDeleted) {
                    Log.e(TAG, "Cannot remove dictionary files. filesDir: " + filesDir.absolutePath
                            + ", dictNamePrefix: " + UserHistoryDictionary.NAME)
                }
            }
        }
    }
}
