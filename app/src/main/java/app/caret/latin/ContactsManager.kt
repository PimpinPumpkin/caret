/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.ContactsContract.Contacts
import app.caret.latin.utils.Log

import app.caret.latin.common.Constants

import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages all interactions with Contacts DB.
 * <p>
 * The manager provides an API for listening to meaning full updates by keeping a
 * measure of the current state of the content provider.
 */
class ContactsManager(private val context: Context) {
    protected class RankedContact(cursor: Cursor) {
        @JvmField
        val name: String
        @JvmField
        val lastContactedTime: Long
        @JvmField
        val timesContacted: Int
        @JvmField
        val inVisibleGroup: Boolean

        var affinity = 0.0f
            private set

        init {
            name = cursor.getString(
                    ContactsDictionaryConstants.NAME_INDEX)
            timesContacted = cursor.getInt(
                    ContactsDictionaryConstants.TIMES_CONTACTED_INDEX)
            lastContactedTime = cursor.getLong(
                    ContactsDictionaryConstants.LAST_TIME_CONTACTED_INDEX)
            inVisibleGroup = cursor.getInt(
                    ContactsDictionaryConstants.IN_VISIBLE_GROUP_INDEX) == 1
        }

        /**
         * Calculates the affinity with the contact based on:
         * - How many times it has been contacted
         * - How long since the last contact.
         * - Whether the contact is in the visible group (i.e., Contacts list).
         * <p>
         * Note: This affinity is limited by the fact that some apps currently do not update the
         * LAST_TIME_CONTACTED or TIMES_CONTACTED counters. As a result, a frequently messaged
         * contact may still have 0 affinity.
         */
        fun computeAffinity(maxTimesContacted: Int, currentTime: Long) {
            val timesWeight = (timesContacted.toFloat() + 1) / (maxTimesContacted + 1)
            val timeSinceLastContact = Math.min(
                    Math.max(0L, currentTime - lastContactedTime),
                    TimeUnit.MILLISECONDS.convert(180, TimeUnit.DAYS))
            val lastTimeWeight = Math.pow(0.5,
                    (timeSinceLastContact / (TimeUnit.MILLISECONDS.convert(10, TimeUnit.DAYS))).toDouble()).toFloat()
            val visibleWeight = if (inVisibleGroup) 1.0f else 0.0f
            affinity = (timesWeight + lastTimeWeight + visibleWeight) / 3
        }
    }

    private class AffinityComparator : Comparator<RankedContact> {
        override fun compare(contact1: RankedContact, contact2: RankedContact): Int {
            return java.lang.Float.compare(contact2.affinity, contact1.affinity)
        }
    }

    /**
     * Interface to implement for classes interested in getting notified for updates
     * to Contacts content provider.
     */
    fun interface ContactsChangedListener {
        fun onContactsChange()
    }

    /**
     * The number of contacts observed in the most recent instance of
     * contacts content provider.
     */
    private val lastRebuildContactCount = AtomicInteger(0)

    /**
     * The hash code of list of valid contacts names in the most recent dictionary
     * rebuild.
     */
    private val lastRebuildHashCode = AtomicInteger(0)

    private val observer: ContactsContentObserver

    init {
        observer = ContactsContentObserver(this /* ContactsManager */, context)
    }

    // TODO: This was synchronized in previous version. Why?
    fun registerForUpdates(listener: ContactsChangedListener?) {
        observer.registerObserver(listener)
    }

    val contactCountAtLastRebuild: Int
        get() = lastRebuildContactCount.get()

    val hashCodeAtLastRebuild: Int
        get() = lastRebuildHashCode.get()

    /**
     * Returns all the valid names in the Contacts DB. Callers should also
     * call {@link #updateLocalState(ArrayList)} after they are done with result
     * so that the manager can cache local state for determining updates.
     * <p>
     * These names are sorted by their affinity to the user, with favorite
     * contacts appearing first.
     */
    fun getValidNames(uri: Uri): ArrayList<String> {
        // Check all contacts since it's not possible to find out which names have changed.
        // This is needed because it's possible to receive extraneous onChange events even when no
        // name has changed.
        val cursor = context.getContentResolver().query(uri,
                ContactsDictionaryConstants.PROJECTION, null, null, null)
        val contacts = ArrayList<RankedContact>()
        var maxTimesContacted = 0
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    while (!cursor.isAfterLast()) {
                        val name = cursor.getString(
                                ContactsDictionaryConstants.NAME_INDEX)
                        if (isValidName(name)) {
                            val timesContacted = cursor.getInt(
                                    ContactsDictionaryConstants.TIMES_CONTACTED_INDEX)
                            if (timesContacted > maxTimesContacted) {
                                maxTimesContacted = timesContacted
                            }
                            contacts.add(RankedContact(cursor))
                        }
                        cursor.moveToNext()
                    }
                }
            } finally {
                cursor.close()
            }
        }
        val currentTime = System.currentTimeMillis()
        for (contact in contacts) {
            contact.computeAffinity(maxTimesContacted, currentTime)
        }
        Collections.sort(contacts, AffinityComparator())
        val names = HashSet<String>()
        var i = 0
        while (i < contacts.size && names.size < MAX_CONTACT_NAMES) {
            names.add(contacts.get(i).name)
            ++i
        }
        return ArrayList(names)
    }

    /**
     * Returns the number of contacts in contacts content provider.
     */
    fun getContactCount(): Int {
        // TODO: consider switching to a rawQuery("select count(*)...") on the database if performance is a bottleneck.
        try {
            context.getContentResolver().query(Contacts.CONTENT_URI,
                    ContactsDictionaryConstants.PROJECTION_ID_ONLY, null, null, null
            ).use { cursor ->
                if (null == cursor)
                    return 0
                return cursor.getCount()
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "SQLiteException in the remote Contacts process.", e)
        }
        return 0
    }

    /**
     * Updates the local state of the manager. This should be called when the callers
     * are done with all the updates of the content provider successfully.
     */
    fun updateLocalState(names: ArrayList<String>) {
        lastRebuildContactCount.set(getContactCount())
        lastRebuildHashCode.set(names.hashCode())
    }

    /**
     * Performs any necessary cleanup.
     */
    fun close() {
        observer.unregister()
    }

    companion object {
        private const val TAG = "ContactsManager"

        /**
         * Use at most this many of the highest affinity contacts.
         */
        const val MAX_CONTACT_NAMES = 200

        private fun isValidName(name: String?): Boolean {
            if (name.isNullOrEmpty() || name.indexOf(Constants.CODE_COMMERCIAL_AT.toChar()) != -1) {
                return false
            }
            val hasSpace = name.indexOf(Constants.CODE_SPACE.toChar()) != -1
            if (!hasSpace) {
                // Only allow an isolated word if it does not contain a hyphen.
                // This helps to filter out mailing lists.
                return name.indexOf(Constants.CODE_DASH.toChar()) == -1
            }
            return true
        }
    }
}
