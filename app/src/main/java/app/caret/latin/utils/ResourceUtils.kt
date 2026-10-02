/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.TypedArray
import android.os.Build
import android.util.TypedValue
import android.view.WindowInsets
import android.view.WindowManager

import app.caret.latin.R
import app.caret.latin.settings.SettingsValues

object ResourceUtils {

    const val UNDEFINED_RATIO = -1.0f
    const val UNDEFINED_DIMENSION = -1

    // This utility class is not publicly instantiable.

    @JvmStatic
    fun getKeyboardWidth(ctx: Context, settingsValues: SettingsValues): Int {
        // Floating keyboard width takes priority
        if (settingsValues.isFloatingKeyboard) {
            return settingsValues.floatingWidth
        }
        val defaultKeyboardWidth = getDefaultKeyboardWidth(ctx)
        if (settingsValues.oneHandedModeEnabled) {
            return (settingsValues.oneHandedModeScale * defaultKeyboardWidth).toInt()
        }
        return defaultKeyboardWidth
    }

    @JvmStatic
    fun getDefaultKeyboardWidth(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 35) {
            val dm = ctx.getResources().getDisplayMetrics()
            return dm.widthPixels
        }
        // Since Android 15, insets aren't subtracted from DisplayMetrics.widthPixels, despite
        // targetSdk remaining set to 30.
        val wm = ctx.getSystemService(WindowManager::class.java)
        val windowMetrics = wm.getCurrentWindowMetrics()
        val windowBounds = windowMetrics.getBounds()
        val windowInsets = windowMetrics.getWindowInsets()
        val insetTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val insets = windowInsets.getInsetsIgnoringVisibility(insetTypes)
        return windowBounds.width() - insets.left - insets.right
    }

    @JvmStatic
    fun getSecondaryKeyboardHeight(res: Resources, settingsValues: SettingsValues): Int {
        val keyboardHeight = getKeyboardHeight(res, settingsValues)
        if (settingsValues.toolbarMode == ToolbarMode.HIDDEN && !settingsValues.toolbarHidingGlobal) {
            // Small adjustment to match the height of the main keyboard which has a hidden strip container.
            return keyboardHeight - res.getDimension(R.dimen.config_suggestions_strip_height).toInt()
        }
        return keyboardHeight
    }

    @JvmStatic
    fun getKeyboardHeight(res: Resources, settingsValues: SettingsValues): Int {
        if (settingsValues.isFloatingKeyboard) {
            return settingsValues.floatingHeight
        }
        val defaultKeyboardHeight = getDefaultKeyboardHeight(res, settingsValues.showsNumberRow)
        return (defaultKeyboardHeight * settingsValues.keyboardHeightScale).toInt()
    }

    @JvmStatic
    fun getDefaultKeyboardHeight(res: Resources, showsNumberRow: Boolean): Int {
        val dm = res.getDisplayMetrics()
        val keyboardHeight = res.getDimension(R.dimen.config_default_keyboard_height) * (if (showsNumberRow) 1.33f else 1f)
        val maxKeyboardHeight = res.getFraction(
                R.fraction.config_max_keyboard_height, dm.heightPixels, dm.heightPixels)
        var minKeyboardHeight = res.getFraction(
                R.fraction.config_min_keyboard_height, dm.heightPixels, dm.heightPixels)
        if (minKeyboardHeight < 0.0f) {
            // Specified fraction was negative, so it should be calculated against display
            // width.
            minKeyboardHeight = -res.getFraction(
                    R.fraction.config_min_keyboard_height, dm.widthPixels, dm.widthPixels)
        }
        // Keyboard height will not exceed maxKeyboardHeight and will not be less than
        // minKeyboardHeight.
        return Math.max(Math.min(keyboardHeight, maxKeyboardHeight), minKeyboardHeight).toInt()
    }

    @JvmStatic
    fun isValidFraction(fraction: Float): Boolean {
        return fraction >= 0.0f
    }

    // {@link Resources#getDimensionPixelSize(int)} returns at least one pixel size.
    @JvmStatic
    fun isValidDimensionPixelSize(dimension: Int): Boolean {
        return dimension > 0
    }

    @JvmStatic
    fun getFraction(a: TypedArray, index: Int, defValue: Float): Float {
        val value = a.peekValue(index)
        if (value == null || !isFractionValue(value)) {
            return defValue
        }
        return a.getFraction(index, 1, 1, defValue)
    }

    @JvmStatic
    fun getFraction(a: TypedArray, index: Int): Float {
        return getFraction(a, index, UNDEFINED_RATIO)
    }

    @JvmStatic
    fun getDimensionPixelSize(a: TypedArray, index: Int): Int {
        val value = a.peekValue(index)
        if (value == null || !isDimensionValue(value)) {
            return ResourceUtils.UNDEFINED_DIMENSION
        }
        return a.getDimensionPixelSize(index, ResourceUtils.UNDEFINED_DIMENSION)
    }

    @JvmStatic
    fun getDimensionOrFraction(a: TypedArray, index: Int, base: Int,
            defValue: Float): Float {
        val value = a.peekValue(index)
        if (value == null) {
            return defValue
        }
        if (isFractionValue(value)) {
            return a.getFraction(index, base, base, defValue)
        } else if (isDimensionValue(value)) {
            return a.getDimension(index, defValue)
        }
        return defValue
    }

    @JvmStatic
    fun isFractionValue(v: TypedValue): Boolean {
        return v.type == TypedValue.TYPE_FRACTION
    }

    @JvmStatic
    fun isDimensionValue(v: TypedValue): Boolean {
        return v.type == TypedValue.TYPE_DIMENSION
    }

    @JvmStatic
    fun isNight(res: Resources): Boolean {
        return (res.getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
