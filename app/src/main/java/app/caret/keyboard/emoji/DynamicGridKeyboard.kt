/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.emoji

import android.content.SharedPreferences
import android.text.TextUtils
import app.caret.keyboard.Key
import app.caret.keyboard.Keyboard
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.keyboard.internal.PopupKeySpec
import app.caret.keyboard.internal.keyboard_parser.EMOJI_HINT_LABEL
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.common.Constants
import app.caret.latin.common.StringUtils
import app.caret.latin.settings.Settings
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale

/**
 * This is a Keyboard class where you can add keys dynamically shown in a grid layout
 */
class DynamicGridKeyboard private constructor(
    prefs: SharedPreferences, templateKeyboard: Keyboard,
    maxCount: Int, val isRecents: Boolean, width: Int, private val fixedRowCount: Boolean
) : Keyboard(templateKeyboard) {
    private val lock = Any()

    private val horizontalStep: Int
    private val horizontalGap: Int
    private val verticalStep: Int
    private val columnsNum: Int
    private val maxKeyCount: Int
    private val gridKeys = ArrayDeque<GridKey>()
    private val pendingKeys = ArrayDeque<Key>()

    private var cachedGridKeys: List<Key>? = null
    private val emptyColumnIndices = ArrayList<Int>(4)

    init {
        // todo: would be better to keep them final and not require width, but how to properly set width of the template keyboard?
        //  an alternative would be to always create the templateKeyboard with full width
        val paddingWidth = this.occupiedWidth - this.baseWidth
        this.baseWidth = width - paddingWidth
        this.occupiedWidth = width
        val spacerWidth = Settings.getValues().splitKeyboardSpacerRelativeWidth * this.baseWidth
        val key0 = getTemplateKey(Constants.RECENTS_TEMPLATE_KEY_CODE_0)
        val key1 = getTemplateKey(Constants.RECENTS_TEMPLATE_KEY_CODE_1)
        val templateGap = Math.abs(key1.getX() - key0.getX()) - key0.width
        val widthScale = determineWidthScale((key0.width + templateGap).toFloat())
        horizontalGap = (templateGap * widthScale).toInt()
        horizontalStep = ((key0.width + templateGap) * widthScale).toInt()
        verticalStep = ((key0.height + this.verticalGap) / Math.sqrt(Settings.getValues().keyboardHeightScale.toDouble())).toInt()
        columnsNum = this.baseWidth / horizontalStep
        if (spacerWidth > 0)
            setSpacerColumns(spacerWidth)
        maxKeyCount = if (fixedRowCount) maxCount * occupiedColumnCount else maxCount
    }

    private fun setSpacerColumns(spacerWidth: Float) {
        var spacerColumnsWidth = (spacerWidth / horizontalStep).toInt()
        if (spacerColumnsWidth == 0) return
        if (columnsNum % 2 != spacerColumnsWidth % 2)
            spacerColumnsWidth++
        val leftmost: Int
        val rightmost: Int
        if (spacerColumnsWidth % 2 == 0) {
            val center = columnsNum / 2
            leftmost = center - (spacerColumnsWidth / 2 - 1)
            rightmost = center + spacerColumnsWidth / 2
        } else {
            val center = columnsNum / 2 + 1
            leftmost = center - spacerColumnsWidth / 2
            rightmost = center + spacerColumnsWidth / 2
        }
        var i = leftmost
        while (i <= rightmost) {
            emptyColumnIndices.add(i - 1)
            i++
        }
    }

    // determine a width scale so emojis evenly fill the entire width
    private fun determineWidthScale(step: Float): Float {
        val columnsNumRaw = this.baseWidth / step
        val roundedColumnsNum = Math.round(columnsNumRaw).toFloat()
        return columnsNumRaw / roundedColumnsNum
    }

    private fun getTemplateKey(code: Int): Key {
        for (key in super.getSortedKeys()) {
            if (key.code == code) {
                return key
            }
        }
        throw RuntimeException("Can't find template key: code=$code")
    }

    // height is dynamic if we don't have a fixed row count
    fun getOccupiedHeight(): Int {
        val count = if (fixedRowCount) maxKeyCount else gridKeys.size
        val row = (count - 1) / occupiedColumnCount + 1
        return row * verticalStep
    }

    val occupiedColumnCount: Int
        get() = columnsNum - emptyColumnIndices.size

    fun addPendingKey(usedKey: Key) {
        synchronized(lock) {
            pendingKeys.addLast(usedKey)
        }
    }

    fun removeRecentsKey(key: Key) {
        val outputText = key.outputText
        if (outputText != null) RecentEmojis.remove(outputText)
        else RecentEmojis.removeCodepoint(key.code)
        if (key !is GridKey) return
        synchronized(lock) {
            gridKeys.remove(key)
            cachedGridKeys = null
            updateCoordinates()
        }
    }

    fun flushPendingRecentKeys() {
        synchronized(lock) {
            while (!pendingKeys.isEmpty()) {
                val key: Key? = pendingKeys.pollFirst()
                addKey(key, true)
                saveRecentKey(key)
            }
        }
    }

    fun addKeyFirst(usedKey: Key) {
        if (usedKey.code == KeyCode.UNSPECIFIED) return
        addKey(usedKey, true)
        if (isRecents) {
            saveRecentKey(usedKey)
        }
    }

    fun addKeyLast(usedKey: Key?) {
        addKey(usedKey, false)
    }

    fun removeAllKeys() {
        synchronized(lock) {
            gridKeys.clear()
            pendingKeys.clear()
            cachedGridKeys = null
        }
    }

    private fun addKey(usedKey: Key?, addFirst: Boolean) {
        if (usedKey == null) {
            return
        }
        synchronized(lock) {
            cachedGridKeys = null
            // When a key is added to recents keyboard, we don't want to keep its popup keys
            // neither its hint label. Also, we make sure its background type is matching our keyboard
            // if key comes from another keyboard (ie. a {@link PopupKeysKeyboard}).
            // Check if hint was a more emoji indicator and prevent its copy if popup keys aren't copied
            val dropHintLabel = isRecents && EMOJI_HINT_LABEL == usedKey.hintLabel
            val key = GridKey(usedKey,
                    if (isRecents) REMOVE_RECENT_POPUP_KEYS else usedKey.popupKeys,
                    if (dropHintLabel) null else usedKey.hintLabel,
                    if (isRecents) Key.BACKGROUND_TYPE_EMPTY else usedKey.backgroundType)
            while (gridKeys.remove(key)) {
                // Remove duplicate keys.
            }
            if (addFirst) {
                gridKeys.addFirst(key)
            } else {
                gridKeys.addLast(key)
            }
            while (gridKeys.size > maxKeyCount) {
                gridKeys.removeLast()
            }
            updateCoordinates()
        }
    }

    private fun updateCoordinates() {
        var index = 0
        for (gridKey in gridKeys) {
            while (emptyColumnIndices.contains(index % columnsNum)) {
                index++
            }
            val keyX0 = getKeyX0(index)
            val keyY0 = getKeyY0(index)
            val keyX1 = getKeyX1(index)
            val keyY1 = getKeyY1(index)
            gridKey.updateCoordinates(keyX0, keyY0, keyX1, keyY1)
            index++
        }
    }

    private fun saveRecentKey(key: Key?) {
        if (key == null) return
        val outputText = key.outputText
        if (outputText != null) RecentEmojis.add(outputText)
        else RecentEmojis.addCodepoint(key.code)
    }

    private fun getKeyByCode(keyboards: Collection<DynamicGridKeyboard>,
            code: Int): Key {
        for (keyboard in keyboards) {
            for (key in keyboard.getSortedKeys()) {
                if (key.code == code) {
                    return key
                }
            }
        }

        // fall back to creating the key
        return Key(getTemplateKey(Constants.RECENTS_TEMPLATE_KEY_CODE_0), null, null, Key.BACKGROUND_TYPE_EMPTY, code, null)
    }

    private fun getKeyByOutputText(keyboards: Collection<DynamicGridKeyboard>,
            outputText: String): Key {
        for (keyboard in keyboards) {
            for (key in keyboard.getSortedKeys()) {
                if (outputText == key.outputText) {
                    return key
                }
            }
        }

        // fall back to creating the key
        return Key(getTemplateKey(Constants.RECENTS_TEMPLATE_KEY_CODE_0), null, null, Key.BACKGROUND_TYPE_EMPTY, 0, outputText)
    }

    fun loadRecentKeys(keyboards: Collection<DynamicGridKeyboard>) {
        val emojis = RecentEmojis.get()
        for (emoji in emojis) {
            val key: Key
            if (StringUtils.codePointCount(emoji) == 1) {
                val code = Character.codePointAt(emoji, 0)
                key = getKeyByCode(keyboards, code)
            } else {
                key = getKeyByOutputText(keyboards, emoji)
            }
            addKeyLast(key)
        }
    }

    private fun getKeyX0(index: Int): Int {
        val column = index % columnsNum
        return column * horizontalStep + horizontalGap / 2
    }

    private fun getKeyX1(index: Int): Int {
        val column = index % columnsNum + 1
        return column * horizontalStep + horizontalGap / 2
    }

    private fun getKeyY0(index: Int): Int {
        val row = index / columnsNum
        return row * verticalStep + this.verticalGap / 2
    }

    private fun getKeyY1(index: Int): Int {
        val row = index / columnsNum + 1
        return row * verticalStep + this.verticalGap / 2
    }

    override fun getSortedKeys(): List<Key> {
        synchronized(lock) {
            val cached = cachedGridKeys
            if (cached != null) {
                return cached
            }
            val cachedKeys = ArrayList<Key>(gridKeys)
            val newCachedGridKeys = Collections.unmodifiableList(cachedKeys)
            cachedGridKeys = newCachedGridKeys
            return newCachedGridKeys
        }
    }

    override fun getNearestKeys(x: Int, y: Int): List<Key> {
        // TODO: Calculate the nearest key index in gridKeys from x and y.
        return getSortedKeys()
    }

    class GridKey(originalKey: Key, popupKeys: Array<PopupKeySpec>?,
            labelHint: String?, backgroundType: Int
    ) : Key(originalKey, popupKeys, labelHint, backgroundType) {
        private var currentX = 0
        private var currentY = 0

        fun updateCoordinates(x0: Int, y0: Int, x1: Int, y1: Int) {
            currentX = x0
            currentY = y0
            this.hitBox.set(x0, y0, x1, y1)
        }

        override fun getX(): Int {
            return currentX
        }

        override fun getY(): Int {
            return currentY
        }

        override fun equals(o: Any?): Boolean {
            if (o !is Key) return false
            val key: Key = o
            if (this.code != key.code) return false
            if (!TextUtils.equals(this.label, key.label)) return false
            return TextUtils.equals(this.outputText, key.outputText)
        }

        override fun toString(): String {
            return "GridKey: " + super.toString()
        }
    }

    companion object {
        private val TAG = DynamicGridKeyboard::class.java.simpleName

        @JvmStatic
        fun ofKeyCount(prefs: SharedPreferences, templateKeyboard: Keyboard,
                maxKeyCount: Int, isRecents: Boolean, width: Int): DynamicGridKeyboard {
            return DynamicGridKeyboard(prefs, templateKeyboard, maxKeyCount, isRecents, width, false)
        }

        @JvmStatic
        fun ofRowCount(prefs: SharedPreferences, templateKeyboard: Keyboard,
                maxRowCount: Int, isRecents: Boolean, width: Int): DynamicGridKeyboard {
            return DynamicGridKeyboard(prefs, templateKeyboard, maxRowCount, isRecents, width, true)
        }

        // Single delete button shown when long-pressing a key in the recents category.
        private val REMOVE_RECENT_POPUP_KEYS = arrayOf(
            PopupKeySpec(KeyboardIconsSet.PREFIX_ICON + KeyboardIconsSet.NAME_BIN
                + "|!code/" + KeyCode.UNSPECIFIED, false, Locale.ROOT)
        )
    }
}
