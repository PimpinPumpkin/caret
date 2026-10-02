/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager

/*
 * A utility class for {@link InputMethodManager}. Unlike {@link RichInputMethodManager}, this
 * class provides synchronous, non-cached access to {@link InputMethodManager}. The setup activity
 * is a good example to use this class because {@link InputMethodManagerService} may not be aware of
 * this IME immediately after this IME is installed.
 */
class UncachedInputMethodManagerUtils {
    companion object {
        /**
         * Check if the IME specified by the context is enabled.
         * CAVEAT: This may cause a round trip IPC.
         *
         * @param context package context of the IME to be checked.
         * @param imm the {@link InputMethodManager}.
         * @return true if this IME is enabled.
         */
        @JvmStatic
        fun isThisImeEnabled(context: Context, imm: InputMethodManager): Boolean {
            val packageName = context.getPackageName()
            for (imi in imm.getEnabledInputMethodList()) {
                if (packageName == imi.getPackageName()) {
                    return true
                }
            }
            return false
        }

        /**
         * Check if the IME specified by the context is the current IME.
         * CAVEAT: This may cause a round trip IPC.
         *
         * @param context package context of the IME to be checked.
         * @param imm the {@link InputMethodManager}.
         * @return true if this IME is the current IME.
         */
        @JvmStatic
        fun isThisImeCurrent(context: Context, imm: InputMethodManager): Boolean {
            val imi = getInputMethodInfoOf(context.getPackageName(), imm)
            val currentImeId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD
            )
            return imi != null && imi.getId() == currentImeId
        }

        /**
         * Get {@link InputMethodInfo} of the IME specified by the package name.
         * CAVEAT: This may cause a round trip IPC.
         *
         * @param packageName package name of the IME.
         * @param imm the {@link InputMethodManager}.
         * @return the {@link InputMethodInfo} of the IME specified by the <code>packageName</code>,
         * or null if not found.
         */
        @JvmStatic
        fun getInputMethodInfoOf(packageName: String, imm: InputMethodManager): InputMethodInfo? {
            for (imi in imm.getInputMethodList()) {
                if (packageName == imi.getPackageName()) {
                    return imi
                }
            }
            return null
        }
    }
}
