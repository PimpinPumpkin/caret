// SPDX-License-Identifier: GPL-3.0-only
package app.caret.keyboard

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Using the keyboard with a TV remote or a game pad: the arrow keys move a highlight from key
 * to key and the middle button presses the highlighted key, held down for its long-press popup.
 * The highlight is the key's pressed look. It is remembered as a point, not a key, because every
 * switch between letters, capitals and symbols builds new keys.
 */
class RemoteNavigation(private val view: () -> MainKeyboardView?) {
    private var focusX = -1
    private var focusY = -1
    private var highlighted: Key? = null
    private var selectDown = false

    /** True when the event was used to move or press, so the app must not get it. */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keyboardView = usableView() ?: return false
        val keys = navigableKeys(keyboardView)
        if (keys.isEmpty()) return false
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                // repeats of a held button are part of the same press
                if (event.repeatCount == 0) {
                    val key = current(keys) ?: return false
                    selectDown = true
                    touch(keyboardView, MotionEvent.ACTION_DOWN, key)
                }
                return true
            }
            else -> return false
        }
        val boxes = keys.map { KeyBounds(it.getX(), it.getY(), it.getX() + it.width, it.getY() + it.height) }
        val from = if (focusX < 0) -1 else nearest(boxes, focusX, focusY)
        val to = if (from < 0) startIndex(boxes) else next(boxes, from, direction)
        focusOn(keyboardView, keys[to])
        return true
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keyboardView = usableView() ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (selectDown) {
                    selectDown = false
                    current(navigableKeys(keyboardView))?.let { touch(keyboardView, MotionEvent.ACTION_UP, it) }
                    // the press may have switched the layout; show the highlight on whatever is there now
                    keyboardView.post { usableView()?.let { v -> current(navigableKeys(v))?.let { focusOn(v, it) } } }
                }
                true
            }
            else -> false
        }
    }

    /** Forgets the highlight, for when the keyboard goes away. */
    fun reset() {
        highlighted?.let { key ->
            key.onReleased()
            view()?.invalidateKey(key)
        }
        view()?.remoteFocusKey = null
        highlighted = null
        focusX = -1
        focusY = -1
        selectDown = false
    }

    private fun usableView(): MainKeyboardView? = view()?.takeIf { it.isShown && it.keyboard != null }

    private fun navigableKeys(keyboardView: MainKeyboardView): List<Key> =
        keyboardView.keyboard?.getSortedKeys()?.filter { !it.isSpacer && it.width > 0 && it.height > 0 } ?: emptyList()

    private fun current(keys: List<Key>): Key? {
        if (keys.isEmpty() || focusX < 0) return null
        val boxes = keys.map { KeyBounds(it.getX(), it.getY(), it.getX() + it.width, it.getY() + it.height) }
        return keys[nearest(boxes, focusX, focusY)]
    }

    private fun focusOn(keyboardView: MainKeyboardView, key: Key) {
        highlighted?.takeIf { it !== key }?.let { old ->
            old.onReleased()
            keyboardView.invalidateKey(old)
        }
        focusX = key.getX() + key.width / 2
        focusY = key.getY() + key.height / 2
        key.onPressed()
        keyboardView.invalidateKey(key)
        keyboardView.remoteFocusKey = key
        highlighted = key
    }

    private fun touch(keyboardView: MainKeyboardView, action: Int, key: Key) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, key.hitBox.centerX().toFloat(), key.hitBox.centerY().toFloat(), 0)
        keyboardView.onTouchEvent(event)
        event.recycle()
    }

    /** Where a key is, all that moving between keys needs to know. */
    data class KeyBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }

    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val UP = 2
        const val DOWN = 3

        /** The key under the point, or else the one whose middle is closest to it. */
        fun nearest(boxes: List<KeyBounds>, x: Int, y: Int): Int {
            boxes.indexOfFirst { x >= it.left && x < it.right && y >= it.top && y < it.bottom }.let { if (it >= 0) return it }
            return boxes.indices.minBy { i ->
                val dx = (boxes[i].centerX - x).toLong()
                val dy = (boxes[i].centerY - y).toLong()
                dx * dx + dy * dy
            }
        }

        /** Where the highlight first appears: the middle of the keyboard. */
        fun startIndex(boxes: List<KeyBounds>): Int =
            nearest(boxes, (boxes.minOf { it.left } + boxes.maxOf { it.right }) / 2, (boxes.minOf { it.top } + boxes.maxOf { it.bottom }) / 2)

        /**
         * The key one step from [from] in [direction]. Left and right stay in the row and stop at
         * its ends. Up and down go to the closest row that way and pick the key there that lies
         * over or under the middle of the current key; at the first and last row they stay.
         */
        fun next(boxes: List<KeyBounds>, from: Int, direction: Int): Int {
            val current = boxes[from]
            fun sameRow(other: KeyBounds) = other.centerY >= current.top && other.centerY < current.bottom
            return when (direction) {
                LEFT -> boxes.indices.filter { it != from && sameRow(boxes[it]) && boxes[it].centerX < current.centerX }
                    .maxByOrNull { boxes[it].centerX } ?: from
                RIGHT -> boxes.indices.filter { it != from && sameRow(boxes[it]) && boxes[it].centerX > current.centerX }
                    .minByOrNull { boxes[it].centerX } ?: from
                else -> {
                    val candidates = boxes.indices.filter {
                        if (direction == UP) boxes[it].centerY < current.top else boxes[it].centerY >= current.bottom
                    }
                    if (candidates.isEmpty()) return from
                    val rowY = if (direction == UP) candidates.maxOf { boxes[it].centerY } else candidates.minOf { boxes[it].centerY }
                    val row = candidates.filter { boxes[it].centerY >= boxes[candidates.first { c -> boxes[c].centerY == rowY }].top
                        && boxes[it].centerY < boxes[candidates.first { c -> boxes[c].centerY == rowY }].bottom }
                    row.firstOrNull { current.centerX >= boxes[it].left && current.centerX < boxes[it].right }
                        ?: row.minBy { kotlin.math.abs(boxes[it].centerX - current.centerX) }
                }
            }
        }
    }
}
