/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.Context
import android.util.AttributeSet
import android.util.SparseIntArray

import app.caret.keyboard.Key
import app.caret.keyboard.KeyboardId
import app.caret.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.R
import app.caret.latin.settings.Settings
import app.caret.latin.utils.ResourceUtils

import java.util.ArrayList
import java.util.Comparator
import java.util.Locale
import java.util.SortedSet
import java.util.TreeSet

open class KeyboardParams {
    lateinit var id: KeyboardId
    @JvmField
    var themeId = 0

    /** Total height and width of the keyboard, including the paddings and keys */
    @JvmField
    var occupiedHeight = 0
    @JvmField
    var occupiedWidth = 0

    /** Base height and width of the keyboard used to calculate rows' or keys' heights and
     *  widths
     */
    @JvmField
    var baseHeight = 0
    @JvmField
    var baseWidth = 0

    @JvmField
    var topPadding = 0
    @JvmField
    var bottomPadding = 0
    @JvmField
    var leftPadding = 0
    @JvmField
    var rightPadding = 0

    @JvmField
    var keyVisualAttributes: KeyVisualAttributes? = null

    @JvmField
    var defaultRowHeight = 0f
    @JvmField
    var defaultKeyWidth = 0f
    @JvmField
    var relativeHorizontalGap = 0f
    @JvmField
    var relativeVerticalGap = 0f
    // relative values multiplied with baseHeight / baseWidth
    @JvmField
    var defaultAbsoluteRowHeight = 0
    @JvmField
    var defaultAbsoluteKeyWidth = 0
    @JvmField
    var horizontalGap = 0
    @JvmField
    var verticalGap = 0

    @JvmField
    var popupKeysTemplate = 0
    @JvmField
    var maxPopupKeysKeyboardColumn = 0
    // popup key width is separate from defaultAbsoluteKeyWidth because it should not depend on alpha or number layout
    @JvmField
    var absolutePopupKeyWidth = 0

    @JvmField
    var GRID_WIDTH = 0
    @JvmField
    var GRID_HEIGHT = 0

    // Keys are sorted from top-left to bottom-right order.
    @JvmField
    val sortedKeys: SortedSet<Key> = TreeSet(ROW_COLUMN_COMPARATOR)
    @JvmField
    val shiftKeys = ArrayList<Key>()
    @JvmField
    val altCodeKeysWhileTyping = ArrayList<Key>()
    @JvmField
    val iconsSet = KeyboardIconsSet.instance
    // todo: not good, this only works because params are currently always created for the active subtype
    @JvmField
    val secondaryLocales: List<Locale> = Settings.values.secondaryLocales
    @JvmField
    val popupKeyOrder = ArrayList<String>()
    @JvmField
    val popupKeyHintOrder = ArrayList<String>()

    private val uniqueKeysCache: UniqueKeysCache
    @JvmField
    var allowRedundantPopupKeys = false
    lateinit var localeKeyboardInfos: LocaleKeyboardInfos
    @JvmField
    var setTabletExtraKeys = false

    @JvmField
    var mostCommonKeyHeight = 0
    @JvmField
    var mostCommonKeyWidth = 0

    // should be enabled for all alphabet layouts, except for specific layouts when shifted
    @JvmField
    var proximityCharsCorrectionEnabled = false

    // only for removing redundant popup keys
    @JvmField
    var baseKeys: List<Key.KeyParams>? = null

    @JvmField
    val touchPositionCorrection = TouchPositionCorrection()

    constructor() : this(UniqueKeysCache.NO_CACHE)

    constructor(keysCache: UniqueKeysCache) {
        uniqueKeysCache = keysCache
    }

    protected fun clearKeys() {
        sortedKeys.clear()
        shiftKeys.clear()
        clearHistogram()
    }

    fun onAddKey(newKey: Key) {
        val key = uniqueKeysCache.getUniqueKey(newKey)
        val isSpacer = key.isSpacer
        if (isSpacer && key.width == 0) {
            // Ignore zero width {@link Spacer}.
            return
        }
        sortedKeys.add(key)
        if (isSpacer) {
            return
        }
        updateHistogram(key)
        if (key.code == KeyCode.SHIFT) {
            shiftKeys.add(key)
        }
        if (key.altCodeWhileTyping()) {
            altCodeKeysWhileTyping.add(key)
        }
    }

    fun removeRedundantPopupKeys() {
        val keys = baseKeys
        if (allowRedundantPopupKeys || keys == null) {
            return
        }
        val lettersOnBaseLayout =
                PopupKeySpec.LettersOnBaseLayout()
        for (key in keys) {
            lettersOnBaseLayout.addLetter(key)
        }
        val allKeys = ArrayList<Key>(sortedKeys)
        sortedKeys.clear()
        for (key in allKeys) {
            val filteredKey = Key.removeRedundantPopupKeys(key, lettersOnBaseLayout)
            sortedKeys.add(uniqueKeysCache.getUniqueKey(filteredKey))
        }
        baseKeys = null
    }

    private var maxHeightCount = 0
    private var maxWidthCount = 0
    private val heightHistogram = SparseIntArray()
    private val widthHistogram = SparseIntArray()

    private fun clearHistogram() {
        mostCommonKeyHeight = 0
        maxHeightCount = 0
        heightHistogram.clear()

        maxWidthCount = 0
        mostCommonKeyWidth = 0
        widthHistogram.clear()
    }

    private fun updateHistogram(key: Key) {
        val height = key.height + verticalGap
        val heightCount = updateHistogramCounter(heightHistogram, height)
        if (heightCount > maxHeightCount) {
            maxHeightCount = heightCount
            mostCommonKeyHeight = height
        }

        val width = key.width + horizontalGap
        val widthCount = updateHistogramCounter(widthHistogram, width)
        if (widthCount > maxWidthCount) {
            maxWidthCount = widthCount
            mostCommonKeyWidth = width
        }
    }

    // when attr is null, default attributes will be loaded
    //  these are good for basic keyboards already, but have wrong/unsuitable sizes e.g. for emojis,
    //  popupKeys and moreSuggestions
    fun readAttributes(context: Context, attr: AttributeSet?) {
        val keyboardAttr = context.obtainStyledAttributes(
                attr, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
        val keyAttr = if (attr == null)
            context.obtainStyledAttributes(attr, R.styleable.Keyboard_Key)
            else context.getResources().obtainAttributes(attr, R.styleable.Keyboard_Key)
        val sv = Settings.values
        try {
            val height = id.height
            val width = id.width
            occupiedHeight = height
            occupiedWidth = width
            topPadding = keyboardAttr.getFraction(
                    R.styleable.Keyboard_keyboardTopPadding, height, height, 0f).toInt()
            bottomPadding = (keyboardAttr.getFraction(
                    R.styleable.Keyboard_keyboardBottomPadding, height, height, 0f)
                    * sv.bottomPaddingScale).toInt()
            leftPadding = (keyboardAttr.getFraction(
                    R.styleable.Keyboard_keyboardLeftPadding, width, width, 0f)
                    * sv.sidePaddingScale).toInt()
            rightPadding = (keyboardAttr.getFraction(
                    R.styleable.Keyboard_keyboardRightPadding, width, width, 0f)
                    * sv.sidePaddingScale).toInt()

            baseWidth = occupiedWidth - leftPadding - rightPadding
            val defaultKeyWidthFactor = if (context.getResources().getInteger(R.integer.config_screen_metrics) > 2) 0.9f else 1f
            val alphaSymbolKeyWidth = keyAttr.getFraction(R.styleable.Keyboard_Key_keyWidth,
                    1, 1, defaultKeyWidthFactor / DEFAULT_KEYBOARD_COLUMNS)
            defaultKeyWidth = if (id.element.isNumberLayout) 0.17f else alphaSymbolKeyWidth
            defaultAbsoluteKeyWidth = (defaultKeyWidth * baseWidth).toInt()
            absolutePopupKeyWidth = (alphaSymbolKeyWidth * baseWidth).toInt()

            relativeHorizontalGap = keyboardAttr.getFraction(
                    R.styleable.Keyboard_horizontalGap, 1, 1, 0f) * sv.keyGapScale
            relativeVerticalGap = keyboardAttr.getFraction(
                    R.styleable.Keyboard_verticalGap, 1, 1, 0f) * sv.keyGapScale
            horizontalGap = (relativeHorizontalGap * width).toInt()
            verticalGap = (relativeVerticalGap * height).toInt()

            baseHeight = occupiedHeight - topPadding - bottomPadding + verticalGap
            defaultRowHeight = ResourceUtils.getDimensionOrFraction(keyboardAttr,
                    R.styleable.Keyboard_rowHeight, 1, 1f / DEFAULT_KEYBOARD_ROWS)
            if (defaultRowHeight > 1) { // can be absolute size, in that case will be > 1
                defaultAbsoluteRowHeight = defaultRowHeight.toInt()
                defaultRowHeight *= -1f // make it negative when it's absolute
            } else {
                defaultAbsoluteRowHeight = (defaultRowHeight * baseHeight).toInt()
            }

            keyVisualAttributes = KeyVisualAttributes.newInstance(keyAttr)

            popupKeysTemplate = keyboardAttr.getResourceId(R.styleable.Keyboard_popupKeysTemplate, 0)
            maxPopupKeysKeyboardColumn = keyAttr.getInt(R.styleable.Keyboard_Key_maxPopupKeysColumn, 5)

            themeId = keyboardAttr.getInt(R.styleable.Keyboard_themeId, 0)
            iconsSet.loadIcons(context)

            // touchPositionResId currently is 0 for popups, and touch_position_correction_data_holo for others
            val touchPositionResId = keyboardAttr.getResourceId(R.styleable.Keyboard_touchPositionCorrectionData, 0)
            if (touchPositionResId != 0) {
                val actualId = if (id.element.isAlphabet) touchPositionResId else R.array.touch_position_correction_data_default
                val data = context.getResources().getStringArray(actualId)
                touchPositionCorrection.load(data)
            }
        } finally {
            keyAttr.recycle()
            keyboardAttr.recycle()
        }
        setTabletExtraKeys = Settings.instance.isTablet() && !id.subtype.isCustom
    }

    companion object {
        private const val DEFAULT_KEYBOARD_COLUMNS = 10
        const val DEFAULT_KEYBOARD_ROWS = 4

        // Comparator to sort {@link Key}s from top-left to bottom-right order.
        private val ROW_COLUMN_COMPARATOR = Comparator<Key> { lhs, rhs ->
            if (lhs.getY() < rhs.getY()) return@Comparator -1
            if (lhs.getY() > rhs.getY()) return@Comparator 1
            if (lhs.getX() < rhs.getX()) return@Comparator -1
            if (lhs.getX() > rhs.getX()) return@Comparator 1
            0
        }

        private fun updateHistogramCounter(histogram: SparseIntArray, key: Int): Int {
            val index = histogram.indexOfKey(key)
            val count = (if (index >= 0) histogram.get(key) else 0) + 1
            histogram.put(key, count)
            return count
        }
    }
}
