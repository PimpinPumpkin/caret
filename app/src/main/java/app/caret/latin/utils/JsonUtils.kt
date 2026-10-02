/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.util.JsonReader

import java.io.Closeable
import java.io.IOException
import java.io.StringReader
import java.util.ArrayList
import java.util.Collections

// todo: remove around end of 2026 (used only for upgrading old recent emoji pref)
class JsonUtils {
    companion object {
        private val TAG = JsonUtils::class.java.simpleName

        private val INTEGER_CLASS_NAME = java.lang.Integer::class.java.simpleName
        private val STRING_CLASS_NAME = java.lang.String::class.java.simpleName

        private const val EMPTY_STRING = ""

        @JvmStatic
        fun jsonStrToList(s: String?): List<Any> {
            val list = ArrayList<Any>()
            val reader = JsonReader(StringReader(s))
            try {
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name == INTEGER_CLASS_NAME) {
                            list.add(reader.nextInt())
                        } else if (name == STRING_CLASS_NAME) {
                            list.add(reader.nextString())
                        } else {
                            Log.w(TAG, "Invalid name: " + name)
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                reader.endArray()
                return list
            } catch (ignored: IOException) {
            } finally {
                close(reader)
            }
            return Collections.emptyList()
        }

        private fun close(closeable: Closeable?) {
            try {
                if (closeable != null) {
                    closeable.close()
                }
            } catch (e: IOException) {
                // Ignore
            }
        }
    }
}
