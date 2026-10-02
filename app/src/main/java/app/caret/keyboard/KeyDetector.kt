/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

/**
 * This class handles key detection.
 */
open class KeyDetector {
    private val keyHysteresisDistanceSquared: Int
    private val keyHysteresisDistanceForSlidingModifierSquared: Int

    var keyboard: Keyboard? = null
        private set
    private var correctionX = 0
    private var correctionY = 0

    constructor() : this(0.0f /* keyHysteresisDistance */, 0.0f /* keyHysteresisDistanceForSlidingModifier */)

    /**
     * Key detection object constructor with key hysteresis distances.
     *
     * @param keyHysteresisDistance if the pointer movement distance is smaller than this, the
     * movement will not be handled as meaningful movement. The unit is pixel.
     * @param keyHysteresisDistanceForSlidingModifier the same parameter for sliding input that
     * starts from a modifier key such as shift and symbols key.
     */
    constructor(keyHysteresisDistance: Float, keyHysteresisDistanceForSlidingModifier: Float) {
        keyHysteresisDistanceSquared = (keyHysteresisDistance * keyHysteresisDistance).toInt()
        keyHysteresisDistanceForSlidingModifierSquared = (
                keyHysteresisDistanceForSlidingModifier * keyHysteresisDistanceForSlidingModifier).toInt()
    }

    fun setKeyboard(keyboard: Keyboard?, correctionX: Float, correctionY: Float) {
        if (keyboard == null) {
            throw NullPointerException()
        }
        this.correctionX = correctionX.toInt()
        this.correctionY = correctionY.toInt()
        this.keyboard = keyboard
    }

    fun getKeyHysteresisDistanceSquared(isSlidingFromModifier: Boolean): Int {
        return if (isSlidingFromModifier)
            keyHysteresisDistanceForSlidingModifierSquared else keyHysteresisDistanceSquared
    }

    fun getTouchX(x: Int): Int {
        return x + correctionX
    }

    // TODO: Remove vertical correction.
    fun getTouchY(y: Int): Int {
        return y + correctionY
    }

    open fun alwaysAllowsKeySelectionByDraggingFinger(): Boolean {
        return false
    }

    /**
     * Detect the key whose hitbox the touch point is in.
     *
     * @param x The x-coordinate of a touch point
     * @param y The y-coordinate of a touch point
     * @return the key that the touch point hits.
     */
    open fun detectHitKey(x: Int, y: Int): Key? {
        val currentKeyboard = keyboard ?: return null
        val touchX = getTouchX(x)
        val touchY = getTouchY(y)

        var minDistance = Int.MAX_VALUE
        var primaryKey: Key? = null
        for (key in currentKeyboard.getNearestKeys(touchX, touchY)) {
            // An edge key always has its enlarged hitbox to respond to an event that occurred in
            // the empty area around the key. (@see Key#markAsLeftEdge(KeyboardParams)} etc.)
            if (!key.isOnKey(touchX, touchY)) {
                continue
            }
            val distance = key.squaredDistanceToEdge(touchX, touchY)
            if (distance > minDistance) {
                continue
            }
            // To take care of hitbox overlaps, we compare key's code here too.
            if (primaryKey == null || distance < minDistance
                    || key.code > primaryKey.code) {
                minDistance = distance
                primaryKey = key
            }
        }
        return primaryKey
    }
}
