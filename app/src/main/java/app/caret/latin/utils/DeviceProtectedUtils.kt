/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

import java.io.File

object DeviceProtectedUtils {

    @JvmField
    val TAG: String = DeviceProtectedUtils::class.java.simpleName
    private var prefs: SharedPreferences? = null

    @JvmStatic
    fun getSharedPreferences(context: Context): SharedPreferences {
        val cachedPrefs = prefs
        if (cachedPrefs != null)
            return cachedPrefs
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val defaultPrefs = getDefaultSharedPreferences(context)
            prefs = defaultPrefs
            return defaultPrefs
        }
        val deviceProtectedContext = getDeviceProtectedContext(context)
        val newPrefs = getDefaultSharedPreferences(deviceProtectedContext)
        prefs = newPrefs
        if (newPrefs.getAll() == null)
            return newPrefs // happens for compose previews
        if (newPrefs.getAll().isEmpty()) {
            Log.i(TAG, "Device encrypted storage is empty, copying values from credential encrypted storage")
            deviceProtectedContext.moveSharedPreferencesFrom(context, android.preference.PreferenceManager.getDefaultSharedPreferencesName(context))
        }
        return newPrefs
    }

    // keep this private to avoid accidental use of device protected context anywhere in the app
    private fun getDeviceProtectedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return context
        val ctx: Context? = if (context.isDeviceProtectedStorage()) context else context.createDeviceProtectedStorageContext()
        if (ctx == null) return context // happens for compose previews
        else return ctx
    }

    private fun getDefaultSharedPreferences(context: Context): SharedPreferences {
        // from androidx.preference.PreferenceManager
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun getFilesDir(context: Context): File {
        return getDeviceProtectedContext(context).getFilesDir()
    }
}
