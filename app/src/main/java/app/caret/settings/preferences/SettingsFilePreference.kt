// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.preferences

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.caret.latin.BuildConfig
import app.caret.latin.R
import app.caret.latin.utils.IntentUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.prefs
import app.caret.settings.Setting
import app.caret.settings.SettingsFile
import app.caret.settings.dialogs.ConfirmationDialog
import app.caret.settings.filePicker

/** Writes the settings that were set to a small file the user picks. */
@Composable
fun SettingsExportPreference(setting: Setting) {
    val ctx = LocalContext.current
    val picker = filePicker { uri ->
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(SettingsFile.export(ctx.prefs(), BuildConfig.VERSION_NAME).toByteArray()) }
            Toast.makeText(ctx, R.string.settings_export_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w("SettingsFile", "could not export settings", e)
        }
    }
    Preference(name = setting.title, description = setting.description, onClick = {
        val intent = IntentUtils.getResolvableTypeIntent(ctx, "application/json")
            .setAction(Intent.ACTION_CREATE_DOCUMENT)
            .putExtra(Intent.EXTRA_TITLE, "caret-settings.json")
        picker.launch(intent)
    })
}

/** Reads a settings file, shows what it would set, and sets it when confirmed. */
@Composable
fun SettingsImportPreference(setting: Setting) {
    val ctx = LocalContext.current
    var pending: Map<String, Any>? by remember { mutableStateOf(null) }
    val picker = filePicker { uri ->
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return@filePicker
            pending = SettingsFile.read(text)
        } catch (e: Exception) {
            Log.w("SettingsFile", "could not read settings", e)
            Toast.makeText(ctx, R.string.settings_import_failed, Toast.LENGTH_LONG).show()
        }
    }
    Preference(name = setting.title, description = setting.description, onClick = {
        val intent = IntentUtils.getResolvableTypeIntent(ctx, "application/json").setAction(Intent.ACTION_OPEN_DOCUMENT)
        picker.launch(intent)
    })
    val settings = pending ?: return
    ConfirmationDialog(
        onDismissRequest = { pending = null },
        onConfirmed = {
            SettingsFile.apply(ctx.prefs(), settings)
            Toast.makeText(ctx, R.string.settings_import_done, Toast.LENGTH_SHORT).show()
        },
        title = { Text(stringResource(R.string.settings_import_confirm)) },
        content = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                settings.toSortedMap().forEach { (key, value) ->
                    Text("$key: ${value.toString().take(60)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
