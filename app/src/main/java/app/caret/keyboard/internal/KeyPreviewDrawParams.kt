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
    @JvmField val previewOffset: Int
    @JvmField val previewBackgroundResId: Int
    var isPopupEnabled = true

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
    var visibleWidth = 0
        private set
    var visibleHeight = 0
        private set
    // The key preview may have an arbitrary offset and its background that may have a bottom
    // padding. To align the popup keys keyboard and the key preview we also need to record the
    // offset between the top edge of parent key and the bottom of the visible part of key
    // preview background.
    var visibleOffset = 0

    init {
        previewOffset = mainKeyboardViewAttr.getDimensionPixelOffset(
                R.styleable.MainKeyboardView_keyPreviewOffset, 0)
        previewBackgroundResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_keyPreviewBackground, 0)
    }

    fun setGeometry(previewTextView: View) {
        val previewWidth = previewTextView.measuredWidth
        val previewHeight = previewTextView.measuredHeight
        // The width and height of visible part of the key preview background. The content marker
        // of the background 9-patch have to cover the visible part of the background.
        visibleWidth = previewWidth - previewTextView.paddingLeft - previewTextView.paddingRight
        visibleHeight = previewHeight - previewTextView.paddingTop - previewTextView.paddingBottom
        // The distance between the top edge of the parent key and the bottom of the visible part
        // of the key preview background.
        visibleOffset = -previewTextView.paddingBottom / 2
    }

}
