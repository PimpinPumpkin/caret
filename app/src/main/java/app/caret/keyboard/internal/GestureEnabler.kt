/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.accessibility.AccessibilityUtils

class GestureEnabler {
    /** True if we should handle gesture events. */
    private var gestureHandlingEnabled = false
    private var mainDictionaryAvailable = false
    private var gestureHandlingEnabledByInputField = false
    private var gestureHandlingEnabledByUser = false

    private fun updateGestureHandlingMode() {
        gestureHandlingEnabled = mainDictionaryAvailable
                && gestureHandlingEnabledByInputField
                && gestureHandlingEnabledByUser
                && !AccessibilityUtils.instance.isTouchExplorationEnabled
    }

    // Note that this method is called from a non-UI thread.
    fun setMainDictionaryAvailability(mainDictionaryAvailable: Boolean) {
        this.mainDictionaryAvailable = mainDictionaryAvailable
        updateGestureHandlingMode()
    }

    fun setGestureHandlingEnabledByUser(gestureHandlingEnabledByUser: Boolean) {
        this.gestureHandlingEnabledByUser = gestureHandlingEnabledByUser
        updateGestureHandlingMode()
    }

    fun setPasswordMode(passwordMode: Boolean) {
        gestureHandlingEnabledByInputField = !passwordMode
        updateGestureHandlingMode()
    }

    fun shouldHandleGesture(): Boolean {
        return gestureHandlingEnabled
    }
}
