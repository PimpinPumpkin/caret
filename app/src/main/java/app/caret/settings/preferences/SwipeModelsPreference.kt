// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.preferences

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.caret.latin.R
import app.caret.latin.swipe.FutoSwipe
import app.caret.latin.swipe.SwipeModels
import app.caret.latin.utils.IntentUtils
import app.caret.latin.utils.Log
import app.caret.settings.dialogs.ConfirmationDialog
import app.caret.settings.filePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Two rows: download the swipe models (or remove them), and import them from a zip. */
@Composable
fun SwipeModelsPreference(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(FutoSwipe.hasFutoModels(ctx)) }
    var progress: Float? by remember { mutableStateOf(null) }
    var error: String? by remember { mutableStateOf(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun run(work: () -> Unit) {
        if (progress != null) return
        progress = 0f
        error = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    work()
                    null
                } catch (e: Exception) {
                    Log.w("SwipeModels", "could not get swipe models", e)
                    e.message ?: e.javaClass.simpleName
                }
            }
            progress = null
            error = failure
            installed = FutoSwipe.hasFutoModels(ctx)
            onChanged()
        }
    }

    // Caret reads swipes with its own model when the build has it: FUTO's are then not used, and all
    // that is left to offer is taking them off the phone
    val own = FutoSwipe.hasOwnEncoder(ctx)
    if (own && !installed) return
    val picker = filePicker { uri -> run { SwipeModels.importZip(ctx, uri) } }
    val size = Formatter.formatShortFileSize(ctx, SwipeModels.totalSize)
    val currentProgress = progress
    val status = when {
        currentProgress != null -> stringResource(R.string.swipe_models_working, (currentProgress * 100).toInt())
        error != null -> stringResource(R.string.swipe_models_failed, error ?: "")
        installed && FutoSwipe.hasOwnEncoder(ctx) -> stringResource(R.string.swipe_models_installed_extra)
        installed -> stringResource(R.string.swipe_models_installed)
        else -> stringResource(R.string.swipe_models_download_summary, size)
    }
    val (upper, lower) = LocalRowPosition.current.split()
    Column {
        CompositionLocalProvider(LocalRowPosition provides upper) {
        Preference(
            name = stringResource(when {
                FutoSwipe.hasOwnEncoder(ctx) -> R.string.swipe_models_title_extra
                installed -> R.string.swipe_models_title_installed
                else -> R.string.swipe_models_download
            }),
            description = status,
            onClick = {
                if (installed) confirmRemove = true
                else run { SwipeModels.download(ctx) { progress = it } }
            },
        )
        }
        if (!own) CompositionLocalProvider(LocalRowPosition provides lower) {
        Preference(
            name = stringResource(R.string.swipe_models_import),
            description = stringResource(R.string.swipe_models_import_summary),
            onClick = {
                val intent = IntentUtils.getResolvableTypeIntent(ctx, "application/zip")
                    .setAction(Intent.ACTION_OPEN_DOCUMENT)
                picker.launch(intent)
            },
        )
        }
    }
    if (confirmRemove) {
        ConfirmationDialog(
            onDismissRequest = { confirmRemove = false },
            onConfirmed = {
                confirmRemove = false
                SwipeModels.remove(ctx)
                installed = false
                onChanged()
            },
            confirmButtonText = stringResource(R.string.swipe_models_remove),
            title = { Text(stringResource(R.string.swipe_models_title_installed)) },
            content = { Text(stringResource(if (own) R.string.swipe_models_remove_message_extra else R.string.swipe_models_remove_message)) },
        )
    }
}
