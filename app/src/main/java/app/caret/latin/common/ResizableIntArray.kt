/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.common

import java.util.Arrays

// TODO: This class is not thread-safe.
class ResizableIntArray(capacity: Int) {
    private lateinit var mArray: IntArray
    private var mLength = 0

    init {
        reset(capacity)
    }

    fun get(index: Int): Int {
        if (index < mLength) {
            return mArray[index]
        }
        throw ArrayIndexOutOfBoundsException("length=$mLength; index=$index")
    }

    fun addAt(index: Int, value: Int) {
        if (index < mLength) {
            mArray[index] = value
        } else {
            mLength = index
            add(value)
        }
    }

    fun add(value: Int) {
        val currentLength = mLength
        ensureCapacity(currentLength + 1)
        mArray[currentLength] = value
        mLength = currentLength + 1
    }

    /**
     * Calculate the new capacity of `mArray`.
     * @param minimumCapacity the minimum capacity that the `mArray` should have.
     * @return the new capacity that the `mArray` should have. Returns zero when there is no
     * need to expand `mArray`.
     */
    private fun calculateCapacity(minimumCapacity: Int): Int {
        val currentCapcity = mArray.size
        if (currentCapcity < minimumCapacity) {
            val nextCapacity = currentCapcity * 2
            // The following is the same as return Math.max(minimumCapacity, nextCapacity);
            return if (minimumCapacity > nextCapacity) minimumCapacity else nextCapacity
        }
        return 0
    }

    private fun ensureCapacity(minimumCapacity: Int) {
        val newCapacity = calculateCapacity(minimumCapacity)
        if (newCapacity > 0) {
            // TODO: Implement primitive array pool.
            mArray = Arrays.copyOf(mArray, newCapacity)
        }
    }

    fun getLength(): Int {
        return mLength
    }

    fun setLength(newLength: Int) {
        ensureCapacity(newLength)
        mLength = newLength
    }

    fun reset(capacity: Int) {
        // TODO: Implement primitive array pool.
        mArray = IntArray(capacity)
        mLength = 0
    }

    fun getPrimitiveArray(): IntArray {
        return mArray
    }

    fun set(ip: ResizableIntArray) {
        // TODO: Implement primitive array pool.
        mArray = ip.mArray
        mLength = ip.mLength
    }

    fun copy(ip: ResizableIntArray) {
        val newCapacity = calculateCapacity(ip.mLength)
        if (newCapacity > 0) {
            // TODO: Implement primitive array pool.
            mArray = IntArray(newCapacity)
        }
        System.arraycopy(ip.mArray, 0, mArray, 0, ip.mLength)
        mLength = ip.mLength
    }

    fun append(src: ResizableIntArray, startPos: Int, length: Int) {
        if (length == 0) {
            return
        }
        val currentLength = mLength
        val newLength = currentLength + length
        ensureCapacity(newLength)
        System.arraycopy(src.mArray, startPos, mArray, currentLength, length)
        mLength = newLength
    }

    fun fill(value: Int, startPos: Int, length: Int) {
        if (startPos < 0 || length < 0) {
            throw IllegalArgumentException("startPos=$startPos; length=$length")
        }
        val endPos = startPos + length
        ensureCapacity(endPos)
        Arrays.fill(mArray, startPos, endPos, value)
        if (mLength < endPos) {
            mLength = endPos
        }
    }

    /**
     * Shift to the left by elementCount, discarding elementCount pointers at the start.
     * @param elementCount how many elements to shift.
     */
    fun shift(elementCount: Int) {
        System.arraycopy(mArray, elementCount, mArray, 0, mLength - elementCount)
        mLength -= elementCount
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (i in 0 until mLength) {
            if (i != 0) {
                sb.append(",")
            }
            sb.append(mArray[i])
        }
        return "[$sb]"
    }
}
