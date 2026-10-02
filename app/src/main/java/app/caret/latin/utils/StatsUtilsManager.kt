/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */


package app.caret.latin.utils

import android.content.Context

import app.caret.latin.DictionaryFacilitator
import app.caret.latin.settings.SettingsValues

@Suppress("unused", "UNUSED_PARAMETER")
class StatsUtilsManager {

    fun onCreate(context: Context?, dictionaryFacilitator: DictionaryFacilitator?) {
    }

    fun onLoadSettings(context: Context?, settingsValues: SettingsValues?) {
    }

    fun onStartInputView() {
    }

    fun onFinishInputView() {
    }

    fun onDestroy(context: Context?) {
    }

    companion object {
        private val instance = StatsUtilsManager()
        private var testInstance: StatsUtilsManager? = null

        /**
         * @return the singleton instance of {@link StatsUtilsManager}.
         */
        @JvmStatic
        fun getInstance(): StatsUtilsManager {
            return testInstance ?: instance
        }

        @JvmStatic
        fun setTestInstance(testInstance: StatsUtilsManager?) {
            this.testInstance = testInstance
        }
    }
}
