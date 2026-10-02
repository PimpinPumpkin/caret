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
    val code: Int

    /** Label to display */
    val label: String?
    /** Hint label to display on the key in conjunction with the label */
    val hintLabel: String?
    private val hintIconName: String?
    /** Flags of the label */
    private val labelFlags: Int

    /** Icon to display instead of a label. Icon takes precedence over a label */
    val iconName: String?

    /**
     * Width of the key, excluding the gap
     *
     * Gets the width of the key in pixels, excluding the gap.
     * @return The width of the key in pixels, excluding the gap.
     */
    val width: Int
    /**
     * Height of the key, excluding the gap
     *
     * Gets the height of the key in pixels, excluding the gap.
     * @return The height of the key in pixels, excluding the gap.
     */
    val height: Int
    /**
     * The combined width in pixels of the horizontal gaps belonging to this key, both to the left
     * and to the right. I.e., width + horizontalGap = total width belonging to the key.
     *
     * The combined width in pixels of the horizontal gaps belonging to this key, both above and
     * below. I.e., getWidth() + getHorizontalGap() = total width belonging to the key.
     * @return Horizontal gap belonging to this key.
     */
    val horizontalGap: Int
    /**
     * The combined height in pixels of the vertical gaps belonging to this key, both above and
     * below. I.e., height + verticalGap = total height belonging to the key.
     *
     * The combined height in pixels of the vertical gaps belonging to this key, both above and
     * below. I.e., getHeight() + getVerticalGap() = total height belonging to the key.
     * @return Vertical gap belonging to this key.
     */
    val verticalGap: Int
    /** X coordinate of the top-left corner of the key in the keyboard layout, excluding the gap. */
    private val x: Int
    /** Y coordinate of the top-left corner of the key in the keyboard layout, excluding the gap. */
    private val y: Int
    /** Hit bounding box of the key */
    val hitBox = Rect()

    /** Popup keys. It is guaranteed that this is null or an array of one or more elements */
    val popupKeys: Array<PopupKeySpec>?
    /** Popup keys column number and flags */
    private val popupKeysColumnAndFlags: Int

    /**
     * Background type that represents different key background visual than normal one.
     *
     * Gets the background type of this key.
     * @return Background type.
     * @see Key#BACKGROUND_TYPE_EMPTY
     * @see Key#BACKGROUND_TYPE_NORMAL
     * @see Key#BACKGROUND_TYPE_FUNCTIONAL
     * @see Key#BACKGROUND_TYPE_ACTION
     * @see Key#BACKGROUND_TYPE_SPACEBAR
     */
    val backgroundType: Int

    private val actionFlags: Int

    val visualAttributes: KeyVisualAttributes?
    private val optionalAttributes: OptionalAttributes?

    class OptionalAttributes private constructor(
        /** Text to output when pressed. This can be multiple characters, like ".com" */
        @JvmField val outputText: String?,
        @JvmField val altCode: Int,
        /** Icon for disabled state */
        @JvmField val disabledIconName: String?,
        /** The visual insets */
        @JvmField val visualInsetsLeft: Int,
        @JvmField val visualInsetsRight: Int
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

    private val hashCode: Int

    /** The current pressed state of this key */
    private var pressed = false
    /** Key is enabled and responds on press */
    var isEnabled: Boolean
    /** Key is locked (appears permanently pressed) */
    private var locked = false

    /**
     * Constructor for a key on <code>PopupKeyKeyboard</code> and on <code>MoreSuggestions</code>.
     */
    constructor(label: String?, iconName: String?, code: Int,
            outputText: String?, hintLabel: String?,
            labelFlags: Int, backgroundType: Int, x: Int, y: Int,
            width: Int, height: Int, horizontalGap: Int, verticalGap: Int) {
        this.width = width - horizontalGap
        this.height = height - verticalGap
        this.horizontalGap = horizontalGap
        this.verticalGap = verticalGap
        this.hintLabel = hintLabel
        hintIconName = null
        this.labelFlags = labelFlags
        this.backgroundType = backgroundType
        // TODO: Pass keyActionFlags as an argument.
        actionFlags = ACTION_FLAGS_NO_KEY_PREVIEW
        popupKeys = null
        popupKeysColumnAndFlags = 0
        this.label = label
        this.code = code
        isEnabled = (code != KeyCode.NOT_SPECIFIED)
        this.iconName = iconName
        optionalAttributes = OptionalAttributes.newInstance(outputText, KeyCode.NOT_SPECIFIED,
                if (iconName == null) null else getDisabledIconName(iconName), 0, 0)
        // Horizontal gap is divided equally to both sides of the key.
        this.x = x + horizontalGap / 2
        this.y = y
        hitBox.set(x, y, x + width + 1, y + height)
        visualAttributes = null

        hashCode = computeHashCode(this)
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
        code = key.code
        label = key.label
        hintLabel = labelHint
        hintIconName = key.hintIconName
        labelFlags = key.labelFlags
        iconName = key.iconName
        width = key.width
        height = key.height
        horizontalGap = key.horizontalGap
        verticalGap = key.verticalGap
        x = key.x
        y = key.y
        hitBox.set(key.hitBox)
        this.popupKeys = popupKeys
        popupKeysColumnAndFlags = key.popupKeysColumnAndFlags
        this.backgroundType = backgroundType
        actionFlags = key.actionFlags
        visualAttributes = key.visualAttributes
        optionalAttributes = key.optionalAttributes
        hashCode = key.hashCode
        // Key state.
        pressed = key.pressed
        isEnabled = key.isEnabled
    }

    /** constructor for creating emoji recent keys when there is no keyboard to take keys from */
    constructor(key: Key, popupKeys: Array<PopupKeySpec>?,
             labelHint: String?, backgroundType: Int, code: Int, outputText: String?) {
        // Final attributes.
        this.code = if (outputText == null) code else KeyCode.MULTIPLE_CODE_POINTS
        label = if (outputText == null) StringUtils.newSingleCodePointString(code) else outputText
        hintLabel = labelHint
        hintIconName = key.hintIconName
        labelFlags = key.labelFlags
        iconName = key.iconName
        width = key.width
        height = key.height
        horizontalGap = key.horizontalGap
        verticalGap = key.verticalGap
        x = key.x
        y = key.y
        hitBox.set(key.hitBox)
        this.popupKeys = popupKeys
        popupKeysColumnAndFlags = key.popupKeysColumnAndFlags
        this.backgroundType = backgroundType
        actionFlags = key.actionFlags
        visualAttributes = key.visualAttributes
        optionalAttributes = if (outputText == null) null
                else OptionalAttributes.newInstance(outputText, KeyCode.NOT_SPECIFIED, null, 0, 0)
        hashCode = key.hashCode
        // Key state.
        pressed = key.pressed
        isEnabled = key.isEnabled
    }

    /** constructor from KeyParams */
    private constructor(keyParams: KeyParams) {
        // stuff to copy
        code = keyParams.code
        label = keyParams.label
        hintLabel = keyParams.hintLabel
        hintIconName = keyParams.hintIconName
        labelFlags = keyParams.labelFlags
        iconName = keyParams.iconName
        popupKeys = keyParams.popupKeys
        popupKeysColumnAndFlags = keyParams.popupKeysColumnAndFlags
        backgroundType = keyParams.backgroundType
        actionFlags = keyParams.actionFlags
        visualAttributes = keyParams.keyVisualAttributes
        optionalAttributes = keyParams.optionalAttributes
        isEnabled = keyParams.enabled

        // stuff to create

        // get the "correct" float gap: may shift keys by one pixel, but results in more uniform gaps between keys
        val horizontalGapFloat = if (isSpacer) 0f else (keyParams.keyboardParams.relativeHorizontalGap * keyParams.keyboardParams.occupiedWidth)
        horizontalGap = Math.round(horizontalGapFloat)
        verticalGap = Math.round(keyParams.keyboardParams.relativeVerticalGap * keyParams.keyboardParams.occupiedHeight)
        width = Math.round(keyParams.absoluteWidth - horizontalGapFloat)
        // height is always rounded down, because rounding up may make the keyboard too high to fit, leading to issues
        height = (keyParams.absoluteHeight - keyParams.keyboardParams.verticalGap).toInt()
        if (!isSpacer && (width == 0 || height == 0)) {
            throw IllegalStateException("key needs positive width and height")
        }
        // Horizontal gap is divided equally to both sides of the key.
        x = Math.round(keyParams.xPos + horizontalGapFloat / 2)
        y = Math.round(keyParams.yPos)
        hitBox.set(Math.round(keyParams.xPos), Math.round(keyParams.yPos), Math.round(keyParams.xPos + keyParams.absoluteWidth) + 1,
                Math.round(keyParams.yPos + keyParams.absoluteHeight))
        hashCode = computeHashCode(this)
    }

    private constructor(key: Key, popupKeys: Array<PopupKeySpec>?) {
        // Final attributes.
        code = key.code
        label = key.label
        val labelAndIcon = findPopupHintLabelOrIcon(popupKeys, key.hintLabel, key.hintIconName)
        hintLabel = labelAndIcon.component1()
        hintIconName = labelAndIcon.component2()
        labelFlags = key.labelFlags
        iconName = key.iconName
        width = key.width
        height = key.height
        horizontalGap = key.horizontalGap
        verticalGap = key.verticalGap
        x = key.x
        y = key.y
        hitBox.set(key.hitBox)
        this.popupKeys = popupKeys
        popupKeysColumnAndFlags = key.popupKeysColumnAndFlags
        backgroundType = key.backgroundType
        if (popupKeys == null && code > Constants.CODE_SPACE && (key.actionFlags and ACTION_FLAGS_ENABLE_LONG_PRESS) != 0)
            actionFlags = key.actionFlags - ACTION_FLAGS_ENABLE_LONG_PRESS
        else
            actionFlags = key.actionFlags
        visualAttributes = key.visualAttributes
        optionalAttributes = key.optionalAttributes
        hashCode = key.hashCode
        // Key state.
        pressed = key.pressed
        isEnabled = key.isEnabled
    }

    private fun equalsInternal(o: Key): Boolean {
        if (this === o) return true
        return o.x == x
                && o.y == y
                && o.width == width
                && o.height == height
                && o.code == code
                && TextUtils.equals(o.label, label)
                && TextUtils.equals(o.hintLabel, hintLabel)
                && TextUtils.equals(o.hintIconName, hintIconName)
                && TextUtils.equals(o.iconName, iconName)
                && o.backgroundType == backgroundType
                && Arrays.equals(o.popupKeys, popupKeys)
                && TextUtils.equals(o.outputText, outputText)
                && o.actionFlags == actionFlags
                && o.labelFlags == labelFlags
    }

    override fun compareTo(o: Key): Int {
        if (equalsInternal(o)) return 0
        if (hashCode > o.hashCode) return 1
        return -1
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun equals(o: Any?): Boolean {
        return o is Key && equalsInternal(o)
    }

    override fun toString(): String {
        return toShortString() + " " + getX() + "," + getY() + " " + width + "x" + height
    }

    fun toShortString(): String? {
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            return outputText
        }
        return Constants.printableCode(code)
    }

    fun toLongString(): String {
        val topVisual = if (iconName != null)
                KeyboardIconsSet.PREFIX_ICON + iconName else label
        val visual = if (hintLabel == null) topVisual else topVisual + "^" + hintLabel
        return toString() + " " + visual + "/" + backgroundName(backgroundType)
    }

    fun markAsLeftEdge(params: KeyboardParams) {
        hitBox.left = params.leftPadding
    }

    fun markAsRightEdge(params: KeyboardParams) {
        hitBox.right = params.occupiedWidth - params.rightPadding
    }

    fun markAsTopEdge(params: KeyboardParams) {
        hitBox.top = params.topPadding
    }

    fun markAsBottomEdge(params: KeyboardParams) {
        hitBox.bottom = params.occupiedHeight + params.bottomPadding
    }

    val isSpacer: Boolean
        get() = this is Spacer

    fun hasActionKeyBackground(): Boolean {
        return backgroundType == BACKGROUND_TYPE_ACTION
    }

    val isShift: Boolean
        get() = code == KeyCode.SHIFT

    fun isModifier(): Boolean {
        return code.isModifier()
    }

    val isRepeatable: Boolean
        get() = (actionFlags and ACTION_FLAGS_IS_REPEATABLE) != 0

    fun hasPreview(): Boolean {
        return (actionFlags and ACTION_FLAGS_NO_KEY_PREVIEW) == 0
    }

    /**
     *  altCodeWhileTyping is a weird thing.
     *  When user pressed a typing key less than ignoreAltCodeKeyTimeout (config_ignore_alt_code_key_timeout / 350 ms) ago,
     *  this code will be used instead. There is no documentation, but it appears the purpose is to avoid unintentional layout switches.
     *  Assuming this is true, the key still is used now if pressed near the center, where we assume it's less likely to be accidental.
     *  See PointerTracker.isClearlyInsideKey
     */
    fun altCodeWhileTyping(): Boolean {
        return (actionFlags and ACTION_FLAGS_ALT_CODE_WHILE_TYPING) != 0
    }

    val isLongPressEnabled: Boolean
        // We need not start long press timer on the key which has activated shifted letter.
        get() = (actionFlags and ACTION_FLAGS_ENABLE_LONG_PRESS) != 0
                && (labelFlags and LABEL_FLAGS_SHIFTED_LETTER_ACTIVATED) == 0

    fun selectTypeface(params: KeyDrawParams): Typeface {
        return when (labelFlags and LABEL_FLAGS_FONT_MASK) {
            LABEL_FLAGS_FONT_NORMAL -> Typeface.DEFAULT
            LABEL_FLAGS_FONT_MONO_SPACE -> Typeface.MONOSPACE
            else -> params.typeface // The type-face is specified by keyTypeface attribute.
        }
    }

    fun selectTextSize(params: KeyDrawParams): Int {
        return when (labelFlags and LABEL_FLAGS_FOLLOW_KEY_TEXT_RATIO_MASK) {
            LABEL_FLAGS_FOLLOW_KEY_LETTER_RATIO -> params.letterSize
            LABEL_FLAGS_FOLLOW_KEY_LARGE_LETTER_RATIO -> params.largeLetterSize
            LABEL_FLAGS_FOLLOW_KEY_LABEL_RATIO -> params.labelSize
            LABEL_FLAGS_FOLLOW_KEY_HINT_LABEL_RATIO -> params.hintLabelSize
            // No follow key ratio flag specified.
            else -> if (StringUtils.codePointCount(label) == 1) params.letterSize else params.labelSize
        }
    }

    fun selectTextColor(params: KeyDrawParams): Int {
        if ((labelFlags and LABEL_FLAGS_FOLLOW_FUNCTIONAL_TEXT_COLOR) != 0) {
            return params.functionalTextColor
        }
        return if (isShiftedLetterActivated) params.textInactivatedColor else params.textColor
    }

    fun selectHintTextSize(params: KeyDrawParams): Int {
        if (hasHintLabel()) {
            return params.hintLabelSize
        }
        if (hasShiftedLetterHint()) {
            return params.shiftedLetterHintSize
        }
        return params.hintLetterSize
    }

    fun selectHintTextColor(params: KeyDrawParams): Int {
        if (hasHintLabel()) {
            return params.hintLabelColor
        }
        if (hasShiftedLetterHint()) {
            return if (isShiftedLetterActivated) params.shiftedLetterHintActivatedColor
                    else params.shiftedLetterHintInactivatedColor
        }
        return params.hintLetterColor
    }

    fun selectPopupKeyTextSize(params: KeyDrawParams): Int {
        return if (hasLabelsInPopupKeys()) params.labelSize else params.letterSize
    }

    val previewLabel: String?
        get() = if (isShiftedLetterActivated) hintLabel else label

    private fun previewHasLetterSize(): Boolean {
        return (labelFlags and LABEL_FLAGS_FOLLOW_KEY_LETTER_RATIO) != 0
                || StringUtils.codePointCount(previewLabel) == 1
    }

    fun selectPreviewTextSize(params: KeyDrawParams): Int {
        if (previewHasLetterSize()) {
            return params.previewTextSize
        }
        return params.letterSize
    }

    fun selectPreviewTypeface(params: KeyDrawParams): Typeface {
        if (previewHasLetterSize()) {
            return selectTypeface(params)
        }
        return Typeface.DEFAULT_BOLD
    }

    fun isAlignHintLabelToBottom(defaultFlags: Int): Boolean {
        return ((labelFlags or defaultFlags) and LABEL_FLAGS_ALIGN_HINT_LABEL_TO_BOTTOM) != 0
    }

    val isAlignIconToBottom: Boolean
        get() = (labelFlags and LABEL_FLAGS_ALIGN_ICON_TO_BOTTOM) != 0

    val isAlignLabelOffCenter: Boolean
        get() = (labelFlags and LABEL_FLAGS_ALIGN_LABEL_OFF_CENTER) != 0

    fun hasShiftedLetterHint(): Boolean {
        return (labelFlags and LABEL_FLAGS_HAS_SHIFTED_LETTER_HINT) != 0
                && !TextUtils.isEmpty(hintLabel)
    }

    fun hasShiftedLetterHintIcon(): Boolean {
        return (labelFlags and LABEL_FLAGS_HAS_SHIFTED_LETTER_HINT) != 0
            && !TextUtils.isEmpty(hintIconName)
    }

    fun hasHintLabel(): Boolean {
        return (labelFlags and LABEL_FLAGS_HAS_HINT_LABEL) != 0
    }

    fun needsAutoXScale(): Boolean {
        return (labelFlags and LABEL_FLAGS_AUTO_X_SCALE) != 0
    }

    fun needsAutoScale(): Boolean {
        return (labelFlags and LABEL_FLAGS_AUTO_SCALE) == LABEL_FLAGS_AUTO_SCALE
    }

    fun needsToKeepBackgroundAspectRatio(defaultFlags: Int): Boolean {
        return ((labelFlags or defaultFlags) and LABEL_FLAGS_KEEP_BACKGROUND_ASPECT_RATIO) != 0
    }

    fun hasCustomActionLabel(): Boolean {
        return (labelFlags and LABEL_FLAGS_FROM_CUSTOM_ACTION_LABEL) != 0
    }

    private val isShiftedLetterActivated: Boolean
        get() = (labelFlags and LABEL_FLAGS_SHIFTED_LETTER_ACTIVATED) != 0
                && !TextUtils.isEmpty(hintLabel)

    val popupKeysColumnNumber: Int
        get() = popupKeysColumnAndFlags and POPUP_KEYS_COLUMN_NUMBER_MASK

    val isPopupKeysFixedColumn: Boolean
        get() = (popupKeysColumnAndFlags and POPUP_KEYS_FLAGS_FIXED_COLUMN) != 0

    val isPopupKeysFixedOrder: Boolean
        get() = (popupKeysColumnAndFlags and POPUP_KEYS_FLAGS_FIXED_ORDER) != 0

    fun hasLabelsInPopupKeys(): Boolean {
        return (popupKeysColumnAndFlags and POPUP_KEYS_FLAGS_HAS_LABELS) != 0
    }

    fun getPopupKeyLabelFlags(): Int {
        val labelSizeFlag = if (hasLabelsInPopupKeys())
                LABEL_FLAGS_FOLLOW_KEY_LABEL_RATIO
                else LABEL_FLAGS_FOLLOW_KEY_LETTER_RATIO
        return labelSizeFlag or LABEL_FLAGS_AUTO_X_SCALE
    }

    fun needsDividersInPopupKeys(): Boolean {
        return (popupKeysColumnAndFlags and POPUP_KEYS_FLAGS_NEEDS_DIVIDERS) != 0
    }

    fun hasNoPanelAutoPopupKey(): Boolean {
        return (popupKeysColumnAndFlags and POPUP_KEYS_FLAGS_NO_PANEL_AUTO_POPUP_KEY) != 0
    }

    val outputText: String?
        get() = optionalAttributes?.outputText

    val altCode: Int
        get() = optionalAttributes?.altCode ?: KeyCode.NOT_SPECIFIED

    open fun getIcon(iconSet: KeyboardIconsSet, alpha: Int): Drawable? {
        val attrs = optionalAttributes
        val name = if (isEnabled) iconName else (if (attrs != null) attrs.disabledIconName else null)
        val icon = iconSet.getIconDrawable(name)
        if (icon != null) {
            icon.setAlpha(alpha)
        }
        return icon
    }

    fun getHintIcon(iconSet: KeyboardIconsSet, alpha: Int): Drawable? {
        val attrs = optionalAttributes
        val name = if (isEnabled) hintIconName else (if (attrs != null) attrs.disabledIconName else null)
        val icon = iconSet.getIconDrawable(name)
        if (icon != null) {
            icon.setAlpha(alpha)
        }
        return icon
    }

    fun getPreviewIcon(iconSet: KeyboardIconsSet): Drawable? {
        return iconSet.getIconDrawable(iconName)
    }

    /**
     * Gets the x-coordinate of the top-left corner of the key in pixels, excluding the gap.
     * @return The x-coordinate of the top-left corner of the key in pixels, excluding the gap.
     */
    open fun getX(): Int {
        return x
    }

    /**
     * Gets the y-coordinate of the top-left corner of the key in pixels, excluding the gap.
     * @return The y-coordinate of the top-left corner of the key in pixels, excluding the gap.
     */
    open fun getY(): Int {
        return y
    }

    fun getDrawX(): Int {
        val left = getX()
        val attrs = optionalAttributes
        return if (attrs == null) left else left + attrs.visualInsetsLeft
    }

    fun getDrawWidth(): Int {
        val attrs = optionalAttributes
        return if (attrs == null) width
                else width - attrs.visualInsetsLeft - attrs.visualInsetsRight
    }

    /**
     * Informs the key that it has been pressed, in case it needs to change its appearance or
     * state.
     * @see #onReleased()
     */
    fun onPressed() {
        pressed = true
    }

    /**
     * Informs the key that it has been released, in case it needs to change its appearance or
     * state.
     * @see #onPressed()
     */
    fun onReleased() {
        pressed = false
    }

    fun setLocked(locked: Boolean) {
        this.locked = locked
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
        return hitBox.contains(x, y)
    }

    /**
     * Returns the square of the distance to the nearest edge of the key and the given point.
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the square of the distance of the point from the nearest edge of the key
     */
    fun squaredDistanceToEdge(x: Int, y: Int): Int {
        val left = getX()
        val right = left + width
        val top = getY()
        val bottom = top + height
        val edgeX = if (x < left) left else Math.min(x, right)
        val edgeY = if (y < top) top else Math.min(y, bottom)
        val dx = x - edgeX
        val dy = y - edgeY
        return dx * dx + dy * dy
    }

    class KeyBackgroundState private constructor(vararg attrs: Int) {
        private val releasedState: IntArray
        private val pressedState: IntArray

        init {
            releasedState = attrs
            pressedState = Arrays.copyOf(attrs, attrs.size + 1)
            pressedState[attrs.size] = android.R.attr.state_pressed
        }

        fun getState(pressed: Boolean): IntArray {
            return if (pressed) pressedState else releasedState
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
        } else if (backgroundType == BACKGROUND_TYPE_SPACEBAR) {
            background = spacebarBackground
        } else {
            background = keyBackground
        }
        val state = KeyBackgroundState.STATES[backgroundType].getState(pressed || locked)
        background.setState(state)
        return background
    }

    fun hasActionKeyPopups(): Boolean {
        if (!hasActionKeyBackground()) return false
        // only use the special action key popups for action colored keys, and only for icon popups
        return popupKeys!!.none { key -> key.iconName == null }
    }

    fun hasFunctionalBackground(): Boolean {
        return backgroundType == BACKGROUND_TYPE_FUNCTIONAL
    }

    open class Spacer : Key {
        constructor(keyParams: KeyParams) : super(keyParams)

        /**
         * This constructor is being used only for divider in popup keys keyboard.
         */
        protected constructor(params: KeyboardParams, x: Int, y: Int, width: Int,
                height: Int) : super(null, null, KeyCode.NOT_SPECIFIED, null,
                    null, 0, BACKGROUND_TYPE_EMPTY, x, y, width,
                    height, params.horizontalGap, params.verticalGap)
    }

    // for creating keys that might get modified later
    class KeyParams {
        // params for building
        @JvmField var isSpacer = false
        @JvmField val keyboardParams: KeyboardParams // for reading gaps and keyboard width / height
        @JvmField var width = 0f
        @JvmField var height = 0f // also should allow negative values, indicating absolute height is defined

        // params that may change
        @JvmField var absoluteWidth = 0f
        @JvmField var absoluteHeight = 0f
        @JvmField var xPos = 0f
        @JvmField var yPos = 0f

        // params that remains constant
        @JvmField val code: Int
        @JvmField val label: String?
        @JvmField val hintLabel: String?
        @JvmField val hintIconName: String?
        @JvmField val labelFlags: Int
        @JvmField val iconName: String?
        @JvmField val popupKeys: Array<PopupKeySpec>?
        @JvmField val popupKeysColumnAndFlags: Int
        @JvmField val backgroundType: Int
        @JvmField val actionFlags: Int
        @JvmField val keyVisualAttributes: KeyVisualAttributes?
        @JvmField val optionalAttributes: OptionalAttributes?
        @JvmField val enabled: Boolean

        fun createKey(): Key {
            if (isSpacer) return Spacer(this)
            return Key(this)
        }

        fun setAbsoluteDimensions(newX: Float, newY: Float) {
            if (height == 0f)
                height = keyboardParams.defaultRowHeight
            if (!isSpacer && width == 0f)
                throw IllegalStateException("width = 0 should have been evaluated already")
            if (height < 0)
                // todo (later): deal with it properly when it needs to be adjusted, i.e. when changing popupKeys or moreSuggestions
                throw IllegalStateException("can't (yet) deal with absolute height")
            xPos = newX
            yPos = newY
            absoluteWidth = width * keyboardParams.baseWidth
            absoluteHeight = height * keyboardParams.baseHeight
        }

        val outputText: String?
            get() = if (optionalAttributes == null) null else optionalAttributes.outputText

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
            keyboardParams = params
            this.backgroundType = backgroundType
            this.labelFlags = labelFlags
            this.width = width
            height = params.defaultRowHeight
            iconName = KeySpecParser.getIconName(keySpec)

            val needsToUpcase = Key.needsToUpcase(labelFlags, params.id.element)
            val localeForUpcasing = params.id.locale
            var flags = 0
            if (params.id.element.isNumberLayout)
                flags = ACTION_FLAGS_NO_KEY_PREVIEW

            // label
            var keySpecLabel: String? = null
            if ((labelFlags and LABEL_FLAGS_FROM_CUSTOM_ACTION_LABEL) != 0) {
                label = params.id.customActionLabel
            } else if (code >= Character.MIN_SUPPLEMENTARY_CODE_POINT) {
                // This is a workaround to have a key that has a supplementary code point in its label.
                // Because we can put a string in resource neither as a XML entity of a supplementary
                // code point nor as a surrogate pair.
                label = StringBuilder().appendCodePoint(code).toString()
            } else {
                keySpecLabel = KeySpecParser.getLabel(keySpec)
                label = if (needsToUpcase)
                        StringUtils.toTitleCaseOfKeyLabel(keySpecLabel, localeForUpcasing)
                        else keySpecLabel
            }

            // popupKeys
            // the array is declared with nullable elements because reading the column and flags sets entries to null
            @Suppress("UNCHECKED_CAST")
            val popupKeyStrings = createPopupKeysArray(popupSet, keyboardParams, if (keySpecLabel != null) keySpecLabel else keySpec) as Array<String?>?
            popupKeysColumnAndFlags = getPopupKeysColumnAndFlagsAndSetNullInArray(params, popupKeyStrings)
            val finalPopupKeys = if (popupKeyStrings == null) null else PopupKeySpec.filterOutEmptyString(popupKeyStrings)
            if (finalPopupKeys != null && finalPopupKeys.size > 0) {
                val tempPopupKeys = arrayOfNulls<PopupKeySpec>(finalPopupKeys.size)
                var hasRepeatPopup = false
                for (i in finalPopupKeys.indices) {
                    val popupKeySpec = PopupKeySpec(finalPopupKeys[i], needsToUpcase, localeForUpcasing)
                    tempPopupKeys[i] = popupKeySpec
                    if (popupKeySpec.code == KeyCode.KEY_REPEAT)
                        hasRepeatPopup = true
                }
                if (hasRepeatPopup) {
                    popupKeys = null
                    flags = flags or ACTION_FLAGS_IS_REPEATABLE
                } else {
                    @Suppress("UNCHECKED_CAST")
                    popupKeys = tempPopupKeys as Array<PopupKeySpec>
                    flags = flags or ACTION_FLAGS_ENABLE_LONG_PRESS
                }
            } else {
                popupKeys = null
            }

            // hint label
            if ((labelFlags and LABEL_FLAGS_DISABLE_HINT_LABEL) != 0) {
                hintLabel = null
                hintIconName = null
            } else {
                // maybe also always null for comma and period keys
                val rawHintLabel = getHintLabel(popupSet, params, keySpec)
                hintLabel = if (needsToUpcase)
                        StringUtils.toTitleCaseOfKeyLabel(rawHintLabel, localeForUpcasing)
                        else rawHintLabel
                hintIconName = if (hintLabel != null) null else getHintIcon(popupSet, params, keySpec)
            }

            var outputText: String? = KeySpecParser.getOutputText(keySpec, code)
            if (needsToUpcase) {
                outputText = StringUtils.toTitleCaseOfKeyLabel(outputText, localeForUpcasing)
            }
            // Choose the first letter of the label as primary code if not specified.
            if (code == KeyCode.NOT_SPECIFIED && TextUtils.isEmpty(outputText) && !label.isNullOrEmpty()) {
                if (StringUtils.codePointCount(label) == 1) {
                    // Use the first letter of the hint label if shiftedLetterActivated flag is
                    // specified.
                    if ((labelFlags and LABEL_FLAGS_HAS_SHIFTED_LETTER_HINT) != 0 && (labelFlags and LABEL_FLAGS_SHIFTED_LETTER_ACTIVATED) != 0
                            && !hintLabel.isNullOrEmpty()) {
                        this.code = hintLabel.codePointAt(0)
                    } else {
                        this.code = label.codePointAt(0)
                    }
                } else {
                    // In some locale and case, the character might be represented by multiple code
                    // points, such as upper case Eszett of German alphabet.
                    outputText = label
                    this.code = KeyCode.MULTIPLE_CODE_POINTS
                }
            } else if (code == KeyCode.NOT_SPECIFIED && outputText != null) {
                if (StringUtils.codePointCount(outputText) == 1) {
                    this.code = outputText.codePointAt(0)
                    outputText = null
                } else {
                    this.code = KeyCode.MULTIPLE_CODE_POINTS
                }
            } else {
                this.code = if (needsToUpcase) StringUtils.toTitleCaseOfKeyCode(code, localeForUpcasing) else code
            }

            // action flags don't need to be specified, they can be deduced from the key
            if (this.code == Constants.CODE_SPACE
                    || this.code == KeyCode.LANGUAGE_SWITCH
                    || (this.code == KeyCode.SYMBOL_ALPHA && !params.id.element.isAlphabet)
            )
                flags = flags or ACTION_FLAGS_ENABLE_LONG_PRESS
            if (this.code <= Constants.CODE_SPACE && this.code != KeyCode.MULTIPLE_CODE_POINTS && iconName == null)
                flags = flags or ACTION_FLAGS_NO_KEY_PREVIEW
            when (this.code) {
                KeyCode.DELETE, KeyCode.ARROW_LEFT, KeyCode.ARROW_RIGHT, KeyCode.ARROW_UP, KeyCode.ARROW_DOWN,
                        KeyCode.WORD_LEFT, KeyCode.WORD_RIGHT, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN -> {
                    // repeating is disabled if a key is configured with pop-ups
                    if (popupKeys == null)
                        flags = flags or ACTION_FLAGS_IS_REPEATABLE
                    // fallthrough
                    flags = flags or ACTION_FLAGS_NO_KEY_PREVIEW // no preview even if icon!
                }
                KeyCode.SHIFT, Constants.CODE_ENTER, KeyCode.SHIFT_ENTER, KeyCode.ALPHA, Constants.CODE_SPACE, KeyCode.NUMPAD,
                        KeyCode.DPAD, KeyCode.SYMBOL, KeyCode.SYMBOL_ALPHA, KeyCode.LANGUAGE_SWITCH, KeyCode.EMOJI, KeyCode.CLIPBOARD,
                        KeyCode.MOVE_START_OF_LINE, KeyCode.MOVE_END_OF_LINE, KeyCode.MOVE_START_OF_PAGE, KeyCode.MOVE_END_OF_PAGE,
                        KeyCode.EMOJI_SEARCH ->
                    flags = flags or ACTION_FLAGS_NO_KEY_PREVIEW // no preview even if icon!
            }
            if (this.code == KeyCode.SETTINGS || this.code == KeyCode.LANGUAGE_SWITCH)
                flags = flags or ACTION_FLAGS_ALT_CODE_WHILE_TYPING
            actionFlags = flags

            val altCodeInAttr: Int // settings and language switch keys have alt code space, all others nothing
            if (this.code == KeyCode.SETTINGS || this.code == KeyCode.LANGUAGE_SWITCH || this.code == KeyCode.EMOJI || this.code == KeyCode.CLIPBOARD)
                altCodeInAttr = Constants.CODE_SPACE
            else
                altCodeInAttr = KeyCode.NOT_SPECIFIED
            val altCode = if (needsToUpcase)
                    StringUtils.toTitleCaseOfKeyCode(altCodeInAttr, localeForUpcasing)
                    else altCodeInAttr
            optionalAttributes = OptionalAttributes.newInstance(outputText, altCode,
                    // disabled icon only shortcut / voice key, visual insets can be replaced with spacer
                    if (iconName == null) null else getDisabledIconName(iconName), 0, 0)
            // KeyVisualAttributes for a key essentially are what the theme has, but on a per-key base
            // could be used e.g. for having a color gradient on key color
            keyVisualAttributes = null
            enabled = true
        }

        /** constructor for emoji parser */
        constructor(label: String?, code: Int, hintLabel: String?,
                   popupKeySpecs: String?, labelFlags: Int, params: KeyboardParams) {
            keyboardParams = params
            this.hintLabel = hintLabel
            this.labelFlags = labelFlags
            backgroundType = BACKGROUND_TYPE_EMPTY

            if (popupKeySpecs != null) {
                var popupKeyStrings = PopupKeySpec.splitKeySpecs(popupKeySpecs)
                popupKeysColumnAndFlags = getPopupKeysColumnAndFlagsAndSetNullInArray(params, popupKeyStrings)

                popupKeyStrings = PopupKeySpec.insertAdditionalPopupKeys(popupKeyStrings, null)
                var flags = 0
                if (popupKeyStrings != null) {
                    flags = flags or ACTION_FLAGS_ENABLE_LONG_PRESS
                    val newPopupKeys = arrayOfNulls<PopupKeySpec>(popupKeyStrings.size)
                    for (i in popupKeyStrings.indices) {
                        newPopupKeys[i] = PopupKeySpec(popupKeyStrings[i], false, Locale.getDefault())
                    }
                    @Suppress("UNCHECKED_CAST")
                    popupKeys = newPopupKeys as Array<PopupKeySpec>
                } else {
                    popupKeys = null
                }
                actionFlags = flags
            } else {
                // TODO: Pass keyActionFlags as an argument.
                actionFlags = ACTION_FLAGS_NO_KEY_PREVIEW
                popupKeys = null
                popupKeysColumnAndFlags = 0
            }

            this.label = label
            optionalAttributes = if (code == KeyCode.MULTIPLE_CODE_POINTS)
                    OptionalAttributes.newInstance(label, KeyCode.NOT_SPECIFIED, null, 0, 0)
                    else null
            this.code = code
            enabled = (code != KeyCode.NOT_SPECIFIED)
            iconName = null
            keyVisualAttributes = null
            hintIconName = null
        }

        /** constructor for a spacer whose size MUST be determined using setDimensionsFromRelativeSize */
        private constructor(params: KeyboardParams) {
            isSpacer = true // this is only for spacer!
            keyboardParams = params

            code = KeyCode.NOT_SPECIFIED
            label = null
            hintLabel = null
            keyVisualAttributes = null
            optionalAttributes = null
            iconName = null
            backgroundType = BACKGROUND_TYPE_NORMAL
            actionFlags = ACTION_FLAGS_NO_KEY_PREVIEW
            popupKeys = null
            popupKeysColumnAndFlags = 0
            labelFlags = LABEL_FLAGS_FONT_NORMAL
            enabled = true
            hintIconName = null
        }

        constructor(keyParams: KeyParams) {
            xPos = keyParams.xPos
            yPos = keyParams.yPos
            width = keyParams.width
            height = keyParams.height
            isSpacer = keyParams.isSpacer
            keyboardParams = keyParams.keyboardParams
            enabled = keyParams.enabled

            code = keyParams.code
            label = keyParams.label
            hintLabel = keyParams.hintLabel
            hintIconName = keyParams.hintIconName
            labelFlags = keyParams.labelFlags
            iconName = keyParams.iconName
            absoluteWidth = keyParams.absoluteWidth
            absoluteHeight = keyParams.absoluteHeight
            popupKeys = keyParams.popupKeys
            popupKeysColumnAndFlags = keyParams.popupKeysColumnAndFlags
            backgroundType = keyParams.backgroundType
            actionFlags = keyParams.actionFlags
            keyVisualAttributes = keyParams.keyVisualAttributes
            optionalAttributes = keyParams.optionalAttributes
        }

        companion object {
            @JvmStatic
            fun newSpacer(params: KeyboardParams, width: Float): KeyParams {
                val spacer = KeyParams(params)
                spacer.width = width
                spacer.height = params.defaultRowHeight
                return spacer
            }

            private fun getPopupKeysColumnAndFlagsAndSetNullInArray(params: KeyboardParams, popupKeys: Array<out String?>?): Int {
                // Get maximum column order number and set a relevant mode value.
                var popupKeysColumnAndFlags = POPUP_KEYS_MODE_MAX_COLUMN_WITH_AUTO_ORDER or params.maxPopupKeysKeyboardColumn
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
            if ((key.popupKeysColumnAndFlags and POPUP_KEYS_FLAGS_FIXED_COLUMN) != 0)
                return key // don't remove anything for fixed column popup keys
            val popupKeys = key.popupKeys
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
                    key.x,
                    key.y,
                    key.width,
                    key.height,
                    key.code,
                    key.label,
                    key.hintLabel,
                    key.hintIconName,
                    key.iconName,
                    key.backgroundType,
                    Arrays.hashCode(key.popupKeys),
                    key.outputText,
                    key.actionFlags,
                    key.labelFlags,
                    // Key can be distinguishable without the following members.
                    // key.optionalAttributes.altCode,
                    // key.optionalAttributes.mDisabledIconId,
                    // key.optionalAttributes.mPreviewIconId,
                    // key.horizontalGap,
                    // key.verticalGap,
                    // key.optionalAttributes.mVisualInsetLeft,
                    // key.optionalAttributes.mVisualInsetRight,
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
