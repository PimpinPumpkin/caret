/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import app.caret.keyboard.internal.KeyDrawParams
import app.caret.keyboard.internal.KeySpecParser
import app.caret.keyboard.internal.KeyVisualAttributes
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.keyboard.internal.KeyboardParams
import app.caret.keyboard.internal.PopupKeySpec
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode.isModifier
import app.caret.keyboard.internal.keyboard_parser.floris.PopupSet
import app.caret.latin.common.Constants
import app.caret.latin.common.StringUtils
import app.caret.latin.utils.ToolbarKey
import app.caret.latin.utils.createPopupKeysArray
import app.caret.latin.utils.findPopupHintLabelOrIcon
import app.caret.latin.utils.getHintIcon
import app.caret.latin.utils.getHintLabel
import app.caret.latin.utils.toolbarKeyStrings
import java.util.Arrays
import java.util.Locale

/**
 * Class for describing the position and characteristics of a single key in the keyboard.
 */
open class Key : Comparable<Key> {
    /**
     * The key code (unicode or custom code) that this key generates.
     */
    private val mCode: Int

    /** Label to display */
    private val mLabel: String?
    /** Hint label to display on the key in conjunction with the label */
    private val mHintLabel: String?
    private val mHintIconName: String?
    /** Flags of the label */
    private val mLabelFlags: Int

    /** Icon to display instead of a label. Icon takes precedence over a label */
    private val mIconName: String?

    /** Width of the key, excluding the gap */
    private val mWidth: Int
    /** Height of the key, excluding the gap */
    private val mHeight: Int
    /**
     * The combined width in pixels of the horizontal gaps belonging to this key, both to the left
     * and to the right. I.e., mWidth + mHorizontalGap = total width belonging to the key.
     */
    private val mHorizontalGap: Int
    /**
     * The combined height in pixels of the vertical gaps belonging to this key, both above and
     * below. I.e., mHeight + mVerticalGap = total height belonging to the key.
     */
    private val mVerticalGap: Int
    /** X coordinate of the top-left corner of the key in the keyboard layout, excluding the gap. */
    private val mX: Int
    /** Y coordinate of the top-left corner of the key in the keyboard layout, excluding the gap. */
    private val mY: Int
    /** Hit bounding box of the key */
    private val mHitBox = Rect()

    /** Popup keys. It is guaranteed that this is null or an array of one or more elements */
    private val mPopupKeys: Array<PopupKeySpec>?
    /** Popup keys column number and flags */
    private val mPopupKeysColumnAndFlags: Int

    /** Background type that represents different key background visual than normal one. */
    private val mBackgroundType: Int

    private val mActionFlags: Int

    private val mKeyVisualAttributes: KeyVisualAttributes?
    private val mOptionalAttributes: OptionalAttributes?

    class OptionalAttributes private constructor(
        /** Text to output when pressed. This can be multiple characters, like ".com" */
        @JvmField val mOutputText: String?,
        @JvmField val mAltCode: Int,
        /** Icon for disabled state */
        @JvmField val mDisabledIconName: String?,
        /** The visual insets */
        @JvmField val mVisualInsetsLeft: Int,
        @JvmField val mVisualInsetsRight: Int
    ) {
        companion object {
            @JvmStatic
            fun newInstance(outputText: String?, altCode: Int,
                     disabledIconName: String?, visualInsetsLeft: Int, visualInsetsRight: Int): OptionalAttributes? {
                if (outputText == null && altCode == KeyCode.NOT_SPECIFIED
                        && disabledIconName == null && visualInsetsLeft == 0
                        && visualInsetsRight == 0) {
                    return null
                }
                return OptionalAttributes(outputText, altCode, disabledIconName, visualInsetsLeft,
                        visualInsetsRight)
            }
        }
    }

    private val mHashCode: Int

    /** The current pressed state of this key */
    private var mPressed = false
    /** Key is enabled and responds on press */
    private var mEnabled: Boolean
    /** Key is locked (appears permanently pressed) */
    private var mLocked = false

    /**
     * Constructor for a key on <code>PopupKeyKeyboard</code> and on <code>MoreSuggestions</code>.
     */
    constructor(label: String?, iconName: String?, code: Int,
            outputText: String?, hintLabel: String?,
            labelFlags: Int, backgroundType: Int, x: Int, y: Int,
            width: Int, height: Int, horizontalGap: Int, verticalGap: Int) {
        mWidth = width - horizontalGap
        mHeight = height - verticalGap
        mHorizontalGap = horizontalGap
        mVerticalGap = verticalGap
        mHintLabel = hintLabel
        mHintIconName = null
        mLabelFlags = labelFlags
        mBackgroundType = backgroundType
        // TODO: Pass keyActionFlags as an argument.
        mActionFlags = ACTION_FLAGS_NO_KEY_PREVIEW
        mPopupKeys = null
        mPopupKeysColumnAndFlags = 0
        mLabel = label
        mCode = code
        mEnabled = (code != KeyCode.NOT_SPECIFIED)
        mIconName = iconName
        mOptionalAttributes = OptionalAttributes.newInstance(outputText, KeyCode.NOT_SPECIFIED,
                if (iconName == null) null else getDisabledIconName(iconName), 0, 0)
        // Horizontal gap is divided equally to both sides of the key.
        mX = x + mHorizontalGap / 2
        mY = y
        mHitBox.set(x, y, x + width + 1, y + height)
        mKeyVisualAttributes = null

        mHashCode = computeHashCode(this)
    }

    /**
     * Copy constructor for DynamicGridKeyboard.GridKey.
     *
     * @param key the original key.
     * @param popupKeys the popup keys that should be assigned to this key.
     * @param labelHint the label hint that should be assigned to this key.
     * @param backgroundType the background type that should be assigned to this key.
     */
    protected constructor(key: Key, popupKeys: Array<PopupKeySpec>?,
                labelHint: String?, backgroundType: Int) {
        // Final attributes.
        mCode = key.mCode
        mLabel = key.mLabel
        mHintLabel = labelHint
        mHintIconName = key.mHintIconName
        mLabelFlags = key.mLabelFlags
        mIconName = key.mIconName
        mWidth = key.mWidth
        mHeight = key.mHeight
        mHorizontalGap = key.mHorizontalGap
        mVerticalGap = key.mVerticalGap
        mX = key.mX
        mY = key.mY
        mHitBox.set(key.mHitBox)
        mPopupKeys = popupKeys
        mPopupKeysColumnAndFlags = key.mPopupKeysColumnAndFlags
        mBackgroundType = backgroundType
        mActionFlags = key.mActionFlags
        mKeyVisualAttributes = key.mKeyVisualAttributes
        mOptionalAttributes = key.mOptionalAttributes
        mHashCode = key.mHashCode
        // Key state.
        mPressed = key.mPressed
        mEnabled = key.mEnabled
    }

    /** constructor for creating emoji recent keys when there is no keyboard to take keys from */
    constructor(key: Key, popupKeys: Array<PopupKeySpec>?,
             labelHint: String?, backgroundType: Int, code: Int, outputText: String?) {
        // Final attributes.
        mCode = if (outputText == null) code else KeyCode.MULTIPLE_CODE_POINTS
        mLabel = if (outputText == null) StringUtils.newSingleCodePointString(code) else outputText
        mHintLabel = labelHint
        mHintIconName = key.mHintIconName
        mLabelFlags = key.mLabelFlags
        mIconName = key.mIconName
        mWidth = key.mWidth
        mHeight = key.mHeight
        mHorizontalGap = key.mHorizontalGap
        mVerticalGap = key.mVerticalGap
        mX = key.mX
        mY = key.mY
        mHitBox.set(key.mHitBox)
        mPopupKeys = popupKeys
        mPopupKeysColumnAndFlags = key.mPopupKeysColumnAndFlags
        mBackgroundType = backgroundType
        mActionFlags = key.mActionFlags
        mKeyVisualAttributes = key.mKeyVisualAttributes
        mOptionalAttributes = if (outputText == null) null
                else OptionalAttributes.newInstance(outputText, KeyCode.NOT_SPECIFIED, null, 0, 0)
        mHashCode = key.mHashCode
        // Key state.
        mPressed = key.mPressed
        mEnabled = key.mEnabled
    }

    /** constructor from KeyParams */
    private constructor(keyParams: KeyParams) {
        // stuff to copy
        mCode = keyParams.mCode
        mLabel = keyParams.mLabel
        mHintLabel = keyParams.mHintLabel
        mHintIconName = keyParams.mHintIconName
        mLabelFlags = keyParams.mLabelFlags
        mIconName = keyParams.mIconName
        mPopupKeys = keyParams.mPopupKeys
        mPopupKeysColumnAndFlags = keyParams.mPopupKeysColumnAndFlags
        mBackgroundType = keyParams.mBackgroundType
        mActionFlags = keyParams.mActionFlags
        mKeyVisualAttributes = keyParams.mKeyVisualAttributes
        mOptionalAttributes = keyParams.mOptionalAttributes
        mEnabled = keyParams.mEnabled

        // stuff to create

        // get the "correct" float gap: may shift keys by one pixel, but results in more uniform gaps between keys
        val horizontalGapFloat = if (isSpacer()) 0f else (keyParams.mKeyboardParams.mRelativeHorizontalGap * keyParams.mKeyboardParams.mOccupiedWidth)
        mHorizontalGap = Math.round(horizontalGapFloat)
        mVerticalGap = Math.round(keyParams.mKeyboardParams.mRelativeVerticalGap * keyParams.mKeyboardParams.mOccupiedHeight)
        mWidth = Math.round(keyParams.mAbsoluteWidth - horizontalGapFloat)
        // height is always rounded down, because rounding up may make the keyboard too high to fit, leading to issues
        mHeight = (keyParams.mAbsoluteHeight - keyParams.mKeyboardParams.mVerticalGap).toInt()
        if (!isSpacer() && (mWidth == 0 || mHeight == 0)) {
            throw IllegalStateException("key needs positive width and height")
        }
        // Horizontal gap is divided equally to both sides of the key.
        mX = Math.round(keyParams.xPos + horizontalGapFloat / 2)
        mY = Math.round(keyParams.yPos)
        mHitBox.set(Math.round(keyParams.xPos), Math.round(keyParams.yPos), Math.round(keyParams.xPos + keyParams.mAbsoluteWidth) + 1,
                Math.round(keyParams.yPos + keyParams.mAbsoluteHeight))
        mHashCode = computeHashCode(this)
    }

    private constructor(key: Key, popupKeys: Array<PopupKeySpec>?) {
        // Final attributes.
        mCode = key.mCode
        mLabel = key.mLabel
        val labelAndIcon = findPopupHintLabelOrIcon(popupKeys, key.mHintLabel, key.mHintIconName)
        mHintLabel = labelAndIcon.component1()
        mHintIconName = labelAndIcon.component2()
        mLabelFlags = key.mLabelFlags
        mIconName = key.mIconName
        mWidth = key.mWidth
        mHeight = key.mHeight
        mHorizontalGap = key.mHorizontalGap
        mVerticalGap = key.mVerticalGap
        mX = key.mX
        mY = key.mY
        mHitBox.set(key.mHitBox)
        mPopupKeys = popupKeys
        mPopupKeysColumnAndFlags = key.mPopupKeysColumnAndFlags
        mBackgroundType = key.mBackgroundType
        if (popupKeys == null && mCode > Constants.CODE_SPACE && (key.mActionFlags and ACTION_FLAGS_ENABLE_LONG_PRESS) != 0)
            mActionFlags = key.mActionFlags - ACTION_FLAGS_ENABLE_LONG_PRESS
        else
            mActionFlags = key.mActionFlags
        mKeyVisualAttributes = key.mKeyVisualAttributes
        mOptionalAttributes = key.mOptionalAttributes
        mHashCode = key.mHashCode
        // Key state.
        mPressed = key.mPressed
        mEnabled = key.mEnabled
    }

    private fun equalsInternal(o: Key): Boolean {
        if (this === o) return true
        return o.mX == mX
                && o.mY == mY
                && o.mWidth == mWidth
                && o.mHeight == mHeight
                && o.mCode == mCode
                && TextUtils.equals(o.mLabel, mLabel)
                && TextUtils.equals(o.mHintLabel, mHintLabel)
                && TextUtils.equals(o.mHintIconName, mHintIconName)
                && TextUtils.equals(o.mIconName, mIconName)
                && o.mBackgroundType == mBackgroundType
                && Arrays.equals(o.mPopupKeys, mPopupKeys)
                && TextUtils.equals(o.getOutputText(), getOutputText())
                && o.mActionFlags == mActionFlags
                && o.mLabelFlags == mLabelFlags
    }

    override fun compareTo(o: Key): Int {
        if (equalsInternal(o)) return 0
        if (mHashCode > o.mHashCode) return 1
        return -1
    }

    override fun hashCode(): Int {
        return mHashCode
    }

    override fun equals(o: Any?): Boolean {
        return o is Key && equalsInternal(o)
    }

    override fun toString(): String {
        return toShortString() + " " + getX() + "," + getY() + " " + getWidth() + "x" + getHeight()
    }

    fun toShortString(): String? {
        val code = getCode()
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            return getOutputText()
        }
        return Constants.printableCode(code)
    }

    fun toLongString(): String {
        val iconName = getIconName()
        val topVisual = if (iconName != null)
                KeyboardIconsSet.PREFIX_ICON + iconName else getLabel()
        val hintLabel = getHintLabel()
        val visual = if (hintLabel == null) topVisual else topVisual + "^" + hintLabel
        return toString() + " " + visual + "/" + backgroundName(mBackgroundType)
    }

    fun getCode(): Int {
        return mCode
    }

    fun getLabel(): String? {
        return mLabel
    }

    fun getHintLabel(): String? {
        return mHintLabel
    }

    fun getPopupKeys(): Array<PopupKeySpec>? {
        return mPopupKeys
    }

    fun markAsLeftEdge(params: KeyboardParams) {
        mHitBox.left = params.mLeftPadding
    }

    fun markAsRightEdge(params: KeyboardParams) {
        mHitBox.right = params.mOccupiedWidth - params.mRightPadding
    }

    fun markAsTopEdge(params: KeyboardParams) {
        mHitBox.top = params.mTopPadding
    }

    fun markAsBottomEdge(params: KeyboardParams) {
        mHitBox.bottom = params.mOccupiedHeight + params.mBottomPadding
    }

    fun isSpacer(): Boolean {
        return this is Spacer
    }

    fun hasActionKeyBackground(): Boolean {
        return mBackgroundType == BACKGROUND_TYPE_ACTION
    }

    fun isShift(): Boolean {
        return mCode == KeyCode.SHIFT
    }

    fun isModifier(): Boolean {
        return mCode.isModifier()
    }

    fun isRepeatable(): Boolean {
        return (mActionFlags and ACTION_FLAGS_IS_REPEATABLE) != 0
    }

    fun hasPreview(): Boolean {
        return (mActionFlags and ACTION_FLAGS_NO_KEY_PREVIEW) == 0
    }

    /**
     *  altCodeWhileTyping is a weird thing.
     *  When user pressed a typing key less than ignoreAltCodeKeyTimeout (config_ignore_alt_code_key_timeout / 350 ms) ago,
     *  this code will be used instead. There is no documentation, but it appears the purpose is to avoid unintentional layout switches.
     *  Assuming this is true, the key still is used now if pressed near the center, where we assume it's less likely to be accidental.
     *  See PointerTracker.isClearlyInsideKey
     */
    fun altCodeWhileTyping(): Boolean {
        return (mActionFlags and ACTION_FLAGS_ALT_CODE_WHILE_TYPING) != 0
    }

    fun isLongPressEnabled(): Boolean {
        // We need not start long press timer on the key which has activated shifted letter.
        return (mActionFlags and ACTION_FLAGS_ENABLE_LONG_PRESS) != 0
                && (mLabelFlags and LABEL_FLAGS_SHIFTED_LETTER_ACTIVATED) == 0
    }

    fun getVisualAttributes(): KeyVisualAttributes? {
        return mKeyVisualAttributes
    }

    fun selectTypeface(params: KeyDrawParams): Typeface {
        return when (mLabelFlags and LABEL_FLAGS_FONT_MASK) {
            LABEL_FLAGS_FONT_NORMAL -> Typeface.DEFAULT
            LABEL_FLAGS_FONT_MONO_SPACE -> Typeface.MONOSPACE
            else -> params.mTypeface // The type-face is specified by keyTypeface attribute.
        }
    }

    fun selectTextSize(params: KeyDrawParams): Int {
        return when (mLabelFlags and LABEL_FLAGS_FOLLOW_KEY_TEXT_RATIO_MASK) {
            LABEL_FLAGS_FOLLOW_KEY_LETTER_RATIO -> params.mLetterSize
            LABEL_FLAGS_FOLLOW_KEY_LARGE_LETTER_RATIO -> params.mLargeLetterSize
            LABEL_FLAGS_FOLLOW_KEY_LABEL_RATIO -> params.mLabelSize
            LABEL_FLAGS_FOLLOW_KEY_HINT_LABEL_RATIO -> params.mHintLabelSize
            // No follow key ratio flag specified.
            else -> if (StringUtils.codePointCount(mLabel) == 1) params.mLetterSize else params.mLabelSize
        }
    }

    fun selectTextColor(params: KeyDrawParams): Int {
        if ((mLabelFlags and LABEL_FLAGS_FOLLOW_FUNCTIONAL_TEXT_COLOR) != 0) {
            return params.mFunctionalTextColor
        }
        return if (isShiftedLetterActivated()) params.mTextInactivatedColor else params.mTextColor
    }

    fun selectHintTextSize(params: KeyDrawParams): Int {
        if (hasHintLabel()) {
            return params.mHintLabelSize
        }
        if (hasShiftedLetterHint()) {
            return params.mShiftedLetterHintSize
        }
        return params.mHintLetterSize
    }

    fun selectHintTextColor(params: KeyDrawParams): Int {
        if (hasHintLabel()) {
            return params.mHintLabelColor
        }
        if (hasShiftedLetterHint()) {
            return if (isShiftedLetterActivated()) params.mShiftedLetterHintActivatedColor
                    else params.mShiftedLetterHintInactivatedColor
        }
        return params.mHintLetterColor
    }

    fun selectPopupKeyTextSize(params: KeyDrawParams): Int {
        return if (hasLabelsInPopupKeys()) params.mLabelSize else params.mLetterSize
    }

    fun getPreviewLabel(): String? {
        return if (isShiftedLetterActivated()) mHintLabel else mLabel
    }

    private fun previewHasLetterSize(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_FOLLOW_KEY_LETTER_RATIO) != 0
                || StringUtils.codePointCount(getPreviewLabel()) == 1
    }

    fun selectPreviewTextSize(params: KeyDrawParams): Int {
        if (previewHasLetterSize()) {
            return params.mPreviewTextSize
        }
        return params.mLetterSize
    }

    fun selectPreviewTypeface(params: KeyDrawParams): Typeface {
        if (previewHasLetterSize()) {
            return selectTypeface(params)
        }
        return Typeface.DEFAULT_BOLD
    }

    fun isAlignHintLabelToBottom(defaultFlags: Int): Boolean {
        return ((mLabelFlags or defaultFlags) and LABEL_FLAGS_ALIGN_HINT_LABEL_TO_BOTTOM) != 0
    }

    fun isAlignIconToBottom(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_ALIGN_ICON_TO_BOTTOM) != 0
    }

    fun isAlignLabelOffCenter(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_ALIGN_LABEL_OFF_CENTER) != 0
    }

    fun hasShiftedLetterHint(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_HAS_SHIFTED_LETTER_HINT) != 0
                && !TextUtils.isEmpty(mHintLabel)
    }

    fun hasShiftedLetterHintIcon(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_HAS_SHIFTED_LETTER_HINT) != 0
            && !TextUtils.isEmpty(mHintIconName)
    }

    fun hasHintLabel(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_HAS_HINT_LABEL) != 0
    }

    fun needsAutoXScale(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_AUTO_X_SCALE) != 0
    }

    fun needsAutoScale(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_AUTO_SCALE) == LABEL_FLAGS_AUTO_SCALE
    }

    fun needsToKeepBackgroundAspectRatio(defaultFlags: Int): Boolean {
        return ((mLabelFlags or defaultFlags) and LABEL_FLAGS_KEEP_BACKGROUND_ASPECT_RATIO) != 0
    }

    fun hasCustomActionLabel(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_FROM_CUSTOM_ACTION_LABEL) != 0
    }

    private fun isShiftedLetterActivated(): Boolean {
        return (mLabelFlags and LABEL_FLAGS_SHIFTED_LETTER_ACTIVATED) != 0
                && !TextUtils.isEmpty(mHintLabel)
    }

    fun getPopupKeysColumnNumber(): Int {
        return mPopupKeysColumnAndFlags and POPUP_KEYS_COLUMN_NUMBER_MASK
    }

    fun isPopupKeysFixedColumn(): Boolean {
        return (mPopupKeysColumnAndFlags and POPUP_KEYS_FLAGS_FIXED_COLUMN) != 0
    }

    fun isPopupKeysFixedOrder(): Boolean {
        return (mPopupKeysColumnAndFlags and POPUP_KEYS_FLAGS_FIXED_ORDER) != 0
    }

    fun hasLabelsInPopupKeys(): Boolean {
        return (mPopupKeysColumnAndFlags and POPUP_KEYS_FLAGS_HAS_LABELS) != 0
    }

    fun getPopupKeyLabelFlags(): Int {
        val labelSizeFlag = if (hasLabelsInPopupKeys())
                LABEL_FLAGS_FOLLOW_KEY_LABEL_RATIO
                else LABEL_FLAGS_FOLLOW_KEY_LETTER_RATIO
        return labelSizeFlag or LABEL_FLAGS_AUTO_X_SCALE
    }

    fun needsDividersInPopupKeys(): Boolean {
        return (mPopupKeysColumnAndFlags and POPUP_KEYS_FLAGS_NEEDS_DIVIDERS) != 0
    }

    fun hasNoPanelAutoPopupKey(): Boolean {
        return (mPopupKeysColumnAndFlags and POPUP_KEYS_FLAGS_NO_PANEL_AUTO_POPUP_KEY) != 0
    }

    fun getOutputText(): String? {
        val attrs = mOptionalAttributes
        return if (attrs != null) attrs.mOutputText else null
    }

    fun getAltCode(): Int {
        val attrs = mOptionalAttributes
        return if (attrs != null) attrs.mAltCode else KeyCode.NOT_SPECIFIED
    }

    fun getIconName(): String? {
        return mIconName
    }

    open fun getIcon(iconSet: KeyboardIconsSet, alpha: Int): Drawable? {
        val attrs = mOptionalAttributes
        val iconName = if (mEnabled) getIconName() else (if (attrs != null) attrs.mDisabledIconName else null)
        val icon = iconSet.getIconDrawable(iconName)
        if (icon != null) {
            icon.setAlpha(alpha)
        }
        return icon
    }

    fun getHintIcon(iconSet: KeyboardIconsSet, alpha: Int): Drawable? {
        val attrs = mOptionalAttributes
        val iconName = if (mEnabled) mHintIconName else (if (attrs != null) attrs.mDisabledIconName else null)
        val icon = iconSet.getIconDrawable(iconName)
        if (icon != null) {
            icon.setAlpha(alpha)
        }
        return icon
    }

    fun getPreviewIcon(iconSet: KeyboardIconsSet): Drawable? {
        return iconSet.getIconDrawable(getIconName())
    }

    /**
     * Gets the background type of this key.
     * @return Background type.
     * @see Key#BACKGROUND_TYPE_EMPTY
     * @see Key#BACKGROUND_TYPE_NORMAL
     * @see Key#BACKGROUND_TYPE_FUNCTIONAL
     * @see Key#BACKGROUND_TYPE_ACTION
     * @see Key#BACKGROUND_TYPE_SPACEBAR
     */
    fun getBackgroundType(): Int {
        return mBackgroundType
    }

    /**
     * Gets the width of the key in pixels, excluding the gap.
     * @return The width of the key in pixels, excluding the gap.
     */
    fun getWidth(): Int {
        return mWidth
    }

    /**
     * Gets the height of the key in pixels, excluding the gap.
     * @return The height of the key in pixels, excluding the gap.
     */
    fun getHeight(): Int {
        return mHeight
    }

    /**
     * The combined width in pixels of the horizontal gaps belonging to this key, both above and
     * below. I.e., getWidth() + getHorizontalGap() = total width belonging to the key.
     * @return Horizontal gap belonging to this key.
     */
    fun getHorizontalGap(): Int {
        return mHorizontalGap
    }

    /**
     * The combined height in pixels of the vertical gaps belonging to this key, both above and
     * below. I.e., getHeight() + getVerticalGap() = total height belonging to the key.
     * @return Vertical gap belonging to this key.
     */
    fun getVerticalGap(): Int {
        return mVerticalGap
    }

    /**
     * Gets the x-coordinate of the top-left corner of the key in pixels, excluding the gap.
     * @return The x-coordinate of the top-left corner of the key in pixels, excluding the gap.
     */
    open fun getX(): Int {
        return mX
    }

    /**
     * Gets the y-coordinate of the top-left corner of the key in pixels, excluding the gap.
     * @return The y-coordinate of the top-left corner of the key in pixels, excluding the gap.
     */
    open fun getY(): Int {
        return mY
    }

    fun getDrawX(): Int {
        val x = getX()
        val attrs = mOptionalAttributes
        return if (attrs == null) x else x + attrs.mVisualInsetsLeft
    }

    fun getDrawWidth(): Int {
        val attrs = mOptionalAttributes
        return if (attrs == null) mWidth
                else mWidth - attrs.mVisualInsetsLeft - attrs.mVisualInsetsRight
    }

    /**
     * Informs the key that it has been pressed, in case it needs to change its appearance or
     * state.
     * @see #onReleased()
     */
    fun onPressed() {
        mPressed = true
    }

    /**
     * Informs the key that it has been released, in case it needs to change its appearance or
     * state.
     * @see #onPressed()
     */
    fun onReleased() {
        mPressed = false
    }

    fun isEnabled(): Boolean {
        return mEnabled
    }

    fun setEnabled(enabled: Boolean) {
        mEnabled = enabled
    }

    fun setLocked(locked: Boolean) {
        mLocked = locked
    }

    fun getHitBox(): Rect {
        return mHitBox
    }

    /**
     * Detects if a point falls on this key.
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return whether or not the point falls on the key. If the key is attached to an edge, it
     * will assume that all points between the key and the edge are considered to be on the key.
     * @see #markAsLeftEdge(KeyboardParams) etc.
     */
    fun isOnKey(x: Int, y: Int): Boolean {
        return mHitBox.contains(x, y)
    }

    /**
     * Returns the square of the distance to the nearest edge of the key and the given point.
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the square of the distance of the point from the nearest edge of the key
     */
    fun squaredDistanceToEdge(x: Int, y: Int): Int {
        val left = getX()
        val right = left + mWidth
        val top = getY()
        val bottom = top + mHeight
        val edgeX = if (x < left) left else Math.min(x, right)
        val edgeY = if (y < top) top else Math.min(y, bottom)
        val dx = x - edgeX
        val dy = y - edgeY
        return dx * dx + dy * dy
    }

    class KeyBackgroundState private constructor(vararg attrs: Int) {
        private val mReleasedState: IntArray
        private val mPressedState: IntArray

        init {
            mReleasedState = attrs
            mPressedState = Arrays.copyOf(attrs, attrs.size + 1)
            mPressedState[attrs.size] = android.R.attr.state_pressed
        }

        fun getState(pressed: Boolean): IntArray {
            return if (pressed) mPressedState else mReleasedState
        }

        companion object {
            @JvmField
            val STATES = arrayOf(
                // 0: BACKGROUND_TYPE_EMPTY
                KeyBackgroundState(android.R.attr.state_empty),
                // 1: BACKGROUND_TYPE_NORMAL
                KeyBackgroundState(),
                // 2: BACKGROUND_TYPE_FUNCTIONAL
                KeyBackgroundState(),
                // 3: BACKGROUND_TYPE_ACTION
                KeyBackgroundState(android.R.attr.state_active),
                // 4: BACKGROUND_TYPE_SPACEBAR
                KeyBackgroundState(),
            )
        }
    }

    /**
     * Returns the background drawable for the key, based on the current state and type of the key.
     * @return the background drawable of the key.
     * @see android.graphics.drawable.StateListDrawable#setState(int[])
     */
    fun selectBackgroundDrawable(keyBackground: Drawable,
            functionalKeyBackground: Drawable,
            spacebarBackground: Drawable,
            actionKeyBackground: Drawable): Drawable {
        val background: Drawable
        if (hasActionKeyBackground()) {
            background = actionKeyBackground
        } else if (hasFunctionalBackground()) {
            background = functionalKeyBackground
        } else if (mBackgroundType == BACKGROUND_TYPE_SPACEBAR) {
            background = spacebarBackground
        } else {
            background = keyBackground
        }
        val state = KeyBackgroundState.STATES[mBackgroundType].getState(mPressed || mLocked)
        background.setState(state)
        return background
    }

    fun hasActionKeyPopups(): Boolean {
        if (!hasActionKeyBackground()) return false
        // only use the special action key popups for action colored keys, and only for icon popups
        return getPopupKeys()!!.none { key -> key.mIconName == null }
    }

    fun hasFunctionalBackground(): Boolean {
        return mBackgroundType == BACKGROUND_TYPE_FUNCTIONAL
    }

    open class Spacer : Key {
        constructor(keyParams: KeyParams) : super(keyParams)

        /**
         * This constructor is being used only for divider in popup keys keyboard.
         */
        protected constructor(params: KeyboardParams, x: Int, y: Int, width: Int,
                height: Int) : super(null, null, KeyCode.NOT_SPECIFIED, null,
                    null, 0, BACKGROUND_TYPE_EMPTY, x, y, width,
                    height, params.mHorizontalGap, params.mVerticalGap)
    }

    // for creating keys that might get modified later
    class KeyParams {
        // params for building
        @JvmField var isSpacer = false
        @JvmField val mKeyboardParams: KeyboardParams // for reading gaps and keyboard width / height
        @JvmField var mWidth = 0f
        @JvmField var mHeight = 0f // also should allow negative values, indicating absolute height is defined

        // params that may change
        @JvmField var mAbsoluteWidth = 0f
        @JvmField var mAbsoluteHeight = 0f
        @JvmField var xPos = 0f
        @JvmField var yPos = 0f

        // params that remains constant
        @JvmField val mCode: Int
        @JvmField val mLabel: String?
        @JvmField val mHintLabel: String?
        @JvmField val mHintIconName: String?
        @JvmField val mLabelFlags: Int
        @JvmField val mIconName: String?
        @JvmField val mPopupKeys: Array<PopupKeySpec>?
        @JvmField val mPopupKeysColumnAndFlags: Int
        @JvmField val mBackgroundType: Int
        @JvmField val mActionFlags: Int
        @JvmField val mKeyVisualAttributes: KeyVisualAttributes?
        @JvmField val mOptionalAttributes: OptionalAttributes?
        @JvmField val mEnabled: Boolean

        fun createKey(): Key {
            if (isSpacer) return Spacer(this)
            return Key(this)
        }

        fun setAbsoluteDimensions(newX: Float, newY: Float) {
            if (mHeight == 0f)
                mHeight = mKeyboardParams.mDefaultRowHeight
            if (!isSpacer && mWidth == 0f)
                throw IllegalStateException("width = 0 should have been evaluated already")
            if (mHeight < 0)
                // todo (later): deal with it properly when it needs to be adjusted, i.e. when changing popupKeys or moreSuggestions
                throw IllegalStateException("can't (yet) deal with absolute height")
            xPos = newX
            yPos = newY
            mAbsoluteWidth = mWidth * mKeyboardParams.mBaseWidth
            mAbsoluteHeight = mHeight * mKeyboardParams.mBaseHeight
        }

        fun getOutputText(): String? {
            return if (mOptionalAttributes == null) null else mOptionalAttributes.mOutputText
        }

        constructor(
                keySpec: String,
                params: KeyboardParams,
                relativeWidth: Float,
                labelFlags: Int,
                backgroundType: Int,
                popupSet: PopupSet<*>?
        ) : this(keySpec, KeySpecParser.getCode(keySpec), params, relativeWidth, labelFlags, backgroundType, popupSet)

        /**
         *  constructor that does not require attrs, style or absolute key dimension / position
         *  setDimensionsFromRelativeSize needs to be called before creating the key
         */
        constructor(
                // todo (much later): replace keySpec? these encoded icons and codes are not really great
                keySpec: String, // key text or some special string for KeySpecParser, e.g. "!icon/shift_key|!code/key_shift" (avoid using !text, should be removed)
                code: Int,
                params: KeyboardParams,
                width: Float,
                labelFlags: Int,
                backgroundType: Int,
                popupSet: PopupSet<*>?
        ) {
            mKeyboardParams = params
            mBackgroundType = backgroundType
            mLabelFlags = labelFlags
            mWidth = width
            mHeight = params.mDefaultRowHeight
            mIconName = KeySpecParser.getIconName(keySpec)

            val needsToUpcase = Key.needsToUpcase(mLabelFlags, params.mId.element)
            val localeForUpcasing = params.mId.locale
            var actionFlags = 0
            if (params.mId.element.isNumberLayout)
                actionFlags = ACTION_FLAGS_NO_KEY_PREVIEW

            // label
            var label: String? = null
            if ((mLabelFlags and LABEL_FLAGS_FROM_CUSTOM_ACTION_LABEL) != 0) {
                mLabel = params.mId.customActionLabel
            } else if (code >= Character.MIN_SUPPLEMENTARY_CODE_POINT) {
                // This is a workaround to have a key that has a supplementary code point in its label.
                // Because we can put a string in resource neither as a XML entity of a supplementary
                // code point nor as a surrogate pair.
                mLabel = StringBuilder().appendCodePoint(code).toString()
            } else {
                label = KeySpecParser.getLabel(keySpec)
                mLabel = if (needsToUpcase)
                        StringUtils.toTitleCaseOfKeyLabel(label, localeForUpcasing)
                        else label
            }

            // popupKeys
            // the array is declared with nullable elements because reading the column and flags sets entries to null
            @Suppress("UNCHECKED_CAST")
            val popupKeys = createPopupKeysArray(popupSet, mKeyboardParams, if (label != null) label else keySpec) as Array<String?>?
            mPopupKeysColumnAndFlags = getPopupKeysColumnAndFlagsAndSetNullInArray(params, popupKeys)
            val finalPopupKeys = if (popupKeys == null) null else PopupKeySpec.filterOutEmptyString(popupKeys)
            if (finalPopupKeys != null && finalPopupKeys.size > 0) {
                val tempPopupKeys = arrayOfNulls<PopupKeySpec>(finalPopupKeys.size)
                var hasRepeatPopup = false
                for (i in finalPopupKeys.indices) {
                    tempPopupKeys[i] = PopupKeySpec(finalPopupKeys[i], needsToUpcase, localeForUpcasing)
                    if (tempPopupKeys[i]!!.mCode == KeyCode.KEY_REPEAT)
                        hasRepeatPopup = true
                }
                if (hasRepeatPopup) {
                    mPopupKeys = null
                    actionFlags = actionFlags or ACTION_FLAGS_IS_REPEATABLE
                } else {
                    @Suppress("UNCHECKED_CAST")
                    mPopupKeys = tempPopupKeys as Array<PopupKeySpec>
                    actionFlags = actionFlags or ACTION_FLAGS_ENABLE_LONG_PRESS
                }
            } else {
                mPopupKeys = null
            }

            // hint label
            if ((mLabelFlags and LABEL_FLAGS_DISABLE_HINT_LABEL) != 0) {
                mHintLabel = null
                mHintIconName = null
            } else {
                // maybe also always null for comma and period keys
                val hintLabel = getHintLabel(popupSet, params, keySpec)
                mHintLabel = if (needsToUpcase)
                        StringUtils.toTitleCaseOfKeyLabel(hintLabel, localeForUpcasing)
                        else hintLabel
                mHintIconName = if (mHintLabel != null) null else getHintIcon(popupSet, params, keySpec)
            }

            var outputText: String? = KeySpecParser.getOutputText(keySpec, code)
            if (needsToUpcase) {
                outputText = StringUtils.toTitleCaseOfKeyLabel(outputText, localeForUpcasing)
            }
            // Choose the first letter of the label as primary code if not specified.
            if (code == KeyCode.NOT_SPECIFIED && TextUtils.isEmpty(outputText) && !TextUtils.isEmpty(mLabel)) {
                if (StringUtils.codePointCount(mLabel) == 1) {
                    // Use the first letter of the hint label if shiftedLetterActivated flag is
                    // specified.
                    if ((mLabelFlags and LABEL_FLAGS_HAS_SHIFTED_LETTER_HINT) != 0 && (mLabelFlags and LABEL_FLAGS_SHIFTED_LETTER_ACTIVATED) != 0
                            && !TextUtils.isEmpty(mHintLabel)) {
                        mCode = mHintLabel!!.codePointAt(0)
                    } else {
                        mCode = mLabel!!.codePointAt(0)
                    }
                } else {
                    // In some locale and case, the character might be represented by multiple code
                    // points, such as upper case Eszett of German alphabet.
                    outputText = mLabel
                    mCode = KeyCode.MULTIPLE_CODE_POINTS
                }
            } else if (code == KeyCode.NOT_SPECIFIED && outputText != null) {
                if (StringUtils.codePointCount(outputText) == 1) {
                    mCode = outputText.codePointAt(0)
                    outputText = null
                } else {
                    mCode = KeyCode.MULTIPLE_CODE_POINTS
                }
            } else {
                mCode = if (needsToUpcase) StringUtils.toTitleCaseOfKeyCode(code, localeForUpcasing) else code
            }

            // action flags don't need to be specified, they can be deduced from the key
            if (mCode == Constants.CODE_SPACE
                    || mCode == KeyCode.LANGUAGE_SWITCH
                    || (mCode == KeyCode.SYMBOL_ALPHA && !params.mId.element.isAlphabet)
            )
                actionFlags = actionFlags or ACTION_FLAGS_ENABLE_LONG_PRESS
            if (mCode <= Constants.CODE_SPACE && mCode != KeyCode.MULTIPLE_CODE_POINTS && mIconName == null)
                actionFlags = actionFlags or ACTION_FLAGS_NO_KEY_PREVIEW
            when (mCode) {
                KeyCode.DELETE, KeyCode.ARROW_LEFT, KeyCode.ARROW_RIGHT, KeyCode.ARROW_UP, KeyCode.ARROW_DOWN,
                        KeyCode.WORD_LEFT, KeyCode.WORD_RIGHT, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN -> {
                    // repeating is disabled if a key is configured with pop-ups
                    if (mPopupKeys == null)
                        actionFlags = actionFlags or ACTION_FLAGS_IS_REPEATABLE
                    // fallthrough
                    actionFlags = actionFlags or ACTION_FLAGS_NO_KEY_PREVIEW // no preview even if icon!
                }
                KeyCode.SHIFT, Constants.CODE_ENTER, KeyCode.SHIFT_ENTER, KeyCode.ALPHA, Constants.CODE_SPACE, KeyCode.NUMPAD,
                        KeyCode.DPAD, KeyCode.SYMBOL, KeyCode.SYMBOL_ALPHA, KeyCode.LANGUAGE_SWITCH, KeyCode.EMOJI, KeyCode.CLIPBOARD,
                        KeyCode.MOVE_START_OF_LINE, KeyCode.MOVE_END_OF_LINE, KeyCode.MOVE_START_OF_PAGE, KeyCode.MOVE_END_OF_PAGE,
                        KeyCode.EMOJI_SEARCH ->
                    actionFlags = actionFlags or ACTION_FLAGS_NO_KEY_PREVIEW // no preview even if icon!
            }
            if (mCode == KeyCode.SETTINGS || mCode == KeyCode.LANGUAGE_SWITCH)
                actionFlags = actionFlags or ACTION_FLAGS_ALT_CODE_WHILE_TYPING
            mActionFlags = actionFlags

            val altCodeInAttr: Int // settings and language switch keys have alt code space, all others nothing
            if (mCode == KeyCode.SETTINGS || mCode == KeyCode.LANGUAGE_SWITCH || mCode == KeyCode.EMOJI || mCode == KeyCode.CLIPBOARD)
                altCodeInAttr = Constants.CODE_SPACE
            else
                altCodeInAttr = KeyCode.NOT_SPECIFIED
            val altCode = if (needsToUpcase)
                    StringUtils.toTitleCaseOfKeyCode(altCodeInAttr, localeForUpcasing)
                    else altCodeInAttr
            mOptionalAttributes = OptionalAttributes.newInstance(outputText, altCode,
                    // disabled icon only shortcut / voice key, visual insets can be replaced with spacer
                    if (mIconName == null) null else getDisabledIconName(mIconName), 0, 0)
            // KeyVisualAttributes for a key essentially are what the theme has, but on a per-key base
            // could be used e.g. for having a color gradient on key color
            mKeyVisualAttributes = null
            mEnabled = true
        }

        /** constructor for emoji parser */
        constructor(label: String?, code: Int, hintLabel: String?,
                   popupKeySpecs: String?, labelFlags: Int, params: KeyboardParams) {
            mKeyboardParams = params
            mHintLabel = hintLabel
            mLabelFlags = labelFlags
            mBackgroundType = BACKGROUND_TYPE_EMPTY

            if (popupKeySpecs != null) {
                var popupKeys = PopupKeySpec.splitKeySpecs(popupKeySpecs)
                mPopupKeysColumnAndFlags = getPopupKeysColumnAndFlagsAndSetNullInArray(params, popupKeys)

                popupKeys = PopupKeySpec.insertAdditionalPopupKeys(popupKeys, null)
                var actionFlags = 0
                if (popupKeys != null) {
                    actionFlags = actionFlags or ACTION_FLAGS_ENABLE_LONG_PRESS
                    val newPopupKeys = arrayOfNulls<PopupKeySpec>(popupKeys.size)
                    for (i in popupKeys.indices) {
                        newPopupKeys[i] = PopupKeySpec(popupKeys[i], false, Locale.getDefault())
                    }
                    @Suppress("UNCHECKED_CAST")
                    mPopupKeys = newPopupKeys as Array<PopupKeySpec>
                } else {
                    mPopupKeys = null
                }
                mActionFlags = actionFlags
            } else {
                // TODO: Pass keyActionFlags as an argument.
                mActionFlags = ACTION_FLAGS_NO_KEY_PREVIEW
                mPopupKeys = null
                mPopupKeysColumnAndFlags = 0
            }

            mLabel = label
            mOptionalAttributes = if (code == KeyCode.MULTIPLE_CODE_POINTS)
                    OptionalAttributes.newInstance(label, KeyCode.NOT_SPECIFIED, null, 0, 0)
                    else null
            mCode = code
            mEnabled = (code != KeyCode.NOT_SPECIFIED)
            mIconName = null
            mKeyVisualAttributes = null
            mHintIconName = null
        }

        /** constructor for a spacer whose size MUST be determined using setDimensionsFromRelativeSize */
        private constructor(params: KeyboardParams) {
            isSpacer = true // this is only for spacer!
            mKeyboardParams = params

            mCode = KeyCode.NOT_SPECIFIED
            mLabel = null
            mHintLabel = null
            mKeyVisualAttributes = null
            mOptionalAttributes = null
            mIconName = null
            mBackgroundType = BACKGROUND_TYPE_NORMAL
            mActionFlags = ACTION_FLAGS_NO_KEY_PREVIEW
            mPopupKeys = null
            mPopupKeysColumnAndFlags = 0
            mLabelFlags = LABEL_FLAGS_FONT_NORMAL
            mEnabled = true
            mHintIconName = null
        }

        constructor(keyParams: KeyParams) {
            xPos = keyParams.xPos
            yPos = keyParams.yPos
            mWidth = keyParams.mWidth
            mHeight = keyParams.mHeight
            isSpacer = keyParams.isSpacer
            mKeyboardParams = keyParams.mKeyboardParams
            mEnabled = keyParams.mEnabled

            mCode = keyParams.mCode
            mLabel = keyParams.mLabel
            mHintLabel = keyParams.mHintLabel
            mHintIconName = keyParams.mHintIconName
            mLabelFlags = keyParams.mLabelFlags
            mIconName = keyParams.mIconName
            mAbsoluteWidth = keyParams.mAbsoluteWidth
            mAbsoluteHeight = keyParams.mAbsoluteHeight
            mPopupKeys = keyParams.mPopupKeys
            mPopupKeysColumnAndFlags = keyParams.mPopupKeysColumnAndFlags
            mBackgroundType = keyParams.mBackgroundType
            mActionFlags = keyParams.mActionFlags
            mKeyVisualAttributes = keyParams.mKeyVisualAttributes
            mOptionalAttributes = keyParams.mOptionalAttributes
        }

        companion object {
            @JvmStatic
            fun newSpacer(params: KeyboardParams, width: Float): KeyParams {
                val spacer = KeyParams(params)
                spacer.mWidth = width
                spacer.mHeight = params.mDefaultRowHeight
                return spacer
            }

            private fun getPopupKeysColumnAndFlagsAndSetNullInArray(params: KeyboardParams, popupKeys: Array<out String?>?): Int {
                // Get maximum column order number and set a relevant mode value.
                var popupKeysColumnAndFlags = POPUP_KEYS_MODE_MAX_COLUMN_WITH_AUTO_ORDER or params.mMaxPopupKeysKeyboardColumn
                var value: Int
                value = PopupKeySpec.getIntValue(popupKeys, POPUP_KEYS_AUTO_COLUMN_ORDER, -1)
                if (value > 0) {
                    // Override with fixed column order number and set a relevant mode value.
                    popupKeysColumnAndFlags = POPUP_KEYS_MODE_FIXED_COLUMN_WITH_AUTO_ORDER or (value and POPUP_KEYS_COLUMN_NUMBER_MASK)
                }
                value = PopupKeySpec.getIntValue(popupKeys, POPUP_KEYS_FIXED_COLUMN_ORDER, -1)
                if (value > 0) {
                    // Override with fixed column order number and set a relevant mode value.
                    popupKeysColumnAndFlags = POPUP_KEYS_MODE_FIXED_COLUMN_WITH_FIXED_ORDER or (value and POPUP_KEYS_COLUMN_NUMBER_MASK)
                }
                if (PopupKeySpec.getBooleanValue(popupKeys, POPUP_KEYS_HAS_LABELS)) {
                    popupKeysColumnAndFlags = popupKeysColumnAndFlags or POPUP_KEYS_FLAGS_HAS_LABELS
                }
                if (PopupKeySpec.getBooleanValue(popupKeys, POPUP_KEYS_NEEDS_DIVIDERS)) {
                    popupKeysColumnAndFlags = popupKeysColumnAndFlags or POPUP_KEYS_FLAGS_NEEDS_DIVIDERS
                }
                if (PopupKeySpec.getBooleanValue(popupKeys, POPUP_KEYS_NO_PANEL_AUTO_POPUP_KEY)) {
                    popupKeysColumnAndFlags = popupKeysColumnAndFlags or POPUP_KEYS_FLAGS_NO_PANEL_AUTO_POPUP_KEY
                }
                return popupKeysColumnAndFlags
            }
        }
    }

    companion object {
        const val LABEL_FLAGS_ALIGN_HINT_LABEL_TO_BOTTOM = 0x02
        const val LABEL_FLAGS_ALIGN_ICON_TO_BOTTOM = 0x04
        const val LABEL_FLAGS_ALIGN_LABEL_OFF_CENTER = 0x08
        // Font typeface specification.
        private const val LABEL_FLAGS_FONT_MASK = 0x30
        const val LABEL_FLAGS_FONT_NORMAL = 0x10
        const val LABEL_FLAGS_FONT_MONO_SPACE = 0x20
        const val LABEL_FLAGS_FONT_DEFAULT = 0x30
        // Start of key text ratio enum values
        private const val LABEL_FLAGS_FOLLOW_KEY_TEXT_RATIO_MASK = 0x1C0
        const val LABEL_FLAGS_FOLLOW_KEY_LARGE_LETTER_RATIO = 0x40
        const val LABEL_FLAGS_FOLLOW_KEY_LETTER_RATIO = 0x80
        const val LABEL_FLAGS_FOLLOW_KEY_LABEL_RATIO = 0xC0
        const val LABEL_FLAGS_FOLLOW_KEY_HINT_LABEL_RATIO = 0x140
        // End of key text ratio mask enum values
        const val LABEL_FLAGS_HAS_SHIFTED_LETTER_HINT = 0x400
        const val LABEL_FLAGS_HAS_HINT_LABEL = 0x800
        // The bit to calculate the ratio of key label width against key width. If autoXScale bit is on
        // and autoYScale bit is off, the key label may be shrunk only for X-direction.
        // If both autoXScale and autoYScale bits are on, the key label text size may be auto scaled.
        const val LABEL_FLAGS_AUTO_X_SCALE = 0x4000
        const val LABEL_FLAGS_AUTO_Y_SCALE = 0x8000
        const val LABEL_FLAGS_AUTO_SCALE = (LABEL_FLAGS_AUTO_X_SCALE
                or LABEL_FLAGS_AUTO_Y_SCALE)
        const val LABEL_FLAGS_PRESERVE_CASE = 0x10000
        const val LABEL_FLAGS_SHIFTED_LETTER_ACTIVATED = 0x20000
        const val LABEL_FLAGS_FROM_CUSTOM_ACTION_LABEL = 0x40000
        const val LABEL_FLAGS_FOLLOW_FUNCTIONAL_TEXT_COLOR = 0x80000
        const val LABEL_FLAGS_KEEP_BACKGROUND_ASPECT_RATIO = 0x100000
        const val LABEL_FLAGS_DISABLE_HINT_LABEL = 0x40000000

        private const val POPUP_KEYS_COLUMN_NUMBER_MASK = 0x000000ff
        // If this flag is specified, popup keys keyboard should have the specified number of columns.
        // Otherwise popup keys keyboard should have less than or equal to the specified maximum number
        // of columns.
        private const val POPUP_KEYS_FLAGS_FIXED_COLUMN = 0x00000100
        // If this flag is specified, the order of popup keys is determined by the order in the popup
        // keys' specification. Otherwise the order of popup keys is automatically determined.
        private const val POPUP_KEYS_FLAGS_FIXED_ORDER = 0x00000200
        private const val POPUP_KEYS_MODE_MAX_COLUMN_WITH_AUTO_ORDER = 0
        private const val POPUP_KEYS_MODE_FIXED_COLUMN_WITH_AUTO_ORDER = POPUP_KEYS_FLAGS_FIXED_COLUMN
        private const val POPUP_KEYS_MODE_FIXED_COLUMN_WITH_FIXED_ORDER = (POPUP_KEYS_FLAGS_FIXED_COLUMN or POPUP_KEYS_FLAGS_FIXED_ORDER)
        private const val POPUP_KEYS_FLAGS_HAS_LABELS = 0x40000000
        private const val POPUP_KEYS_FLAGS_NEEDS_DIVIDERS = 0x20000000
        private const val POPUP_KEYS_FLAGS_NO_PANEL_AUTO_POPUP_KEY = 0x10000000
        // TODO: Rename these specifiers to !autoOrder! and !fixedOrder! respectively.
        const val POPUP_KEYS_AUTO_COLUMN_ORDER = "!autoColumnOrder!"
        const val POPUP_KEYS_FIXED_COLUMN_ORDER = "!fixedColumnOrder!"
        const val POPUP_KEYS_HAS_LABELS = "!hasLabels!"
        private const val POPUP_KEYS_NEEDS_DIVIDERS = "!needsDividers!"
        private const val POPUP_KEYS_NO_PANEL_AUTO_POPUP_KEY = "!noPanelAutoPopupKey!"

        const val BACKGROUND_TYPE_EMPTY = 0
        const val BACKGROUND_TYPE_NORMAL = 1
        const val BACKGROUND_TYPE_FUNCTIONAL = 2
        const val BACKGROUND_TYPE_ACTION = 3
        const val BACKGROUND_TYPE_SPACEBAR = 4

        private const val ACTION_FLAGS_IS_REPEATABLE = 0x01
        private const val ACTION_FLAGS_NO_KEY_PREVIEW = 0x02
        private const val ACTION_FLAGS_ALT_CODE_WHILE_TYPING = 0x04
        private const val ACTION_FLAGS_ENABLE_LONG_PRESS = 0x08

        @JvmStatic
        fun removeRedundantPopupKeys(key: Key,
                lettersOnBaseLayout: PopupKeySpec.LettersOnBaseLayout): Key {
            if ((key.mPopupKeysColumnAndFlags and POPUP_KEYS_FLAGS_FIXED_COLUMN) != 0)
                return key // don't remove anything for fixed column popup keys
            val popupKeys = key.getPopupKeys()
            val filteredPopupKeys = PopupKeySpec.removeRedundantPopupKeys(
                    popupKeys, lettersOnBaseLayout)
            return if (filteredPopupKeys === popupKeys) key else Key(key, filteredPopupKeys)
        }

        private fun needsToUpcase(labelFlags: Int, keyboardElement: KeyboardElement): Boolean {
            if ((labelFlags and LABEL_FLAGS_PRESERVE_CASE) != 0) return false
            return when (keyboardElement) {
                KeyboardElement.ALPHABET_MANUAL_SHIFTED, KeyboardElement.ALPHABET_AUTOMATIC_SHIFTED, KeyboardElement.ALPHABET_SHIFT_LOCKED -> true
                else -> false
            }
        }

        private fun computeHashCode(key: Key): Int {
            return Arrays.hashCode(arrayOf<Any?>(
                    key.mX,
                    key.mY,
                    key.mWidth,
                    key.mHeight,
                    key.mCode,
                    key.mLabel,
                    key.mHintLabel,
                    key.mHintIconName,
                    key.mIconName,
                    key.mBackgroundType,
                    Arrays.hashCode(key.mPopupKeys),
                    key.getOutputText(),
                    key.mActionFlags,
                    key.mLabelFlags,
                    // Key can be distinguishable without the following members.
                    // key.mOptionalAttributes.mAltCode,
                    // key.mOptionalAttributes.mDisabledIconId,
                    // key.mOptionalAttributes.mPreviewIconId,
                    // key.mHorizontalGap,
                    // key.mVerticalGap,
                    // key.mOptionalAttributes.mVisualInsetLeft,
                    // key.mOptionalAttributes.mVisualInsetRight,
                    // key.mMaxPopupKeysColumn,
            ))
        }

        private fun backgroundName(backgroundType: Int): String? {
            return when (backgroundType) {
                BACKGROUND_TYPE_EMPTY -> "empty"
                BACKGROUND_TYPE_NORMAL -> "normal"
                BACKGROUND_TYPE_FUNCTIONAL -> "functional"
                BACKGROUND_TYPE_ACTION -> "action"
                BACKGROUND_TYPE_SPACEBAR -> "spacebar"
                else -> null
            }
        }

        private fun getDisabledIconName(iconName: String): String? {
            if (iconName == toolbarKeyStrings[ToolbarKey.VOICE])
                return KeyboardIconsSet.NAME_SHORTCUT_KEY_DISABLED
            return null
        }
    }
}
