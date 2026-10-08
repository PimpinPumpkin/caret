// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.lm

import kotlin.math.sqrt

/**
 * How the taps of a word are written for the typing model, the same way training/typing_data.py
 * writes its simulated ones: each tap as the letter of the key whose middle it is nearest to, and a
 * tap near the border to another key as both letters, the nearer first ("oi": on the o, close to the
 * i). So the model can tell a tap in the middle of a key, which means that key, from one that could
 * be either, and a rare word tapped cleanly is not taken for a slip toward a common one.
 */
object TapEvidence {
    /** Near the border: the next key's middle is less than this much further away, in key widths. */
    const val NEAR = 0.4f

    /**
     * The taps at [tapX], [tapY] as the model reads them, one entry per tap.
     *
     * @param centers the middle of every letter key (a to z) of the keyboard
     * @param keyWidth the width of a letter key, in the units of the taps and centers
     */
    fun written(tapX: IntArray, tapY: IntArray, centers: Map<Char, Pair<Float, Float>>, keyWidth: Float): List<String> {
        if (keyWidth <= 0f || centers.size < 2) return emptyList()
        return tapX.indices.map { i ->
            var first = ' '
            var second = ' '
            var firstDistance = Float.MAX_VALUE
            var secondDistance = Float.MAX_VALUE
            for ((letter, center) in centers) {
                val dx = (tapX[i] - center.first) / keyWidth
                val dy = (tapY[i] - center.second) / keyWidth
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < firstDistance) {
                    second = first
                    secondDistance = firstDistance
                    first = letter
                    firstDistance = distance
                } else if (distance < secondDistance) {
                    second = letter
                    secondDistance = distance
                }
            }
            if (secondDistance - firstDistance < NEAR) "$first$second" else "$first"
        }
    }
}
