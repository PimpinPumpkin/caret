// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.common.moveWordStepsToCharCount
import kotlin.test.Test
import kotlin.test.assertEquals

/** Deleting whole words by swiping backspace: how many characters a number of words is. */
class WordStepsTest {
    @Test fun oneWordBack() {
        assertEquals(-3, moveWordStepsToCharCount("hello world foo", -1))
    }

    @Test fun twoWordsBackIncludeTheSpaceBetween() {
        assertEquals(-9, moveWordStepsToCharCount("hello world foo", -2))
    }

    @Test fun trailingSpaceGoesWithTheWord() {
        assertEquals(-6, moveWordStepsToCharCount("hello world ", -1))
    }

    @Test fun punctuationAloneIsNotAWord() {
        assertEquals(-6, moveWordStepsToCharCount("wait, what?!", -1)) // "what?!"
    }

    @Test fun moreWordsThanThereAreStopsAtTheStart() {
        assertEquals(-11, moveWordStepsToCharCount("hello world", -5))
    }

    @Test fun apostrophesStayInsideAWord() {
        assertEquals(-5, moveWordStepsToCharCount("I don't", -1))
    }

    @Test fun zeroStepsMoveNothing() {
        assertEquals(0, moveWordStepsToCharCount("hello", 0))
    }

    @Test fun forwardOverSelectedText() {
        assertEquals(5, moveWordStepsToCharCount("hello world", 1))
        assertEquals(11, moveWordStepsToCharCount("hello world", 2))
    }
}
