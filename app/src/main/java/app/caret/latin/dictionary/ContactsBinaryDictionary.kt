/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.dictionary

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import app.caret.latin.ContactsDictionaryConstants
import app.caret.latin.ContactsDictionaryUtils
import app.caret.latin.ContactsManager
import app.caret.latin.NgramContext
import app.caret.latin.utils.Log

import com.android.inputmethod.latin.BinaryDictionary

import app.caret.latin.ContactsManager.ContactsChangedListener
import app.caret.latin.common.StringUtils
import app.caret.latin.permissions.PermissionsUtil

import java.io.File
import java.util.Locale

class ContactsBinaryDictionary protected constructor(context: Context, locale: Locale,
        dictFile: File?, name: String) :
        ExpandableBinaryDictionary(context, ExpandableBinaryDictionary.getDictName(name, locale, dictFile), locale, Dictionary.TYPE_CONTACTS,
                dictFile),
        ContactsChangedListener {

    /**
     * Whether to use "firstname lastname" in bigram predictions.
     */
    private val useFirstLastBigrams: Boolean
    private val contactsManager: ContactsManager

    init {
        useFirstLastBigrams = ContactsDictionaryUtils.useFirstLastBigramsForLocale(locale)
        contactsManager = ContactsManager(context)
        contactsManager.registerForUpdates(this /* listener */)
        reloadDictionaryIfRequired()
    }

    @Synchronized
    override fun close() {
        contactsManager.close()
        super.close()
    }

    /**
     * Typically called whenever the dictionary is created for the first time or
     * recreated when we think that there are updates to the dictionary.
     * This is called asynchronously.
     */
    public override fun loadInitialContentsLocked() {
        loadDictionaryForUriLocked(ContactsContract.Profile.CONTENT_URI)
        // TODO: Switch this URL to the newer ContactsContract too
        loadDictionaryForUriLocked(Contacts.CONTENT_URI)
    }

    /**
     * Loads data within content providers to the dictionary.
     */
    private fun loadDictionaryForUriLocked(uri: Uri) {
        if (!PermissionsUtil.checkAllPermissionsGranted(
                mContext, Manifest.permission.READ_CONTACTS)) {
            Log.i(TAG, "No permission to read contacts. Not loading the Dictionary.")
        }

        val validNames = contactsManager.getValidNames(uri)
        for (name in validNames) {
            addNameLocked(name)
        }
        if (uri == Contacts.CONTENT_URI) {
            // Since we were able to add content successfully, update the local
            // state of the manager.
            contactsManager.updateLocalState(validNames)
        }
    }

    /**
     * Adds the words in a name (e.g., firstname/lastname) to the binary dictionary along with their
     * bigrams depending on locale.
     */
    private fun addNameLocked(name: String) {
        val len = StringUtils.codePointCount(name)
        var ngramContext = NgramContext.getEmptyPrevWordsContext(
                BinaryDictionary.MAX_PREV_WORD_COUNT_FOR_N_GRAM)
        // TODO: Better tokenization for non-Latin writing systems
        var i = 0
        while (i < len) {
            if (Character.isLetter(name.codePointAt(i))) {
                val end = ContactsDictionaryUtils.getWordEndPosition(name, len, i)
                val word = name.substring(i, end)
                if (DEBUG_DUMP) {
                    Log.d(TAG, "addName word = " + word)
                }
                i = end - 1
                // Don't add single letter words, possibly confuses
                // capitalization of i.
                val wordLen = StringUtils.codePointCount(word)
                if (wordLen <= MAX_WORD_LENGTH && wordLen > 1) {
                    if (DEBUG) {
                        Log.d(TAG, "addName " + name + ", " + word + ", "  + ngramContext)
                    }
                    runGCIfRequiredLocked(true /* mindsBlockByGC */)
                    addUnigramLocked(word, ContactsDictionaryConstants.FREQUENCY_FOR_CONTACTS,
                            null /* shortcut */, 0 /* shortcutFreq */, false /* isNotAWord */,
                            false /* isPossiblyOffensive */,
                            BinaryDictionary.NOT_A_VALID_TIMESTAMP)
                    if (ngramContext.isValid() && useFirstLastBigrams) {
                        runGCIfRequiredLocked(true /* mindsBlockByGC */)
                        addNgramEntryLocked(ngramContext,
                                word,
                                ContactsDictionaryConstants.FREQUENCY_FOR_CONTACTS_BIGRAM,
                                BinaryDictionary.NOT_A_VALID_TIMESTAMP)
                    }
                    ngramContext = ngramContext.getNextNgramContext(
                            NgramContext.WordInfo(word))
                }
            }
            i++
        }
    }

    override fun onContactsChange() {
        setNeedsToRecreate()
    }

    companion object {
        private val TAG = ContactsBinaryDictionary::class.java.simpleName
        private const val NAME = "contacts"

        private const val DEBUG = false
        private const val DEBUG_DUMP = false

        @JvmStatic
        fun getDictionary(context: Context, locale: Locale,
                dictFile: File?, dictNamePrefix: String): ContactsBinaryDictionary {
            return ContactsBinaryDictionary(context, locale, dictFile, dictNamePrefix + NAME)
        }
    }
}
