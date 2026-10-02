// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.swipe

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceWordsTest {
    @Test fun wordsOfTheCurrentSentence() {
        assertEquals(listOf("How", "are", "you"), FutoSwipe.sentenceWords("Hello there. How are you ", 16))
    }

    @Test fun punctuationInsideTheSentenceDoesNotCutIt() {
        assertEquals(listOf("Well", "I", "don't", "think", "so"), FutoSwipe.sentenceWords("Well, I don't think so", 16))
    }

    @Test fun nothingRightAfterTheEndOfASentence() {
        assertEquals(emptyList(), FutoSwipe.sentenceWords("That was it. ", 16))
        assertEquals(emptyList(), FutoSwipe.sentenceWords("line\n", 16))
        assertEquals(emptyList(), FutoSwipe.sentenceWords("", 16))
    }

    @Test fun onlyTheLastWords() {
        assertEquals(listOf("c", "d"), FutoSwipe.sentenceWords("a b c d", 2))
    }
}
