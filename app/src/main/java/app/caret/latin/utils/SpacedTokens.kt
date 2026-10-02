// SPDX-License-Identifier: GPL-3.0-only

package app.caret.latin.utils

/**
 * Tokenizes strings by groupings of non-space characters, making them iterable. Note that letters,
 * punctuations, etc. are all treated the same by this construct.
 */
class SpacedTokens(private val phrase: String) : Iterable<String> {
    private val length = phrase.length
    private val startPos = phrase.indexOfFirst { !Character.isWhitespace(it) }
    // the iterator should start at the first non-whitespace character

    override fun iterator() = object : Iterator<String> {
        private var tokenStart = startPos

        override fun hasNext(): Boolean {
            return tokenStart < length && tokenStart != -1
        }

        override fun next(): String {
            var endPos = tokenStart

            do if (++endPos >= length) break
            while (!Character.isWhitespace(phrase[endPos]))
            val word = phrase.substring(tokenStart, endPos)

            if (endPos < length) {
                do if (++endPos >= length) break
                while (Character.isWhitespace(phrase[endPos]))
            }
            tokenStart = endPos

            return word
        }
    }
}
