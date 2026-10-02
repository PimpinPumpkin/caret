// SPDX-License-Identifier: GPL-3.0-only
package app.caret.keyboard.emoji

import androidx.core.content.edit
import app.caret.latin.common.StringUtils
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.utils.prefs
import kotlinx.serialization.json.Json

// manages recently used emojis
object RecentEmojis {
    private val prefs = Settings.getCurrentContext().prefs()

    fun set(emojis: List<String>) {
        prefs.edit { putString(Settings.PREF_RECENT_EMOJIS, Json.encodeToString(emojis)) }
    }

    @JvmStatic
    fun add(emoji: String) {
        if (emoji.isEmpty()) return
        val recents = get()
        recents.removeAll { it == emoji }
        recents.add(0, emoji)
        set(recents.take(MAX_COUNT))
    }

     @JvmStatic
     fun addCodepoint(emoji: Int) {
         if (emoji > 0) add(StringUtils.newSingleCodePointString(emoji))
     }

    @JvmStatic
    fun remove(emoji: String) {
        if (emoji.isEmpty()) return
        val recents = get()
        if (recents.removeAll { it == emoji }) set(recents)
    }

    @JvmStatic
    fun removeCodepoint(emoji: Int) {
        if (emoji > 0) remove(StringUtils.newSingleCodePointString(emoji))
    }

    @JvmStatic
    fun clear() {
        set(emptyList())
    }

    @JvmStatic
    fun get(): MutableList<String> {
        val pref = prefs.getString(Settings.PREF_RECENT_EMOJIS, Defaults.PREF_RECENT_EMOJIS)
        if (pref.isNullOrEmpty()) return mutableListOf()
        return runCatching { Json.decodeFromString<MutableList<String>>(pref) }
            .getOrNull() ?: mutableListOf()
    }

    private const val MAX_COUNT = 39 // max for config_emoji_keyboard_max_recents_key_count
}
