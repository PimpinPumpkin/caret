// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import app.caret.keyboard.LetterModel
import app.caret.keyboard.LikelyKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LikelyKeysTest {
    private val model = LetterModel.build { add ->
        add("the", 1000.0); add("then", 300.0); add("they", 300.0); add("this", 500.0); add("that", 500.0)
        add("two", 50.0); add("thwart", 1.0); add("we", 400.0); add("were", 300.0); add("Don't", 100.0)
    }

    @Test fun lettersAreCountedByWhatCameBefore() {
        val e = model.score("th", 'e')!!
        val w = model.score("th", 'w')!!
        assertTrue(e > w + 3, "after th an e ($e) is far likelier than a w ($w)")
        assertTrue(model.score("", 't')!! > model.score("", 'w')!!)
        assertEquals(null, model.score("th", '\''))
        assertEquals(null, model.score("d'", 't'))
    }

    @Test fun onlyATapNearTheEdgeGoesToAMuchLikelierNeighbor() {
        val e = model.score("th", 'e')!!
        val w = model.score("th", 'w')!!
        assertTrue(LikelyKeys.wins(own = w, other = e, distance = 0.02f))
        assertTrue(LikelyKeys.wins(own = w, other = e, distance = 0.15f))
        assertFalse(LikelyKeys.wins(own = w, other = e, distance = 0.46f)) // the middle of the w: a w was meant
        assertFalse(LikelyKeys.wins(own = e, other = w, distance = 0.02f)) // never toward the less likely letter
    }

    @Test fun aLetterAboutAsLikelyIsLeftAlone() {
        val i = model.score("th", 'i')!!
        val a = model.score("th", 'a')!!
        assertFalse(LikelyKeys.wins(own = i, other = a, distance = 0.01f))
        assertFalse(LikelyKeys.wins(own = a, other = i, distance = 0.01f))
    }
}
