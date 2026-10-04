// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.content.Context
import app.caret.keyboard.Keyboard
import app.caret.latin.common.InputPointers
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.swipe.SwipeLayout
import app.caret.latin.utils.Log
import app.caret.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the typing corrections a person makes, on the phone, so the keyboard can be measured on real
 * typing and later learn from it: where the taps of a word landed, the words before it, what came
 * out, and what was meant instead. Three kinds of correction are kept:
 *
 * - `undone`: an autocorrection taken back with backspace; what was typed was meant.
 * - `picked`: another suggestion picked in place of the word that space would have put in.
 * - `retyped`: a word deleted and a different one typed in its place soon after.
 *
 * Words that nobody corrects are only counted. Never in password fields or incognito mode. One JSON
 * object per line: "keys" lines describe the letter keys and are written when they change, "words"
 * lines count the words typed, the rest are corrections.
 */
object CorrectionRecorder {
    private const val TAG = "CorrectionRecorder"
    private const val FILE_NAME = "typing_corrections.jsonl"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val RETYPE_WINDOW_MILLIS = 60_000L
    private const val CONTEXT_WORDS = 4

    private class Typed(
        val start: Int, val typed: String, val out: String, val before: List<String>,
        val x: IntArray, val y: IntArray, val time: Long,
        /** An autocorrection taken back: a wish to keep the word as typed, unless it is typed anew right after. */
        var keptAsTyped: Boolean = false,
    )

    private val recent = ArrayDeque<Typed>()
    private var writtenKeys: SwipeLayout? = null
    private var keysId = 0
    private var words = 0

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    // settings may not be loaded yet (or at all, in tests); then incognito cannot be on either
    private fun isOn(context: Context) = (Settings.values as app.caret.latin.settings.SettingsValues?)?.incognitoModeEnabled != true &&
        context.prefs().getBoolean(Settings.PREF_RECORD_CORRECTIONS, Defaults.PREF_RECORD_CORRECTIONS)

    /**
     * A typed word went into the text. [start] is where it begins in the field, [wouldHaveBeen] what
     * space would have put in, which differs from [out] when a suggestion was picked instead.
     */
    @Synchronized
    fun onCommit(
        context: Context, keyboard: Keyboard?, pointers: InputPointers, typed: String, out: String,
        wouldHaveBeen: String?, picked: Boolean, start: Int, textBefore: String?,
    ) {
        if (!isOn(context) || typed.isEmpty()) return
        val size = pointers.pointerSize
        val entry = Typed(start, typed, out, swipeContext(textBefore),
            pointers.xCoordinates.copyOf(size), pointers.yCoordinates.copyOf(size), System.currentTimeMillis())
        // the same place as a word a moment ago, but another word: that one was deleted and retyped
        val replaced = recent.firstOrNull { it.start == start && start >= 0 && entry.time - it.time < RETYPE_WINDOW_MILLIS }
        // the words taken back from autocorrect before this one stayed: those were meant as typed
        recent.filter { it.keptAsTyped && it !== replaced }.forEach {
            it.keptAsTyped = false
            PersonalCorrections.learn(context, it.typed, it.typed)
        }
        if (replaced != null) {
            recent.remove(replaced)
            if (replaced.keptAsTyped && replaced.out.equals(out, ignoreCase = true)) PersonalCorrections.learn(context, replaced.typed, replaced.typed)
            // a fix looks like what it fixes; a different word in the same spot is new text, as after clearing the field
            if (!replaced.out.equals(out, ignoreCase = true) && looksLike(replaced.typed, out))
                write(context, keyboard, "retyped", replaced, replaced.out, out)
        }
        if (picked && wouldHaveBeen != null && !wouldHaveBeen.equals(out, ignoreCase = true))
            write(context, keyboard, "picked", entry, wouldHaveBeen, out)
        recent.addLast(entry)
        while (recent.size > 5) recent.removeFirst()
        if (++words % 50 == 0) append(context, JSONObject().put("words", 50))
    }

    /** Backspace took back an autocorrection: [typed] was meant, [out] had replaced it. */
    @Synchronized
    fun onUndone(context: Context, keyboard: Keyboard?, pointers: InputPointers, typed: String, out: String, textBefore: String?) {
        if (!isOn(context) || typed.isEmpty()) return
        val size = pointers.pointerSize
        val entry = Typed(-1, typed, out, swipeContext(textBefore),
            pointers.xCoordinates.copyOf(size), pointers.yCoordinates.copyOf(size), System.currentTimeMillis())
        // the word stands as typed now; when it is deleted and typed anew after all, that is a fix of it
        val start = recent.lastOrNull { it.out == out }?.start ?: -1
        recent.removeAll { it.out == out }
        if (start >= 0) recent.addLast(Typed(start, typed, typed, entry.before, entry.x, entry.y, entry.time, keptAsTyped = true))
        // the text before the cursor still ends in the corrected word here
        val before = if (entry.before.lastOrNull() == out) entry.before.dropLast(1) else entry.before
        write(context, keyboard, "undone", Typed(entry.start, entry.typed, entry.out, before, entry.x, entry.y, entry.time), out, typed,
            learn = start < 0)
    }

    /** Whether [b] could be what someone meant when typing [a]: at most about half the letters differ. */
    fun looksLike(a: String, b: String): Boolean {
        val x = a.lowercase()
        val y = b.lowercase()
        val row = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            var diagonal = row[0]
            row[0] = i
            for (j in 1..y.length) {
                val above = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, diagonal + if (x[i - 1] == y[j - 1]) 0 else 1)
                diagonal = above
            }
        }
        return row[y.length] <= maxOf(1, (maxOf(x.length, y.length) + 1) / 2)
    }

    private fun swipeContext(textBefore: String?) =
        textBefore?.let { app.caret.latin.swipe.FutoSwipe.sentenceWords(it, CONTEXT_WORDS) } ?: emptyList()

    private fun write(context: Context, keyboard: Keyboard?, kind: String, entry: Typed, cameOut: String, meant: String, learn: Boolean = true) {
        if (learn) PersonalCorrections.learn(context, entry.typed, meant)
        val keys = keyboard?.let { SwipeLayout.fromKeyboard(it, 64) }
        val line = StringBuilder()
        if (keys != null && writtenKeys?.sameAs(keys) != true) {
            keysId++
            writtenKeys = keys
            line.append(JSONObject().put("keys", JSONObject()
                .put("id", keysId)
                .put("letters", keys.letters)
                .put("cx", JSONArray(keys.cx.map { it.toDouble() }))
                .put("cy", JSONArray(keys.cy.map { it.toDouble() }))
                .put("width", keys.width.toDouble())
                .put("top", keys.top.toDouble())
                .put("height", keys.height.toDouble())
            )).append('\n')
        }
        line.append(JSONObject()
            .put("kind", kind)
            .put("time", entry.time)
            .put("app", BuildConfig.VERSION_NAME)
            .put("layout", keysId)
            .put("typed", entry.typed)
            .put("cameOut", cameOut)
            .put("meant", meant)
            .put("before", JSONArray(entry.before))
            .put("x", JSONArray(entry.x))
            .put("y", JSONArray(entry.y))
        ).append('\n')
        append(context, line.toString())
    }

    private fun append(context: Context, line: Any) {
        val file = file(context)
        if (file.length() > MAX_BYTES) return
        try {
            file.appendText(if (line is String) line else "$line\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not record a correction", e)
        }
    }

    /** How many corrections are in the file. */
    fun count(context: Context): Int = try {
        file(context).takeIf { it.isFile }?.useLines { lines -> lines.count { it.startsWith("{\"kind\"") } } ?: 0
    } catch (e: Exception) {
        0
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        PersonalCorrections.clear(context)
        recent.clear()
        writtenKeys = null
        words = 0
    }
}
