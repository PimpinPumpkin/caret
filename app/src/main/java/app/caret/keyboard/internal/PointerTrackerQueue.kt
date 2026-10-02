/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.latin.utils.Log

import java.util.ArrayList

class PointerTrackerQueue {
    interface Element {
        fun isModifier(): Boolean
        fun isInDraggingFinger(): Boolean
        fun onPhantomUpEvent(eventTime: Long)
        fun cancelTrackingForAction()
    }

    // Note: {@link #expandableArrayOfActivePointers} and {@link #mArraySize} are synchronized by
    // {@link #expandableArrayOfActivePointers}
    private val expandableArrayOfActivePointers =
            ArrayList<Element>(INITIAL_CAPACITY)
    private var mArraySize = 0

    fun size(): Int {
        synchronized(expandableArrayOfActivePointers) {
            return mArraySize
        }
    }

    fun add(pointer: Element) {
        synchronized(expandableArrayOfActivePointers) {
            if (DEBUG) {
                Log.d(TAG, "add: " + pointer + " " + this)
            }
            val expandableArray = expandableArrayOfActivePointers
            val arraySize = mArraySize
            if (arraySize < expandableArray.size) {
                expandableArray.set(arraySize, pointer)
            } else {
                expandableArray.add(pointer)
            }
            mArraySize = arraySize + 1
        }
    }

    fun remove(pointer: Element) {
        synchronized(expandableArrayOfActivePointers) {
            if (DEBUG) {
                Log.d(TAG, "remove: " + pointer + " " + this)
            }
            val expandableArray = expandableArrayOfActivePointers
            val arraySize = mArraySize
            var newIndex = 0
            for (index in 0 until arraySize) {
                val element = expandableArray.get(index)
                if (element === pointer) {
                    if (newIndex != index) {
                        Log.w(TAG, "Found duplicated element in remove: " + pointer)
                    }
                    continue // Remove this element from the expandableArray.
                }
                if (newIndex != index) {
                    // Shift this element toward the beginning of the expandableArray.
                    expandableArray.set(newIndex, element)
                }
                newIndex++
            }
            mArraySize = newIndex
        }
    }

    fun getOldestElement(): Element? {
        synchronized(expandableArrayOfActivePointers) {
            return if (mArraySize == 0) null else expandableArrayOfActivePointers.get(0)
        }
    }

    fun releaseAllPointersOlderThan(pointer: Element, eventTime: Long) {
        synchronized(expandableArrayOfActivePointers) {
            if (DEBUG) {
                Log.d(TAG, "releaseAllPointerOlderThan: " + pointer + " " + this)
            }
            val expandableArray = expandableArrayOfActivePointers
            val arraySize = mArraySize
            var newIndex = 0
            var index = 0
            while (index < arraySize) {
                val element = expandableArray.get(index)
                if (element === pointer) {
                    break // Stop releasing elements.
                }
                if (!element.isModifier()) {
                    element.onPhantomUpEvent(eventTime)
                    index++
                    continue // Remove this element from the expandableArray.
                }
                if (newIndex != index) {
                    // Shift this element toward the beginning of the expandableArray.
                    expandableArray.set(newIndex, element)
                }
                newIndex++
                index++
            }
            // Shift rest of the expandableArray.
            var count = 0
            while (index < arraySize) {
                val element = expandableArray.get(index)
                if (element === pointer) {
                    count++
                    if (count > 1) {
                        Log.w(TAG, "Found duplicated element in releaseAllPointersOlderThan: "
                                + pointer)
                    }
                }
                if (newIndex != index) {
                    // Shift this element toward the beginning of the expandableArray.
                    expandableArray.set(newIndex, expandableArray.get(index))
                }
                newIndex++
                index++
            }
            mArraySize = newIndex
        }
    }

    fun releaseAllPointers(eventTime: Long) {
        releaseAllPointersExcept(null, eventTime)
    }

    fun releaseAllPointersExcept(pointer: Element?, eventTime: Long) {
        synchronized(expandableArrayOfActivePointers) {
            if (DEBUG) {
                if (pointer == null) {
                    Log.d(TAG, "releaseAllPointers: " + this)
                } else {
                    Log.d(TAG, "releaseAllPointerExcept: " + pointer + " " + this)
                }
            }
            val expandableArray = expandableArrayOfActivePointers
            val arraySize = mArraySize
            var newIndex = 0
            var count = 0
            for (index in 0 until arraySize) {
                val element = expandableArray.get(index)
                if (element === pointer) {
                    count++
                    if (count > 1) {
                        Log.w(TAG, "Found duplicated element in releaseAllPointersExcept: "
                                + pointer)
                    }
                } else {
                    element.onPhantomUpEvent(eventTime)
                    continue // Remove this element from the expandableArray.
                }
                if (newIndex != index) {
                    // Shift this element toward the beginning of the expandableArray.
                    expandableArray.set(newIndex, element)
                }
                newIndex++
            }
            mArraySize = newIndex
        }
    }

    fun hasModifierKeyOlderThan(pointer: Element): Boolean {
        synchronized(expandableArrayOfActivePointers) {
            val arraySize = mArraySize
            for (index in 0 until arraySize) {
                val element = expandableArrayOfActivePointers.get(index)
                if (element === pointer) {
                    return false // Stop searching modifier key.
                }
                if (element.isModifier()) {
                    return true
                }
            }
            return false
        }
    }

    fun isAnyInDraggingFinger(): Boolean {
        synchronized(expandableArrayOfActivePointers) {
            val arraySize = mArraySize
            for (index in 0 until arraySize) {
                val element = expandableArrayOfActivePointers.get(index)
                if (element.isInDraggingFinger()) {
                    return true
                }
            }
            return false
        }
    }

    fun cancelAllPointerTrackers() {
        synchronized(expandableArrayOfActivePointers) {
            if (DEBUG) {
                Log.d(TAG, "cancelAllPointerTracker: " + this)
            }
            val arraySize = mArraySize
            for (index in 0 until arraySize) {
                val element = expandableArrayOfActivePointers.get(index)
                element.cancelTrackingForAction()
            }
        }
    }

    override fun toString(): String {
        synchronized(expandableArrayOfActivePointers) {
            val sb = StringBuilder()
            val arraySize = mArraySize
            for (index in 0 until arraySize) {
                val element = expandableArrayOfActivePointers.get(index)
                if (sb.length > 0) {
                    sb.append(" ")
                }
                sb.append(element.toString())
            }
            return "[" + sb + "]"
        }
    }

    companion object {
        private val TAG = PointerTrackerQueue::class.java.getSimpleName()
        private const val DEBUG = false

        private const val INITIAL_CAPACITY = 10
    }
}
