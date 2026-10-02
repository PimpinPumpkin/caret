/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.provider.BaseColumns
import android.provider.ContactsContract.Contacts

/**
 * Constants related to Contacts Content Provider.
 */
class ContactsDictionaryConstants {
    companion object {
        @JvmField
        val PROJECTION = arrayOf(BaseColumns._ID, Contacts.DISPLAY_NAME,
                Contacts.TIMES_CONTACTED, Contacts.LAST_TIME_CONTACTED, Contacts.IN_VISIBLE_GROUP)
        @JvmField
        val PROJECTION_ID_ONLY = arrayOf(BaseColumns._ID)

        /**
         * Frequency for contacts information into the dictionary
         */
        const val FREQUENCY_FOR_CONTACTS = 100 // much increased from original frequency because contacts were barely suggested
        const val FREQUENCY_FOR_CONTACTS_BIGRAM = 200 // todo: seems broken, how to actually get bigrams?

        /**
         *  Do not attempt to query contacts if there are more than this many entries.
         */
        const val MAX_CONTACTS_PROVIDER_QUERY_LIMIT = 10000

        /**
         * Index of the column for 'name' in content providers:
         * Contacts & ContactsContract.Profile.
         */
        const val NAME_INDEX = 1
        const val TIMES_CONTACTED_INDEX = 2
        const val LAST_TIME_CONTACTED_INDEX = 3
        const val IN_VISIBLE_GROUP_INDEX = 4
    }
}
