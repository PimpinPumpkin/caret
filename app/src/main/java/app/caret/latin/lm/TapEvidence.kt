// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.lm

/** What the places of the taps of a word say about which word was meant. */
object TapEvidence {
    /**
     * How well the taps fit [word], as a log probability up to a constant: each tap is taken to be
     * aimed at a letter of the word and to land around the middle of its key (a bell curve, [sharpness]
     * being one over twice its squared spread in key widths), and now and then a letter is skipped,
     * tapped twice or swapped with the next, each of which costs [slip]. The best way of lining the taps
     * up with the letters counts. Letters that are no key (an apostrophe) cost nothing and need no tap.
     */
    fun fit(
        word: String, tapX: IntArray, tapY: IntArray, centers: Map<Char, Pair<Float, Float>>,
        keyWidth: Float, sharpness: Float, slip: Float,
    ): Float {
        val letters = word.lowercase().filter { it in centers }
        val taps = tapX.size
        if (keyWidth <= 0f) return 0f
        // off[i][j]: what it costs to take tap j for letter i, never more than two slips
        fun off(i: Int, j: Int): Float {
            val center = centers.getValue(letters[i])
            val dx = (tapX[j] - center.first) / keyWidth
            val dy = (tapY[j] - center.second) / keyWidth
            return minOf(sharpness * (dx * dx + dy * dy), 2 * slip)
        }
        var twoBack = FloatArray(taps + 1)
        var previous = FloatArray(taps + 1) { -it * slip } // no letters yet: every tap is one too many
        for (i in 1..letters.length) {
            val row = FloatArray(taps + 1)
            row[0] = -i * slip // no taps yet: every letter was skipped
            for (j in 1..taps) {
                var best = previous[j - 1] - off(i - 1, j - 1)          // this tap is this letter
                best = maxOf(best, previous[j] - slip)                    // the letter was skipped
                best = maxOf(best, row[j - 1] - slip)                     // a tap too many
                if (i > 1 && j > 1)                                       // two letters in the wrong order
                    best = maxOf(best, twoBack[j - 2] - slip - off(i - 1, j - 2) - off(i - 2, j - 1))
                row[j] = best
            }
            twoBack = previous
            previous = row
        }
        return previous[taps]
    }
}
