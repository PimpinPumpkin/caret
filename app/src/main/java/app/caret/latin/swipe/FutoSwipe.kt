// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.swipe

import android.content.Context
import com.android.inputmethod.latin.BinaryDictionary
import app.caret.keyboard.Keyboard
import app.caret.latin.BuildConfig
import app.caret.latin.NgramContext
import app.caret.latin.define.DebugFlags
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.ComposedData
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.dictionary.DictionaryFactory
import app.caret.latin.utils.DictionaryInfoUtils
import app.caret.latin.settings.Settings
import app.caret.latin.utils.JniUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.prefs
import org.futo.ml.inference.SwipeDecoder
import java.io.File
import java.util.Locale

/**
 * Swipe typing with the FUTO Swipe library.
 *
 * The models are not part of the app. They live in [modelDir], one folder per model, as published
 * at https://huggingface.co/futo-org/futo-swipe. The dictionary is a word list written once per
 * locale from the main dictionary.
 */
object FutoSwipe {
    private const val TAG = "FutoSwipe"
    const val PREF_ENABLED = "futo_swipe_enabled"
    private const val PREF_MODELS_CHANGED = "futo_swipe_models_changed"
    const val ENCODER = "honorable_sturgeon"
    /** Caret's own encoder (training/swipe_model.py), which comes with the app when the build has it. */
    const val OWN_ENCODER = "caret_swipe_encoder"
    private const val OWN_ASSETS = "swipe_models/$OWN_ENCODER"
    const val DECODER_EN_QWERTY = "magic_macaw"
    const val CONTEXT_LM_EN = "hungry_jellyfish"
    private const val MAX_LETTERS = 48 // limit of the library's trie
    private const val MAX_CONTEXT_WORDS = 16
    private const val CONTEXT_CHARS = 240
    private const val TOP_SCORE = 1_000_000
    // half a point of engine score is about as far as "close enough to prefer a known word" goes
    private const val SCORE_PER_UNIT = 140_000f
    private const val USER_WORDS_REFRESH_MILLIS = 5 * 60 * 1000L
    private const val USER_WORD_MIN_FREQUENCY = 120

    private var libraryLoaded: Boolean? = null
    private var decoder: SwipeDecoder? = null
    private var loadedLocale: Locale? = null
    private var layout: SwipeLayout? = null
    private var trie = 0L
    private var userTrie = 0L
    private var userTrieTime = 0L
    private var userSurfaceForms: Map<String, String> = emptyMap()
    private var surfaceForms: Map<String, String> = emptyMap()
    private var alternateForms: Map<String, List<String>> = emptyMap()

    private var appContext: Context? = null

    /** True when a gesture typing library other than this engine was loaded at app start. */
    @JvmStatic
    var hasOtherLibrary = false
        private set

    /** Called once at app start. */
    fun init(context: Context) {
        appContext = context.applicationContext
        hasOtherLibrary = JniUtils.haveGestureLib
        refreshAvailability(context)
    }

    /** Swipe input is offered when this engine is in use or another gesture typing library is loaded. */
    fun refreshAvailability(context: Context) {
        JniUtils.haveGestureLib = hasOtherLibrary || isActive(context)
    }

    /** Call after the models were installed or removed. Drops what is loaded and reloads settings. */
    @Synchronized
    fun onModelsChanged(context: Context) {
        decoder?.close()
        decoder = null
        if (trie != 0L) SwipeDecoder.nativeFreeTrie(trie)
        trie = 0L
        if (userTrie != 0L) SwipeDecoder.nativeFreeTrie(userTrie)
        userTrie = 0L
        userTrieTime = 0L
        loadedLocale = null
        layout = null
        refreshAvailability(context)
        // settings cache whether swipe input is on, a changed preference makes them reload
        context.prefs().edit().putLong(PREF_MODELS_CHANGED, System.currentTimeMillis()).apply()
    }

    /** Loads models and dictionary for this keyboard ahead of the first swipe. */
    fun warmUp(keyboard: Keyboard) {
        val context = appContext ?: return
        if (!isActive(context)) return
        if (decoder != null && keyboard.id.locale == loadedLocale) return // only the slow first load needs a thread
        Thread({
            try {
                synchronized(this) { prepare(context, keyboard, keyboard.id.locale) }
            } catch (e: Throwable) {
                Log.w(TAG, "warm up failed", e)
            }
        }, "FutoSwipeWarmUp").start()
    }

    fun modelDir(context: Context) = File(context.filesDir, "swipe_models")

    private fun futoEncoderFile(context: Context) = File(modelDir(context), "$ENCODER/model_fp32.pte")

    /** Whether this build comes with Caret's own encoder (scripts/build-swipe.sh puts it into the assets). */
    fun hasOwnEncoder(context: Context): Boolean = ownEncoder ?: try {
        context.assets.list(OWN_ASSETS)?.contains("model_fp32.pte") == true
    } catch (e: Exception) {
        false
    }.also { ownEncoder = it }

    private var ownEncoder: Boolean? = null

    /**
     * Caret's own encoder as a file, which is what the library loads: copied out of the package once
     * per version of the app, with the metadata the library reads next to it. Null when the build has none.
     */
    @Synchronized
    private fun ownEncoderFile(context: Context): File? {
        if (!hasOwnEncoder(context)) return null
        val directory = File(context.filesDir, "swipe_own/${BuildConfig.VERSION_CODE}/$OWN_ENCODER")
        val model = File(directory, "model_fp32.pte")
        if (model.isFile && File(directory, "metadata.json").isFile) return model
        return try {
            File(context.filesDir, "swipe_own").deleteRecursively()
            directory.mkdirs()
            for (name in listOf("metadata.json", "model_fp32.pte")) {
                val part = File(directory, "$name.part")
                context.assets.open("$OWN_ASSETS/$name").use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(File(directory, name))
            }
            model.takeIf { it.isFile }
        } catch (e: Exception) {
            Log.w(TAG, "could not unpack the swipe encoder", e)
            null
        }
    }

    /** Caret's own encoder when the app has it, else FUTO's when that was downloaded. */
    private fun encoderFile(context: Context): File = ownEncoderFile(context) ?: futoEncoderFile(context)

    /** Whether FUTO's models are on the phone (they are an extra since Caret has its own encoder). */
    fun hasFutoModels(context: Context): Boolean = futoEncoderFile(context).isFile

    /** True when the native library is in the app and there is an encoder model to run. */
    @JvmStatic
    fun isInstalled(context: Context): Boolean = hasLibrary() && (hasOwnEncoder(context) || futoEncoderFile(context).isFile)

    /** True when swipe input should go to this engine instead of a gesture typing library. */
    @JvmStatic
    fun isActive(context: Context): Boolean =
        isInstalled(context) && context.prefs().getBoolean(PREF_ENABLED, true)

    /** True when the native library is part of this build. */
    @JvmStatic
    fun hasLibrary(): Boolean {
        libraryLoaded?.let { return it }
        val loaded = try {
            System.loadLibrary("swipe_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.i(TAG, "swipe library is not in this build")
            false
        }
        libraryLoaded = loaded
        return loaded
    }

    /**
     * Suggestions for a finished or ongoing swipe, best first. Null only when the engine is not
     * in use. Once it is in use it always answers, with an empty list if it has nothing, because
     * the built-in native code crashes on swipe input when no gesture typing library is loaded.
     */
    @Synchronized
    fun getSuggestions(
        userWords: () -> List<Pair<String, Int>>,
        isMainWord: (String) -> Boolean,
        composedData: ComposedData, ngramContext: NgramContext,
        keyboard: Keyboard, locale: Locale, sourceDict: Dictionary
    ): List<SuggestedWordInfo>? {
        val context = appContext ?: return null
        if (!isActive(context)) return null
        val pointers = composedData.inputPointers
        val size = pointers.pointerSize
        if (size < 2) return emptyList()
        val swipeLayout = prepare(context, keyboard, locale) ?: return emptyList()
        val engine = decoder ?: return emptyList()
        refreshUserWords(context, engine, swipeLayout, locale, userWords, isMainWord)

        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates
        val times = pointers.times
        val x = FloatArray(size) { swipeLayout.normalizeX(xs[it]) }
        val y = FloatArray(size) { swipeLayout.normalizeY(ys[it]) }
        val t = FloatArray(size) { (times[it] - times[0]).toFloat() }

        val before = contextWords(ngramContext)
        if (DebugFlags.DEBUG_ENABLED) Log.d(TAG, "swipe after ${before.size} words")
        engine.setContext(before)
        val results = try {
            engine.recognize(x, y, t, topK = 8)
        } catch (e: Exception) {
            Log.w(TAG, "recognize failed", e)
            return emptyList()
        }
        SwipeRecorder.onRecognized(context, swipeLayout, locale, xs, ys, times, size, before, results)
        // Scores keep the engine's gaps. Suggest prefers a candidate that was typed before in this
        // context when it is within 7% of the best score, so a clearly worse candidate must also
        // be clearly lower here, or one bad swipe keeps coming back.
        val best = results.firstOrNull()?.score ?: 0f
        val suggestions = ArrayList<SuggestedWordInfo>()
        results.forEachIndexed { index, result ->
            val gap = ((best - result.score) * SCORE_PER_UNIT).toInt().coerceIn(0, TOP_SCORE - 1000)
            val score = TOP_SCORE - gap - index * 10
            val word = surfaceForms[result.word] ?: userSurfaceForms[result.word] ?: result.word
            suggestions.add(SuggestedWordInfo(word, "", score, SuggestedWordInfo.KIND_CORRECTION, sourceDict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
            // other words that are swiped over the same keys ("schon" and "schön"), right behind it
            alternateForms[result.word]?.forEachIndexed { n, other ->
                if (other != word)
                    suggestions.add(SuggestedWordInfo(other, "", score - 1 - n, SuggestedWordInfo.KIND_CORRECTION, sourceDict,
                        SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
            }
        }
        return suggestions
    }

    /**
     * How likely each of [words] is as the next word after the text before it, from the context
     * language model of the swipe models; higher is likelier. Null when that model is not loaded
     * (see [predictNext]). Used to rank the corrections of a typed word by what fits the sentence.
     */
    @Synchronized
    fun scoreWords(ngramContext: NgramContext, locale: Locale, words: List<String>): FloatArray? {
        val context = appContext ?: return null
        if (locale.language != "en" || loadedLocale?.language != "en" || !isActive(context)) return null
        val engine = decoder ?: return null
        return try {
            if (!engine.hasLm()) return null
            engine.setContext(contextWords(ngramContext))
            engine.scoreWords(words)
        } catch (e: Throwable) {
            // also an UnsatisfiedLinkError, from a swipe library built before it could score words
            Log.w(TAG, "scoring words failed", e)
            null
        }
    }

    /**
     * The words most likely to come next after the text in [ngramContext], best first, from the
     * context language model that ships with the swipe models. Null when that model is not loaded:
     * models not installed, engine switched off, another language than English, no keyboard
     * shown yet, or the cursor is at the start of a sentence.
     */
    @Synchronized
    fun predictNext(ngramContext: NgramContext, locale: Locale, count: Int): List<String>? {
        val context = appContext ?: return null
        if (locale.language != "en" || loadedLocale?.language != "en" || !isActive(context)) return null
        val engine = decoder ?: return null
        return try {
            if (!engine.hasLm()) return null
            // with nothing to go on the model's guesses are worse than the dictionary's sentence starters
            val words = contextWords(ngramContext)
            if (words.isEmpty() || ngramContext.isBeginningOfSentenceContext) return null
            engine.setContext(words)
            engine.predictNext(count).map { it.word }
        } catch (e: Exception) {
            Log.w(TAG, "predictNext failed", e)
            null
        }
    }

    /**
     * Personal dictionary, typing history and contacts as a second, small trie. Rebuilt when the
     * main dictionary was reloaded and otherwise every few minutes, so new words become swipeable.
     */
    private fun refreshUserWords(
        context: Context, engine: SwipeDecoder, swipeLayout: SwipeLayout, locale: Locale,
        userWords: () -> List<Pair<String, Int>>, isMainWord: (String) -> Boolean
    ) {
        val now = System.currentTimeMillis()
        if (userTrieTime != 0L && now - userTrieTime < USER_WORDS_REFRESH_MILLIS) return
        userTrieTime = now
        try {
            val words = userWords().filter { (word, _) -> word.length > 1 && word.none { it == ',' || it == '\n' || it == ' ' } }
            val file = File(File(context.cacheDir, "swipe_words").apply { mkdirs() }, "user_${locale.toLanguageTag()}.combined")
            file.bufferedWriter().use { writer ->
                writer.write("dictionary=user:${locale.toLanguageTag()}\n")
                // personal words should win over rare main dictionary words
                words.forEach { (word, frequency) ->
                    val keys = swipeForm(word, swipeLayout.letters)
                    if (keys.isNotEmpty()) writer.write(" word=$keys,f=${frequency.coerceIn(USER_WORD_MIN_FREQUENCY, 255)}\n")
                }
            }
            val newTrie = if (words.isEmpty()) 0L else SwipeDecoder.nativeLoadTrie(file.path, swipeLayout.letters)
            val tries = if (newTrie == 0L) longArrayOf(SwipeDecoder.nativeTrieInterface(trie))
                else longArrayOf(SwipeDecoder.nativeTrieInterface(trie), SwipeDecoder.nativeTrieInterface(newTrie))
            engine.setMode(tries = tries)
            if (userTrie != 0L) SwipeDecoder.nativeFreeTrie(userTrie)
            userTrie = newTrie
            Log.i(TAG, "personal words for swipe: ${words.size}")
            userSurfaceForms = HashMap<String, String>().apply {
                words.forEach { (word, _) ->
                    val alpha = swipeForm(word, swipeLayout.letters)
                    // "Something" in the history must not turn every swiped "something" into a capital:
                    // only spell a result the personal way when the plain letters are not a word themselves
                    if (alpha != word && alpha.isNotEmpty() && !isMainWord(alpha)) put(alpha, word)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not load personal words for swipe", e)
        }
    }

    /**
     * The text before the word being entered, set by the input logic right before it asks for
     * suggestions. The n-gram context only carries three words and stops at any punctuation, while
     * the models were trained on the whole sentence so far.
     */
    @Volatile
    var textBeforeWord: String? = null

    /** How much text before the cursor is worth handing over in [textBeforeWord]. */
    const val CONTEXT_LENGTH = CONTEXT_CHARS

    private fun contextWords(ngramContext: NgramContext): List<String> {
        textBeforeWord?.let { return sentenceWords(it, MAX_CONTEXT_WORDS) }
        val words = ArrayList<String>()
        for (n in minOf(ngramContext.prevWordCount, MAX_CONTEXT_WORDS) downTo 1) {
            val word = ngramContext.getNthPrevWord(n)?.toString() ?: continue
            if (word.isNotEmpty() && word != NgramContext.BEGINNING_OF_SENTENCE_TAG) words.add(word)
        }
        return words
    }

    /** The last [max] words of the sentence that [text] ends in, without punctuation. */
    fun sentenceWords(text: String, max: Int): List<String> {
        val trimmed = text.trimEnd { it == ' ' }
        val sentenceStart = trimmed.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' } + 1
        return trimmed.substring(sentenceStart).split(' ', '\t')
            .map { word -> word.trim { !it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .takeLast(max)
    }

    /**
     * Loads the models and sets layout and dictionary for this keyboard. The dictionary is only
     * reloaded when the language or the set of letters changes, the layout whenever keys moved.
     */
    private fun prepare(context: Context, keyboard: Keyboard, locale: Locale): SwipeLayout? {
        val swipeLayout = SwipeLayout.fromKeyboard(keyboard, MAX_LETTERS) ?: return null
        val current = layout
        if (decoder != null && current != null && locale == loadedLocale && swipeLayout.sameAs(current)) return current

        val models = modelDir(context)
        val english = locale.language == "en"
        val decoderFile = File(models, "$DECODER_EN_QWERTY/model_fp32.pte")
        // FUTO's second model reads what FUTO's encoder puts out, so it only goes with that one
        val futoEncoder = !hasOwnEncoder(context)
        val decoderPath = if (futoEncoder && english && swipeLayout.isQwerty && decoderFile.isFile) decoderFile.path else ""
        val lmFile = File(models, "$CONTEXT_LM_EN/context_lm.pte")
        val lmVocab = File(models, "$CONTEXT_LM_EN/vocab.txt")
        // nor does FUTO's context model: its weights against the encoder were set for FUTO's, and with
        // Caret's it made the benchmark worse (90.3% against 91.7% without it)
        val useLm = futoEncoder && english && lmFile.isFile && lmVocab.isFile

        val engine = decoder ?: try {
            SwipeDecoder(encoderFile(context).path)
        } catch (e: Throwable) {
            Log.w(TAG, "cannot load swipe encoder", e)
            return null
        }
        decoder = engine

        val newDictionary = trie == 0L || locale != loadedLocale || swipeLayout.letters != current?.letters
        if (newDictionary) {
            val wordList = WordList.forLocale(context, locale) ?: return null
            layout = null
            if (trie != 0L) {
                // drop the trie from the engine before freeing it
                engine.setMode(tries = LongArray(0))
                SwipeDecoder.nativeFreeTrie(trie)
                trie = 0L
            }
            if (userTrie != 0L) {
                SwipeDecoder.nativeFreeTrie(userTrie)
                userTrie = 0L
            }
            userTrieTime = 0L
            val start = System.currentTimeMillis()
            val keysFile = wordList.keysFile(swipeLayout.letters)
            val afterKeys = System.currentTimeMillis()
            trie = SwipeDecoder.nativeLoadTrie(keysFile.path, swipeLayout.letters)
            if (trie == 0L) return null
            val afterTrie = System.currentTimeMillis()
            val forms = wordList.surfaceForms(swipeLayout.letters)
            Log.i(TAG, "word list ${afterKeys - start} ms, trie ${afterTrie - afterKeys} ms, spellings ${System.currentTimeMillis() - afterTrie} ms")
            surfaceForms = forms.first
            alternateForms = forms.second
        }

        val ok = engine.setMode(
            letters = swipeLayout.letters, cx = swipeLayout.cx, cy = swipeLayout.cy,
            tries = if (userTrie == 0L) longArrayOf(SwipeDecoder.nativeTrieInterface(trie))
                else longArrayOf(SwipeDecoder.nativeTrieInterface(trie), SwipeDecoder.nativeTrieInterface(userTrie)),
            decoderPath = decoderPath,
            lmModelPath = if (useLm) lmFile.path else "",
            lmVocabPath = if (useLm) lmVocab.path else "",
        )
        if (!ok) {
            Log.w(TAG, "setMode failed for $locale")
            return null
        }
        if (newDictionary)
            Log.i(TAG, "ready for $locale, ${swipeLayout.letters.length} letters, decoder=${decoderPath.isNotEmpty()}, lm=$useLm, scoring=${engine.scoring}")
        loadedLocale = locale
        layout = swipeLayout
        return swipeLayout
    }
}

/** Letter keys of a keyboard, in alphabetical order and in the coordinates the library wants: 0..1 over the letter area. */
class SwipeLayout(val letters: String, val cx: FloatArray, val cy: FloatArray,
                  val width: Float, val top: Float, val height: Float,
                  /** The keys are the 26 letters in QWERTY order, which is what the English decoder model was trained on. */
                  val isQwerty: Boolean = false) {

    fun sameAs(other: SwipeLayout) = letters == other.letters && width == other.width && top == other.top
        && height == other.height && cx.contentEquals(other.cx) && cy.contentEquals(other.cy)

    fun normalizeX(x: Int) = x / width
    fun normalizeY(y: Int) = (y - top) / height

    companion object {
        fun fromKeyboard(keyboard: Keyboard, maxLetters: Int): SwipeLayout? =
            fromKeys(keyboard.getSortedKeys().map { KeyBox(it.code, it.getX(), it.getY(), it.width, it.height) }, keyboard.occupiedWidth, maxLetters)

        /**
         * Letters with their centers. x runs 0..1 over the keyboard width, y runs
         * 0..1 from the top of the first letter row to the bottom of the last one, so a number row
         * above or a bottom row below does not shift the letters.
         */
        fun fromKeys(all: List<KeyBox>, keyboardWidth: Int, maxLetters: Int): SwipeLayout? {
            val seen = HashSet<Int>()
            val keys = all.filter {
                it.code > 0 && Character.isLetter(it.code) && Character.charCount(it.code) == 1
                    && seen.add(Character.toLowerCase(it.code))
            }.sortedWith(compareBy({ it.y }, { it.x }))
            if (keys.size < 2 || keys.size > maxLetters) return null
            val top = keys.minOf { it.y }.toFloat()
            val bottom = keys.maxOf { it.y + it.height }.toFloat()
            val width = keyboardWidth.toFloat()
            val height = bottom - top
            if (width <= 0f || height <= 0f) return null
            val reading = String(CharArray(keys.size) { Character.toLowerCase(keys[it].code).toChar() })
            // The library takes the letters in alphabetical order, as its own layout files have
            // them: its decoder model counts letters that way. Handed over in keyboard order the
            // decoder reads every letter as another one, and swipes come out as near misses.
            val sorted = keys.sortedBy { Character.toLowerCase(it.code) }
            return SwipeLayout(
                String(CharArray(sorted.size) { Character.toLowerCase(sorted[it].code).toChar() }),
                FloatArray(sorted.size) { (sorted[it].x + sorted[it].width / 2f) / width },
                FloatArray(sorted.size) { (sorted[it].y + sorted[it].height / 2f - top) / height },
                width, top, height,
                isQwerty = reading == "qwertyuiopasdfghjklzxcvbnm"
            )
        }
    }
}

/**
 * A word as it is swiped on a keyboard with these [letters]: lower case, a letter with an accent
 * that has no key of its own becomes its base letter, and anything else that is not a key is left
 * out. "Über" is "uber" on QWERTZ, "mañana" stays "mañana" on a Spanish layout, "don't" is "dont".
 */
fun swipeForm(word: String, letters: String): String {
    val out = StringBuilder(word.length)
    for (c in word.lowercase()) {
        if (c in letters) { out.append(c); continue }
        val replacement = when (c) {
            'ß' -> "ss"
            'æ' -> "ae"
            'œ' -> "oe"
            'ø' -> "o"
            'ł' -> "l"
            'đ' -> "d"
            else -> if (c.code < 0x80) "" else java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).take(1)
        }
        replacement.forEach { if (it in letters) out.append(it) }
    }
    return out.toString()
}

/** Position and code of one key, all that [SwipeLayout] needs from a keyboard. */
data class KeyBox(val code: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/** The main dictionary of a locale as a .combined word list, which is what the library's trie reads. */
class WordList private constructor(val file: File) {
    /**
     * The same words spelled with the keys of a layout (see [swipeForm]), which is what the
     * library's trie gets: it can only match letters that are keys, so "über" has to go in as
     * "uber" on a keyboard without an ü key. Written once per set of letters.
     */
    fun keysFile(letters: String): File {
        val out = File(file.parentFile, "${file.name}.${Integer.toHexString(letters.hashCode())}.keys")
        if (out.isFile && out.length() > 0) return out
        val frequencies = LinkedHashMap<String, Int>()
        forEachWord { word, frequency ->
            val keys = swipeForm(word, letters)
            if (keys.isNotEmpty() && frequency > (frequencies[keys] ?: -1)) frequencies[keys] = frequency
        }
        val tmp = File(out.path + ".tmp")
        tmp.bufferedWriter().use { writer ->
            writer.write("dictionary=main:keys\n")
            frequencies.forEach { (keys, frequency) -> writer.write(" word=$keys,f=$frequency\n") }
        }
        if (!tmp.renameTo(out)) tmp.delete()
        return if (out.isFile) out else file
    }

    /**
     * The library returns the keys form, "dont", "i" or "uber". The first map gives the real word
     * for it, "don't", "I" or "über": the most frequent one, and only when the keys form is not
     * a word itself ("its" stays "its"). The second map lists up to two other real words with the
     * same keys that differ by more than capitals, such as "it's" for "its" or "schön" for "schon".
     */
    fun surfaceForms(letters: String): Pair<Map<String, String>, Map<String, List<String>>> {
        // working this out reads the whole word list, which delays the first swipe after the
        // keyboard starts; the result is small and kept in a file next to the list
        val cache = File(file.parentFile, "${file.name}.${Integer.toHexString(letters.hashCode())}.forms")
        readForms(cache)?.let { return it }
        return computeSurfaceForms(letters).also { writeForms(cache, it) }
    }

    private fun readForms(cache: File): Pair<Map<String, String>, Map<String, List<String>>>? {
        if (!cache.isFile || cache.length() == 0L) return null
        return try {
            val primary = HashMap<String, String>()
            val alternates = HashMap<String, List<String>>()
            cache.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size < 2) return@forEachLine
                if (parts[1].isNotEmpty()) primary[parts[0]] = parts[1]
                if (parts.size > 2) alternates[parts[0]] = parts.subList(2, parts.size)
            }
            primary to alternates
        } catch (e: Exception) {
            null
        }
    }

    private fun writeForms(cache: File, forms: Pair<Map<String, String>, Map<String, List<String>>>) {
        try {
            val tmp = File(cache.path + ".tmp")
            tmp.bufferedWriter().use { writer ->
                (forms.first.keys + forms.second.keys).forEach { keys ->
                    writer.write(keys)
                    writer.write("\t")
                    writer.write(forms.first[keys] ?: "")
                    forms.second[keys]?.forEach { writer.write("\t"); writer.write(it) }
                    writer.write("\n")
                }
            }
            if (!tmp.renameTo(cache)) tmp.delete()
        } catch (e: Exception) {
            Log.w("FutoSwipe", "could not keep the spellings", e)
        }
    }

    private fun computeSurfaceForms(letters: String): Pair<Map<String, String>, Map<String, List<String>>> {
        val spellings = HashMap<String, ArrayList<Pair<String, Int>>>()
        val plain = HashSet<String>()
        forEachWord { word, frequency ->
            val keys = swipeForm(word, letters)
            if (keys.isEmpty()) return@forEachWord
            if (keys == word) plain.add(word)
            else spellings.getOrPut(keys) { ArrayList(1) }.add(word to frequency)
        }
        val primary = HashMap<String, String>()
        val alternates = HashMap<String, List<String>>()
        spellings.forEach { (keys, words) ->
            words.sortByDescending { it.second }
            val first = if (keys in plain) keys else words[0].first.also { primary[keys] = it }
            val others = words.map { it.first }.filter { it != first && it.lowercase() != keys }.take(2)
            if (others.isNotEmpty()) alternates[keys] = others
        }
        return primary to alternates
    }

    /** Every word with its frequency (0 to 255, a log scale). */
    fun words(action: (String, Int) -> Unit) = forEachWord { word, frequency -> action(word, frequency) }

    private inline fun forEachWord(crossinline action: (String, Int) -> Unit) {
        file.forEachLine { line ->
            if (!line.startsWith(" word=")) return@forEachLine
            val end = line.lastIndexOf(",f=")
            action(line.substring(6, end), line.substring(end + 3).toIntOrNull() ?: 0)
        }
    }

    companion object {
        private const val TAG = "FutoSwipe"

        fun forLocale(context: Context, locale: Locale): WordList? {
            val dictFile = mainDictionaryFile(context, locale) ?: return null
            val dir = File(context.cacheDir, "swipe_words").apply { mkdirs() }
            val blockOffensive = Settings.values.blockPotentiallyOffensive
            val out = File(dir, "${locale.toLanguageTag()}_${dictFile.length()}_${dictFile.lastModified()}${if (blockOffensive) "_clean" else ""}.combined")
            if (out.isFile && out.length() > 0) return WordList(out)
            dir.listFiles()?.filter { it.name.startsWith("${locale.toLanguageTag()}_") }?.forEach { it.delete() }
            val dict = BinaryDictionary(dictFile.path, 0, dictFile.length(), false, locale, Dictionary.TYPE_MAIN, false)
            if (!dict.isValidDictionary) return null
            val tmp = File(dir, out.name + ".tmp")
            var count = 0
            try {
                tmp.bufferedWriter().use { writer ->
                    writer.write("dictionary=main:${locale.toLanguageTag()}\n")
                    var token = 0
                    do {
                        val result = dict.getNextWordProperty(token)
                        val property = result.mWordProperty
                        token = result.mNextToken
                        if (property != null && property.isValid && !property.isNotAWord
                            && !property.isBeginningOfSentence && !(blockOffensive && property.isPossiblyOffensive)
                            && property.word.none { it == ',' || it == '\n' }
                        ) {
                            writer.write(" word=${property.word},f=${property.probabilityInfo.probability}\n")
                            count++
                        }
                    } while (token != 0)
                }
            } finally {
                dict.close()
            }
            if (count == 0 || !tmp.renameTo(out)) {
                tmp.delete()
                return null
            }
            Log.i(TAG, "wrote $count words for $locale")
            return WordList(out)
        }

        private fun mainDictionaryFile(context: Context, locale: Locale): File? {
            val (extracted, inAssets) = DictionaryFactory.getAvailableDictsForLocale(locale, context, false)
            extracted.firstOrNull { it.name.substringBefore("_") == Dictionary.TYPE_MAIN && it.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) }
                ?.let { return it }
            extracted.firstOrNull { it.name.substringBefore("_").substringBefore(".") == Dictionary.TYPE_MAIN }?.let { return it }
            val asset = inAssets.firstOrNull { it.substringBefore("_") == Dictionary.TYPE_MAIN } ?: return null
            return DictionaryInfoUtils.extractAssetsDictionary(asset, locale, context)
        }
    }
}
