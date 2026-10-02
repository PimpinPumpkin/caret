/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.common

import java.util.Arrays

// TODO: This class is not thread-safe.
class ResizableIntArray(capacity: Int) {
    private lateinit var array: IntArray
    var length = 0
        private set

    init {
        reset(capacity)
    }

    fun get(index: Int): Int {
        if (index < length) {
            return array[index]
        }
        throw ArrayIndexOutOfBoundsException("length=$length; index=$index")
    }

    fun addAt(index: Int, value: Int) {
        if (index < length) {
            array[index] = value
        } else {
            length = index
            add(value)
        }
    }

    fun add(value: Int) {
        val currentLength = length
        ensureCapacity(currentLength + 1)
        array[currentLength] = value
        length = currentLength + 1
    }

    /**
     * Calculate the new capacity of `array`.
     * @param minimumCapacity the minimum capacity that the `array` should have.
     * @return the new capacity that the `array` should have. Returns zero when there is no
     * need to expand `array`.
     */
    private fun calculateCapacity(minimumCapacity: Int): Int {
        val currentCapcity = array.size
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
            array = Arrays.copyOf(array, newCapacity)
        }
    }

    fun setLength(newLength: Int) {
        ensureCapacity(newLength)
        length = newLength
    }

    fun reset(capacity: Int) {
        // TODO: Implement primitive array pool.
        array = IntArray(capacity)
        length = 0
    }

    val primitiveArray: IntArray
        get() = array

    fun set(ip: ResizableIntArray) {
        // TODO: Implement primitive array pool.
        array = ip.array
        length = ip.length
    }

    fun copy(ip: ResizableIntArray) {
        val newCapacity = calculateCapacity(ip.length)
        if (newCapacity > 0) {
            // TODO: Implement primitive array pool.
            array = IntArray(newCapacity)
        }
        System.arraycopy(ip.array, 0, array, 0, ip.length)
        length = ip.length
    }

    fun append(src: ResizableIntArray, startPos: Int, length: Int) {
        if (length == 0) {
            return
        }
        val currentLength = this.length
        val newLength = currentLength + length
        ensureCapacity(newLength)
        System.arraycopy(src.array, startPos, array, currentLength, length)
        this.length = newLength
    }

    fun fill(value: Int, startPos: Int, length: Int) {
        if (startPos < 0 || length < 0) {
            throw IllegalArgumentException("startPos=$startPos; length=$length")
        }
        val endPos = startPos + length
        ensureCapacity(endPos)
        Arrays.fill(array, startPos, endPos, value)
        if (this.length < endPos) {
            this.length = endPos
        }
    }

    /**
     * Shift to the left by elementCount, discarding elementCount pointers at the start.
     * @param elementCount how many elements to shift.
     */
    fun shift(elementCount: Int) {
        System.arraycopy(array, elementCount, array, 0, length - elementCount)
        length -= elementCount
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (i in 0 until length) {
            if (i != 0) {
                sb.append(",")
            }
            sb.append(array[i])
        }
        return "[$sb]"
    }
}
