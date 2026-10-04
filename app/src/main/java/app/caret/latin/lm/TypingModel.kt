// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.lm

import android.content.Context
import android.net.Uri
import app.caret.latin.BuildConfig
import app.caret.latin.utils.Log
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Caret's typing language model: a small transformer in a GGUF file, run on the phone by llama.cpp
 * (lm/native). It reads the text before the word being typed and says how well each candidate word
 * fits, and which words are likely to come next.
 *
 * The model is not part of the app. It is imported in the settings into [modelFile]; without one,
 * or without the library on this kind of processor, everything here answers null and the keyboard
 * falls back to the context model of the swipe models.
 */
object TypingModel {
    private const val TAG = "TypingModel"
    private const val MAX_BYTES = 512L * 1024 * 1024
    /** The model was trained on single lines; only so much of the current line is worth reading. */
    private const val CONTEXT_CHARS = 320
    private const val THREADS = 2

    private var appContext: Context? = null
    private var handle = 0L
    private var loading = false
    private var libraryState = 0 // 0 not tried, 1 there, -1 missing

    fun modelFile(context: Context) = File(context.filesDir, "typing_model/model.gguf")

    fun isInstalled(context: Context) = modelFile(context).isFile

    /** Called once at app start. Loads the model in the background when there is one. */
    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    @Synchronized
    fun hasLibrary(): Boolean {
        if (libraryState == 0) libraryState = try {
            System.loadLibrary("caret_lm")
            1
        } catch (e: Throwable) {
            Log.i(TAG, "no typing model library: ${e.message}")
            -1
        }
        return libraryState == 1
    }

    @Synchronized
    private fun load() {
        val context = appContext ?: return
        if (handle != 0L || loading || !isInstalled(context) || !hasLibrary()) return
        loading = true
        thread(name = "typing-model-load") {
            val start = System.currentTimeMillis()
            val loaded = try {
                nativeLoad(modelFile(context).path, THREADS)
            } catch (e: Throwable) {
                Log.w(TAG, "could not load the typing model", e)
                0L
            }
            synchronized(this) {
                loading = false
                // removed or replaced while loading
                if (handle == 0L && isInstalled(context)) handle = loaded else if (loaded != 0L) nativeFree(loaded)
            }
            Log.i(TAG, "typing model ${if (loaded != 0L) "loaded" else "not loaded"} in ${System.currentTimeMillis() - start} ms")
        }
    }

    @Synchronized
    private fun unload() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    /**
     * The log probability of each of [words] as the next word after [textBefore]; higher is likelier.
     * Null when no model is loaded.
     */
    @Synchronized
    fun scoreWords(textBefore: String?, words: List<String>): FloatArray? {
        if (handle == 0L || words.isEmpty()) return null
        return try {
            val start = System.nanoTime()
            val scores = nativeScore(handle, context(textBefore), words.toTypedArray())?.takeIf { it.size == words.size }
            if (BuildConfig.DEBUG) Log.d(TAG, "scored ${words.size} words in ${(System.nanoTime() - start) / 1000} us")
            scores
        } catch (e: Throwable) {
            Log.w(TAG, "scoring words failed", e)
            null
        }
    }

    /** The words most likely to come next after [textBefore], best first. Null when no model is loaded. */
    @Synchronized
    fun predictNext(textBefore: String?, count: Int): List<String>? {
        if (handle == 0L) return null
        return try {
            nativePredict(handle, context(textBefore), count)?.toList()
        } catch (e: Throwable) {
            Log.w(TAG, "predicting failed", e)
            null
        }
    }

    /** What the model gets to read: the current line, and not more of it than it can use. */
    fun context(textBefore: String?): String {
        val line = (textBefore ?: "").substringAfterLast('\n').trimEnd()
        if (line.length <= CONTEXT_CHARS) return line
        // cut at a word, half a word at the start would read as a word of its own
        val tail = line.takeLast(CONTEXT_CHARS)
        return tail.substringAfter(' ', tail)
    }

    /** Blocking. Copies the GGUF file the user picked into the app and loads it. */
    fun import(context: Context, uri: Uri) {
        val staging = File(context.cacheDir, "typing_model_staging.gguf")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
            input.use { source ->
                staging.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("the file is larger than a typing model")
                        target.write(buffer, 0, read)
                    }
                }
            }
            if (!isGguf(staging)) throw IOException("not a GGUF model file")
            if (hasLibrary()) {
                val test = try { nativeLoad(staging.path, 1) } catch (e: Throwable) { 0L }
                if (test == 0L) throw IOException("the model could not be loaded")
                nativeFree(test)
            }
            unload()
            val target = modelFile(context)
            target.parentFile?.mkdirs()
            target.delete()
            if (!staging.renameTo(target)) staging.copyTo(target, overwrite = true)
            load()
        } finally {
            staging.delete()
        }
    }

    fun remove(context: Context) {
        unload()
        modelFile(context).parentFile?.deleteRecursively()
    }

    fun isGguf(file: File): Boolean = try {
        file.inputStream().use { input -> ByteArray(4).let { input.read(it) == 4 && String(it, Charsets.US_ASCII) == "GGUF" } }
    } catch (e: IOException) {
        false
    }

    @JvmStatic private external fun nativeLoad(path: String, threads: Int): Long
    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeScore(handle: Long, context: String, words: Array<String>): FloatArray?
    @JvmStatic private external fun nativePredict(handle: Long, context: String, count: Int): Array<String>?
}
