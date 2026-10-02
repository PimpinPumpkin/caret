/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.content.Context
import android.graphics.Paint
import app.caret.keyboard.internal.KeyboardBuilder
import app.caret.keyboard.internal.KeyboardParams
import app.caret.latin.R
import app.caret.latin.common.StringUtils
import app.caret.latin.utils.TypefaceUtils

class PopupKeysKeyboard(params: PopupKeysKeyboardParams) : Keyboard(params) {
    private val defaultKeyCoordX: Int

    init {
        defaultKeyCoordX = params.getDefaultKeyCoordX() + params.mAbsolutePopupKeyWidth / 2
    }

    fun getDefaultCoordX(): Int {
        return defaultKeyCoordX
    }

    class PopupKeysKeyboardParams : KeyboardParams() {
        @JvmField
        var mIsPopupKeysFixedOrder: Boolean = false
        /* package */@JvmField
        var mTopRowAdjustment: Int = 0
        @JvmField
        var mNumRows: Int = 0
        @JvmField
        var mNumColumns: Int = 0
        @JvmField
        var mTopKeys: Int = 0
        @JvmField
        var mLeftKeys: Int = 0
        @JvmField
        var mRightKeys: Int = 0 // includes default key.
        @JvmField
        var mDividerWidth: Int = 0
        @JvmField
        var mColumnWidth: Int = 0

        /**
         * Set keyboard parameters of popup keys keyboard.
         *
         * @param numKeys number of keys in this popup keys keyboard.
         * @param numColumn number of columns of this popup keys keyboard.
         * @param keyWidth popup keys keyboard key width in pixel, including horizontal gap.
         * @param rowHeight popup keys keyboard row height in pixel, including vertical gap.
         * @param coordXInParent coordinate x of the key preview in parent keyboard.
         * @param parentKeyboardWidth parent keyboard width in pixel.
         * @param isPopupKeysFixedColumn true if popup keys keyboard should have
         *   <code>numColumn</code> columns. Otherwise popup keys keyboard should have
         *   <code>numColumn</code> columns at most.
         * @param isPopupKeysFixedOrder true if the order of popup keys is determined by the order in
         *   the popup keys' specification. Otherwise the order of popup keys is automatically
         *   determined.
         * @param dividerWidth width of divider, zero for no dividers.
         */
        fun setParameters(numKeys: Int, numColumn: Int, keyWidth: Int,
                rowHeight: Int, coordXInParent: Int, parentKeyboardWidth: Int,
                isPopupKeysFixedColumn: Boolean, isPopupKeysFixedOrder: Boolean,
                dividerWidth: Int) {
            mIsPopupKeysFixedOrder = isPopupKeysFixedOrder
            if (parentKeyboardWidth / keyWidth < Math.min(numKeys, numColumn)) {
                throw IllegalArgumentException("Keyboard is too small to hold popup keys: "
                        + parentKeyboardWidth + " " + keyWidth + " " + numKeys + " " + numColumn)
            }
            mDefaultAbsoluteKeyWidth = keyWidth
            mDefaultAbsoluteRowHeight = rowHeight

            mNumRows = (numKeys + numColumn - 1) / numColumn
            val numColumns = if (isPopupKeysFixedColumn) Math.min(numKeys, numColumn)
                    else getOptimizedColumns(numKeys, numColumn)
            mNumColumns = numColumns
            val topKeys = numKeys % numColumns
            mTopKeys = if (topKeys == 0) numColumns else topKeys

            val numLeftKeys = (numColumns - 1) / 2
            val numRightKeys = numColumns - numLeftKeys // including default key.
            // Maximum number of keys we can layout both side of the parent key
            val maxLeftKeys = coordXInParent / keyWidth
            val maxRightKeys = (parentKeyboardWidth - coordXInParent) / keyWidth
            var leftKeys: Int
            var rightKeys: Int
            if (numLeftKeys > maxLeftKeys) {
                leftKeys = maxLeftKeys
                rightKeys = numColumns - leftKeys
            } else if (numRightKeys > maxRightKeys + 1) {
                rightKeys = maxRightKeys + 1 // include default key
                leftKeys = numColumns - rightKeys
            } else {
                leftKeys = numLeftKeys
                rightKeys = numRightKeys
            }
            // If the left keys fill the left side of the parent key, entire popup keys keyboard
            // should be shifted to the right unless the parent key is on the left edge.
            if (maxLeftKeys == leftKeys && leftKeys > 0) {
                leftKeys--
                rightKeys++
            }
            // If the right keys fill the right side of the parent key, entire popup keys
            // should be shifted to the left unless the parent key is on the right edge.
            if (maxRightKeys == rightKeys - 1 && rightKeys > 1) {
                leftKeys++
                rightKeys--
            }
            mLeftKeys = leftKeys
            mRightKeys = rightKeys

            // Adjustment of the top row.
            mTopRowAdjustment = if (isPopupKeysFixedOrder) getFixedOrderTopRowAdjustment()
                    else getAutoOrderTopRowAdjustment()
            mDividerWidth = dividerWidth
            mColumnWidth = mDefaultAbsoluteKeyWidth + mDividerWidth
            mOccupiedWidth = mNumColumns * mColumnWidth - mDividerWidth
            mBaseWidth = mOccupiedWidth
            // Need to subtract the bottom row's gutter only.
            mOccupiedHeight = mNumRows * mDefaultAbsoluteRowHeight - mVerticalGap +
                    mTopPadding + mBottomPadding
            mBaseHeight = mOccupiedHeight
        }

        private fun getFixedOrderTopRowAdjustment(): Int {
            if (mNumRows == 1 || mTopKeys % 2 == 1 || mTopKeys == mNumColumns
                    || mLeftKeys == 0  || mRightKeys == 1) {
                return 0
            }
            return -1
        }

        private fun getAutoOrderTopRowAdjustment(): Int {
            if (mNumRows == 1 || mTopKeys == 1 || mNumColumns % 2 == mTopKeys % 2
                    || mLeftKeys == 0 || mRightKeys == 1) {
                return 0
            }
            return -1
        }

        // Return key position according to column count (0 is default).
        /* package */fun getColumnPos(n: Int): Int {
            return if (mIsPopupKeysFixedOrder) getFixedOrderColumnPos(n) else getAutomaticColumnPos(n)
        }

        private fun getFixedOrderColumnPos(n: Int): Int {
            val col = n % mNumColumns
            val row = n / mNumColumns
            if (!isTopRow(row)) {
                return col - mLeftKeys
            }
            val rightSideKeys = mTopKeys / 2
            val leftSideKeys = mTopKeys - (rightSideKeys + 1)
            val pos = col - leftSideKeys
            val numLeftKeys = mLeftKeys + mTopRowAdjustment
            val numRightKeys = mRightKeys - 1
            if (numRightKeys >= rightSideKeys && numLeftKeys >= leftSideKeys) {
                return pos
            } else if (numRightKeys < rightSideKeys) {
                return pos - (rightSideKeys - numRightKeys)
            } else { // numLeftKeys < leftSideKeys
                return pos + (leftSideKeys - numLeftKeys)
            }
        }

        private fun getAutomaticColumnPos(n: Int): Int {
            val col = n % mNumColumns
            val row = n / mNumColumns
            var leftKeys = mLeftKeys
            if (isTopRow(row)) {
                leftKeys += mTopRowAdjustment
            }
            if (col == 0) {
                // default position.
                return 0
            }

            var pos = 0
            var right = 1 // include default position key.
            var left = 0
            var i = 0
            while (true) {
                // Assign right key if available.
                if (right < mRightKeys) {
                    pos = right
                    right++
                    i++
                }
                if (i >= col)
                    break
                // Assign left key if available.
                if (left < leftKeys) {
                    left++
                    pos = -left
                    i++
                }
                if (i >= col)
                    break
            }
            return pos
        }

        private fun getOptimizedColumns(numKeys: Int, maxColumns: Int): Int {
            var numColumns = Math.min(numKeys, maxColumns)
            while (getTopRowEmptySlots(numKeys, numColumns) >= mNumRows) {
                numColumns--
            }
            return numColumns
        }

        fun getDefaultKeyCoordX(): Int {
            return mLeftKeys * mColumnWidth + mLeftPadding
        }

        fun getX(n: Int, row: Int): Int {
            val x = getColumnPos(n) * mColumnWidth + getDefaultKeyCoordX()
            if (isTopRow(row)) {
                return x + mTopRowAdjustment * (mColumnWidth / 2)
            }
            return x
        }

        fun getY(row: Int): Int {
            return (mNumRows - 1 - row) * mDefaultAbsoluteRowHeight + mTopPadding
        }

        fun markAsEdgeKey(key: Key, row: Int) {
            if (row == 0)
                key.markAsTopEdge(this)
            if (isTopRow(row))
                key.markAsBottomEdge(this)
        }

        private fun isTopRow(rowCount: Int): Boolean {
            return mNumRows > 1 && rowCount == mNumRows - 1
        }

        companion object {
            private fun getTopRowEmptySlots(numKeys: Int, numColumns: Int): Int {
                val remainings = numKeys % numColumns
                return if (remainings == 0) 0 else numColumns - remainings
            }
        }
    }

    class Builder
    /**
     * The builder of PopupKeysKeyboard.
     * @param context the context of {@link PopupKeysKeyboardView}.
     * @param key the {@link Key} that invokes popup keys keyboard.
     * @param keyboard the {@link Keyboard} that contains the parentKey.
     * @param isSinglePopupKeyWithPreview true if the <code>key</code> has just a single
     *        "popup key" and its key popup preview is enabled.
     * @param keyPreviewVisibleWidth the width of visible part of key popup preview.
     * @param keyPreviewVisibleHeight the height of visible part of key popup preview
     * @param paintToMeasure the {@link Paint} object to measure a "popup key" width
     */
    constructor(context: Context, key: Key, keyboard: Keyboard?,
            isSinglePopupKeyWithPreview: Boolean, keyPreviewVisibleWidth: Int,
            keyPreviewVisibleHeight: Int, paintToMeasure: Paint
    ) : KeyboardBuilder<PopupKeysKeyboardParams>(context, PopupKeysKeyboardParams()) {
        private val mParentKey: Key

        init {
            mParams.mId = keyboard!!.mId
            readAttributes(keyboard.mPopupKeysTemplate)

            // TODO: Popup keys keyboard's vertical gap is currently calculated heuristically.
            // Should revise the algorithm.
            mParams.mVerticalGap = keyboard.mVerticalGap / 2
            // This {@link PopupKeysKeyboard} is invoked from the <code>key</code>.
            mParentKey = key

            val keyWidth: Int
            val rowHeight: Int
            if (isSinglePopupKeyWithPreview) {
                // Use pre-computed width and height if this popup keys keyboard has only one key to
                // mitigate visual flicker between key preview and popup keys keyboard.
                // Caveats for the visual assets: To achieve this effect, both the key preview
                // backgrounds and the popup keys keyboard panel background have the exact same
                // left/right/top paddings. The bottom paddings of both backgrounds don't need to
                // be considered because the vertical positions of both backgrounds were already
                // adjusted with their bottom paddings deducted.
                keyWidth = keyPreviewVisibleWidth
                rowHeight = keyPreviewVisibleHeight + mParams.mVerticalGap
            } else {
                val padding = context.resources.getDimension(
                        R.dimen.config_popup_keys_keyboard_key_horizontal_padding) +
                        (if (key.hasLabelsInPopupKeys())
                                mParams.mAbsolutePopupKeyWidth * LABEL_PADDING_RATIO else 0.0f)
                keyWidth = getMaxKeyWidth(key, mParams.mAbsolutePopupKeyWidth, padding, paintToMeasure)
                rowHeight = keyboard.mMostCommonKeyHeight
            }
            val dividerWidth: Int
            if (key.needsDividersInPopupKeys()) {
                dividerWidth = (keyWidth * DIVIDER_RATIO).toInt()
            } else {
                dividerWidth = 0
            }
            val popupKeys = key.getPopupKeys()
            val defaultColumns = key.getPopupKeysColumnNumber()
            val spaceForKeys = keyboard.mId.width / keyWidth
            var finalNumColumns = if (spaceForKeys >= Math.min(popupKeys!!.size, defaultColumns))
                    defaultColumns
                    else (if (spaceForKeys > 0) spaceForKeys else defaultColumns)
            if (finalNumColumns < 1) finalNumColumns = 1 // avoid exception in setParameters
            mParams.setParameters(popupKeys.size, finalNumColumns, keyWidth,
                    rowHeight, key.getX() + key.getWidth() / 2, keyboard.mId.width,
                    key.isPopupKeysFixedColumn(), key.isPopupKeysFixedOrder(), dividerWidth)
        }

        override fun build(): PopupKeysKeyboard {
            val params = mParams
            val popupKeyFlags = mParentKey.getPopupKeyLabelFlags()
            val popupKeys = mParentKey.getPopupKeys()
            val background = if (mParentKey.hasActionKeyPopups()) Key.BACKGROUND_TYPE_ACTION else Key.BACKGROUND_TYPE_NORMAL
            for (n in 0 until popupKeys!!.size) {
                val popupKeySpec = popupKeys[n]
                val row = n / params.mNumColumns
                val x = params.getX(n, row)
                val y = params.getY(row)
                val key = popupKeySpec.buildKey(x, y, popupKeyFlags, background, params)
                params.markAsEdgeKey(key, row)
                params.onAddKey(key)

                val pos = params.getColumnPos(n)
                // The "pos" value represents the offset from the default position. Negative means
                // left of the default position.
                if (params.mDividerWidth > 0 && pos != 0) {
                    val dividerX = if (pos > 0) x - params.mDividerWidth
                            else x + params.mAbsolutePopupKeyWidth
                    val divider: Key = PopupKeyDivider(
                            params, dividerX, y, params.mDividerWidth, params.mDefaultAbsoluteRowHeight)
                    params.onAddKey(divider)
                }
            }
            return PopupKeysKeyboard(params)
        }

        companion object {
            private const val LABEL_PADDING_RATIO = 0.2f
            private const val DIVIDER_RATIO = 0.2f

            private fun getMaxKeyWidth(parentKey: Key, minKeyWidth: Int,
                    padding: Float, paint: Paint): Int {
                var maxWidth = minKeyWidth
                for (spec in parentKey.getPopupKeys()!!) {
                    val label = spec.mLabel
                    // If the label is single letter, minKeyWidth is enough to hold the label.
                    if (label != null && StringUtils.codePointCount(label) > 1) {
                        maxWidth = Math.max(maxWidth,
                                (TypefaceUtils.getStringWidth(label, paint) + padding).toInt())
                    }
                }
                return maxWidth
            }
        }
    }

    // Used as a divider maker. A divider is drawn by {@link PopupKeysKeyboardView}.
    class PopupKeyDivider(params: KeyboardParams, x: Int, y: Int,
            width: Int, height: Int) : Key.Spacer(params, x, y, width, height)
}
