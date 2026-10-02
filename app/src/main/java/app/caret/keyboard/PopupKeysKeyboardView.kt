/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import app.caret.accessibility.AccessibilityUtils
import app.caret.accessibility.PopupKeysKeyboardAccessibilityDelegate
import app.caret.keyboard.internal.KeyDrawParams
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.R
import app.caret.latin.RichInputMethodManager
import app.caret.latin.common.Constants
import app.caret.latin.common.CoordinateUtils

/**
 * A view that renders a virtual {@link PopupKeysKeyboard}. It handles rendering of keys and
 * detecting key presses and touch movements.
 */
open class PopupKeysKeyboardView : KeyboardView, PopupKeysPanel {
    private val coordinates: IntArray = CoordinateUtils.newInstance()

    private val divider: Drawable?
    @JvmField
    protected val keyDetector: KeyDetector
    private var controller: PopupKeysPanel.Controller = PopupKeysPanel.EMPTY_CONTROLLER
    protected var keyboardActionListener: KeyboardActionListener? = null
    protected var onReleaseKey: ((Key) -> Unit)? = null
    private var originX: Int = 0
    private var originY: Int = 0
    private var currentKey: Key? = null

    private var activePointerId: Int = 0

    @JvmField
    protected var keyboardAccessibilityDelegate: PopupKeysKeyboardAccessibilityDelegate? = null

    constructor(context: Context, attrs: AttributeSet?) :
            this(context, attrs, R.attr.popupKeysKeyboardViewStyle)

    constructor(context: Context, attrs: AttributeSet?,
                defStyle: Int) : super(context, attrs, defStyle) {
        val popupKeysKeyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.PopupKeysKeyboardView, defStyle, R.style.PopupKeysKeyboardView)
        divider = popupKeysKeyboardViewAttr.getDrawable(R.styleable.PopupKeysKeyboardView_divider)
        popupKeysKeyboardViewAttr.recycle()
        keyDetector = PopupKeysDetector(resources.getDimension(
                R.dimen.config_popup_keys_keyboard_slide_allowance))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keyboard = this.keyboard
        if (keyboard != null) {
            val width = keyboard.occupiedWidth + paddingLeft + paddingRight
            val height = keyboard.occupiedHeight + paddingTop + paddingBottom
            setMeasuredDimension(width, height)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDrawKeyTopVisuals(key: Key, canvas: Canvas,
            paint: Paint, params: KeyDrawParams) {
        if (!key.isSpacer || key !is PopupKeysKeyboard.PopupKeyDivider
                || divider == null) {
            super.onDrawKeyTopVisuals(key, canvas, paint, params)
            return
        }
        val keyWidth = key.getDrawWidth()
        val keyHeight = key.height
        val iconWidth = Math.min(divider.intrinsicWidth, keyWidth)
        val iconHeight = divider.intrinsicHeight
        val iconX = (keyWidth - iconWidth) / 2 // Align horizontally center
        val iconY = (keyHeight - iconHeight) / 2 // Align vertically center
        drawIcon(canvas, divider, iconX, iconY, iconWidth, iconHeight)
    }

    override fun setKeyboard(keyboard: Keyboard) {
        super.setKeyboard(keyboard)
        keyDetector.setKeyboard(
                keyboard, -paddingLeft.toFloat(), -paddingTop + this.verticalCorrection)
        if (AccessibilityUtils.instance.isAccessibilityEnabled) {
            val delegate = keyboardAccessibilityDelegate
                    ?: PopupKeysKeyboardAccessibilityDelegate(this, keyDetector).also {
                        keyboardAccessibilityDelegate = it
                        it.setOpenAnnounce(R.string.spoken_open_popup_keys_keyboard)
                        it.setCloseAnnounce(R.string.spoken_close_popup_keys_keyboard)
                    }
            delegate.keyboard = keyboard
        } else {
            keyboardAccessibilityDelegate = null
        }
        val shortcutKey = keyboard.getKey(KeyCode.VOICE_INPUT)
        if (shortcutKey != null) {
            shortcutKey.isEnabled = RichInputMethodManager.getInstance().isShortcutImeReady
            invalidateKey(shortcutKey)
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int, listener: KeyboardActionListener) {
        keyboardActionListener = listener
        onReleaseKey = null
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY)
    }

    /**
     * {@inheritDoc}
     */
    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int, onReleaseKey: (Key) -> Unit) {
        keyboardActionListener = null
        this.onReleaseKey = onReleaseKey
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY)
    }

    @SuppressLint("RtlHardcoded") // a key on the left is on the left, independent of layout direction
    private fun showPopupKeysPanelInternal(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int) {
        this.controller = controller
        val container = getContainerView()
        // The coordinates of panel's left-top corner in parentView's coordinate system.
        // We need to consider background drawable paddings.
        val x = pointX - getDefaultCoordX() - container.paddingLeft - paddingLeft
        val y = pointY - container.measuredHeight + container.paddingBottom +
                paddingBottom

        parentView.getLocationInWindow(coordinates)
        val containerY = y + CoordinateUtils.y(coordinates)
        container.y = containerY.toFloat()

        // This is needed for cases where there's also a text popup above this keyboard
        val panelMaxX = parentView.measuredWidth - measuredWidth
        val panelFinalX = Math.max(0, Math.min(panelMaxX, x))
        val center = panelFinalX + measuredWidth / 2
        val mostCommonKeyWidth = this.keyboard!!.mostCommonKeyWidth
        val layoutGravity = if (center < pointX - mostCommonKeyWidth / 2)
                        Gravity.RIGHT else if (center > pointX + mostCommonKeyWidth / 2) Gravity.LEFT else Gravity.CENTER_HORIZONTAL

        var containerAdjustedX = x
        if (measuredWidth < container.measuredWidth) {
            containerAdjustedX = if (layoutGravity == Gravity.LEFT) panelFinalX else if (layoutGravity == Gravity.RIGHT)
                panelFinalX + measuredWidth - container.measuredWidth
                else panelFinalX + (measuredWidth - container.measuredWidth) / 2
        }

        // Ensure the horizontal position of the panel does not extend past the parentView edges.
        val containerMaxX = parentView.measuredWidth - container.measuredWidth
        val containerFinalX = Math.max(0, Math.min(containerMaxX, containerAdjustedX))
        val containerX = containerFinalX + CoordinateUtils.x(coordinates)
        container.x = containerX.toFloat()
        translationX = (panelFinalX - containerFinalX).toFloat()
        controller.setLayoutGravity(layoutGravity)

        originX = panelFinalX
        originY = y + container.paddingTop + this.y.toInt()
        controller.onShowPopupKeysPanel(this)
        val accessibilityDelegate = keyboardAccessibilityDelegate
        if (accessibilityDelegate != null
                && AccessibilityUtils.instance.isAccessibilityEnabled) {
            accessibilityDelegate.onShowPopupKeysKeyboard()
        }
    }

    /**
     * Returns the default x coordinate for showing this panel.
     */
    protected open fun getDefaultCoordX(): Int {
        return (this.keyboard as PopupKeysKeyboard).defaultCoordX
    }

    override fun onDownEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        activePointerId = pointerId
        currentKey = detectKey(x, y)
    }

    override fun onMoveEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        if (activePointerId != pointerId) {
            return
        }
        val hasOldKey = (currentKey != null)
        currentKey = detectKey(x, y)
        if (hasOldKey && currentKey == null) {
            // A popup keys keyboard is canceled when detecting no key.
            controller.onCancelPopupKeysPanel()
        }
    }

    override fun onUpEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        if (activePointerId != pointerId) {
            return
        }
        // Calling {@link #detectKey(int,int,int)} here is harmless because the last move event and
        // the following up event share the same coordinates.
        currentKey = detectKey(x, y)
        val key = currentKey
        if (key != null) {
            updateReleaseKeyGraphics(key)
            onKeyInput(key, x, y)
            currentKey = null
        }
    }

    /**
     * Performs the specific action for this panel when the user presses a key on the panel.
     */
    protected open fun onKeyInput(key: Key, x: Int, y: Int) {
        val listener = keyboardActionListener
        val releaseCallback = onReleaseKey
        if (listener != null) {
            val code = key.code
            if (code == KeyCode.MULTIPLE_CODE_POINTS) {
                listener.onTextInput(currentKey!!.outputText)
            } else if (code != KeyCode.NOT_SPECIFIED) {
                if (this.keyboard!!.hasProximityCharsCorrection(code)) {
                    listener.onCodeInput(code, x, y, false /* isKeyRepeat */)
                } else {
                    listener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE,
                            false /* isKeyRepeat */)
                }
            }
        } else if (releaseCallback != null) {
            releaseCallback.invoke(key)
        }
    }

    private fun detectKey(x: Int, y: Int): Key? {
        val oldKey = currentKey
        val newKey = keyDetector.detectHitKey(x, y)
        if (newKey === oldKey) {
            return newKey
        }
        // A new key is detected.
        if (oldKey != null) {
            updateReleaseKeyGraphics(oldKey)
            invalidateKey(oldKey)
        }
        if (newKey != null) {
            updatePressKeyGraphics(newKey)
            invalidateKey(newKey)
        }
        return newKey
    }

    private fun updateReleaseKeyGraphics(key: Key) {
        key.onReleased()
        invalidateKey(key)
    }

    private fun updatePressKeyGraphics(key: Key) {
        key.onPressed()
        invalidateKey(key)
    }

    override fun dismissPopupKeysPanel() {
        if (!isShowingInParent()) {
            return
        }
        val accessibilityDelegate = keyboardAccessibilityDelegate
        if (accessibilityDelegate != null
                && AccessibilityUtils.instance.isAccessibilityEnabled) {
            accessibilityDelegate.onDismissPopupKeysKeyboard()
        }
        controller.onDismissPopupKeysPanel()
    }

    override fun translateX(x: Int): Int {
        return x - originX
    }

    override fun translateY(y: Int): Int {
        return y - originY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent?): Boolean {
        val action = me!!.actionMasked
        val eventTime = me.eventTime
        val index = me.actionIndex
        val x = me.getX(index).toInt()
        val y = me.getY(index).toInt()
        val pointerId = me.getPointerId(index)
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                onDownEvent(x, y, pointerId, eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                onUpEvent(x, y, pointerId, eventTime)
            MotionEvent.ACTION_MOVE ->
                onMoveEvent(x, y, pointerId, eventTime)
        }
        return true
    }

    /**
     * {@inheritDoc}
     */
    override fun onHoverEvent(event: MotionEvent?): Boolean {
        val accessibilityDelegate = keyboardAccessibilityDelegate
        if (accessibilityDelegate != null
                && AccessibilityUtils.instance.isTouchExplorationEnabled) {
            return accessibilityDelegate.onHoverEvent(event!!)
        }
        return super.onHoverEvent(event)
    }
}
