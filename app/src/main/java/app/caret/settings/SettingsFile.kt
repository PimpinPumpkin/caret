// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import app.caret.latin.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The settings as a small file a person can read: only the ones that were set, nothing the keyboard
 * keeps for itself (recent emoji, the last language used, counters), no dictionaries, history or
 * models. A few kilobytes, where the full backup is a zip with all of that.
 */
object SettingsFile {
    private const val FORMAT = 1

    /** Kept by the keyboard for itself, not set by anyone: left out of the file and never imported. */
    private fun isState(key: String) = key in STATE_KEYS || STATE_PREFIXES.any { key.startsWith(it) }

    private val STATE_KEYS = setOf(
        Settings.PREF_VERSION_CODE, Settings.PREF_LIBRARY_CHECKSUM, Settings.PREF_RECENT_EMOJIS,
        Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_ID, Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID,
        Settings.PREF_SELECTED_SUBTYPE, Settings.PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG, "emoji_max_sdk",
    )
    private val STATE_PREFIXES = listOf(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX, "floating_pos", "debug_", "swipe_models_changed")

    private val json = Json { prettyPrint = true }

    fun export(prefs: SharedPreferences, appVersion: String): String {
        val settings = prefs.all.filterKeys { it != null && !isState(it) }.toSortedMap(compareBy { it }).mapNotNull { (key, value) ->
            val element: JsonElement = when (value) {
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is Float -> JsonPrimitive(value.toString().toBigDecimal()) // 0.65 stays 0.65, 1.0 keeps its point
                is String -> JsonPrimitive(value)
                is Set<*> -> JsonArray(value.filterIsInstance<String>().sorted().map { JsonPrimitive(it) })
                else -> return@mapNotNull null
            }
            key!! to element
        }.toMap()
        return json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
            "caret" to JsonPrimitive(appVersion),
            "format" to JsonPrimitive(FORMAT),
            "settings" to JsonObject(settings),
        )))
    }

    /** The settings in a file written by [export], with their types; throws when it is not such a file. */
    fun read(text: String): Map<String, Any> {
        val root = Json.parseToJsonElement(text).jsonObject
        require("caret" in root && "settings" in root) { "not a Caret settings file" }
        return root.getValue("settings").jsonObject.mapNotNull { (key, element) ->
            if (isState(key)) return@mapNotNull null
            val value: Any = when (element) {
                is JsonArray -> element.map { (it as JsonPrimitive).content }.toSet()
                is JsonPrimitive -> when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.booleanOrNull!!
                    element.content.contains('.') || element.content.contains('E') -> element.content.toFloat()
                    element.intOrNull != null -> element.intOrNull!!
                    element.longOrNull != null -> element.longOrNull!!
                    else -> return@mapNotNull null
                }
                else -> return@mapNotNull null
            }
            key to value
        }.toMap()
    }

    /** Sets [settings] on top of the current ones; settings not in the file stay as they are. */
    fun apply(prefs: SharedPreferences, settings: Map<String, Any>) = prefs.edit {
        settings.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value as Set<String>)
            }
        }
    }
}
