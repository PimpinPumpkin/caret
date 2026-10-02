/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.util.SparseArray

import com.android.inputmethod.keyboard.ProximityInfo

import app.caret.keyboard.internal.KeyVisualAttributes
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.keyboard.internal.KeyboardParams
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.common.Constants
import app.caret.latin.common.CoordinateUtils

import java.util.ArrayList
import java.util.Collections

/**
 * Loads an XML description of a keyboard and stores the attributes of the keys. A keyboard
 * consists of rows of keys.
 * <p>The layout file for a keyboard contains XML that looks like the following snippet:</p>
 * <pre>
 * &lt;Keyboard
 *         latin:keyWidth="10%p"
 *         latin:rowHeight="50px"
 *         latin:horizontalGap="2%p"
 *         latin:verticalGap="2%p" &gt;
 *     &lt;Row latin:keyWidth="10%p" &gt;
 *         &lt;Key latin:keyLabel="A" /&gt;
 *         ...
 *     &lt;/Row&gt;
 *     ...
 * &lt;/Keyboard&gt;
 * </pre>
 */
open class Keyboard {
    @JvmField
    val mId: KeyboardId
    @JvmField
    val mThemeId: Int

    /** Total height of the keyboard, including the padding and keys */
    @JvmField
    val mOccupiedHeight: Int
    /** Total width of the keyboard, including the padding and keys */
    @JvmField
    var mOccupiedWidth: Int

    /** Base height of the keyboard, used to calculate rows' height */
    @JvmField
    val mBaseHeight: Int
    /** Base width of the keyboard, used to calculate keys' width */
    @JvmField
    var mBaseWidth: Int

    /** The padding above the keyboard */
    @JvmField
    val mTopPadding: Int
    /** Default gap between rows */
    @JvmField
    val mVerticalGap: Int

    /** Per keyboard key visual parameters */
    @JvmField
    val mKeyVisualAttributes: KeyVisualAttributes?

    @JvmField
    val mMostCommonKeyHeight: Int
    @JvmField
    val mMostCommonKeyWidth: Int

    /** Popup keys keyboard template */
    @JvmField
    val mPopupKeysTemplate: Int

    /** Maximum column for popup keys keyboard */
    @JvmField
    val mMaxPopupKeysKeyboardColumn: Int

    /** List of keys in this keyboard */
    private val mSortedKeys: List<Key>
    @JvmField
    val mShiftKeys: List<Key>
    @JvmField
    val mAltCodeKeysWhileTyping: List<Key>
    @JvmField
    val mIconsSet: KeyboardIconsSet

    private val keyCache = SparseArray<Key?>()

    private val mProximityInfo: ProximityInfo

    private val proximityCharsCorrectionEnabled: Boolean

    constructor(params: KeyboardParams) {
        mId = params.mId
        mThemeId = params.mThemeId
        mOccupiedHeight = params.mOccupiedHeight
        mOccupiedWidth = params.mOccupiedWidth
        mBaseHeight = params.mBaseHeight
        mBaseWidth = params.mBaseWidth
        mMostCommonKeyHeight = params.mMostCommonKeyHeight
        mMostCommonKeyWidth = params.mMostCommonKeyWidth
        mPopupKeysTemplate = params.mPopupKeysTemplate
        mMaxPopupKeysKeyboardColumn = params.mMaxPopupKeysKeyboardColumn
        mKeyVisualAttributes = params.mKeyVisualAttributes
        mTopPadding = params.mTopPadding
        mVerticalGap = params.mVerticalGap

        mSortedKeys = Collections.unmodifiableList(ArrayList(params.mSortedKeys))
        mShiftKeys = Collections.unmodifiableList(params.mShiftKeys)
        mAltCodeKeysWhileTyping = Collections.unmodifiableList(params.mAltCodeKeysWhileTyping)
        mIconsSet = params.mIconsSet

        mProximityInfo = ProximityInfo(params.GRID_WIDTH, params.GRID_HEIGHT,
                mOccupiedWidth, mOccupiedHeight, mMostCommonKeyWidth, mMostCommonKeyHeight,
                mSortedKeys, params.mTouchPositionCorrection)
        proximityCharsCorrectionEnabled = params.mProximityCharsCorrectionEnabled
    }

    protected constructor(keyboard: Keyboard) {
        mId = keyboard.mId
        mThemeId = keyboard.mThemeId
        mOccupiedHeight = keyboard.mOccupiedHeight
        mOccupiedWidth = keyboard.mOccupiedWidth
        mBaseHeight = keyboard.mBaseHeight
        mBaseWidth = keyboard.mBaseWidth
        mMostCommonKeyHeight = keyboard.mMostCommonKeyHeight
        mMostCommonKeyWidth = keyboard.mMostCommonKeyWidth
        mPopupKeysTemplate = keyboard.mPopupKeysTemplate
        mMaxPopupKeysKeyboardColumn = keyboard.mMaxPopupKeysKeyboardColumn
        mKeyVisualAttributes = keyboard.mKeyVisualAttributes
        mTopPadding = keyboard.mTopPadding
        mVerticalGap = keyboard.mVerticalGap

        mSortedKeys = keyboard.mSortedKeys
        mShiftKeys = keyboard.mShiftKeys
        mAltCodeKeysWhileTyping = keyboard.mAltCodeKeysWhileTyping
        mIconsSet = keyboard.mIconsSet

        mProximityInfo = keyboard.mProximityInfo
        proximityCharsCorrectionEnabled = keyboard.proximityCharsCorrectionEnabled
    }

    fun hasProximityCharsCorrection(code: Int): Boolean {
        if (!proximityCharsCorrectionEnabled) {
            return false
        }
        // Note: The native code has the main keyboard layout only at this moment.
        // TODO: Figure out how to handle proximity characters information of all layouts.
        val canAssumeNativeHasProximityCharsInfoOfAllKeys = (
                mId.element == KeyboardElement.ALPHABET
                || mId.element == KeyboardElement.ALPHABET_AUTOMATIC_SHIFTED)
        return canAssumeNativeHasProximityCharsInfoOfAllKeys || Character.isLetter(code)
    }

    fun getProximityInfo(): ProximityInfo {
        return mProximityInfo
    }

    /**
     * Return the sorted list of keys of this keyboard.
     * The keys are sorted from top-left to bottom-right order.
     * The list may contain {@link Key.Spacer} object as well.
     * @return the sorted unmodifiable list of {@link Key}s of this keyboard.
     */
    open fun getSortedKeys(): List<Key> {
        return mSortedKeys
    }

    fun getKey(code: Int): Key? {
        if (code == KeyCode.NOT_SPECIFIED) {
            return null
        }
        synchronized(keyCache) {
            val index = keyCache.indexOfKey(code)
            if (index >= 0) {
                return keyCache.valueAt(index)
            }

            for (key in getSortedKeys()) {
                if (key.getCode() == code) {
                    keyCache.put(code, key)
                    return key
                }
            }
            keyCache.put(code, null)
            return null
        }
    }

    fun hasKey(aKey: Key): Boolean {
        if (keyCache.indexOfValue(aKey) >= 0) {
            return true
        }

        for (key in getSortedKeys()) {
            if (key === aKey) {
                keyCache.put(key.getCode(), key)
                return true
            }
        }
        return false
    }

    override fun toString(): String {
        return mId.toString()
    }

    /**
     * Returns the array of the keys that are closest to the given point.
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the list of the nearest keys to the given point. If the given
     * point is out of range, then an array of size zero is returned.
     */
    open fun getNearestKeys(x: Int, y: Int): List<Key> {
        // Avoid dead pixels at edges of the keyboard
        val adjustedX = Math.max(0, Math.min(x, mOccupiedWidth - 1))
        val adjustedY = Math.max(0, Math.min(y, mOccupiedHeight - 1))
        return mProximityInfo.getNearestKeys(adjustedX, adjustedY)
    }

    fun getCoordinates(codePoints: IntArray): IntArray {
        val length = codePoints.size
        val coordinates = CoordinateUtils.newCoordinateArray(length)
        for (i in 0 until length) {
            val key = getKey(codePoints[i])
            if (null != key) {
                CoordinateUtils.setXYInArray(coordinates, i,
                        key.getX() + key.getWidth() / 2, key.getY() + key.getHeight() / 2)
            } else {
                CoordinateUtils.setXYInArray(coordinates, i,
                        Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE)
            }
        }
        return coordinates
    }
}
