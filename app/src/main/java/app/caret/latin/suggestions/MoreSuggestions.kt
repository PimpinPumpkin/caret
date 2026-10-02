/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.suggestions

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import app.caret.keyboard.Key
import app.caret.keyboard.Keyboard
import app.caret.keyboard.internal.KeyboardBuilder
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.keyboard.internal.KeyboardParams
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.R
import app.caret.latin.SuggestedWords
import app.caret.latin.utils.TypefaceUtils

class MoreSuggestions(params: MoreSuggestionsParam, suggestedWords: SuggestedWords) : Keyboard(params) {
    @JvmField
    val mSuggestedWords: SuggestedWords

    init {
        mSuggestedWords = suggestedWords
    }

    // not private as in Java: it is the type argument of the public Builder's supertype
    class MoreSuggestionsParam : KeyboardParams() {
        private val widths = IntArray(SuggestedWords.MAX_SUGGESTIONS)
        private val rowNumbers = IntArray(SuggestedWords.MAX_SUGGESTIONS)
        private val columnOrders = IntArray(SuggestedWords.MAX_SUGGESTIONS)
        private val numColumnsInRow = IntArray(SuggestedWords.MAX_SUGGESTIONS)
        private var numRows = 0
        var mDivider: Drawable? = null
        var dividerWidth = 0

        fun layout(suggestedWords: SuggestedWords, fromIndex: Int,
                maxWidth: Int, minWidth: Int, maxRow: Int, paint: Paint,
                context: Context): Int {
            clearKeys()
            mDivider = ContextCompat.getDrawable(context, R.drawable.more_suggestions_divider)
            dividerWidth = if (mDivider == null) 0 else mDivider!!.intrinsicWidth
            val padding = context.resources.getDimension(R.dimen.config_more_suggestions_key_horizontal_padding)

            var row = 0
            var index = fromIndex
            var rowStartIndex = fromIndex
            val size = Math.min(suggestedWords.size(), SuggestedWords.MAX_SUGGESTIONS)
            while (index < size) {
                val word = if (isIndexSubjectToAutoCorrection(suggestedWords, index)) {
                    // INDEX_OF_AUTO_CORRECTION and INDEX_OF_TYPED_WORD got swapped.
                    suggestedWords.getLabel(SuggestedWords.INDEX_OF_TYPED_WORD)
                } else {
                    suggestedWords.getLabel(index)
                }
                // TODO: Should take care of text x-scaling.
                widths[index] = (TypefaceUtils.getStringWidth(word, paint) + padding).toInt()
                val numColumn = index - rowStartIndex + 1
                val columnWidth =
                        (maxWidth - dividerWidth * (numColumn - 1)) / numColumn
                if (numColumn > MAX_COLUMNS_IN_ROW
                        || !fitInWidth(rowStartIndex, index + 1, columnWidth)) {
                    if ((row + 1) >= maxRow) {
                        break
                    }
                    numColumnsInRow[row] = index - rowStartIndex
                    rowStartIndex = index
                    row++
                }
                columnOrders[index] = index - rowStartIndex
                rowNumbers[index] = row
                index++
            }
            numColumnsInRow[row] = index - rowStartIndex
            numRows = row + 1
            this.occupiedWidth = Math.max(
                    minWidth, calcurateMaxRowWidth(fromIndex, index))
            this.baseWidth = this.occupiedWidth
            this.occupiedHeight = numRows * this.defaultAbsoluteRowHeight + this.verticalGap
            this.baseHeight = this.occupiedHeight
            return index - fromIndex
        }

        private fun fitInWidth(startIndex: Int, endIndex: Int, width: Int): Boolean {
            for (index in startIndex until endIndex) {
                if (widths[index] > width)
                    return false
            }
            return true
        }

        private fun calcurateMaxRowWidth(startIndex: Int, endIndex: Int): Int {
            var maxRowWidth = 0
            var index = startIndex
            for (row in 0 until numRows) {
                val numColumnInRow = numColumnsInRow[row]
                var maxKeyWidth = 0
                while (index < endIndex && rowNumbers[index] == row) {
                    maxKeyWidth = Math.max(maxKeyWidth, widths[index])
                    index++
                }
                maxRowWidth = Math.max(maxRowWidth,
                        maxKeyWidth * numColumnInRow + dividerWidth * (numColumnInRow - 1))
            }
            return maxRowWidth
        }

        fun getNumColumnInRow(index: Int): Int {
            return numColumnsInRow[rowNumbers[index]]
        }

        fun getColumnNumber(index: Int): Int {
            val columnOrder = columnOrders[index]
            val numColumn = getNumColumnInRow(index)
            return COLUMN_ORDER_TO_NUMBER[numColumn - 1][columnOrder]
        }

        fun getX(index: Int): Int {
            val columnNumber = getColumnNumber(index)
            return columnNumber * (getWidth(index) + dividerWidth)
        }

        fun getY(index: Int): Int {
            val row = rowNumbers[index]
            return (numRows - 1 - row) * this.defaultAbsoluteRowHeight + this.topPadding
        }

        fun getWidth(index: Int): Int {
            val numColumnInRow = getNumColumnInRow(index)
            return (this.occupiedWidth - dividerWidth * (numColumnInRow - 1)) / numColumnInRow
        }

        fun markAsEdgeKey(key: Key, index: Int) {
            val row = rowNumbers[index]
            if (row == 0)
                key.markAsBottomEdge(this)
            if (row == numRows - 1)
                key.markAsTopEdge(this)

            val numColumnInRow = numColumnsInRow[row]
            val column = getColumnNumber(index)
            if (column == 0)
                key.markAsLeftEdge(this)
            if (column == numColumnInRow - 1)
                key.markAsRightEdge(this)
        }

        companion object {
            private const val MAX_COLUMNS_IN_ROW = 3

            private val COLUMN_ORDER_TO_NUMBER = arrayOf(
                intArrayOf(0), // center
                intArrayOf(1, 0), // right-left
                intArrayOf(1, 0, 2), // center-left-right
            )
        }
    }

    class Builder(context: Context, paneView: MoreSuggestionsView) :
            KeyboardBuilder<MoreSuggestionsParam>(context, MoreSuggestionsParam()) {
        private val mPaneView: MoreSuggestionsView
        private lateinit var mSuggestedWords: SuggestedWords
        private var mFromIndex = 0
        private var toIndex = 0

        init {
            mPaneView = paneView
        }

        fun layout(suggestedWords: SuggestedWords, fromIndex: Int,
                maxWidth: Int, minWidth: Int, maxRow: Int,
                parentKeyboard: Keyboard): Builder {
            val xmlId = R.xml.kbd_suggestions_pane_template
            mParams.id = parentKeyboard.id
            readAttributes(xmlId)
            mParams.topPadding = parentKeyboard.verticalGap / 2
            mParams.verticalGap = mParams.topPadding
            mPaneView.updateKeyboardGeometry(mParams.defaultAbsoluteRowHeight)
            val count = mParams.layout(suggestedWords, fromIndex, maxWidth, minWidth, maxRow,
                    mPaneView.newLabelPaint(null /* key */), this.context)
            mFromIndex = fromIndex
            toIndex = fromIndex + count
            mSuggestedWords = suggestedWords
            return this
        }

        override fun build(): MoreSuggestions {
            val params = mParams
            for (index in mFromIndex until toIndex) {
                val x = params.getX(index)
                val y = params.getY(index)
                val width = params.getWidth(index)
                val word: String?
                val info: String?
                if (isIndexSubjectToAutoCorrection(mSuggestedWords, index)) {
                    // INDEX_OF_AUTO_CORRECTION and INDEX_OF_TYPED_WORD got swapped.
                    word = mSuggestedWords.getLabel(SuggestedWords.INDEX_OF_TYPED_WORD)
                    info = mSuggestedWords.getDebugString(SuggestedWords.INDEX_OF_TYPED_WORD)
                } else {
                    word = mSuggestedWords.getLabel(index)
                    info = mSuggestedWords.getDebugString(index)
                }
                val key: Key = MoreSuggestionKey(word, info, index, params)
                params.markAsEdgeKey(key, index)
                params.onAddKey(key)
                val columnNumber = params.getColumnNumber(index)
                val numColumnInRow = params.getNumColumnInRow(index)
                if (columnNumber < numColumnInRow - 1) {
                    val divider = Divider(params, params.mDivider, x + width, y,
                            params.dividerWidth, params.defaultAbsoluteRowHeight)
                    params.onAddKey(divider)
                }
            }
            return MoreSuggestions(params, mSuggestedWords)
        }
    }

    class MoreSuggestionKey(word: String?, info: String?, index: Int, params: MoreSuggestionsParam) :
            Key(word, null, KeyCode.MULTIPLE_CODE_POINTS,
                    word, info, 0, Key.BACKGROUND_TYPE_NORMAL,
                    params.getX(index), params.getY(index), params.getWidth(index),
                    params.defaultAbsoluteRowHeight, params.horizontalGap, params.verticalGap) {
        @JvmField
        val suggestedWordIndex: Int

        init {
            suggestedWordIndex = index
        }
    }

    private class Divider(params: KeyboardParams, icon: Drawable?, x: Int,
            y: Int, width: Int, height: Int) : Key.Spacer(params, x, y, width, height) {
        private val mIcon: Drawable?

        init {
            mIcon = icon
        }

        override fun getIcon(iconSet: KeyboardIconsSet, alpha: Int): Drawable? {
            // KeyboardIconsSet and alpha are unused. Use the icon that has been passed to the
            // constructor.
            // TODO: Drawable itself should have an alpha value.
            mIcon!!.setAlpha(128)
            return mIcon
        }
    }

    companion object {
        @JvmStatic
        fun isIndexSubjectToAutoCorrection(suggestedWords: SuggestedWords,
                index: Int): Boolean {
            return suggestedWords.willAutoCorrect && index == SuggestedWords.INDEX_OF_AUTO_CORRECTION
        }
    }
}
