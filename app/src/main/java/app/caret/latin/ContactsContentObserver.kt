/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.SystemClock
import android.provider.ContactsContract.Contacts
import app.caret.latin.utils.Log

import app.caret.latin.ContactsManager.ContactsChangedListener
import app.caret.latin.define.DebugFlags
import app.caret.latin.permissions.PermissionsUtil
import app.caret.latin.utils.ExecutorUtils

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A content observer that listens to updates to content provider {@link Contacts#CONTENT_URI}.
 */
class ContactsContentObserver(manager: ContactsManager, context: Context) : Runnable {
    private val mContext: Context
    private val mManager: ContactsManager
    private val mRunning = AtomicBoolean(false)

    private var mContentObserver: ContentObserver? = null
    private var mContactsChangedListener: ContactsChangedListener? = null

    init {
        mManager = manager
        mContext = context
    }

    fun registerObserver(listener: ContactsChangedListener?) {
        if (!PermissionsUtil.checkAllPermissionsGranted(
                mContext, Manifest.permission.READ_CONTACTS)) {
            Log.i(TAG, "No permission to read contacts. Not registering the observer.")
            // do nothing if we do not have the permission to read contacts.
            return
        }

        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "registerObserver()")
        }
        mContactsChangedListener = listener
        val contentObserver = object : ContentObserver(null /* handler */) {
            override fun onChange(self: Boolean) {
                ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD)
                        .execute(this@ContactsContentObserver)
            }
        }
        mContentObserver = contentObserver
        val contentResolver = mContext.getContentResolver()
        contentResolver.registerContentObserver(Contacts.CONTENT_URI, true, contentObserver)
    }

    override fun run() {
        if (!PermissionsUtil.checkAllPermissionsGranted(
                mContext, Manifest.permission.READ_CONTACTS)) {
            Log.i(TAG, "No permission to read contacts. Not updating the contacts.")
            unregister()
            return
        }

        if (!mRunning.compareAndSet(false /* expect */, true /* update */)) {
            if (DebugFlags.DEBUG_ENABLED) {
                Log.d(TAG, "run() : Already running. Don't waste time checking again.")
            }
            return
        }
        if (haveContentsChanged()) {
            if (DebugFlags.DEBUG_ENABLED) {
                Log.d(TAG, "run() : Contacts have changed. Notifying listeners.")
            }
            mContactsChangedListener!!.onContactsChange()
        }
        mRunning.set(false)
    }

    fun haveContentsChanged(): Boolean {
        if (!PermissionsUtil.checkAllPermissionsGranted(
                mContext, Manifest.permission.READ_CONTACTS)) {
            Log.i(TAG, "No permission to read contacts. Marking contacts as not changed.")
            return false
        }

        val startTime = SystemClock.uptimeMillis()
        val contactCount = mManager.getContactCount()
        if (contactCount > ContactsDictionaryConstants.MAX_CONTACTS_PROVIDER_QUERY_LIMIT) {
            // If there are too many contacts then return false. In this rare case it is impossible
            // to include all of them anyways and the cost of rebuilding the dictionary is too high.
            // TODO: Sort and check only the most recent contacts?
            return false
        }
        if (contactCount != mManager.getContactCountAtLastRebuild()) {
            if (DebugFlags.DEBUG_ENABLED) {
                Log.d(TAG, "haveContentsChanged() : Count changed from "
                        + mManager.getContactCountAtLastRebuild() + " to " + contactCount)
            }
            return true
        }
        val names = mManager.getValidNames(Contacts.CONTENT_URI)
        if (names.hashCode() != mManager.getHashCodeAtLastRebuild()) {
            return true
        }
        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "haveContentsChanged() : No change detected in "
                    + (SystemClock.uptimeMillis() - startTime) + " ms)")
        }
        return false
    }

    fun unregister() {
        mContext.getContentResolver().unregisterContentObserver(mContentObserver!!)
    }

    companion object {
        private const val TAG = "ContactsContentObserver"
    }
}
