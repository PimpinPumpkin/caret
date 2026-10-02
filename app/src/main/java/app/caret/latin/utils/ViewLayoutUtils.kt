/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout

// This utility class is not publicly instantiable.
object ViewLayoutUtils {
    @JvmStatic
    fun newLayoutParam(placer: ViewGroup?, width: Int, height: Int): MarginLayoutParams {
        if (placer is FrameLayout) {
            return FrameLayout.LayoutParams(width, height)
        } else if (placer is RelativeLayout) {
            return RelativeLayout.LayoutParams(width, height)
        } else if (placer == null) {
            throw NullPointerException("placer is null")
        } else {
            throw IllegalArgumentException(
                "placer is neither FrameLayout nor RelativeLayout: "
                        + placer.javaClass.getName()
            )
        }
    }

    @JvmStatic
    fun placeViewAt(view: View, x: Int, y: Int, w: Int, h: Int) {
        val lp = view.getLayoutParams()
        if (lp is MarginLayoutParams) {
            val marginLayoutParams: MarginLayoutParams = lp
            marginLayoutParams.width = w
            marginLayoutParams.height = h
            marginLayoutParams.setMargins(x, y, 0, 0)
        }
    }

    @JvmStatic
    fun updateLayoutHeightOf(window: Window, layoutHeight: Int) {
        val params = window.getAttributes()
        if (params != null && params.height != layoutHeight) {
            params.height = layoutHeight
            window.setAttributes(params)
        }
    }

    @JvmStatic
    fun updateLayoutHeightOf(view: View, layoutHeight: Int) {
        val params = view.getLayoutParams()
        if (params != null && params.height != layoutHeight) {
            params.height = layoutHeight
            view.setLayoutParams(params)
        }
    }

    @JvmStatic
    fun updateLayoutGravityOf(view: View, layoutGravity: Int) {
        val lp = view.getLayoutParams()
        if (lp is LinearLayout.LayoutParams) {
            val params: LinearLayout.LayoutParams = lp
            if (params.gravity != layoutGravity) {
                params.gravity = layoutGravity
                view.setLayoutParams(params)
            }
        } else if (lp is FrameLayout.LayoutParams) {
            val params: FrameLayout.LayoutParams = lp
            if (params.gravity != layoutGravity) {
                params.gravity = layoutGravity
                view.setLayoutParams(params)
            }
        } else {
            throw IllegalArgumentException(
                "Layout parameter doesn't have gravity: "
                        + lp!!.javaClass.getName()
            )
        }
    }
}
