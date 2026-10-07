// SPDX-License-Identifier: GPL-3.0-only
package app.caret.keyboard

import android.content.Context
import app.caret.latin.settings.Settings
import app.caret.latin.swipe.WordList
import app.caret.latin.utils.Log
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * How likely each letter is after the letters of a word so far, counted from a dictionary: after "th"
 * an e is far likelier than a w. Looks at the last three letters and at where the word starts.
 */
class LetterModel private constructor(private val logProbability: FloatArray) {
    /** The log probability of [letter] (a to z) coming next in a word that so far reads [prefix]. */
    fun score(prefix: String, letter: Char): Float? {
        val index = letter - 'a'
        if (index !in 0 until LETTERS) return null
        val context = contextOf(prefix) ?: return null
        return logProbability[context * LETTERS + index]
    }

    companion object {
        private const val LETTERS = 26
        private const val START = 26  // before the first letter of a word
        private const val SYMBOLS = 27
        private const val CONTEXTS = SYMBOLS * SYMBOLS * SYMBOLS

        /** The last three letters of [prefix] as one number; null when one of them is not a to z. */
        private fun contextOf(prefix: String): Int? {
            var context = 0
            for (back in 3 downTo 1) {
                val at = prefix.length - back
                val symbol = if (at < 0) START else (prefix[at].lowercaseChar() - 'a').takeIf { it in 0 until LETTERS } ?: return null
                context = context * SYMBOLS + symbol
            }
            return context
        }

        /** [words] gives every word with a weight for how common it is. */
        fun build(words: ((String, Double) -> Unit) -> Unit): LetterModel {
            val counts = DoubleArray(CONTEXTS * LETTERS)
            words { word, weight ->
                val lower = word.lowercase(Locale.ROOT)
                if (lower.isNotEmpty() && lower.all { it in 'a'..'z' }) {
                    for (i in lower.indices) {
                        val context = contextOf(lower.substring(0, i)) ?: break
                        counts[context * LETTERS + (lower[i] - 'a')] += weight
                    }
                }
            }
            val out = FloatArray(counts.size)
            for (context in 0 until CONTEXTS) {
                var total = 0.0
                for (l in 0 until LETTERS) total += counts[context * LETTERS + l]
                // a little weight for every letter: nothing is impossible, and where nothing was seen all are equal
                val floor = maxOf(total * 0.0005, 1e-9)
                val sum = total + floor * LETTERS
                for (l in 0 until LETTERS) out[context * LETTERS + l] = ln((counts[context * LETTERS + l] + floor) / sum).toFloat()
            }
            return LetterModel(out)
        }
    }
}

/**
 * Caret: a letter that is likely to come next gets a larger touch target, without looking any
 * different. Only a tap close to the edge of a key can go to the neighbor instead, and only when the
 * neighbor is much the likelier letter there; the closer to the middle of its own key the tap is, the
 * stronger that has to be, and a tap near the middle always gives the key it is on. So "thw" comes
 * out as "the" when the w was hit at its edge toward the e, and names and odd words can still be typed.
 */
object LikelyKeys {
    private const val TAG = "LikelyKeys"
    // The three numbers below were measured with scripts/typing-benchmark.py, autocorrect off, taps with
    // noise of 0.3 key widths: words that come out letter for letter went from 57% to 69%, rare words
    // tapped with noise of 0.25 from 75% to 87.5%, and cleanly tapped rare words stayed at 100%.
    // A wider zone with a lower bar (0.5, 0.4, 2.0) started to change cleanly tapped rare words.
    /** How far into a key, as a share of its width, a tap can be and still go to the neighbor. */
    private var EDGE_ZONE = 0.45f
    /** How many times likelier the neighbor has to be for a tap right on the border: e to this power. */
    private var RATIO_AT_BORDER = 0.7f
    /** ... and how much more than that at the inner end of the zone. */
    private var RATIO_GROWTH = 3.0f

    @Volatile private var model: LetterModel? = null
    @Volatile private var modelLocale: Locale? = null
    private var loading = false

    /** What has been typed of the current word, or null when the next key does not go on the end of a word in the making. */
    @Volatile var typedSoFar: () -> String? = { null }

    /** Counts the letters of [locale]'s dictionary in the background, once per language. */
    @Synchronized
    fun prepare(context: Context, locale: Locale) {
        if (loading || modelLocale == locale) return
        loading = true
        if (app.caret.latin.BuildConfig.DEBUG) try {
            // debug builds read the three numbers from tuning.properties in the app's external files folder
            val file = java.io.File(context.getExternalFilesDir(null), "tuning.properties")
            if (file.isFile) java.util.Properties().apply { file.inputStream().use { load(it) } }.let { p ->
                p.getProperty("likely_zone")?.toFloatOrNull()?.let { EDGE_ZONE = it }
                p.getProperty("likely_border")?.toFloatOrNull()?.let { RATIO_AT_BORDER = it }
                p.getProperty("likely_growth")?.toFloatOrNull()?.let { RATIO_GROWTH = it }
                Log.i(TAG, "tuning: zone $EDGE_ZONE border $RATIO_AT_BORDER growth $RATIO_GROWTH")
            }
        } catch (e: Exception) {
            Log.w(TAG, "no tuning values", e)
        }
        val appContext = context.applicationContext
        thread(name = "likely-keys") {
            val start = System.currentTimeMillis()
            val built = try {
                WordList.forLocale(appContext, locale)?.let { list ->
                    // dictionary frequencies are on a log scale of 0 to 255
                    LetterModel.build { add -> list.words { word, frequency -> add(word, Math.pow(1.045, frequency.toDouble())) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not count letters", e)
                null
            }
            synchronized(this) {
                model = built
                modelLocale = locale
                loading = false
            }
            Log.i(TAG, "letters of $locale counted in ${System.currentTimeMillis() - start} ms: ${built != null}")
        }
    }

    /** The key a tap at [x], [y] on [key] is taken for: [key] itself, or a much likelier neighbor it was close to. */
    fun adjust(keyboard: Keyboard, key: Key, x: Int, y: Int): Key {
        val letters = model ?: return key
        if (!Settings.values.likelyKeyTargets || !keyboard.id.element.isAlphabet) return key
        val prefix = typedSoFar() ?: return key
        val neighbor = closestNeighbor(keyboard, key, x, y) ?: return key
        return pick(letters, prefix, key, neighbor.first, neighbor.second / key.width) ?: key
    }

    /** The other letter key nearest to the tap, and how far the tap is from it. */
    private fun closestNeighbor(keyboard: Keyboard, key: Key, x: Int, y: Int): Pair<Key, Float>? {
        if (letterOf(key) == null) return null
        var best: Key? = null
        var bestDistance = Int.MAX_VALUE
        for (other in keyboard.getNearestKeys(x, y)) {
            if (other === key || letterOf(other) == null) continue
            val distance = other.squaredDistanceToEdge(x, y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = other
            }
        }
        return best?.let { it to sqrt(bestDistance.toFloat()) }
    }

    private fun letterOf(key: Key): Char? {
        val code = key.code
        if (code <= 0 || key.isModifier()) return null
        return Character.toLowerCase(code).toChar().takeIf { it in 'a'..'z' }
    }

    /**
     * [neighbor] when the tap on [key], [distance] key widths away from [neighbor], should count as
     * [neighbor] after [prefix]; null to leave it.
     */
    fun pick(letters: LetterModel, prefix: String, key: Key, neighbor: Key, distance: Float): Key? {
        if (distance >= EDGE_ZONE) return null
        val own = letters.score(prefix, letterOf(key) ?: return null) ?: return null
        val other = letters.score(prefix, letterOf(neighbor) ?: return null) ?: return null
        return if (wins(own, other, distance)) neighbor else null
    }

    /** Whether a letter of log probability [other], [distance] key widths from the tap, beats the tapped one of [own]. */
    fun wins(own: Float, other: Float, distance: Float): Boolean =
        distance < EDGE_ZONE && other - own >= RATIO_AT_BORDER + RATIO_GROWTH * (distance / EDGE_ZONE)
}
