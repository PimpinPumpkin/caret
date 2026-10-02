/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.view.inputmethod.InputMethodSubtype

import app.caret.latin.RichInputMethodSubtype
import app.caret.latin.settings.Settings

import java.util.Collections
import java.util.Locale

/**
 * This class determines that the language name on the spacebar should be displayed in what format.
 */
// This utility class is not publicly instantiable.
object LanguageOnSpacebarUtils {
    const val FORMAT_TYPE_NONE = 0
    const val FORMAT_TYPE_LANGUAGE_ONLY = 1
    const val FORMAT_TYPE_FULL_LOCALE = 2

    private var enabledSubtypes: List<InputMethodSubtype> = Collections.emptyList()
    private var isSystemLanguageSameAsInputLanguage = false

    @JvmStatic
    fun getLanguageOnSpacebarFormatType(subtype: RichInputMethodSubtype): Int {
        if (!Settings.values.spaceBarText.isEmpty())
            return FORMAT_TYPE_FULL_LOCALE
        if (subtype.isNoLanguage) {
            return FORMAT_TYPE_FULL_LOCALE
        }
        // Only this subtype is enabled and equals to the system locale.
        if (enabledSubtypes.size < 2 && isSystemLanguageSameAsInputLanguage && Settings.values.secondaryLocales.isEmpty()) {
            return FORMAT_TYPE_NONE
        }
        val locale = subtype.locale
        val keyboardLanguage = locale.getLanguage()
        val keyboardLayout = subtype.mainLayoutName
        var sameLanguageAndLayoutCount = 0
        for (ims in enabledSubtypes) {
            val language = ims.locale().getLanguage()
            if (keyboardLanguage == language && keyboardLayout == ims.mainLayoutNameOrQwerty()) {
                sameLanguageAndLayoutCount++
            }
        }
        // Display full locale name only when there are multiple subtypes that have the same
        // locale and keyboard layout. Otherwise displaying language name is enough.
        return if (sameLanguageAndLayoutCount > 1) FORMAT_TYPE_FULL_LOCALE
                else FORMAT_TYPE_LANGUAGE_ONLY
    }

    @JvmStatic
    fun setEnabledSubtypes(enabledSubtypes: List<InputMethodSubtype>) {
        this.enabledSubtypes = enabledSubtypes
    }

    @JvmStatic
    fun onSubtypeChanged(subtype: RichInputMethodSubtype,
           implicitlyEnabledSubtype: Boolean, systemLocale: Locale) {
        val newLocale = subtype.locale
        if (systemLocale == newLocale) {
            isSystemLanguageSameAsInputLanguage = true
            return
        }
        if (systemLocale.getLanguage() != newLocale.getLanguage()) {
            isSystemLanguageSameAsInputLanguage = false
            return
        }
        // If the subtype is enabled explicitly, the language name should be displayed even when
        // the keyboard language and the system language are equal.
        isSystemLanguageSameAsInputLanguage = implicitlyEnabledSubtype
    }
}
