// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
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
    const val ENCODER = "honorable_sturgeon"
    const val DECODER_EN_QWERTY = "magic_macaw"
    const val CONTEXT_LM_EN = "hungry_jellyfish"
    private const val MAX_LETTERS = 48 // limit of the library's trie
    private const val MAX_CONTEXT_WORDS = 8
    private const val TOP_SCORE = 1_000_000

    private var libraryLoaded: Boolean? = null
    private var decoder: SwipeDecoder? = null
    private var layoutKey: LayoutKey? = null
    private var layout: SwipeLayout? = null
    private var trie = 0L
    private var surfaceForms: Map<String, String> = emptyMap()

    private data class LayoutKey(val keyboardHash: Int, val locale: Locale)

    private var appContext: Context? = null

    /** Called once at app start. Swipe input is offered when the engine is installed. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (isInstalled(context)) JniUtils.sHaveGestureLib = true
    }

    fun modelDir(context: Context) = File(context.filesDir, "swipe_models")

    private fun encoderFile(context: Context) = File(modelDir(context), "$ENCODER/model_fp32.pte")

    /** True when the native library is in the app and the encoder model is installed. */
    @JvmStatic
    fun isInstalled(context: Context): Boolean = hasLibrary() && encoderFile(context).isFile

    /** True when swipe input should go to this engine instead of a gesture typing library. */
    @JvmStatic
    fun isActive(context: Context): Boolean =
        isInstalled(context) && context.prefs().getBoolean(PREF_ENABLED, true)

    private fun hasLibrary(): Boolean {
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
        composedData: ComposedData, ngramContext: NgramContext,
        keyboard: Keyboard, locale: Locale, sourceDict: Dictionary
    ): List<SuggestedWordInfo>? {
        val context = appContext ?: return null
        if (!isActive(context)) return null
        val pointers = composedData.mInputPointers
        val size = pointers.pointerSize
        if (size < 2) return emptyList()
        val swipeLayout = prepare(context, keyboard, locale) ?: return emptyList()
        val engine = decoder ?: return emptyList()

        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates
        val times = pointers.times
        val x = FloatArray(size) { swipeLayout.normalizeX(xs[it]) }
        val y = FloatArray(size) { swipeLayout.normalizeY(ys[it]) }
        val t = FloatArray(size) { (times[it] - times[0]).toFloat() }

        engine.setContext(contextWords(ngramContext))
        val results = try {
            engine.recognize(x, y, t, topK = 8)
        } catch (e: Exception) {
            Log.w(TAG, "recognize failed", e)
            return emptyList()
        }
        return results.mapIndexed { index, result ->
            SuggestedWordInfo(
                surfaceForms[result.word] ?: result.word, "", TOP_SCORE - index * 1000,
                SuggestedWordInfo.KIND_CORRECTION, sourceDict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
            )
        }
    }

    private fun contextWords(ngramContext: NgramContext): List<String> {
        val words = ArrayList<String>()
        for (n in minOf(ngramContext.prevWordCount, MAX_CONTEXT_WORDS) downTo 1) {
            val word = ngramContext.getNthPrevWord(n)?.toString() ?: continue
            if (word.isNotEmpty() && word != NgramContext.BEGINNING_OF_SENTENCE_TAG) words.add(word)
        }
        return words
    }

    /** Loads the models and sets layout and dictionary for this keyboard. Cached per keyboard and locale. */
    private fun prepare(context: Context, keyboard: Keyboard, locale: Locale): SwipeLayout? {
        val key = LayoutKey(System.identityHashCode(keyboard), locale)
        if (key == layoutKey && decoder != null) return layout
        val swipeLayout = SwipeLayout.fromKeyboard(keyboard, MAX_LETTERS) ?: return null

        val models = modelDir(context)
        val english = locale.language == "en"
        val decoderFile = File(models, "$DECODER_EN_QWERTY/model_fp32.pte")
        val decoderPath = if (english && swipeLayout.isQwerty && decoderFile.isFile) decoderFile.path else ""
        val lmFile = File(models, "$CONTEXT_LM_EN/context_lm.pte")
        val lmVocab = File(models, "$CONTEXT_LM_EN/vocab.txt")
        val useLm = english && lmFile.isFile && lmVocab.isFile

        val engine = decoder ?: try {
            SwipeDecoder(encoderFile(context).path)
        } catch (e: Throwable) {
            Log.w(TAG, "cannot load swipe encoder", e)
            return null
        }
        decoder = engine

        val wordList = WordList.forLocale(context, locale) ?: return null
        if (trie != 0L) {
            // drop the trie from the engine before freeing it
            engine.setMode(tries = LongArray(0))
            SwipeDecoder.nativeFreeTrie(trie)
            trie = 0L
        }
        trie = SwipeDecoder.nativeLoadTrie(wordList.file.path, swipeLayout.letters)
        if (trie == 0L) return null
        surfaceForms = wordList.surfaceForms(swipeLayout.letters)

        val ok = engine.setMode(
            letters = swipeLayout.letters, cx = swipeLayout.cx, cy = swipeLayout.cy,
            tries = longArrayOf(SwipeDecoder.nativeTrieInterface(trie)),
            decoderPath = decoderPath,
            lmModelPath = if (useLm) lmFile.path else "",
            lmVocabPath = if (useLm) lmVocab.path else "",
        )
        if (!ok) {
            Log.w(TAG, "setMode failed for $locale")
            return null
        }
        Log.i(TAG, "ready for $locale, ${swipeLayout.letters.length} letters, decoder=${decoderPath.isNotEmpty()}, lm=$useLm")
        layoutKey = key
        layout = swipeLayout
        return swipeLayout
    }
}

/** Letter keys of a keyboard in the coordinates the library wants: 0..1 over the letter area. */
class SwipeLayout(val letters: String, val cx: FloatArray, val cy: FloatArray,
                  private val width: Float, private val top: Float, private val height: Float) {
    val isQwerty get() = letters.startsWith("qwertyuiop") && letters.length == 26

    fun normalizeX(x: Int) = x / width
    fun normalizeY(y: Int) = (y - top) / height

    companion object {
        fun fromKeyboard(keyboard: Keyboard, maxLetters: Int): SwipeLayout? {
            val seen = HashSet<Int>()
            val keys = keyboard.sortedKeys.filter {
                val code = it.code
                code > 0 && Character.isLetter(code) && Character.charCount(code) == 1
                    && seen.add(Character.toLowerCase(code))
            }.sortedWith(compareBy({ it.y }, { it.x }))
            if (keys.size < 2 || keys.size > maxLetters) return null
            val top = keys.minOf { it.y }.toFloat()
            val bottom = keys.maxOf { it.y + it.height }.toFloat()
            val width = keyboard.mOccupiedWidth.toFloat()
            val height = bottom - top
            if (width <= 0f || height <= 0f) return null
            val letters = String(CharArray(keys.size) { Character.toLowerCase(keys[it].code).toChar() })
            return SwipeLayout(
                letters,
                FloatArray(keys.size) { (keys[it].x + keys[it].width / 2f) / width },
                FloatArray(keys.size) { (keys[it].y + keys[it].height / 2f - top) / height },
                width, top, height
            )
        }
    }
}

/** The main dictionary of a locale as a .combined word list, which is what the library's trie reads. */
class WordList private constructor(val file: File) {
    /**
     * The library matches on letters only and returns "dont" or "i". This maps such a form back to
     * the most frequent real word, "don't" or "I".
     */
    fun surfaceForms(letters: String): Map<String, String> {
        val best = HashMap<String, Pair<String, Int>>()
        file.forEachLine { line ->
            if (!line.startsWith(" word=")) return@forEachLine
            val word = line.substring(6, line.lastIndexOf(",f="))
            val alpha = buildString { word.lowercase().forEach { if (it in letters) append(it) } }
            if (alpha == word || alpha.isEmpty()) return@forEachLine
            val frequency = line.substring(line.lastIndexOf(",f=") + 3).toIntOrNull() ?: 0
            // an all lowercase spelling of the same letters wins when it is as frequent
            val current = best[alpha]
            if (current == null || frequency > current.second) best[alpha] = word to frequency
        }
        // "its" must stay "its" even though "it's" exists: only map forms that are not words themselves
        val plain = HashSet<String>()
        file.forEachLine { line ->
            if (line.startsWith(" word=")) plain.add(line.substring(6, line.lastIndexOf(",f=")))
        }
        return best.filterKeys { it !in plain }.mapValues { it.value.first }
    }

    companion object {
        private const val TAG = "FutoSwipe"

        fun forLocale(context: Context, locale: Locale): WordList? {
            val dictFile = mainDictionaryFile(context, locale) ?: return null
            val dir = File(context.cacheDir, "swipe_words").apply { mkdirs() }
            val out = File(dir, "${locale.toLanguageTag()}_${dictFile.length()}_${dictFile.lastModified()}.combined")
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
                        if (property != null && property.isValid && !property.mIsNotAWord
                            && !property.mIsBeginningOfSentence && !property.mIsPossiblyOffensive
                            && property.mWord.none { it == ',' || it == '\n' }
                        ) {
                            writer.write(" word=${property.mWord},f=${property.mProbabilityInfo.mProbability}\n")
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
