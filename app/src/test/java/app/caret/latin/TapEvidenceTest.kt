// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import app.caret.latin.lm.TapEvidence
import kotlin.test.Test
import kotlin.test.assertEquals

class TapEvidenceTest {
    // one row of keys 100 wide: q at 50, w at 150, ...
    private val centers = "qwertyuiop".withIndex().associate { (i, c) -> c to ((i * 100 + 50f) to 50f) }
    private fun fit(word: String, vararg x: Int) = TapEvidence.fit(word, x, IntArray(x.size) { 50 }, centers, 100f, 5.5f, 4.5f)

    @Test fun cleanTapsFitTheirWordAndNotItsNeighbor() {
        assertEquals(0f, fit("to", 450, 850), 0.001f)
        assertEquals(-5.5f, fit("ti", 450, 850), 0.01f)   // the second tap was in the middle of the o
        assertEquals(-1.375f, fit("ti", 450, 800), 0.01f) // on the border it could be either
        assertEquals(fit("ti", 450, 800), fit("to", 450, 800), 0.01f)
    }

    @Test fun slipsCostAFixedPrice() {
        assertEquals(-4.5f, fit("too", 450, 850), 0.01f)        // an o not tapped
        assertEquals(-4.5f, fit("to", 450, 850, 850), 0.01f)    // an o tapped twice
        assertEquals(-4.5f, fit("ot", 450, 850), 0.01f)         // the two the wrong way round
        assertEquals(-9f, fit("to", 450, 50), 0.01f)            // the second tap far off: no worse than two slips
    }

    @Test fun lettersWithoutAKeyNeedNoTap() {
        assertEquals(0f, fit("t'o", 450, 850), 0.001f)
    }
}
