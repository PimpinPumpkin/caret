/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package app.caret.keyboard.internal

import android.content.Context
import android.content.res.Resources
import android.util.Xml
import androidx.annotation.XmlRes
import app.caret.keyboard.Key
import app.caret.keyboard.Key.KeyParams
import app.caret.keyboard.Keyboard
import app.caret.keyboard.KeyboardElement
import app.caret.keyboard.KeyboardId
import app.caret.keyboard.internal.keyboard_parser.EmojiParser
import app.caret.keyboard.internal.keyboard_parser.KeyboardParser
import app.caret.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.R
import app.caret.latin.common.Constants
import app.caret.latin.define.DebugFlags
import app.caret.latin.settings.Settings
import app.caret.latin.utils.Log
import app.caret.latin.utils.sumOf
import org.xmlpull.v1.XmlPullParser

open class KeyboardBuilder<KP : KeyboardParams>(protected val mContext: Context, @JvmField val mParams: KP) {
    @JvmField
    protected val resources: Resources
    private var mCurrentY = 0

    private var leftEdge = false
    private var topEdge = false
    private var mRightEdgeKey: Key? = null
    private lateinit var keysInRows: ArrayList<ArrayList<KeyParams>>

    init {
        val res = mContext.resources
        resources = res
        mParams.GRID_WIDTH = res.getInteger(R.integer.config_keyboard_grid_width)
        mParams.GRID_HEIGHT = res.getInteger(R.integer.config_keyboard_grid_height)
    }

    fun load(id: KeyboardId): KeyboardBuilder<KP> {
        mParams.id = id
        if (id.element.isEmojiLayout) {
            mParams.allowRedundantPopupKeys = true
            readAttributes(R.xml.kbd_emoji)
            keysInRows = EmojiParser(mParams, mContext).parse()
        } else {
            try {
                setupParams()
                keysInRows = KeyboardParser(mParams, mContext).parseLayout()
                if (keysInRows.size != 4) // that was effectively the default for OpenBoard
                    mParams.touchPositionCorrection.load(mContext.resources.getStringArray(R.array.touch_position_correction_data_default))
                determineAbsoluteValues()
            } catch (e: Exception) {
                Log.e(TAG, "error parsing layout $id ${id.element}", e)
                throw e
            }
        }
        return this
    }

    private fun setupParams() {
        val sv = Settings.getValues()
        mParams.allowRedundantPopupKeys = !sv.removeRedundantPopups
        mParams.proximityCharsCorrectionEnabled = mParams.id.element == KeyboardElement.ALPHABET
                || (mParams.id.element.isAlphabet && !mParams.id.subtype.hasExtraValue(Constants.Subtype.ExtraValue.NO_SHIFT_PROXIMITY_CORRECTION))

        LocaleKeyboardInfos.addLocaleKeyTextsToParams(mContext, mParams, sv.showMorePopupKeys)
        mParams.popupKeyOrder.addAll(sv.popupKeyOrder)
        // add label source only if popup key type enabled
        sv.popupKeyHintOrder.forEach { if (it in sv.popupKeyOrder) mParams.popupKeyHintOrder.add(it) }
    }

    // todo: remnant of old parser, replace it if reasonably simple
    protected fun readAttributes(@XmlRes xmlId: Int) {
        val parser = resources.getXml(xmlId)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val event = parser.next()
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name
                if ("Keyboard" == tag) {
                    mParams.readAttributes(mContext, Xml.asAttributeSet(parser))
                    return
                }
            }
        }
        mParams.readAttributes(mContext, null)
    }

    fun disableTouchPositionCorrectionDataForTest() {
        mParams.touchPositionCorrection.setEnabled(false)
    }

    fun setProximityCharsCorrectionEnabled(enabled: Boolean) {
        mParams.proximityCharsCorrectionEnabled = enabled
    }

    open fun build(): Keyboard {
        if (mParams.id.isSplitLayout
                && mParams.id.element in KeyboardElement.ALPHABET..KeyboardElement.SYMBOLS_SHIFTED) {
            addSplit()
        }
        addKeysToParams()
        return Keyboard(mParams)
    }

    // determine key size and positions using relative width and height
    private fun determineAbsoluteValues() {
        var currentY = mParams.topPadding.toFloat()
        for (row in keysInRows) {
            if (row.isEmpty()) continue
            var currentX = mParams.leftPadding.toFloat()
            row.forEach {
                it.setAbsoluteDimensions(currentX, currentY)
                if (DebugFlags.DEBUG_ENABLED)
                    Log.d(TAG, "setting size and position for ${it.mLabel ?: it.mIconName}, ${it.mCode}: x ${currentX.toInt()}, w ${it.absoluteWidth.toInt()}")
                currentX += it.absoluteWidth
            }
            currentY += row.first().absoluteHeight
        }
    }

    private fun addSplit() {
        val spacerRelativeWidth = Settings.getValues().splitKeyboardSpacerRelativeWidth
        // adjust gaps for the whole keyboard, so it's the same for all rows
        mParams.relativeHorizontalGap *= 1f / (1f + spacerRelativeWidth)
        mParams.horizontalGap = (mParams.relativeHorizontalGap * mParams.id.width).toInt()
        var maxWidthBeforeSpacer = 0f
        var maxWidthAfterSpacer = 0f
        for (row in keysInRows) {
            val y = row.first().yPos // all have the same y, so this is fine
            val relativeWidthSum = row.sumOf { it.mWidth } // sum up relative widths
            val spacer = KeyParams.newSpacer(mParams, spacerRelativeWidth)
            // insert spacer before first key that starts right of the center (also consider gap)
            var insertIndex = row.indexOfFirst { it.xPos + it.absoluteWidth / 3 > mParams.occupiedWidth / 2 }
                .takeIf { it > -1 } ?: (row.size / 2) // fallback should never be needed, but better than having an error
            val indexOfProperSpace = row.indexOfFirst { key ->
                // should work reasonably with customizable layouts, where space key might be completely different:
                // "normal" width space keys are ignored, and the possibility of space being first in row is considered
                key.mCode == Constants.CODE_SPACE && key.mWidth > row.first { !it.isSpacer && it.mCode != Constants.CODE_SPACE }.mWidth * 1.5f
            }
            if (indexOfProperSpace >= 0) {
                val spaceLeft = row[indexOfProperSpace]
                reduceSymbolAndActionKeyWidth(row)
                insertIndex = row.indexOf(spaceLeft) + 1
                val widthBeforeSpace = row.subList(0, insertIndex - 1).sumOf { it.mWidth }
                val widthAfterSpace = row.subList(insertIndex, row.size).sumOf { it.mWidth }
                val spaceLeftWidth = (maxWidthBeforeSpacer - widthBeforeSpace).coerceAtLeast(mParams.defaultKeyWidth)
                val spaceRightWidth = (maxWidthAfterSpacer - widthAfterSpace).coerceAtLeast(mParams.defaultKeyWidth)
                val spacerWidth = spaceLeft.mWidth + spacerRelativeWidth - spaceLeftWidth - spaceRightWidth
                if (spacerWidth > 0.05f) {
                    // only insert if the spacer has a reasonable width
                    val spaceRight = KeyParams(spaceLeft)
                    spaceLeft.mWidth = spaceLeftWidth
                    spaceRight.mWidth = spaceRightWidth
                    spacer.mWidth = spacerWidth
                    row.add(insertIndex, spaceRight)
                    row.add(insertIndex, spacer)
                } else {
                    // otherwise increase space width, so other keys are resized properly
                    spaceLeft.mWidth += spacerWidth
                }
            } else {
                val widthBeforeSpacer = row.subList(0, insertIndex).sumOf { it.mWidth }
                val widthAfterSpacer = row.subList(insertIndex, row.size).sumOf { it.mWidth }
                maxWidthBeforeSpacer = maxWidthBeforeSpacer.coerceAtLeast(widthBeforeSpacer)
                maxWidthAfterSpacer = maxWidthAfterSpacer.coerceAtLeast(widthAfterSpacer)
                row.add(insertIndex, spacer)
            }
            // re-calculate relative widths
            val relativeWidthSumNew = row.sumOf { it.mWidth }
            val widthFactor = relativeWidthSum / relativeWidthSumNew
            // re-calculate absolute sizes and positions
            var currentX = mParams.leftPadding.toFloat()
            row.forEach {
                it.mWidth *= widthFactor
                it.setAbsoluteDimensions(currentX, y)
                currentX += it.absoluteWidth
            }
        }
    }

    // reduce width of symbol and action key if in the (to be split) row, and add this width to space to keep other key size constant
    private fun reduceSymbolAndActionKeyWidth(row: ArrayList<KeyParams>) {
        val spaceKey = row.first { it.mCode == Constants.CODE_SPACE }
        if (spaceKey.mWidth >= 0.5) return // only if space key is small
        val symbolKey = row.firstOrNull { it.mCode == KeyCode.SYMBOL_ALPHA }
        val symbolKeyWidth = symbolKey?.mWidth ?: 0f
        if (symbolKeyWidth > mParams.defaultKeyWidth) {
            val widthToChange = symbolKey!!.mWidth - mParams.defaultKeyWidth
            symbolKey.mWidth -= widthToChange
            spaceKey.mWidth += widthToChange
        }
        val actionKey = row.firstOrNull { it.mBackgroundType == Key.BACKGROUND_TYPE_ACTION }
        val actionKeyWidth = actionKey?.mWidth ?: 0f
        if (actionKeyWidth > mParams.defaultKeyWidth * 1.1f) { // allow it to stay a little wider
            val widthToChange = actionKey!!.mWidth - mParams.defaultKeyWidth * 1.1f
            actionKey.mWidth -= widthToChange
            spaceKey.mWidth += widthToChange
        }
    }

    private fun startKeyboard() {
        mCurrentY += mParams.topPadding
        topEdge = true
    }

    private fun startRow() {
        leftEdge = true
        mRightEdgeKey = null
    }

    private fun endRow() {
        val rightEdgeKey = mRightEdgeKey
        if (rightEdgeKey != null) {
            rightEdgeKey.markAsRightEdge(mParams)
            mCurrentY += rightEdgeKey.getHeight() + rightEdgeKey.getVerticalGap()
            mRightEdgeKey = null
        }
        leftEdge = false
        topEdge = false
    }

    private fun endKey(key: Key) {
        mParams.onAddKey(key)
        if (leftEdge && !key.isSpacer()) {
            key.markAsLeftEdge(mParams)
            leftEdge = false
        }
        if (topEdge) {
            key.markAsTopEdge(mParams)
        }
        if (!key.isSpacer()) {
            mRightEdgeKey = key
        }
    }

    private fun endKeyboard() {
        mParams.removeRedundantPopupKeys()
        if (!mParams.id.element.isEmojiLayout) return
        val actualHeight = mCurrentY - mParams.verticalGap + mParams.bottomPadding
        mParams.occupiedHeight = mParams.occupiedHeight.coerceAtLeast(actualHeight)
    }

    private fun addKeysToParams() {
        // need to reset it, we need to sum it up to get the height nicely
        // (though in the end we could just not touch it at all, final used value is the same as the one before resetting)
        mCurrentY = 0
        startKeyboard()
        for (row in keysInRows) {
            startRow()
            for (keyParams in row) {
                endKey(keyParams.createKey())
            }
            endRow()
        }
        endKeyboard()
    }

    companion object {
        private const val TAG = "Keyboard.Builder"
    }
}
