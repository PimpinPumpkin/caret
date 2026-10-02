/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.content.res.TypedArray
import android.view.View

import app.caret.latin.R

class KeyPreviewDrawParams(mainKeyboardViewAttr: TypedArray) {
    // XML attributes of {@link MainKeyboardView}.
    @JvmField val mPreviewOffset: Int
    @JvmField val mPreviewBackgroundResId: Int
    private var mShowPopup = true

    // The graphical geometry of the key preview.
    // <-width->
    // +-------+   ^
    // |       |   |
    // |preview| height (visible)
    // |       |   |
    // +       + ^ v
    //  \     /  |offset
    // +-\   /-+ v
    // |  +-+  |
    // |parent |
    // |    key|
    // +-------+
    // The background of a {@link TextView} being used for a key preview may have invisible
    // paddings. To align the popup keys keyboard panel's visible part with the visible part of
    // the background, we need to record the width and height of key preview that don't include
    // invisible paddings.
    private var mVisibleWidth = 0
    private var mVisibleHeight = 0
    // The key preview may have an arbitrary offset and its background that may have a bottom
    // padding. To align the popup keys keyboard and the key preview we also need to record the
    // offset between the top edge of parent key and the bottom of the visible part of key
    // preview background.
    private var mVisibleOffset = 0

    init {
        mPreviewOffset = mainKeyboardViewAttr.getDimensionPixelOffset(
                R.styleable.MainKeyboardView_keyPreviewOffset, 0)
        mPreviewBackgroundResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_keyPreviewBackground, 0)
    }

    fun setVisibleOffset(previewVisibleOffset: Int) {
        mVisibleOffset = previewVisibleOffset
    }

    fun getVisibleOffset(): Int {
        return mVisibleOffset
    }

    fun setGeometry(previewTextView: View) {
        val previewWidth = previewTextView.measuredWidth
        val previewHeight = previewTextView.measuredHeight
        // The width and height of visible part of the key preview background. The content marker
        // of the background 9-patch have to cover the visible part of the background.
        mVisibleWidth = previewWidth - previewTextView.paddingLeft - previewTextView.paddingRight
        mVisibleHeight = previewHeight - previewTextView.paddingTop - previewTextView.paddingBottom
        // The distance between the top edge of the parent key and the bottom of the visible part
        // of the key preview background.
        setVisibleOffset(-previewTextView.paddingBottom / 2)
    }

    fun getVisibleWidth(): Int {
        return mVisibleWidth
    }

    fun getVisibleHeight(): Int {
        return mVisibleHeight
    }

    fun setPopupEnabled(enabled: Boolean) {
        mShowPopup = enabled
    }

    fun isPopupEnabled(): Boolean {
        return mShowPopup
    }

}
