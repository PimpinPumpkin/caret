/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.view.inputmethod.InputMethodManager
import app.caret.keyboard.KeyboardLayoutSet
import app.caret.latin.settings.Settings
import app.caret.latin.utils.Log
import app.caret.latin.utils.UncachedInputMethodManagerUtils
import app.caret.latin.utils.prefs
import app.caret.settings.SettingsActivity

/**
 * This class detects the {@link Intent#ACTION_MY_PACKAGE_REPLACED} broadcast intent when this IME
 * package has been replaced by a newer version of the same package. This class also detects
 * {@link Intent#ACTION_BOOT_COMPLETED} and {@link Intent#ACTION_USER_INITIALIZE} broadcast intent.
 * <p>
 * If this IME has already been installed in the system image and a new version of this IME has
 * been installed, {@link Intent#ACTION_MY_PACKAGE_REPLACED} is received by this receiver and it
 * will hide the setup wizard's icon.
 * <p>
 * If this IME has already been installed in the data partition and a new version of this IME has
 * been installed, {@link Intent#ACTION_MY_PACKAGE_REPLACED} is received by this receiver but it
 * will not hide the setup wizard's icon, and the icon will appear on the launcher.
 * <p>
 * If this IME hasn't been installed yet and has been newly installed, no
 * {@link Intent#ACTION_MY_PACKAGE_REPLACED} will be sent and the setup wizard's icon will appear
 * on the launcher.
 * <p>
 * When the device has been booted, {@link Intent#ACTION_BOOT_COMPLETED} is received by this
 * receiver and it checks whether the setup wizard's icon should be appeared or not on the launcher
 * depending on which partition this IME is installed.
 * <p>
 * When the system locale has been changed, {@link Intent#ACTION_LOCALE_CHANGED} is received by
 * this receiver and the {@link KeyboardLayoutSet}'s cache is cleared.
 */
class SystemBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val intentAction = intent!!.action
        if (Intent.ACTION_MY_PACKAGE_REPLACED == intentAction) {
            Log.i(TAG, "Package has been replaced: " + context!!.packageName)
            toggleAppIcon(context)
        } else if (Intent.ACTION_BOOT_COMPLETED == intentAction) {
            Log.i(TAG, "Boot has been completed")
            toggleAppIcon(context!!)
        } else if (Intent.ACTION_LOCALE_CHANGED == intentAction) {
            Log.i(TAG, "System locale changed")
            KeyboardLayoutSet.onSystemLocaleChanged()
        }

        // The process that hosts this broadcast receiver is invoked and remains alive even after
        // 1) the package has been re-installed,
        // 2) the device has just booted,
        // 3) a new user has been created.
        // There is no good reason to keep the process alive if this IME isn't a current IME.
        val imm = context!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // Called to check whether this IME has been triggered by the current user or not
        val isInputMethodManagerValidForUserOfThisProcess =
                !imm.inputMethodList.isEmpty()
        val isCurrentImeOfCurrentUser = isInputMethodManagerValidForUserOfThisProcess
                && UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm)
        if (!isCurrentImeOfCurrentUser) {
            val myPid = Process.myPid()
            Log.i(TAG, "Killing my process: pid=$myPid")
            Process.killProcess(myPid)
        }
    }

    companion object {
        private val TAG = SystemBroadcastReceiver::class.java.simpleName

        @JvmStatic
        fun toggleAppIcon(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                return // can't change visibility in Android 10 and above
            val prefs = context.prefs()
            context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, SettingsActivity::class.java),
                    if (Settings.readShowSetupWizardIcon(prefs, context))
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
        }
    }
}
