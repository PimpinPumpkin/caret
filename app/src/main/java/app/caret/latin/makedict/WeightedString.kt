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
    @JvmField val word: String
    @JvmField var probabilityInfo: ProbabilityInfo

    constructor(word: String, probability: Int) : this(word, ProbabilityInfo(probability))

    constructor(word: String, probabilityInfo: ProbabilityInfo) {
        this.word = word
        this.probabilityInfo = probabilityInfo
    }

    val probability: Int
        get() = probabilityInfo.probability

    fun setProbability(probability: Int) {
        probabilityInfo = ProbabilityInfo(probability)
    }

    override fun hashCode(): Int {
        return Arrays.hashCode(arrayOf<Any>(word, probabilityInfo))
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) return true
        if (o !is WeightedString) return false
        return word == o.word && probabilityInfo == o.probabilityInfo
    }
}
