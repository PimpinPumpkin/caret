/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.text.TextUtils

import app.caret.latin.App
import app.caret.latin.BuildConfig
import app.caret.latin.settings.Settings

import java.io.File

// This utility class is not publicly instantiable.
@SuppressLint("PrivateApi") // it's a fallback in try/catch
object JniUtils {
    private val TAG = JniUtils::class.java.simpleName
    const val JNI_LIB_NAME = "jni_latinime"
    const val JNI_LIB_NAME_GOOGLE = "jni_latinimegoogle"
    const val JNI_LIB_IMPORT_FILE_NAME = "libjni_latinime.so"
    private const val CHECKSUM_ARM64 = "b1049983e6ac5cfc6d1c66e38959751044fad213dff0637a6cf1d2a2703e754f"
    private const val CHECKSUM_ARM32 = "442a2a8bfcb25489564bc9433a916fa4dc0dba9000fe6f6f03f5939b985091e6"
    private const val CHECKSUM_X86_64 = "c882e12e6d48dd946e0b644c66868a720bd11ac3fecf152000e21a3d5abd59c9"
    private const val CHECKSUM_X86 = "bd946d126c957b5a6dea3bafa07fa36a27950b30e2b684dffc60746d0a1c7ad8"

    @JvmStatic
    fun expectedDefaultChecksum(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when (abi) {
            "arm64-v8a" -> CHECKSUM_ARM64
            "armeabi-v7a" -> CHECKSUM_ARM32
            "x86_64" -> CHECKSUM_X86_64
            "x86" -> CHECKSUM_X86
            else -> "-" // invalid checksum that definitely will not match
        }
    }

    @JvmField
    var haveGestureLib = false
    init {
        // hardcoded default path, may not work on all phones
        @SuppressLint("SdCardPath") var filesDir = "/data/data/" + BuildConfig.APPLICATION_ID + "/files"
        var app: Application? = App.getApp()
        if (app == null) {
            try {
                // try using reflection to get (app)context: https://stackoverflow.com/a/38967293
                // this may not be necessary any more, now that we get the app somewhere else?
                app = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null) as Application?
            } catch (ignored: Exception) { }
        }
        if (app != null && app.getFilesDir() != null) // use the actual path if possible
            filesDir = app.getFilesDir().getAbsolutePath()

        var userSuppliedLibrary: File?
        try {
            userSuppliedLibrary = File(filesDir + File.separator + JNI_LIB_IMPORT_FILE_NAME)
            if (!userSuppliedLibrary.isFile())
                userSuppliedLibrary = null
        } catch (e: Exception) {
            userSuppliedLibrary = null
        }
        if (BuildConfig.BUILD_TYPE != "nouserlib" && userSuppliedLibrary != null) {
            var wantedChecksum: String? = expectedDefaultChecksum()
            try {
                if (app != null) {
                    // we want the default preferences, because storing the checksum in device protected storage is discouraged
                    // see https://developer.android.com/reference/android/content/Context#createDeviceProtectedStorageContext()
                    // if device is locked, this will throw an IllegalStateException
                    wantedChecksum = app.protectedPrefs().getString(Settings.PREF_LIBRARY_CHECKSUM, expectedDefaultChecksum())
                }
                val checksum = ChecksumCalculator.checksum(userSuppliedLibrary)
                if (TextUtils.equals(wantedChecksum, checksum)) {
                    // try loading the library
                    System.load(userSuppliedLibrary.getAbsolutePath())
                    haveGestureLib = true // this is an assumption, any way to actually check?
                } else {
                    // delete if checksum doesn't match
                    // this is bad if we can't get the application and the user has a different library than expected...
                    //noinspection ResultOfMethodCallIgnored
                    userSuppliedLibrary.delete()
                    haveGestureLib = false
                }
            } catch (t: Throwable) { // catch everything, maybe provided library simply doesn't work
                if (t !is IllegalStateException || "SharedPreferences in credential encrypted storage are not available until after user is unlocked" != t.message)
                    // but don't log if device is locked, here we expect the exception and only load system library, if possible
                    Log.w(TAG, "Could not load user-supplied library", t)
            }
        }

        if (!haveGestureLib) {
            // try loading google library, will fail unless the library is in system and this is a system app
            try {
                System.loadLibrary(JNI_LIB_NAME_GOOGLE)
                haveGestureLib = true
            } catch (ul: UnsatisfiedLinkError) {
                Log.w(TAG, "Could not load system glide typing library " + JNI_LIB_NAME_GOOGLE + ": " + ul.message)
            }
        }
        if (!haveGestureLib) {
            // try loading built-in library
            try {
                System.loadLibrary(JNI_LIB_NAME)
            } catch (ul: UnsatisfiedLinkError) {
                Log.w(TAG, "Could not load native library " + JNI_LIB_NAME, ul)
            }
        }
    }

    @JvmStatic
    fun loadNativeLibrary() {
        // Ensures the static initializer is called
    }
}
