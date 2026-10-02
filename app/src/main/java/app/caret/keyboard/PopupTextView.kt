/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import app.caret.keyboard.internal.KeyDrawParams
import app.caret.latin.R
import app.caret.latin.common.ColorType
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.settings.Settings


/**
 * A view that displays popup text.
 */
class PopupTextView : TextView, PopupKeysPanel {
    private val coordinates = CoordinateUtils.newInstance()
    private var controller: PopupKeysPanel.Controller = PopupKeysPanel.EMPTY_CONTROLLER
    private var originX = 0
    private var originY = 0
    private var key: Key? = null
    private var onReleaseKey: ((Key) -> Unit)? = null

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.popupKeysKeyboardViewStyle)

    constructor(context: Context, attrs: AttributeSet?,
                defStyle: Int) : super(context, attrs, defStyle)

    fun setKeyDrawParams(key: Key, drawParams: KeyDrawParams) {
        this.key = key
        Settings.getValues().mColors.setBackground(this, ColorType.KEY_PREVIEW_BACKGROUND)
        setTextColor(drawParams.previewTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (key.selectHintTextSize(drawParams) shl 1).toFloat())
        KeyboardTypeface.applyToTextView(this, this.text, key.selectTypeface(drawParams))
    }

    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int, listener: KeyboardActionListener) {
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY)
    }

    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int, onReleaseKey: (Key) -> Unit) {
        this.onReleaseKey = onReleaseKey
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY)
    }

    private fun showPopupKeysPanelInternal(parentView: View, controller: PopupKeysPanel.Controller,
            pointX: Int, pointY: Int) {
        this.controller = controller
        val container = getContainerView()
        // The coordinates of panel's left-top corner in parentView's coordinate system.
        // We need to consider background drawable paddings.
        val x = pointX - measuredWidth / 2 - container.paddingLeft - paddingLeft
        val y = pointY - container.measuredHeight + container.paddingBottom +
                paddingBottom

        parentView.getLocationInWindow(coordinates)
        // Ensure the horizontal position of the panel does not extend past the parentView edges.
        val maxX = parentView.measuredWidth - container.measuredWidth
        val panelX = Math.max(0, Math.min(maxX, x)) + CoordinateUtils.x(coordinates)
        val panelY = y + CoordinateUtils.y(coordinates)
        container.x = panelX.toFloat()
        container.y = panelY.toFloat()

        originX = x + container.paddingLeft
        originY = y + container.paddingTop
        controller.setLayoutGravity(Gravity.NO_GRAVITY)
        controller.onShowPopupKeysPanel(this)
    }

    override fun onDownEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
    }

    override fun onMoveEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
    }

    override fun onUpEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        onReleaseKey!!.invoke(key!!)
    }

    override fun dismissPopupKeysPanel() {
        if (!isShowingInParent()) {
            return
        }
        controller.onDismissPopupKeysPanel()
    }

    override fun translateX(x: Int): Int {
        return x - originX
    }

    override fun translateY(y: Int): Int {
        return y - originY
    }
}
