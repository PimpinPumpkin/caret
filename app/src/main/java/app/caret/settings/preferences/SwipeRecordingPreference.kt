// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.preferences

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.caret.latin.R
import app.caret.latin.swipe.SwipeRecorder
import app.caret.latin.utils.IntentUtils
import app.caret.latin.utils.Log
import app.caret.settings.Setting
import app.caret.settings.dialogs.ConfirmationDialog
import app.caret.settings.dialogs.ThreeButtonAlertDialog
import app.caret.settings.filePicker

/** Opens a dialog that asks for sentences to be swiped, so the recording knows the words that were meant. */
@Composable
fun SwipePracticePreference(setting: Setting) {
    var showing by remember { mutableStateOf(false) }
    Preference(name = setting.title, description = setting.description, onClick = { showing = true })
    if (!showing) return

    var index by remember { mutableIntStateOf((System.currentTimeMillis() % SwipeRecorder.PRACTICE.size).toInt()) }
    var text by remember { mutableStateOf("") }
    val sentence = SwipeRecorder.PRACTICE[index % SwipeRecorder.PRACTICE.size]
    val focus = remember { FocusRequester() }
    // the recorder takes the word that was meant from here, by its place in the sentence
    LaunchedEffect(sentence) { SwipeRecorder.practice = sentence.split(' ') }
    DisposableEffect(Unit) { onDispose { SwipeRecorder.practice = null } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ThreeButtonAlertDialog(
        onDismissRequest = { showing = false },
        onConfirmed = { },
        confirmButtonText = null,
        cancelButtonText = stringResource(R.string.swipe_practice_done),
        neutralButtonText = stringResource(R.string.swipe_practice_next),
        onNeutral = {
            index++
            text = ""
        },
        title = { Text(setting.title) },
        content = {
            Column {
                Text(stringResource(R.string.swipe_practice_instructions), style = MaterialTheme.typography.bodyMedium)
                Text(sentence, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
    )
}

/** Writes a recording kept in [file] to a file the user picks. */
@Composable
fun RecordingSavePreference(setting: Setting, file: (Context) -> java.io.File, count: (Context) -> Int,
                            fileName: String, @StringRes summary: Int) {
    val ctx = LocalContext.current
    val picker = filePicker { uri ->
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file(ctx).inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            Log.w("Recording", "could not save the recording", e)
        }
    }
    val n = count(ctx)
    Preference(
        name = setting.title,
        description = stringResource(summary, n),
        onClick = {
            if (n == 0) return@Preference
            val intent = IntentUtils.getResolvableTypeIntent(ctx, "application/octet-stream")
                .setAction(Intent.ACTION_CREATE_DOCUMENT)
                .putExtra(Intent.EXTRA_TITLE, fileName)
            picker.launch(intent)
        },
    )
}

@Composable
fun RecordingDeletePreference(setting: Setting, @StringRes message: Int, clear: (Context) -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Preference(name = setting.title, onClick = { confirm = true })
    if (confirm) ConfirmationDialog(
        onDismissRequest = { confirm = false },
        onConfirmed = { clear(ctx) },
        content = { Text(stringResource(message)) },
    )
}

@Composable
fun SwipeRecordingSavePreference(setting: Setting) = RecordingSavePreference(setting,
    { SwipeRecorder.file(it) }, { SwipeRecorder.count(it) }, "caret-swipes.jsonl", R.string.swipe_recording_save_summary)

@Composable
fun SwipeRecordingDeletePreference(setting: Setting) =
    RecordingDeletePreference(setting, R.string.swipe_recording_delete_message) { SwipeRecorder.clear(it) }
