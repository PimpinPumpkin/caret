/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.define.ProductionFlags

import java.util.ArrayList
import java.util.TreeSet

/**
 * A TreeSet of SuggestedWordInfo that is bounded in size and throws everything that's smaller
 * than its limit
 */
class SuggestionResults : TreeSet<SuggestedWordInfo> {
    @JvmField
    val mRawSuggestions: ArrayList<SuggestedWordInfo>?
    // TODO: Instead of a boolean , we may want to include the context of this suggestion results,
    // such as {@link NgramContext}.
    @JvmField
    val mIsBeginningOfSentence: Boolean
    @JvmField
    val mFirstSuggestionExceedsConfidenceThreshold: Boolean
    private val mCapacity: Int

    constructor(capacity: Int, isBeginningOfSentence: Boolean,
            firstSuggestionExceedsConfidenceThreshold: Boolean) :
        this(sSuggestedWordInfoComparator, capacity, isBeginningOfSentence,
                firstSuggestionExceedsConfidenceThreshold)

    private constructor(comparator: Comparator<SuggestedWordInfo>, capacity: Int,
            isBeginningOfSentence: Boolean,
            firstSuggestionExceedsConfidenceThreshold: Boolean) : super(comparator) {
        mCapacity = capacity
        if (ProductionFlags.INCLUDE_RAW_SUGGESTIONS) {
            mRawSuggestions = ArrayList()
        } else {
            mRawSuggestions = null
        }
        mIsBeginningOfSentence = isBeginningOfSentence
        mFirstSuggestionExceedsConfidenceThreshold = firstSuggestionExceedsConfidenceThreshold
    }

    override fun add(e: SuggestedWordInfo): Boolean {
        if (size < mCapacity) return super.add(e)
        if (comparator().compare(e, last()) > 0) return false
        super.add(e)
        pollLast() // removes the last element
        return true
    }

    // Kotlin cannot override TreeSet.addAll with a nullable parameter, so this is an overload
    // (with its own JVM name) that callers passing a nullable collection resolve to.
    @JvmName("addAllOrNull")
    fun addAll(e: Collection<SuggestedWordInfo>?): Boolean {
        if (null == e) return false
        return super.addAll(e)
    }

    class SuggestedWordInfoComparator : Comparator<SuggestedWordInfo> {
        // This comparator ranks the word info with the higher frequency first. That's because
        // that's the order we want our elements in.
        override fun compare(o1: SuggestedWordInfo, o2: SuggestedWordInfo): Int {
            if (o1.mScore > o2.mScore) return -1
            if (o1.mScore < o2.mScore) return 1
            if (o1.mCodePointCount < o2.mCodePointCount) return -1
            if (o1.mCodePointCount > o2.mCodePointCount) return 1
            return o1.mWord.compareTo(o2.mWord)
        }
    }

    companion object {
        private val sSuggestedWordInfoComparator =
                SuggestedWordInfoComparator()
    }
}
