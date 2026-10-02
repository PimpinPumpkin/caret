/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.makedict

import java.util.Arrays

/**
 * A string with a probability.
 *
 * This represents an "attribute", that is either a bigram or a shortcut.
 */
class WeightedString {
    @JvmField val mWord: String
    @JvmField var mProbabilityInfo: ProbabilityInfo

    constructor(word: String, probability: Int) : this(word, ProbabilityInfo(probability))

    constructor(word: String, probabilityInfo: ProbabilityInfo) {
        mWord = word
        mProbabilityInfo = probabilityInfo
    }

    fun getProbability(): Int {
        return mProbabilityInfo.mProbability
    }

    fun setProbability(probability: Int) {
        mProbabilityInfo = ProbabilityInfo(probability)
    }

    override fun hashCode(): Int {
        return Arrays.hashCode(arrayOf<Any>(mWord, mProbabilityInfo))
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) return true
        if (o !is WeightedString) return false
        return mWord == o.mWord && mProbabilityInfo == o.mProbabilityInfo
    }
}
