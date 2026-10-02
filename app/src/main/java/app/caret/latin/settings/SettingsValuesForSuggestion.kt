/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.settings

class SettingsValuesForSuggestion {
    @JvmField
    val mBlockPotentiallyOffensive: Boolean

    constructor(
            blockPotentiallyOffensive: Boolean,
            spaceAwareGesture: Boolean
            ) {
        mBlockPotentiallyOffensive = blockPotentiallyOffensive
        mSpaceAwareGesture = spaceAwareGesture
    }

    @JvmField
    val mSpaceAwareGesture: Boolean
}
