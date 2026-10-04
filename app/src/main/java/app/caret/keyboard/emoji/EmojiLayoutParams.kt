/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package app.caret.keyboard.emoji

import android.content.res.Resources
import android.view.View
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import app.caret.keyboard.internal.KeyboardParams
import app.caret.latin.R
import app.caret.latin.settings.Settings
import app.caret.latin.utils.ResourceUtils

internal class EmojiLayoutParams(res: Resources) {
    private val emojiListBottomMargin: Int
    val emojiKeyboardHeight: Int
    private val emojiCategoryPageIdViewHeight: Int
    val bottomRowKeyboardHeight: Int
    /** How much taller than the keyboard the emoji panel is. */
    val extraHeight: Int

    init {
        val sv = Settings.values
        val defaultKeyboardHeight = ResourceUtils.getSecondaryKeyboardHeight(res, sv)

        val keyVerticalGap = (res.getFraction(R.fraction.config_key_vertical_gap_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.keyGapScale).toInt()
        val bottomPadding = (res.getFraction(R.fraction.config_keyboard_bottom_padding_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.bottomPaddingScale).toInt()
        val topPadding = res.getFraction(R.fraction.config_keyboard_top_padding_holo,
            defaultKeyboardHeight, defaultKeyboardHeight).toInt()

        val rowCount = KeyboardParams.DEFAULT_KEYBOARD_ROWS + if (sv.showsNumberRow) 1 else 0
        bottomRowKeyboardHeight = (defaultKeyboardHeight - bottomPadding - topPadding) / rowCount - keyVerticalGap / 2

        val pageIdHeight = res.getDimension(R.dimen.config_emoji_category_page_id_height)
        emojiCategoryPageIdViewHeight = pageIdHeight.toInt()
        val offset = 1.25f * res.displayMetrics.density * sv.keyboardHeightScale // like ClipboardLayoutParams
        // Caret: upright, the emoji panel is taller than the keyboard and shows two more rows, as on Gboard;
        // the section title above the list comes out of that
        val upright = res.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            && !sv.isFloatingKeyboard
        val taller = if (upright) (EXTRA_HEIGHT_DP * res.displayMetrics.density * sv.keyboardHeightScale).toInt() else 0
        val title = (SECTION_TITLE_DP * res.displayMetrics.density).toInt()
        extraHeight = taller
        val emojiListHeight = defaultKeyboardHeight - bottomRowKeyboardHeight - bottomPadding + (offset.toInt()) + taller - title
        emojiListBottomMargin = 0
        emojiKeyboardHeight = emojiListHeight - emojiCategoryPageIdViewHeight - emojiListBottomMargin
    }

    fun setEmojiListProperties(vp: ViewPager2) {
        val lp = vp.layoutParams as LinearLayout.LayoutParams
        lp.height = emojiKeyboardHeight
        lp.bottomMargin = emojiListBottomMargin
        vp.layoutParams = lp
    }

    fun setCategoryPageIdViewProperties(v: View) {
        val lp = v.layoutParams as LinearLayout.LayoutParams
        lp.height = emojiCategoryPageIdViewHeight
        v.layoutParams = lp
    }

    companion object {
        private const val EXTRA_HEIGHT_DP = 124
        private const val SECTION_TITLE_DP = 26
    }
}
