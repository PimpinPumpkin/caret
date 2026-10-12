// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.swipe

import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeBySentenceTest {
    @Test fun withoutAModelTheEnginesOrderStands() {
        assertEquals(listOf(0, 1, 2), FutoSwipe.bySentence(listOf(-1f, -1.2f, -3f), null))
        assertEquals(listOf(0, 1), FutoSwipe.bySentence(listOf(-1f, -1.2f), floatArrayOf(-2f)))
    }

    @Test fun aCloseSecondThatFitsTheSentenceComesFirst() {
        // "son" a little ahead by the swipe, "soon" far likelier after "see you"
        assertEquals(listOf(1, 0), FutoSwipe.bySentence(listOf(-1.0f, -1.3f), floatArrayOf(-9f, -2f)))
    }

    @Test fun aClearlyBetterSwipeStillWins() {
        assertEquals(listOf(0, 1), FutoSwipe.bySentence(listOf(-1.0f, -6.0f), floatArrayOf(-9f, -2f)))
    }
}
