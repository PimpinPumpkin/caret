// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import app.caret.latin.lm.TapEvidence
import kotlin.test.Test
import kotlin.test.assertEquals

class TapEvidenceTest {
    // one row of keys 100 wide: q at 50, w at 150, ...
    private val centers = "qwertyuiop".withIndex().associate { (i, c) -> c to ((i * 100 + 50f) to 50f) }
    private fun written(vararg x: Int) = TapEvidence.written(x, IntArray(x.size) { 50 }, centers, 100f)

    @Test fun aTapInTheMiddleOfAKeyIsThatLetter() {
        assertEquals(listOf("t", "o"), written(450, 850))
        assertEquals(listOf("o"), written(870)) // still well inside the o
    }

    @Test fun aTapNearTheBorderIsBothLettersTheNearerFirst() {
        assertEquals(listOf("oi"), written(810))
        assertEquals(listOf("io"), written(790))
        assertEquals(listOf("op"), written(885))
    }

    @Test fun nothingWithoutKeys() {
        assertEquals(emptyList(), TapEvidence.written(intArrayOf(10), intArrayOf(10), emptyMap(), 100f))
    }
}
