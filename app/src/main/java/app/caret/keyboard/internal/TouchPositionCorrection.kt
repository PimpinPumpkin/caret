/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.latin.define.DebugFlags

class TouchPositionCorrection {
    private var enabled = false
    private var xs: FloatArray? = null
    private var ys: FloatArray? = null
    private var radii: FloatArray? = null

    fun load(data: Array<String>) {
        val dataLength = data.size
        if (dataLength % TOUCH_POSITION_CORRECTION_RECORD_SIZE != 0) {
            if (DebugFlags.DEBUG_ENABLED) {
                throw RuntimeException(
                        "the size of touch position correction data is invalid")
            }
            return
        }

        val length = dataLength / TOUCH_POSITION_CORRECTION_RECORD_SIZE
        val newXs = FloatArray(length)
        val newYs = FloatArray(length)
        val newRadii = FloatArray(length)
        xs = newXs
        ys = newYs
        radii = newRadii
        try {
            for (i in 0 until dataLength) {
                val type = i % TOUCH_POSITION_CORRECTION_RECORD_SIZE
                val index = i / TOUCH_POSITION_CORRECTION_RECORD_SIZE
                val value = java.lang.Float.parseFloat(data[i])
                if (type == 0) {
                    newXs[index] = value
                } else if (type == 1) {
                    newYs[index] = value
                } else {
                    newRadii[index] = value
                }
            }
            enabled = dataLength > 0
        } catch (e: NumberFormatException) {
            if (DebugFlags.DEBUG_ENABLED) {
                throw RuntimeException(
                        "the number format for touch position correction data is invalid")
            }
            enabled = false
            xs = null
            ys = null
            radii = null
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    val isValid: Boolean
        get() = enabled

    val rows: Int
        get() = radii!!.size

    @Suppress("unused", "UNUSED_PARAMETER")
    fun getX(row: Int): Float {
        return 0.0f
        // Touch position correction data for X coordinate is obsolete.
        // return mXs[row];
    }

    fun getY(row: Int): Float {
        return ys!![row]
    }

    fun getRadius(row: Int): Float {
        return radii!![row]
    }

    companion object {
        private const val TOUCH_POSITION_CORRECTION_RECORD_SIZE = 3
    }
}
