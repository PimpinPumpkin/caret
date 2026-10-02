/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

class PopupKeysDetector(slideAllowance: Float) : KeyDetector() {
    private val slideAllowanceSquare: Int
    private val slideAllowanceSquareTop: Int

    init {
        slideAllowanceSquare = (slideAllowance * slideAllowance).toInt()
        // Top slide allowance is slightly longer (sqrt(2) times) than other edges.
        slideAllowanceSquareTop = slideAllowanceSquare * 2
    }

    override fun alwaysAllowsKeySelectionByDraggingFinger(): Boolean {
        return true
    }

    override fun detectHitKey(x: Int, y: Int): Key? {
        val keyboard = this.keyboard
        if (keyboard == null) {
            return null
        }
        val touchX = getTouchX(x)
        val touchY = getTouchY(y)

        var nearestKey: Key? = null
        var nearestDist = if (y < 0) slideAllowanceSquareTop else slideAllowanceSquare
        for (key in keyboard.getSortedKeys()) {
            val dist = key.squaredDistanceToEdge(touchX, touchY)
            if (dist < nearestDist) {
                nearestKey = key
                nearestDist = dist
            }
        }
        return nearestKey
    }
}
