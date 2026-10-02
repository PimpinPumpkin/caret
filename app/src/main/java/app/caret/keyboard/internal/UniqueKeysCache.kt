/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.keyboard.Key

import java.util.HashMap

abstract class UniqueKeysCache {
    abstract fun setEnabled(enabled: Boolean)
    abstract fun clear()
    abstract fun getUniqueKey(key: Key): Key

    private class UniqueKeysCacheImpl : UniqueKeysCache() {
        private val cache: HashMap<Key, Key>

        private var mEnabled = false

        init {
            cache = HashMap()
        }

        override fun setEnabled(enabled: Boolean) {
            mEnabled = enabled
        }

        override fun clear() {
            cache.clear()
        }

        override fun getUniqueKey(key: Key): Key {
            if (!mEnabled) {
                return key
            }
            val existingKey = cache.get(key)
            if (existingKey != null) {
                // Reuse the existing object that equals to "key" without adding "key" to
                // the cache.
                return existingKey
            }
            cache.put(key, key)
            return key
        }
    }

    companion object {
        @JvmField
        val NO_CACHE: UniqueKeysCache = object : UniqueKeysCache() {
            override fun setEnabled(enabled: Boolean) {}

            override fun clear() {}

            override fun getUniqueKey(key: Key): Key { return key }
        }

        @JvmStatic
        fun newInstance(): UniqueKeysCache {
            return UniqueKeysCacheImpl()
        }
    }
}
