// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.preferences

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.caret.latin.R
import app.caret.latin.lm.TypingModel
import app.caret.latin.utils.Log
import app.caret.settings.Setting
import app.caret.settings.dialogs.ConfirmationDialog
import app.caret.settings.filePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row: import a typing model from a GGUF file, or remove the one that is installed. */
@Composable
fun TypingModelPreference(setting: Setting) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(TypingModel.isInstalled(ctx)) }
    var working by remember { mutableStateOf(false) }
    var error: String? by remember { mutableStateOf(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    val picker = filePicker { uri ->
        if (working) return@filePicker
        working = true
        error = null
        scope.launch {
            error = withContext(Dispatchers.IO) {
                try {
                    TypingModel.import(ctx, uri)
                    null
                } catch (e: Exception) {
                    Log.w("TypingModel", "could not import the typing model", e)
                    e.message ?: e.javaClass.simpleName
                }
            }
            working = false
            installed = TypingModel.isInstalled(ctx)
        }
    }
    Preference(
        name = setting.title,
        description = when {
            working -> stringResource(R.string.typing_model_working)
            error != null -> stringResource(R.string.typing_model_failed, error ?: "")
            !TypingModel.hasLibrary() -> stringResource(R.string.typing_model_no_library)
            installed -> stringResource(R.string.typing_model_installed,
                Formatter.formatShortFileSize(ctx, TypingModel.modelFile(ctx).length()))
            else -> stringResource(R.string.typing_model_summary)
        },
        onClick = {
            if (installed) confirmRemove = true
            else picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
        },
    )
    if (confirmRemove) ConfirmationDialog(
        onDismissRequest = { confirmRemove = false },
        onConfirmed = {
            confirmRemove = false
            TypingModel.remove(ctx)
            installed = false
        },
        confirmButtonText = stringResource(R.string.swipe_models_remove),
        title = { Text(setting.title) },
        content = { Text(stringResource(R.string.typing_model_remove_message)) },
    )
}
