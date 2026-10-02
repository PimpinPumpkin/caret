/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.common

// TODO: This class is not thread-safe.
class InputPointers(private val defaultCapacity: Int) {
    private val xArray = ResizableIntArray(defaultCapacity)
    private val yArray = ResizableIntArray(defaultCapacity)
    private val pointerIdArray = ResizableIntArray(defaultCapacity)
    private val timeArray = ResizableIntArray(defaultCapacity)

    private fun fillWithLastTimeUntil(index: Int) {
        val fromIndex = timeArray.length
        // Fill the gap with the latest time.
        // See {@link #getTime(int)} and {@link #isValidTimeStamps()}.
        if (fromIndex <= 0) {
            return
        }
        val fillLength = index - fromIndex + 1
        if (fillLength <= 0) {
            return
        }
        val lastTime = timeArray.get(fromIndex - 1)
        timeArray.fill(lastTime, fromIndex, fillLength)
    }

    fun addPointerAt(index: Int, x: Int, y: Int, pointerId: Int, time: Int) {
        xArray.addAt(index, x)
        yArray.addAt(index, y)
        pointerIdArray.addAt(index, pointerId)
        if (DEBUG_TIME) {
            fillWithLastTimeUntil(index)
        }
        timeArray.addAt(index, time)
    }

    fun addPointer(x: Int, y: Int, pointerId: Int, time: Int) {
        xArray.add(x)
        yArray.add(y)
        pointerIdArray.add(pointerId)
        timeArray.add(time)
    }

    fun set(ip: InputPointers) {
        xArray.set(ip.xArray)
        yArray.set(ip.yArray)
        pointerIdArray.set(ip.pointerIdArray)
        timeArray.set(ip.timeArray)
    }

    fun copy(ip: InputPointers) {
        xArray.copy(ip.xArray)
        yArray.copy(ip.yArray)
        pointerIdArray.copy(ip.pointerIdArray)
        timeArray.copy(ip.timeArray)
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
        xArray.append(xCoordinates, startPos, length)
        yArray.append(yCoordinates, startPos, length)
        pointerIdArray.fill(pointerId, pointerIdArray.length, length)
        timeArray.append(times, startPos, length)
    }

    /**
     * Shift to the left by elementCount, discarding elementCount pointers at the start.
     * @param elementCount how many elements to shift.
     */
    fun shift(elementCount: Int) {
        xArray.shift(elementCount)
        yArray.shift(elementCount)
        pointerIdArray.shift(elementCount)
        timeArray.shift(elementCount)
    }

    fun reset() {
        xArray.reset(defaultCapacity)
        yArray.reset(defaultCapacity)
        pointerIdArray.reset(defaultCapacity)
        timeArray.reset(defaultCapacity)
    }

    val pointerSize: Int
        get() = xArray.length

    val xCoordinates: IntArray
        get() = xArray.primitiveArray

    val yCoordinates: IntArray
        get() = yArray.primitiveArray

    val pointerIds: IntArray
        get() = pointerIdArray.primitiveArray

    /**
     * Gets the time each point was registered, in milliseconds, relative to the first event in the
     * sequence.
     * @return The time each point was registered, in milliseconds, relative to the first event in
     * the sequence.
     */
    val times: IntArray
        get() = timeArray.primitiveArray

    override fun toString(): String {
        return ("size=" + pointerSize + " id=" + pointerIdArray + " time=" + timeArray
                + " x=" + xArray + " y=" + yArray)
    }

    companion object {
        private const val DEBUG_TIME = false
    }
}
