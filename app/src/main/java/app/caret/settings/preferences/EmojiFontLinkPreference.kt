// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.preferences

import android.graphics.Typeface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.KeyboardTypeface
import app.caret.latin.R
import app.caret.latin.settings.Settings
import app.caret.latin.utils.DeviceProtectedUtils
import app.caret.latin.utils.Log
import app.caret.settings.Setting
import app.caret.settings.dialogs.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_FONT_BYTES = 80L * 1024 * 1024

/**
 * Gets an emoji font from a link the user types in and uses it for the emoji the keyboard draws.
 * Caret ships no emoji font and suggests no source. The font ends up in the same place as one
 * picked with "Set custom emoji font from file".
 */
@Composable
fun EmojiFontLinkPreference(setting: Setting) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var status: String? by remember { mutableStateOf(null) }
    var working by remember { mutableStateOf(false) }

    Preference(
        name = setting.title,
        description = status ?: setting.description,
        onClick = { if (!working) showDialog = true },
    )
    if (showDialog) {
        val workingText = stringResource(R.string.emoji_font_link_working)
        val doneText = stringResource(R.string.emoji_font_link_done)
        val failedText = stringResource(R.string.emoji_font_link_failed)
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { link ->
                showDialog = false
                working = true
                status = workingText
                scope.launch {
                    val error = withContext(Dispatchers.IO) { downloadFont(link.trim(), Settings.getCustomEmojiFontFile(ctx), DeviceProtectedUtils.getFilesDir(ctx)) }
                    working = false
                    status = if (error == null) doneText else failedText.format(error)
                    if (error == null) {
                        KeyboardTypeface.clearCache()
                        KeyboardSwitcher.getInstance().setThemeNeedsReload()
                    }
                }
            },
            title = { Text(setting.title) },
            textInputLabel = { Text("https://") },
            keyboardType = KeyboardType.Uri,
            checkTextValid = { it.trim().startsWith("https://") },
        )
    }
}

/** Returns null on success, otherwise a short reason. */
private fun downloadFont(link: String, target: File, dir: File?): String? {
    val temp = File(dir, "temp_emoji_font")
    try {
        val connection = URL(link).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return "the server answered ${connection.responseCode}"
            var total = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FONT_BYTES) throw IOException("the file is too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        // throws when the file is not a font Android can read
        Typeface.createFromFile(temp)
        target.delete()
        if (!temp.renameTo(target)) return "could not save the font"
        return null
    } catch (e: Exception) {
        Log.w("EmojiFontLink", "could not get emoji font", e)
        return if (e is IOException) e.message ?: "network error" else "that file is not a font"
    } finally {
        temp.delete()
    }
}
