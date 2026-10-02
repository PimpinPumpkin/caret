/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.makedict

import app.caret.latin.NgramContext

class NgramProperty(targetWord: WeightedString, ngramContext: NgramContext) {
    @JvmField val mTargetWord: WeightedString
    @JvmField val mNgramContext: NgramContext

    init {
        mTargetWord = targetWord
        mNgramContext = ngramContext
    }

    override fun hashCode(): Int {
        return mTargetWord.hashCode() xor mNgramContext.hashCode()
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) return true
        if (o !is NgramProperty) return false
        return mTargetWord == o.mTargetWord && mNgramContext == o.mNgramContext
    }
}
