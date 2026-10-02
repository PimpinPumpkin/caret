// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.swipe

import android.content.Context
import app.caret.latin.BuildConfig
import app.caret.latin.settings.Settings
import app.caret.latin.utils.Log
import app.caret.latin.utils.prefs
import org.futo.ml.inference.SwipeDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Keeps swipes in a file on the phone, to find out afterwards why words came out wrong: the path
 * of the finger, where the keys were, the words in front, what the engine answered and what
 * happened to the word. scripts/swipe-replay.py reads the file.
 *
 * One JSON object per line. A "layout" line describes the keys and is written whenever they
 * change. A "swipe" line is one finished swipe. A "picked" or "deleted" line says what the user
 * did with the swipe of that number afterwards.
 */
object SwipeRecorder {
    const val PREF_ENABLED = "swipe_recording"
    private const val TAG = "SwipeRecorder"
    private const val FILE_NAME = "swipe_recording.jsonl"
    private const val MAX_BYTES = 8L * 1024 * 1024

    /**
     * The words of the sentence the practice screen is asking for, while it is open. A swipe made
     * then is recorded whatever the setting says, together with the word that was meant.
     */
    @Volatile
    var practice: List<String>? = null

    private class Pending(
        val context: Context, val layout: SwipeLayout, val locale: Locale,
        val x: IntArray, val y: IntArray, val t: IntArray,
        val before: List<String>, val results: List<SwipeDecoder.Result>, val target: String?,
    )

    private var pending: Pending? = null
    private var writtenLayout: SwipeLayout? = null
    private var layoutId = 0
    private var swipeId = 0L

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun isOn(context: Context) =
        practice != null || (context.prefs().getBoolean(PREF_ENABLED, false) && !Settings.values.incognitoModeEnabled)

    /** The engine's answer to the path so far. Called many times during one swipe; the last one counts. */
    @Synchronized
    fun onRecognized(
        context: Context, layout: SwipeLayout, locale: Locale, xs: IntArray, ys: IntArray, times: IntArray, size: Int,
        before: List<String>, results: List<SwipeDecoder.Result>,
    ) {
        if (!isOn(context)) {
            pending = null
            return
        }
        pending = Pending(context.applicationContext, layout, locale, xs.copyOf(size), ys.copyOf(size), times.copyOf(size),
            before, results, practice?.getOrNull(before.size))
    }

    /** The swipe is over and [word] went into the text. */
    @Synchronized
    fun onCommitted(word: String?) {
        val swipe = pending ?: return
        pending = null
        val file = file(swipe.context)
        if (file.length() > MAX_BYTES) return
        try {
            val lines = StringBuilder()
            // a new process starts counting again, so say where the keys are again too
            if (writtenLayout?.sameAs(swipe.layout) != true) {
                layoutId++
                writtenLayout = swipe.layout
                lines.append(JSONObject().put("layout", JSONObject()
                    .put("id", layoutId)
                    .put("letters", swipe.layout.letters)
                    .put("cx", JSONArray(swipe.layout.cx.map { it.toDouble() }))
                    .put("cy", JSONArray(swipe.layout.cy.map { it.toDouble() }))
                    .put("width", swipe.layout.width.toDouble())
                    .put("top", swipe.layout.top.toDouble())
                    .put("height", swipe.layout.height.toDouble())
                )).append('\n')
            }
            // the time it happened, and never the same number twice
            swipeId = maxOf(System.currentTimeMillis(), swipeId + 1)
            val line = JSONObject()
                .put("swipe", swipeId)
                .put("app", BuildConfig.VERSION_NAME)
                .put("locale", swipe.locale.toLanguageTag())
                .put("keys", layoutId)
                .put("x", JSONArray(swipe.x))
                .put("y", JSONArray(swipe.y))
                .put("t", JSONArray(swipe.t))
                .put("before", JSONArray(swipe.before))
                .put("results", JSONArray(swipe.results.map { JSONObject().put("word", it.word).put("score", it.score.toDouble())
                    .put("ctc", it.ctcScore.toDouble()).put("lm", it.lmScore.toDouble()) }))
                .put("typed", word ?: "")
            if (swipe.target != null) line.put("target", swipe.target)
            lines.append(line).append('\n')
            file.appendText(lines.toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not record a swipe", e)
        }
    }

    /** Another suggestion was chosen for the word that was just swiped. */
    fun onPicked(context: Context, word: String) = note(context, JSONObject().put("picked", swipeId).put("word", word))

    /** The word that was just swiped was deleted again. */
    fun onDeleted(context: Context) = note(context, JSONObject().put("deleted", swipeId))

    @Synchronized
    private fun note(context: Context, line: JSONObject) {
        if (swipeId == 0L || !isOn(context)) return
        try {
            file(context).appendText("$line\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not record what happened to a swipe", e)
        }
    }

    /** How many swipes are in the recording. */
    fun count(context: Context): Int = try {
        file(context).takeIf { it.isFile }?.useLines { lines -> lines.count { it.startsWith("{\"swipe\"") } } ?: 0
    } catch (e: Exception) {
        0
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        pending = null
        writtenLayout = null
        swipeId = 0L
    }

    /** Sentences for the practice screen: everyday words, no punctuation, so each word has one answer. */
    val PRACTICE = listOf(
        "we are going home after the game",
        "can you send me the address before lunch",
        "thanks for letting me know about that",
        "what time should we leave tomorrow morning",
        "it was really good to see you again",
        "they wanted something different this year",
        "please bring the keys when you come over",
        "there is nothing wrong with trying it first",
        "which one would you pick for dinner tonight",
        "people usually think about money and work",
        "she already told them where the house is",
        "maybe another quick question about your phone",
        "every little thing takes longer than expected",
        "our friends from school were waiting outside",
        "just because it works does not mean it is right",
        "probably better to wait until next weekend",
        "his brother bought through an online store",
        "these words look almost exactly the same",
        "have never been quite sure of those either",
        "would love some coffee if that is possible",
    )
}
