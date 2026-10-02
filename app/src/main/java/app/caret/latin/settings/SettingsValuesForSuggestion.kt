/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.settings

class SettingsValuesForSuggestion {
    @JvmField
    val blockPotentiallyOffensive: Boolean

    constructor(
            blockPotentiallyOffensive: Boolean,
            spaceAwareGesture: Boolean
            ) {
        this.blockPotentiallyOffensive = blockPotentiallyOffensive
        this.spaceAwareGesture = spaceAwareGesture
    }

    @JvmField
    val spaceAwareGesture: Boolean
}
