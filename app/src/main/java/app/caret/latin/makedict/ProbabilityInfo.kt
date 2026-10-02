/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.makedict

import com.android.inputmethod.latin.BinaryDictionary
import app.caret.latin.utils.CombinedFormatUtils

import java.util.Arrays

class ProbabilityInfo {
    @JvmField val probability: Int
    // timestamp, level and count are historical info. These values are depend on the
    // implementation in native code; thus, we must not use them and have any assumptions about
    // them except for tests.
    @JvmField val timestamp: Int
    @JvmField val level: Int
    @JvmField val count: Int

    constructor(probability: Int) : this(probability, BinaryDictionary.NOT_A_VALID_TIMESTAMP, 0, 0)

    constructor(probability: Int, timestamp: Int, level: Int,
            count: Int) {
        this.probability = probability
        this.timestamp = timestamp
        this.level = level
        this.count = count
    }

    fun hasHistoricalInfo(): Boolean {
        return timestamp != BinaryDictionary.NOT_A_VALID_TIMESTAMP
    }

    override fun hashCode(): Int {
        if (hasHistoricalInfo()) {
            return Arrays.hashCode(arrayOf<Any>(probability, timestamp, level, count))
        }
        return Arrays.hashCode(arrayOf<Any>(probability))
    }

    override fun toString(): String {
        return CombinedFormatUtils.formatProbabilityInfo(this)
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) return true
        if (o !is ProbabilityInfo) return false
        if (!hasHistoricalInfo() && !o.hasHistoricalInfo()) {
            return probability == o.probability
        }
        return probability == o.probability && timestamp == o.timestamp && level == o.level
                && count == o.count
    }

    companion object {
        @JvmStatic
        fun max(probabilityInfo1: ProbabilityInfo?,
                probabilityInfo2: ProbabilityInfo?): ProbabilityInfo? {
            if (probabilityInfo1 == null) {
                return probabilityInfo2
            }
            if (probabilityInfo2 == null) {
                return probabilityInfo1
            }
            return if (probabilityInfo1.probability > probabilityInfo2.probability) probabilityInfo1
                    else probabilityInfo2
        }
    }
}
