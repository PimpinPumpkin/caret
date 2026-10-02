// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import app.caret.latin.common.lengthOfWordBeforeCursor
import app.caret.latin.common.moveWordStepsToCharCount
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

    // holding backspace: the word before the cursor, by the keyboard's separators
    private fun wordLength(text: String) = lengthOfWordBeforeCursor(text) { it == ','.code || it == '.'.code || it == '?'.code || it == '!'.code }

    @Test fun holdDeletesTheLastWord() {
        assertEquals(5, wordLength("hello world"))
    }

    @Test fun holdTakesTrailingSpacesAndPunctuationWithTheWord() {
        assertEquals(6, wordLength("hello world "))
        assertEquals(7, wordLength("wait, what?! ")) // "what?! "
    }

    @Test fun holdKeepsApostrophesAndDigitsInTheWord() {
        assertEquals(5, wordLength("I don't"))
        assertEquals(4, wordLength("flight ab12"))
    }

    @Test fun holdOnOnlySpacesDeletesThem() {
        assertEquals(3, wordLength("   "))
        assertEquals(0, wordLength(""))
    }

    @Test fun holdHandlesEmojiAsAWord() {
        assertEquals("\uD83D\uDE00".length, wordLength("hi \uD83D\uDE00"))
    }

    @Test fun zeroStepsMoveNothing() {
        assertEquals(0, moveWordStepsToCharCount("hello", 0))
    }

    @Test fun forwardOverSelectedText() {
        assertEquals(5, moveWordStepsToCharCount("hello world", 1))
        assertEquals(11, moveWordStepsToCharCount("hello world", 2))
    }
}
