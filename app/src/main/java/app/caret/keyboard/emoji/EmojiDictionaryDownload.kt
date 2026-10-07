// SPDX-License-Identifier: GPL-3.0-only
package app.caret.keyboard.emoji

import android.content.Context
import android.content.Intent
import app.caret.dictionarypack.DictionaryPackConstants
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.utils.DictionaryInfoUtils
import app.caret.latin.utils.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Gets the emoji dictionary of a language, which emoji search and the emoji names need, from the
 * dictionaries HeliBoard publishes (codeberg.org/Helium314/aosp-dictionaries, made from Unicode CLDR
 * and Signal's emoji names). Only when the user asks for it; the file is stored like a dictionary the
 * user added, so it shows up and can be removed under Dictionaries.
 */
object EmojiDictionaryDownload {
    private const val TAG = "EmojiDictionaryDownload"
    // pinned, so what is fetched is what was looked at
    private const val REVISION = "795c8c4ab3de8286152f53855e006e8362a62103"
    private const val BASE_URL =
        "https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/$REVISION/emoji_cldr_signal_dictionaries/emoji_"
    private const val MAX_BYTES = 4L * 1024 * 1024
    /** Roughly how large one of these files is, for telling the user before fetching it. */
    const val TYPICAL_SIZE = 300_000L

    private val AVAILABLE = ("aa af ak am ar as ast az ba be bew bg bgn blo bn br bs ca ccp ceb chr cs cv cy da de de_ch dsb el en " +
        "en_au en_ca en_gb eo es es_419 es_mx et eu fa ff fi fil fo fr fr_ca frr fy ga gd gl gu ha he hi hi_zz hr hsb hu hy ia id " +
        "ig is it ja jv ka kab kk kl km kn ko kok ku ky lb lij lo lt lv mi mk ml mn mni mr ms mt my nb ne nl nn no nso oc om or pa " +
        "pap pcm pl ps pt pt_pt qu quc rhg rm ro ru rw sat sc sd shn si sk sl so sq sr su sv sw syr ta te tg th ti tk tl tn to tr " +
        "ug uk ur uz vec vi wo xh yo yue zh zh_hant zu").split(' ').toSet()

    /** The published dictionary that fits [locale] best: its own region's when there is one, else the language's. */
    fun nameFor(locale: Locale): String? {
        val language = locale.language.lowercase()
        val script = locale.script.lowercase()
        val country = locale.country.lowercase()
        return listOf("${language}_$script", "${language}_$country", language).firstOrNull { it in AVAILABLE }
    }

    /** Blocking. Fetches and installs the emoji dictionary for [locale]; throws IOException with a reason on failure. */
    fun download(context: Context, locale: Locale) {
        val name = nameFor(locale) ?: throw IOException("no emoji dictionary for ${locale.toLanguageTag()}")
        val directory = DictionaryInfoUtils.getCacheDirectoryForLocale(locale, context) ?: throw IOException("no place to store it")
        val staging = File(context.cacheDir, "emoji_download.dict")
        try {
            val connection = URL("$BASE_URL$name.dict").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("server answered ${connection.responseCode}")
                connection.inputStream.use { input ->
                    staging.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) throw IOException("the file is larger than a dictionary")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val header = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(staging) ?: throw IOException("not a dictionary")
            if (header.idString.substringBefore(":") != Dictionary.TYPE_EMOJI) throw IOException("not an emoji dictionary")
            val target = File(directory, Dictionary.TYPE_EMOJI + "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)
            target.delete()
            if (!staging.renameTo(target)) staging.copyTo(target, overwrite = true)
            Log.i(TAG, "emoji dictionary $name installed")
            context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION).setPackage(context.packageName))
        } finally {
            staging.delete()
        }
    }
}
