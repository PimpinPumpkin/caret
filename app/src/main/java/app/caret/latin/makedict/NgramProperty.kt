/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.makedict

import app.caret.latin.NgramContext

class NgramProperty(targetWord: WeightedString, ngramContext: NgramContext) {
    @JvmField val targetWord: WeightedString
    @JvmField val ngramContext: NgramContext

    init {
        this.targetWord = targetWord
        this.ngramContext = ngramContext
    }

    override fun hashCode(): Int {
        return targetWord.hashCode() xor ngramContext.hashCode()
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) return true
        if (o !is NgramProperty) return false
        return targetWord == o.targetWord && ngramContext == o.ngramContext
    }
}
