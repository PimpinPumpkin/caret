/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.common

// TODO: This class is not thread-safe.
class InputPointers(defaultCapacity: Int) {
    private val mDefaultCapacity: Int
    private val mXCoordinates: ResizableIntArray
    private val mYCoordinates: ResizableIntArray
    private val mPointerIds: ResizableIntArray
    private val mTimes: ResizableIntArray

    init {
        mDefaultCapacity = defaultCapacity
        mXCoordinates = ResizableIntArray(defaultCapacity)
        mYCoordinates = ResizableIntArray(defaultCapacity)
        mPointerIds = ResizableIntArray(defaultCapacity)
        mTimes = ResizableIntArray(defaultCapacity)
    }

    private fun fillWithLastTimeUntil(index: Int) {
        val fromIndex = mTimes.getLength()
        // Fill the gap with the latest time.
        // See {@link #getTime(int)} and {@link #isValidTimeStamps()}.
        if (fromIndex <= 0) {
            return
        }
        val fillLength = index - fromIndex + 1
        if (fillLength <= 0) {
            return
        }
        val lastTime = mTimes.get(fromIndex - 1)
        mTimes.fill(lastTime, fromIndex, fillLength)
    }

    fun addPointerAt(index: Int, x: Int, y: Int, pointerId: Int, time: Int) {
        mXCoordinates.addAt(index, x)
        mYCoordinates.addAt(index, y)
        mPointerIds.addAt(index, pointerId)
        if (DEBUG_TIME) {
            fillWithLastTimeUntil(index)
        }
        mTimes.addAt(index, time)
    }

    fun addPointer(x: Int, y: Int, pointerId: Int, time: Int) {
        mXCoordinates.add(x)
        mYCoordinates.add(y)
        mPointerIds.add(pointerId)
        mTimes.add(time)
    }

    fun set(ip: InputPointers) {
        mXCoordinates.set(ip.mXCoordinates)
        mYCoordinates.set(ip.mYCoordinates)
        mPointerIds.set(ip.mPointerIds)
        mTimes.set(ip.mTimes)
    }

    fun copy(ip: InputPointers) {
        mXCoordinates.copy(ip.mXCoordinates)
        mYCoordinates.copy(ip.mYCoordinates)
        mPointerIds.copy(ip.mPointerIds)
        mTimes.copy(ip.mTimes)
    }

    /**
     * Append the times, x-coordinates and y-coordinates in the specified {@link ResizableIntArray}
     * to the end of this.
     * @param pointerId the pointer id of the source.
     * @param times the source {@link ResizableIntArray} to read the event times from.
     * @param xCoordinates the source {@link ResizableIntArray} to read the x-coordinates from.
     * @param yCoordinates the source {@link ResizableIntArray} to read the y-coordinates from.
     * @param startPos the starting index of the data in {@code times} and etc.
     * @param length the number of data to be appended.
     */
    fun append(pointerId: Int, times: ResizableIntArray, xCoordinates: ResizableIntArray,
            yCoordinates: ResizableIntArray, startPos: Int, length: Int) {
        if (length == 0) {
            return
        }
        mXCoordinates.append(xCoordinates, startPos, length)
        mYCoordinates.append(yCoordinates, startPos, length)
        mPointerIds.fill(pointerId, mPointerIds.getLength(), length)
        mTimes.append(times, startPos, length)
    }

    /**
     * Shift to the left by elementCount, discarding elementCount pointers at the start.
     * @param elementCount how many elements to shift.
     */
    fun shift(elementCount: Int) {
        mXCoordinates.shift(elementCount)
        mYCoordinates.shift(elementCount)
        mPointerIds.shift(elementCount)
        mTimes.shift(elementCount)
    }

    fun reset() {
        val defaultCapacity = mDefaultCapacity
        mXCoordinates.reset(defaultCapacity)
        mYCoordinates.reset(defaultCapacity)
        mPointerIds.reset(defaultCapacity)
        mTimes.reset(defaultCapacity)
    }

    fun getPointerSize(): Int {
        return mXCoordinates.getLength()
    }

    fun getXCoordinates(): IntArray {
        return mXCoordinates.getPrimitiveArray()
    }

    fun getYCoordinates(): IntArray {
        return mYCoordinates.getPrimitiveArray()
    }

    fun getPointerIds(): IntArray {
        return mPointerIds.getPrimitiveArray()
    }

    /**
     * Gets the time each point was registered, in milliseconds, relative to the first event in the
     * sequence.
     * @return The time each point was registered, in milliseconds, relative to the first event in
     * the sequence.
     */
    fun getTimes(): IntArray {
        return mTimes.getPrimitiveArray()
    }

    override fun toString(): String {
        return ("size=" + getPointerSize() + " id=" + mPointerIds + " time=" + mTimes
                + " x=" + mXCoordinates + " y=" + mYCoordinates)
    }

    companion object {
        private const val DEBUG_TIME = false
    }
}
