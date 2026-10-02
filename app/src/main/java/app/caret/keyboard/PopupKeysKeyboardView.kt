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
    private val mCoordinates: IntArray = CoordinateUtils.newInstance()

    private val mDivider: Drawable?
    @JvmField
    protected val mKeyDetector: KeyDetector
    private var mController: PopupKeysPanel.Controller = PopupKeysPanel.EMPTY_CONTROLLER
    protected var mListener: KeyboardActionListener? = null
    protected var mOnReleaseKey: ((Key) -> Unit)? = null
    private var mOriginX: Int = 0
    private var mOriginY: Int = 0
    private var mCurrentKey: Key? = null

    private var mActivePointerId: Int = 0

    @JvmField
    protected var mAccessibilityDelegate: PopupKeysKeyboardAccessibilityDelegate? = null

    constructor(context: Context, attrs: AttributeSet?) :
            this(context, attrs, R.attr.popupKeysKeyboardViewStyle)

    constructor(context: Context, attrs: AttributeSet?,
                defStyle: Int) : super(context, attrs, defStyle) {
        val popupKeysKeyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.PopupKeysKeyboardView, defStyle, R.style.PopupKeysKeyboardView)
        mDivider = popupKeysKeyboardViewAttr.getDrawable(R.styleable.PopupKeysKeyboardView_divider)
        popupKeysKeyboardViewAttr.recycle()
        mKeyDetector = PopupKeysDetector(resources.getDimension(
                R.dimen.config_popup_keys_keyboard_slide_allowance))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keyboard = getKeyboard()
        if (keyboard != null) {
            val width = keyboard.mOccupiedWidth + paddingLeft + paddingRight
            val height = keyboard.mOccupiedHeight + paddingTop + paddingBottom
            setMeasuredDimension(width, height)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDrawKeyTopVisuals(key: Key, canvas: Canvas,
            paint: Paint, params: KeyDrawParams) {
        if (!key.isSpacer() || key !is PopupKeysKeyboard.PopupKeyDivider
                || mDivider == null) {
            super.onDrawKeyTopVisuals(key, canvas, paint, params)
            return
        }
        val keyWidth = key.getDrawWidth()
        val keyHeight = key.getHeight()
        val iconWidth = Math.min(mDivider.intrinsicWidth, keyWidth)
        val iconHeight = mDivider.intrinsicHeight
        val iconX = (keyWidth - iconWidth) / 2 // Align horizontally center
        val iconY = (keyHeight - iconHeight) / 2 // Align vertically center
        drawIcon(canvas, mDivider, iconX, iconY, iconWidth, iconHeight)
    }

    override fun setKeyboard(keyboard: Keyboard) {
        super.setKeyboard(keyboard)
        mKeyDetector.setKeyboard(
                keyboard, -paddingLeft.toFloat(), -paddingTop + getVerticalCorrection())
        if (AccessibilityUtils.instance.isAccessibilityEnabled) {
            if (mAccessibilityDelegate == null) {
                mAccessibilityDelegate = PopupKeysKeyboardAccessibilityDelegate(
                        this, mKeyDetector)
                mAccessibilityDelegate!!.setOpenAnnounce(R.string.spoken_open_popup_keys_keyboard)
                mAccessibilityDelegate!!.setCloseAnnounce(R.string.spoken_close_popup_keys_keyboard)
            }
            mAccessibilityDelegate!!.keyboard = keyboard
        } else {
            mAccessibilityDelegate = null
        }
        val shortcutKey = keyboard.getKey(KeyCode.VOICE_INPUT)
        if (shortcutKey != null) {
            shortcutKey.setEnabled(RichInputMethodManager.getInstance().isShortcutImeReady)
            invalidateKey(shortcutKey)
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int, listener: KeyboardActionListener) {
        mListener = listener
        mOnReleaseKey = null
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY)
    }

    /**
     * {@inheritDoc}
     */
    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int, onReleaseKey: (Key) -> Unit) {
        mListener = null
        mOnReleaseKey = onReleaseKey
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY)
    }

    @SuppressLint("RtlHardcoded") // a key on the left is on the left, independent of layout direction
    private fun showPopupKeysPanelInternal(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int) {
        mController = controller
        val container = getContainerView()
        // The coordinates of panel's left-top corner in parentView's coordinate system.
        // We need to consider background drawable paddings.
        val x = pointX - getDefaultCoordX() - container.paddingLeft - paddingLeft
        val y = pointY - container.measuredHeight + container.paddingBottom +
                paddingBottom

        parentView.getLocationInWindow(mCoordinates)
        val containerY = y + CoordinateUtils.y(mCoordinates)
        container.y = containerY.toFloat()

        // This is needed for cases where there's also a text popup above this keyboard
        val panelMaxX = parentView.measuredWidth - measuredWidth
        val panelFinalX = Math.max(0, Math.min(panelMaxX, x))
        val center = panelFinalX + measuredWidth / 2
        val layoutGravity = if (center < pointX - getKeyboard()!!.mMostCommonKeyWidth / 2)
                        Gravity.RIGHT else if (center > pointX + getKeyboard()!!.mMostCommonKeyWidth / 2) Gravity.LEFT else Gravity.CENTER_HORIZONTAL

        var containerAdjustedX = x
        if (measuredWidth < container.measuredWidth) {
            containerAdjustedX = if (layoutGravity == Gravity.LEFT) panelFinalX else if (layoutGravity == Gravity.RIGHT)
                panelFinalX + measuredWidth - container.measuredWidth
                else panelFinalX + (measuredWidth - container.measuredWidth) / 2
        }

        // Ensure the horizontal position of the panel does not extend past the parentView edges.
        val containerMaxX = parentView.measuredWidth - container.measuredWidth
        val containerFinalX = Math.max(0, Math.min(containerMaxX, containerAdjustedX))
        val containerX = containerFinalX + CoordinateUtils.x(mCoordinates)
        container.x = containerX.toFloat()
        translationX = (panelFinalX - containerFinalX).toFloat()
        controller.setLayoutGravity(layoutGravity)

        mOriginX = panelFinalX
        mOriginY = y + container.paddingTop + this.y.toInt()
        controller.onShowPopupKeysPanel(this)
        val accessibilityDelegate = mAccessibilityDelegate
        if (accessibilityDelegate != null
                && AccessibilityUtils.instance.isAccessibilityEnabled) {
            accessibilityDelegate.onShowPopupKeysKeyboard()
        }
    }

    /**
     * Returns the default x coordinate for showing this panel.
     */
    protected open fun getDefaultCoordX(): Int {
        return (getKeyboard() as PopupKeysKeyboard).getDefaultCoordX()
    }

    override fun onDownEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        mActivePointerId = pointerId
        mCurrentKey = detectKey(x, y)
    }

    override fun onMoveEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        if (mActivePointerId != pointerId) {
            return
        }
        val hasOldKey = (mCurrentKey != null)
        mCurrentKey = detectKey(x, y)
        if (hasOldKey && mCurrentKey == null) {
            // A popup keys keyboard is canceled when detecting no key.
            mController.onCancelPopupKeysPanel()
        }
    }

    override fun onUpEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        if (mActivePointerId != pointerId) {
            return
        }
        // Calling {@link #detectKey(int,int,int)} here is harmless because the last move event and
        // the following up event share the same coordinates.
        mCurrentKey = detectKey(x, y)
        val currentKey = mCurrentKey
        if (currentKey != null) {
            updateReleaseKeyGraphics(currentKey)
            onKeyInput(currentKey, x, y)
            mCurrentKey = null
        }
    }

    /**
     * Performs the specific action for this panel when the user presses a key on the panel.
     */
    protected open fun onKeyInput(key: Key, x: Int, y: Int) {
        val listener = mListener
        val onReleaseKey = mOnReleaseKey
        if (listener != null) {
            val code = key.getCode()
            if (code == KeyCode.MULTIPLE_CODE_POINTS) {
                listener.onTextInput(mCurrentKey!!.getOutputText())
            } else if (code != KeyCode.NOT_SPECIFIED) {
                if (getKeyboard()!!.hasProximityCharsCorrection(code)) {
                    listener.onCodeInput(code, x, y, false /* isKeyRepeat */)
                } else {
                    listener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE,
                            false /* isKeyRepeat */)
                }
            }
        } else if (onReleaseKey != null) {
            onReleaseKey.invoke(key)
        }
    }

    private fun detectKey(x: Int, y: Int): Key? {
        val oldKey = mCurrentKey
        val newKey = mKeyDetector.detectHitKey(x, y)
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
        val accessibilityDelegate = mAccessibilityDelegate
        if (accessibilityDelegate != null
                && AccessibilityUtils.instance.isAccessibilityEnabled) {
            accessibilityDelegate.onDismissPopupKeysKeyboard()
        }
        mController.onDismissPopupKeysPanel()
    }

    override fun translateX(x: Int): Int {
        return x - mOriginX
    }

    override fun translateY(y: Int): Int {
        return y - mOriginY
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
        val accessibilityDelegate = mAccessibilityDelegate
        if (accessibilityDelegate != null
                && AccessibilityUtils.instance.isTouchExplorationEnabled) {
            return accessibilityDelegate.onHoverEvent(event!!)
        }
        return super.onHoverEvent(event)
    }
}
