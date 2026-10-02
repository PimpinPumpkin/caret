// SPDX-License-Identifier: GPL-3.0-only
package app.caret

import app.caret.keyboard.RemoteNavigation
import app.caret.keyboard.RemoteNavigation.Companion.DOWN
import app.caret.keyboard.RemoteNavigation.Companion.LEFT
import app.caret.keyboard.RemoteNavigation.Companion.RIGHT
import app.caret.keyboard.RemoteNavigation.Companion.UP
import app.caret.keyboard.RemoteNavigation.KeyBounds
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteNavigationTest {
    // q w e r t y u i o p / a s d f g h j k l (half a key in) / shift z x c v b n m delete / symbols comma space period enter
    private val labels = ArrayList<String>()
    private val boxes = ArrayList<KeyBounds>()
    private fun row(y: Int, start: Int, vararg keys: Pair<String, Int>) {
        var x = start
        for ((label, width) in keys) {
            labels.add(label); boxes.add(KeyBounds(x, y, x + width, y + 100)); x += width
        }
    }
    init {
        row(0, 0, *"qwertyuiop".map { it.toString() to 100 }.toTypedArray())
        row(100, 50, *"asdfghjkl".map { it.toString() to 100 }.toTypedArray())
        row(200, 0, "shift" to 150, *"zxcvbnm".map { it.toString() to 100 }.toTypedArray(), "delete" to 150)
        row(300, 0, "symbols" to 150, "," to 100, "space" to 500, "." to 100, "enter" to 150)
    }
    private fun move(from: String, direction: Int) = labels[RemoteNavigation.next(boxes, labels.indexOf(from), direction)]

    @Test fun `left and right stay in the row and stop at its ends`() {
        assertEquals("w", move("q", RIGHT))
        assertEquals("q", move("w", LEFT))
        assertEquals("q", move("q", LEFT))
        assertEquals("p", move("p", RIGHT))
        assertEquals("z", move("shift", RIGHT))
        assertEquals("delete", move("m", RIGHT))
    }

    @Test fun `up and down go to the key above or below`() {
        assertEquals("a", move("q", DOWN))
        assertEquals("shift", move("a", DOWN))
        assertEquals("symbols", move("shift", DOWN))
        assertEquals("space", move("v", DOWN))
        assertEquals("v", move("space", UP))
        assertEquals("delete", move("enter", UP))
        assertEquals("l", move("delete", UP))
        assertEquals("p", move("l", UP))
    }

    @Test fun `the first and last row are the end`() {
        assertEquals("t", move("t", UP))
        assertEquals("space", move("space", DOWN))
    }

    @Test fun `the highlight starts in the middle and is found again by position`() {
        assertEquals("v", labels[RemoteNavigation.startIndex(boxes)])
        assertEquals("g", labels[RemoteNavigation.nearest(boxes, 500, 150)])
        assertEquals("space", labels[RemoteNavigation.nearest(boxes, 400, 390)])
    }
}
