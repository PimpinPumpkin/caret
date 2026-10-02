/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import android.view.View
import android.view.ViewGroup

import app.caret.keyboard.Key
import app.caret.latin.common.ColorType
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.settings.Settings
import app.caret.latin.utils.ViewLayoutUtils

import java.util.ArrayDeque
import java.util.HashMap

/**
 * This class controls pop up key previews. This class decides:
 * - what kind of key previews should be shown.
 * - where key previews should be placed.
 * - how key previews should be shown and dismissed.
 */
class KeyPreviewChoreographer(params: KeyPreviewDrawParams) {
    // Free {@link KeyPreviewView} pool that can be used for key preview.
    private val freeKeyPreviewViews = ArrayDeque<KeyPreviewView>()
    // Map from {@link Key} to {@link KeyPreviewView} that is currently being displayed as key
    // preview.
    private val showingKeyPreviewViews = HashMap<Key?, KeyPreviewView>()

    private val mParams: KeyPreviewDrawParams

    init {
        mParams = params
    }

    fun getKeyPreviewView(key: Key?, placerView: ViewGroup): KeyPreviewView {
        var keyPreviewView: KeyPreviewView? = showingKeyPreviewViews.remove(key)
        if (keyPreviewView != null) {
            return keyPreviewView
        }
        keyPreviewView = freeKeyPreviewViews.poll()
        if (keyPreviewView != null) {
            return keyPreviewView
        }
        val context = placerView.context
        keyPreviewView = KeyPreviewView(context, null /* attrs */)
        keyPreviewView.setBackgroundResource(mParams.mPreviewBackgroundResId)
        placerView.addView(keyPreviewView, ViewLayoutUtils.newLayoutParam(placerView, 0, 0))
        return keyPreviewView
    }

    fun isShowingKeyPreview(key: Key?): Boolean {
        return showingKeyPreviewViews.containsKey(key)
    }

    fun dismissKeyPreview(key: Key?) {
        if (key == null) {
            return
        }
        val keyPreviewView = showingKeyPreviewViews[key]
        if (keyPreviewView == null) {
            return
        }
        // Dismiss preview
        showingKeyPreviewViews.remove(key)
        keyPreviewView.tag = null
        keyPreviewView.visibility = View.INVISIBLE
        freeKeyPreviewViews.add(keyPreviewView)
    }

    fun placeAndShowKeyPreview(key: Key, iconsSet: KeyboardIconsSet,
            drawParams: KeyDrawParams, fullKeyboardViewWidth: Int, keyboardOrigin: IntArray,
            placerView: ViewGroup) {
        val keyPreviewView = getKeyPreviewView(key, placerView)
        placeKeyPreview(key, keyPreviewView, iconsSet, drawParams, fullKeyboardViewWidth, keyboardOrigin)
        showKeyPreview(key, keyPreviewView)
    }

    private fun placeKeyPreview(key: Key, keyPreviewView: KeyPreviewView, iconsSet: KeyboardIconsSet,
            drawParams: KeyDrawParams, fullKeyboardViewWidth: Int, originCoords: IntArray) {
        keyPreviewView.setPreviewVisual(key, iconsSet, drawParams)
        keyPreviewView.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mParams.setGeometry(keyPreviewView)
        val previewWidth = keyPreviewView.measuredWidth
        val previewHeight = keyPreviewView.measuredHeight
        val keyDrawWidth = key.getDrawWidth()
        val originX = CoordinateUtils.x(originCoords)
        // The key preview is horizontally aligned with the center of the visible part of the
        // parent key. If it doesn't fit in this {@link KeyboardView}, it is moved inward to fit and
        // the left/right background is used if such background is specified.
        val keyPreviewPosition: Int
        var previewX = key.getDrawX() - (previewWidth - keyDrawWidth) / 2 + originX
        if (previewX < originX) {
            previewX = originX
            keyPreviewPosition = KeyPreviewView.POSITION_LEFT
        } else if (previewX > fullKeyboardViewWidth - previewWidth + originX) {
            previewX = fullKeyboardViewWidth - previewWidth + originX
            keyPreviewPosition = KeyPreviewView.POSITION_RIGHT
        } else {
            keyPreviewPosition = KeyPreviewView.POSITION_MIDDLE
        }
        val hasPopupKeys = (key.getPopupKeys() != null)
        keyPreviewView.setPreviewBackground(hasPopupKeys, keyPreviewPosition)
        val colors = Settings.getValues().mColors
        colors.setBackground(keyPreviewView, ColorType.KEY_PREVIEW_BACKGROUND)

        // The key preview is placed vertically above the top edge of the parent key with an
        // arbitrary offset.
        val previewY = key.getY() - previewHeight + key.getHeight() - mParams.mPreviewOffset +
                CoordinateUtils.y(originCoords)

        ViewLayoutUtils.placeViewAt(keyPreviewView, previewX, previewY, previewWidth, previewHeight)
        keyPreviewView.pivotX = previewWidth / 2.0f
        keyPreviewView.pivotY = previewHeight.toFloat()
    }

    fun showKeyPreview(key: Key, keyPreviewView: KeyPreviewView) {
        keyPreviewView.visibility = View.VISIBLE
        showingKeyPreviewViews.put(key, keyPreviewView)
    }

}
