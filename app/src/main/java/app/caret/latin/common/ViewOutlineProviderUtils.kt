/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package app.caret.latin.common

import android.graphics.Outline
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewOutlineProvider

fun setInsetsOutlineProvider(view: View): InsetsOutlineProvider {
    val provider = InsetsOutlineProvider(view)
    view.outlineProvider = provider
    return provider
}

class InsetsOutlineProvider(private val view: View) : ViewOutlineProvider() {
    private var lastVisibleTopInsets = NO_DATA
    init {
        view.outlineProvider = this
    }
    fun setInsets(insets: InputMethodService.Insets) {
        val visibleTopInsets = insets.visibleTopInsets
        if (lastVisibleTopInsets != visibleTopInsets) {
            lastVisibleTopInsets = visibleTopInsets
            view.invalidateOutline()
        }
    }

    override fun getOutline(view: View, outline: Outline) {
        if (lastVisibleTopInsets == NO_DATA) { // Call default implementation.
            BACKGROUND.getOutline(view, outline)
            return
        }
        // todo: this is now, but it's just fine??
        // TODO: Revisit this when floating/resize keyboard is supported.
        outline.setRect(view.left, lastVisibleTopInsets, view.right, view.bottom)
    }

    companion object {
        private const val NO_DATA = -1
    }
}
