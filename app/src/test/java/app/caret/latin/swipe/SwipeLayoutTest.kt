// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.swipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwipeLayoutTest {
    private val keyWidth = 108
    private val rowHeight = 164

    /** A 1080 wide QWERTY keyboard whose letter rows start at [top], with a number row above and a bottom row below. */
    private fun qwerty(top: Int): List<KeyBox> {
        val keys = ArrayList<KeyBox>()
        "1234567890".forEachIndexed { i, c -> keys.add(KeyBox(c.code, i * keyWidth, top - rowHeight, keyWidth, rowHeight)) }
        "qwertyuiop".forEachIndexed { i, c -> keys.add(KeyBox(c.code, i * keyWidth, top, keyWidth, rowHeight)) }
        "asdfghjkl".forEachIndexed { i, c -> keys.add(KeyBox(c.code, keyWidth / 2 + i * keyWidth, top + rowHeight, keyWidth, rowHeight)) }
        keys.add(KeyBox(-11, 0, top + 2 * rowHeight, 162, rowHeight)) // shift
        "zxcvbnm".forEachIndexed { i, c -> keys.add(KeyBox(c.code, 162 + i * keyWidth, top + 2 * rowHeight, keyWidth, rowHeight)) }
        keys.add(KeyBox(-7, 918, top + 2 * rowHeight, 162, rowHeight)) // delete
        keys.add(KeyBox(' '.code, 270, top + 3 * rowHeight, 540, rowHeight))
        keys.add(KeyBox(','.code, 162, top + 3 * rowHeight, keyWidth, rowHeight))
        return keys
    }

    @Test fun lettersAreInReadingOrder() {
        val layout = assertNotNull(SwipeLayout.fromKeys(qwerty(164).shuffled(), 1080, 48))
        assertEquals("qwertyuiopasdfghjklzxcvbnm", layout.letters)
        assertTrue(layout.isQwerty)
    }

    @Test fun centersMatchTheLibrarysQwertyLayout() {
        // values from models/layouts/en_qwerty.json in the swipe library
        val layout = assertNotNull(SwipeLayout.fromKeys(qwerty(164), 1080, 48))
        fun center(letter: Char) = layout.letters.indexOf(letter).let { layout.cx[it] to layout.cy[it] }
        assertClose(0.05f to 0.1667f, center('q'))
        assertClose(0.1f to 0.5f, center('a'))
        assertClose(0.3f to 0.5f, center('d'))
        assertClose(0.4f to 0.8333f, center('c'))
        assertClose(0.6f to 0.8333f, center('b'))
        assertClose(0.95f to 0.1667f, center('p'))
    }

    @Test fun touchPointsUseTheSameFrameAsTheCenters() {
        val top = 164
        val layout = assertNotNull(SwipeLayout.fromKeys(qwerty(top), 1080, 48))
        // the middle of the g key
        val gx = keyWidth / 2 + 4 * keyWidth + keyWidth / 2
        val gy = top + rowHeight + rowHeight / 2
        val index = layout.letters.indexOf('g')
        assertEquals(layout.cx[index], layout.normalizeX(gx), 0.001f)
        assertEquals(layout.cy[index], layout.normalizeY(gy), 0.001f)
    }

    @Test fun aNumberRowDoesNotShiftTheLetters() {
        val without = assertNotNull(SwipeLayout.fromKeys(qwerty(0).filter { !Character.isDigit(it.code) }, 1080, 48))
        val with = assertNotNull(SwipeLayout.fromKeys(qwerty(164), 1080, 48))
        assertTrue(without.cx.contentEquals(with.cx))
        assertTrue(without.cy.contentEquals(with.cy))
    }

    @Test fun shiftedKeysCountOnce() {
        val upper = qwerty(164).map { if (Character.isLetter(it.code)) it.copy(code = Character.toUpperCase(it.code)) else it }
        assertEquals("qwertyuiopasdfghjklzxcvbnm", assertNotNull(SwipeLayout.fromKeys(upper, 1080, 48)).letters)
    }

    @Test fun keyboardsWithoutLettersGiveNoLayout() {
        val symbols = "1234567890@#$".mapIndexed { i, c -> KeyBox(c.code, i * 80, 0, 80, 100) }
        assertNull(SwipeLayout.fromKeys(symbols, 1080, 48))
    }

    private fun assertClose(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.002f)
        assertEquals(expected.second, actual.second, 0.002f)
    }
}

class SwipeFormTest {
    private val qwertz = "qwertzuiopasdfghjklyxcvbnm"
    private val spanish = "qwertyuiopasdfghjklñzxcvbnm"

    @org.junit.Test fun `accented letters without a key become their base letter`() {
        kotlin.test.assertEquals("uber", swipeForm("Über", qwertz))
        kotlin.test.assertEquals("eleve", swipeForm("élève", qwertz))
        kotlin.test.assertEquals("francais", swipeForm("français", qwertz))
        kotlin.test.assertEquals("strasse", swipeForm("Straße", qwertz))
        kotlin.test.assertEquals("coeur", swipeForm("cœur", qwertz))
    }

    @org.junit.Test fun `a letter that has its own key stays`() {
        kotlin.test.assertEquals("mañana", swipeForm("mañana", spanish))
        kotlin.test.assertEquals("tambien", swipeForm("también", spanish))
        kotlin.test.assertEquals("manana", swipeForm("mañana", qwertz))
    }

    @org.junit.Test fun `what is not a letter is left out`() {
        kotlin.test.assertEquals("dont", swipeForm("don't", qwertz))
        kotlin.test.assertEquals("i", swipeForm("I", qwertz))
        kotlin.test.assertEquals("", swipeForm("123", qwertz))
    }
}
