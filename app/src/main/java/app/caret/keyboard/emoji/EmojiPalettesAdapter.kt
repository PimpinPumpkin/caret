/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.emoji

import app.caret.latin.utils.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import app.caret.latin.R

import androidx.recyclerview.widget.RecyclerView

internal class EmojiPalettesAdapter(
    emojiCategory: EmojiCategory,
    category: EmojiCategory.Category,
    emojiViewCallback: EmojiViewCallback
) : RecyclerView.Adapter<EmojiPalettesAdapter.ViewHolder>() {

    private val mCategory: EmojiCategory.Category
    private val mEmojiViewCallback: EmojiViewCallback
    private val mEmojiCategory: EmojiCategory

    init {
        mEmojiCategory = emojiCategory
        mCategory = category
        mEmojiViewCallback = emojiViewCallback
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val keyboardView = inflater.inflate(
                R.layout.emoji_keyboard_page, parent, false) as EmojiPageKeyboardView
        keyboardView.setEmojiViewCallback(mEmojiViewCallback)
        return ViewHolder(keyboardView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (DEBUG_PAGER) {
            Log.d(TAG, "instantiate item: " + position)
        }

        val keyboard = mEmojiCategory.getKeyboardFromAdapterPosition(mCategory, position)
        holder.getKeyboardView().setKeyboard(keyboard!!)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.getKeyboardView().releaseCurrentKey(false)
        holder.getKeyboardView().deallocateMemory()
    }

    override fun getItemCount(): Int {
        return mEmojiCategory.getCategoryPageCount(mCategory)
    }

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val customView: EmojiPageKeyboardView

        init {
            customView = v as EmojiPageKeyboardView
        }

        fun getKeyboardView(): EmojiPageKeyboardView {
            return customView
        }
    }

    companion object {
        private val TAG = EmojiPalettesAdapter::class.java.simpleName
        private const val DEBUG_PAGER = false
    }
}
