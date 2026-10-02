/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.content.res.Resources
import android.util.Pair
import android.view.KeyEvent
import app.caret.keyboard.KeyboardSwitcher
import app.caret.latin.settings.Settings
import app.caret.latin.utils.Log

/**
 * A class for detecting Emoji-Alt physical key.
 */
class EmojiAltPhysicalKeyDetector(resources: Resources) {
    private val hotKeysList: MutableList<EmojiHotKeys>

    private class HotKeySet : HashSet<Pair<Int, Int>>()

    private abstract class EmojiHotKeys(name: String, keySet: HotKeySet) {
        private val mName: String
        private val mKeySet: HotKeySet

        var canFire: Boolean
        var mMetaState: Int = 0

        init {
            mName = name
            mKeySet = keySet
            canFire = false
        }

        fun onKeyDown(keyEvent: KeyEvent) {
            if (DEBUG) {
                Log.d(TAG, "EmojiHotKeys.onKeyDown() - " + mName + " - considering " + keyEvent)
            }

            val key: Pair<Int, Int> =
                    Pair.create(keyEvent.keyCode, keyEvent.metaState)
            if (mKeySet.contains(key)) {
                if (DEBUG) {
                   Log.d(TAG, "EmojiHotKeys.onKeyDown() - " + mName + " - enabling action")
                }
                canFire = true
                mMetaState = keyEvent.metaState
            } else if (canFire) {
                if (DEBUG) {
                   Log.d(TAG, "EmojiHotKeys.onKeyDown() - " + mName + " - disabling action")
                }
                canFire = false
            }
        }

        fun onKeyUp(keyEvent: KeyEvent) {
            if (DEBUG) {
                Log.d(TAG, "EmojiHotKeys.onKeyUp() - " + mName + " - considering " + keyEvent)
            }

            val keyCode = keyEvent.keyCode
            var metaState = keyEvent.metaState
            if (KeyEvent.isModifierKey(keyCode)) {
                 // Try restoring meta stat in case the released key was a modifier.
                 // I am sure one can come up with scenarios to break this, but it
                 // seems to work well in practice.
                 metaState = metaState or mMetaState
            }

            val key: Pair<Int, Int> = Pair.create(keyCode, metaState)
            if (mKeySet.contains(key)) {
                if (canFire) {
                    if (!keyEvent.isCanceled) {
                        if (DEBUG) {
                            Log.d(TAG, "EmojiHotKeys.onKeyUp() - " + mName + " - firing action")
                        }
                        action()
                    } else {
                        // This key up event was a part of key combinations and
                        // should be ignored.
                        if (DEBUG) {
                            Log.d(TAG, "EmojiHotKeys.onKeyUp() - " + mName + " - canceled, ignoring action")
                        }
                    }
                    canFire = false
                }
            }

            if (canFire) {
                if (DEBUG) {
                    Log.d(TAG, "EmojiHotKeys.onKeyUp() - " + mName + " - disabling action")
                }
                canFire = false
            }
        }

        protected abstract fun action()
    }

    init {
        hotKeysList = ArrayList()

        val emojiSwitchSet = parseHotKeys(
                resources, R.array.keyboard_switcher_emoji)
        val emojiHotKeys: EmojiHotKeys = object : EmojiHotKeys("emoji", emojiSwitchSet) {
            override fun action() {
                val switcher = KeyboardSwitcher.getInstance()
                switcher.onToggleKeyboard(KeyboardSwitcher.KeyboardSwitchState.EMOJI)
            }
        }
        hotKeysList.add(emojiHotKeys)

        val symbolsSwitchSet = parseHotKeys(
                resources, R.array.keyboard_switcher_symbols_shifted)
        val symbolsHotKeys: EmojiHotKeys = object : EmojiHotKeys("symbols", symbolsSwitchSet) {
            override fun action() {
                val switcher = KeyboardSwitcher.getInstance()
                switcher.onToggleKeyboard(KeyboardSwitcher.KeyboardSwitchState.SYMBOLS_SHIFTED)
            }
        }
        hotKeysList.add(symbolsHotKeys)
    }

    fun onKeyDown(keyEvent: KeyEvent) {
        if (DEBUG) {
            Log.d(TAG, "onKeyDown(): " + keyEvent)
        }

        if (shouldProcessEvent(keyEvent)) {
            for (hotKeys in hotKeysList) {
                hotKeys.onKeyDown(keyEvent)
            }
        }
    }

    fun onKeyUp(keyEvent: KeyEvent) {
        if (DEBUG) {
            Log.d(TAG, "onKeyUp(): " + keyEvent)
        }

        if (shouldProcessEvent(keyEvent)) {
            for (hotKeys in hotKeysList) {
                hotKeys.onKeyUp(keyEvent)
            }
        }
    }

    companion object {
        private const val TAG = "EmojiAltPhysKeyDetector"
        private const val DEBUG = false

        private fun shouldProcessEvent(keyEvent: KeyEvent): Boolean {
            if (!Settings.getValues().enableEmojiAltPhysicalKey) {
                // The feature is disabled.
                if (DEBUG) {
                    Log.d(TAG, "shouldProcessEvent(): Disabled")
                }
                return false
            }

            return true
        }

        private fun parseHotKeys(
                resources: Resources, resourceId: Int): HotKeySet {
            val keySet = HotKeySet()
            val name = resources.getResourceEntryName(resourceId)
            val values: Array<String>? = resources.getStringArray(resourceId)
            var i = 0
            while (values != null && i < values.size) {
                val valuePair = values[i].split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (valuePair.size != 2) {
                    Log.w(TAG, "Expected 2 integers in " + name + "[" + i + "] : " + values[i])
                }
                try {
                    val keyCode = Integer.parseInt(valuePair[0])
                    val metaState = Integer.parseInt(valuePair[1])
                    val key: Pair<Int, Int> = Pair.create(
                            keyCode, KeyEvent.normalizeMetaState(metaState))
                    keySet.add(key)
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Failed to parse " + name + "[" + i + "] : " + values[i], e)
                }
                i++
            }
            return keySet
        }
    }
}
