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
    lateinit var mId: KeyboardId
    @JvmField
    var mThemeId = 0

    /** Total height and width of the keyboard, including the paddings and keys */
    @JvmField
    var mOccupiedHeight = 0
    @JvmField
    var mOccupiedWidth = 0

    /** Base height and width of the keyboard used to calculate rows' or keys' heights and
     *  widths
     */
    @JvmField
    var mBaseHeight = 0
    @JvmField
    var mBaseWidth = 0

    @JvmField
    var mTopPadding = 0
    @JvmField
    var mBottomPadding = 0
    @JvmField
    var mLeftPadding = 0
    @JvmField
    var mRightPadding = 0

    @JvmField
    var mKeyVisualAttributes: KeyVisualAttributes? = null

    @JvmField
    var mDefaultRowHeight = 0f
    @JvmField
    var mDefaultKeyWidth = 0f
    @JvmField
    var mRelativeHorizontalGap = 0f
    @JvmField
    var mRelativeVerticalGap = 0f
    // relative values multiplied with baseHeight / baseWidth
    @JvmField
    var mDefaultAbsoluteRowHeight = 0
    @JvmField
    var mDefaultAbsoluteKeyWidth = 0
    @JvmField
    var mHorizontalGap = 0
    @JvmField
    var mVerticalGap = 0

    @JvmField
    var mPopupKeysTemplate = 0
    @JvmField
    var mMaxPopupKeysKeyboardColumn = 0
    // popup key width is separate from mDefaultAbsoluteKeyWidth because it should not depend on alpha or number layout
    @JvmField
    var mAbsolutePopupKeyWidth = 0

    @JvmField
    var GRID_WIDTH = 0
    @JvmField
    var GRID_HEIGHT = 0

    // Keys are sorted from top-left to bottom-right order.
    @JvmField
    val mSortedKeys: SortedSet<Key> = TreeSet(ROW_COLUMN_COMPARATOR)
    @JvmField
    val mShiftKeys = ArrayList<Key>()
    @JvmField
    val mAltCodeKeysWhileTyping = ArrayList<Key>()
    @JvmField
    val mIconsSet = KeyboardIconsSet.instance
    // todo: not good, this only works because params are currently always created for the active subtype
    @JvmField
    val mSecondaryLocales: List<Locale> = Settings.getValues().mSecondaryLocales
    @JvmField
    val mPopupKeyOrder = ArrayList<String>()
    @JvmField
    val mPopupKeyHintOrder = ArrayList<String>()

    private val uniqueKeysCache: UniqueKeysCache
    @JvmField
    var mAllowRedundantPopupKeys = false
    lateinit var mLocaleKeyboardInfos: LocaleKeyboardInfos
    @JvmField
    var setTabletExtraKeys = false

    @JvmField
    var mMostCommonKeyHeight = 0
    @JvmField
    var mMostCommonKeyWidth = 0

    // should be enabled for all alphabet layouts, except for specific layouts when shifted
    @JvmField
    var mProximityCharsCorrectionEnabled = false

    // only for removing redundant popup keys
    @JvmField
    var baseKeys: List<Key.KeyParams>? = null

    @JvmField
    val mTouchPositionCorrection = TouchPositionCorrection()

    constructor() : this(UniqueKeysCache.NO_CACHE)

    constructor(keysCache: UniqueKeysCache) {
        uniqueKeysCache = keysCache
    }

    protected fun clearKeys() {
        mSortedKeys.clear()
        mShiftKeys.clear()
        clearHistogram()
    }

    fun onAddKey(newKey: Key) {
        val key = uniqueKeysCache.getUniqueKey(newKey)
        val isSpacer = key.isSpacer()
        if (isSpacer && key.getWidth() == 0) {
            // Ignore zero width {@link Spacer}.
            return
        }
        mSortedKeys.add(key)
        if (isSpacer) {
            return
        }
        updateHistogram(key)
        if (key.getCode() == KeyCode.SHIFT) {
            mShiftKeys.add(key)
        }
        if (key.altCodeWhileTyping()) {
            mAltCodeKeysWhileTyping.add(key)
        }
    }

    fun removeRedundantPopupKeys() {
        if (mAllowRedundantPopupKeys || baseKeys == null) {
            return
        }
        val lettersOnBaseLayout =
                PopupKeySpec.LettersOnBaseLayout()
        for (key in baseKeys!!) {
            lettersOnBaseLayout.addLetter(key)
        }
        val allKeys = ArrayList<Key>(mSortedKeys)
        mSortedKeys.clear()
        for (key in allKeys) {
            val filteredKey = Key.removeRedundantPopupKeys(key, lettersOnBaseLayout)
            mSortedKeys.add(uniqueKeysCache.getUniqueKey(filteredKey))
        }
        baseKeys = null
    }

    private var maxHeightCount = 0
    private var maxWidthCount = 0
    private val heightHistogram = SparseIntArray()
    private val widthHistogram = SparseIntArray()

    private fun clearHistogram() {
        mMostCommonKeyHeight = 0
        maxHeightCount = 0
        heightHistogram.clear()

        maxWidthCount = 0
        mMostCommonKeyWidth = 0
        widthHistogram.clear()
    }

    private fun updateHistogram(key: Key) {
        val height = key.getHeight() + mVerticalGap
        val heightCount = updateHistogramCounter(heightHistogram, height)
        if (heightCount > maxHeightCount) {
            maxHeightCount = heightCount
            mMostCommonKeyHeight = height
        }

        val width = key.getWidth() + mHorizontalGap
        val widthCount = updateHistogramCounter(widthHistogram, width)
        if (widthCount > maxWidthCount) {
            maxWidthCount = widthCount
            mMostCommonKeyWidth = width
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
        val sv = Settings.getValues()
        try {
            val height = mId.height
            val width = mId.width
            mOccupiedHeight = height
            mOccupiedWidth = width
            mTopPadding = keyboardAttr.getFraction(
                    R.styleable.Keyboard_keyboardTopPadding, height, height, 0f).toInt()
            mBottomPadding = (keyboardAttr.getFraction(
                    R.styleable.Keyboard_keyboardBottomPadding, height, height, 0f)
                    * sv.mBottomPaddingScale).toInt()
            mLeftPadding = (keyboardAttr.getFraction(
                    R.styleable.Keyboard_keyboardLeftPadding, width, width, 0f)
                    * sv.mSidePaddingScale).toInt()
            mRightPadding = (keyboardAttr.getFraction(
                    R.styleable.Keyboard_keyboardRightPadding, width, width, 0f)
                    * sv.mSidePaddingScale).toInt()

            mBaseWidth = mOccupiedWidth - mLeftPadding - mRightPadding
            val defaultKeyWidthFactor = if (context.getResources().getInteger(R.integer.config_screen_metrics) > 2) 0.9f else 1f
            val alphaSymbolKeyWidth = keyAttr.getFraction(R.styleable.Keyboard_Key_keyWidth,
                    1, 1, defaultKeyWidthFactor / DEFAULT_KEYBOARD_COLUMNS)
            mDefaultKeyWidth = if (mId.element.isNumberLayout) 0.17f else alphaSymbolKeyWidth
            mDefaultAbsoluteKeyWidth = (mDefaultKeyWidth * mBaseWidth).toInt()
            mAbsolutePopupKeyWidth = (alphaSymbolKeyWidth * mBaseWidth).toInt()

            mRelativeHorizontalGap = keyboardAttr.getFraction(
                    R.styleable.Keyboard_horizontalGap, 1, 1, 0f) * sv.mKeyGapScale
            mRelativeVerticalGap = keyboardAttr.getFraction(
                    R.styleable.Keyboard_verticalGap, 1, 1, 0f) * sv.mKeyGapScale
            mHorizontalGap = (mRelativeHorizontalGap * width).toInt()
            mVerticalGap = (mRelativeVerticalGap * height).toInt()

            mBaseHeight = mOccupiedHeight - mTopPadding - mBottomPadding + mVerticalGap
            mDefaultRowHeight = ResourceUtils.getDimensionOrFraction(keyboardAttr,
                    R.styleable.Keyboard_rowHeight, 1, 1f / DEFAULT_KEYBOARD_ROWS)
            if (mDefaultRowHeight > 1) { // can be absolute size, in that case will be > 1
                mDefaultAbsoluteRowHeight = mDefaultRowHeight.toInt()
                mDefaultRowHeight *= -1f // make it negative when it's absolute
            } else {
                mDefaultAbsoluteRowHeight = (mDefaultRowHeight * mBaseHeight).toInt()
            }

            mKeyVisualAttributes = KeyVisualAttributes.newInstance(keyAttr)

            mPopupKeysTemplate = keyboardAttr.getResourceId(R.styleable.Keyboard_popupKeysTemplate, 0)
            mMaxPopupKeysKeyboardColumn = keyAttr.getInt(R.styleable.Keyboard_Key_maxPopupKeysColumn, 5)

            mThemeId = keyboardAttr.getInt(R.styleable.Keyboard_themeId, 0)
            mIconsSet.loadIcons(context)

            // touchPositionResId currently is 0 for popups, and touch_position_correction_data_holo for others
            val touchPositionResId = keyboardAttr.getResourceId(R.styleable.Keyboard_touchPositionCorrectionData, 0)
            if (touchPositionResId != 0) {
                val actualId = if (mId.element.isAlphabet) touchPositionResId else R.array.touch_position_correction_data_default
                val data = context.getResources().getStringArray(actualId)
                mTouchPositionCorrection.load(data)
            }
        } finally {
            keyAttr.recycle()
            keyboardAttr.recycle()
        }
        setTabletExtraKeys = Settings.getInstance().isTablet() && !mId.subtype.isCustom
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
