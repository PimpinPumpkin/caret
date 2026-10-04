// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.content.Context
import app.caret.latin.utils.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * What the keyboard has learned from the corrections of the person using it (see [CorrectionRecorder]):
 * for something they typed, the word they meant by it. Fixed the same way twice, it is applied from then
 * on: "teh" becomes "the" at once, and a word they keep taking back from autocorrect is left alone.
 * Taking a learned correction back counts against it, so a wrong lesson is unlearned the same way.
 *
 * Kept on the phone in a small file, typed word to meant word and how often.
 */
object PersonalCorrections {
    private const val TAG = "PersonalCorrections"
    private const val FILE_NAME = "personal_corrections.json"
    /** How often the same fix has to be made before the keyboard makes it by itself. */
    const val LEARN_AFTER = 2
    private const val MAX_COUNT = 6
    private const val MAX_ENTRIES = 2000

    private class Lesson(var meant: String, var count: Int)

    private val lessons = LinkedHashMap<String, Lesson>()
    private var loadedFrom: File? = null
    private val writer = Executors.newSingleThreadExecutor()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    private fun load(context: Context) {
        val file = file(context)
        if (loadedFrom == file) return
        loadedFrom = file
        lessons.clear()
        try {
            if (!file.isFile) return
            val json = JSONObject(file.readText())
            for (typed in json.keys()) {
                val lesson = json.getJSONObject(typed)
                lessons[typed] = Lesson(lesson.getString("meant"), lesson.getInt("count"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the learned corrections", e)
        }
    }

    /** Called once at app start, so [meantBy] has something to answer with. */
    fun init(context: Context) = load(context.applicationContext)

    /**
     * The person typed [typed] and made it clear they meant [meant], which is [typed] itself when
     * they took an autocorrection back.
     */
    @Synchronized
    fun learn(context: Context, typed: String, meant: String) {
        if (typed.length < 2 || meant.isEmpty() || !isLesson(typed, meant)) return
        load(context)
        val key = typed.lowercase()
        // "The" for "teh" at the start of a sentence is the lesson "the"
        val word = if (typed[0].isUpperCase() && meant[0].isUpperCase() && meant.drop(1) == meant.drop(1).lowercase())
            meant.lowercase() else meant
        val lesson = lessons[key]
        when {
            lesson == null -> {
                if (lessons.size >= MAX_ENTRIES) lessons.remove(lessons.keys.first())
                lessons[key] = Lesson(word, 1)
            }
            lesson.meant.equals(word, ignoreCase = true) -> lesson.count = minOf(MAX_COUNT, lesson.count + 1)
            --lesson.count <= 0 -> lessons[key] = Lesson(word, 1)
        }
        save(context)
    }

    /**
     * Whether [meant] for [typed] is a fix to remember: the same word (an autocorrection taken back) or
     * one that looks like it. Not a longer word the typed letters start, that is finishing a word.
     */
    fun isLesson(typed: String, meant: String): Boolean {
        if (typed.equals(meant, ignoreCase = true)) return true
        if (meant.startsWith(typed, ignoreCase = true) || kotlin.math.abs(meant.length - typed.length) > 2) return false
        if (!typed.all { it.isLetter() || it == '\'' } || meant.any { it.isWhitespace() }) return false
        return CorrectionRecorder.looksLike(typed, meant)
    }

    /**
     * What the person means by [typed], when they have shown it often enough: [typed] itself to leave it
     * alone, another word to put in its place. Null when nothing was learned about it.
     */
    @Synchronized
    fun meantBy(typed: String): String? {
        if (lessons.isEmpty()) return null
        val lesson = lessons[typed.lowercase()] ?: return null
        if (lesson.count < LEARN_AFTER) return null
        // the same word: left as typed, unless the lesson is its capitals ("anja" to "Anja")
        if (lesson.meant.equals(typed, ignoreCase = true)) return if (lesson.meant == lesson.meant.lowercase()) typed else lesson.meant
        return if (typed[0].isUpperCase()) lesson.meant.replaceFirstChar { it.uppercase() } else lesson.meant
    }

    @Synchronized
    fun count(context: Context): Int {
        load(context)
        return lessons.values.count { it.count >= LEARN_AFTER }
    }

    @Synchronized
    fun clear(context: Context) {
        lessons.clear()
        loadedFrom = file(context)
        file(context).delete()
    }

    private fun save(context: Context) {
        val json = JSONObject()
        lessons.forEach { (typed, lesson) -> json.put(typed, JSONObject().put("meant", lesson.meant).put("count", lesson.count)) }
        val text = json.toString()
        val file = file(context)
        writer.execute {
            try {
                file.writeText(text)
            } catch (e: Exception) {
                Log.w(TAG, "could not save the learned corrections", e)
            }
        }
    }
}
