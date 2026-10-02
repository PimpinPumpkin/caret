/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.accessibility.AccessibilityUtils

class GestureEnabler {
    /** True if we should handle gesture events. */
    private var mShouldHandleGesture = false
    private var mMainDictionaryAvailable = false
    private var mGestureHandlingEnabledByInputField = false
    private var mGestureHandlingEnabledByUser = false

    private fun updateGestureHandlingMode() {
        mShouldHandleGesture = mMainDictionaryAvailable
                && mGestureHandlingEnabledByInputField
                && mGestureHandlingEnabledByUser
                && !AccessibilityUtils.instance.isTouchExplorationEnabled
    }

    // Note that this method is called from a non-UI thread.
    fun setMainDictionaryAvailability(mainDictionaryAvailable: Boolean) {
        mMainDictionaryAvailable = mainDictionaryAvailable
        updateGestureHandlingMode()
    }

    fun setGestureHandlingEnabledByUser(gestureHandlingEnabledByUser: Boolean) {
        mGestureHandlingEnabledByUser = gestureHandlingEnabledByUser
        updateGestureHandlingMode()
    }

    fun setPasswordMode(passwordMode: Boolean) {
        mGestureHandlingEnabledByInputField = !passwordMode
        updateGestureHandlingMode()
    }

    fun shouldHandleGesture(): Boolean {
        return mShouldHandleGesture
    }
}
