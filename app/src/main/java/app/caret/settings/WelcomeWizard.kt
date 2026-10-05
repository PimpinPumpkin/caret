// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.caret.latin.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import app.caret.latin.settings.Defaults
import app.caret.latin.swipe.FutoSwipe
import app.caret.latin.utils.prefs
import app.caret.latin.voice.InlineDictation
import app.caret.latin.swipe.SwipeModels
import app.caret.latin.utils.Log
import app.caret.latin.utils.Theme
import app.caret.latin.utils.UncachedInputMethodManagerUtils
import app.caret.latin.utils.previewDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run setup on one screen: enable the keyboard, switch to it, and optionally get the swipe
 * typing models. Each step shows as done as soon as the system reports it.
 */
@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    var enabled by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)) }
    var current by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) }
    var swipeInstalled by remember { mutableStateOf(FutoSwipe.isInstalled(ctx)) }
    var swipeSkipped by rememberSaveable { mutableStateOf(false) }
    var swipeProgress: Float? by remember { mutableStateOf(null) }
    var swipeError: String? by remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    // dictation inside the keyboard is offered when the phone has a speech recognizer (Alyo, for one)
    val prefs = ctx.prefs()
    val canDictate = remember { InlineDictation.findService(ctx) != null }
    var voiceOn by remember {
        mutableStateOf(prefs.getBoolean(app.caret.latin.settings.Settings.PREF_INLINE_DICTATION, Defaults.PREF_INLINE_DICTATION) && InlineDictation.hasPermission(ctx))
    }
    var voiceSkipped by rememberSaveable { mutableStateOf(false) }
    val askForMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            prefs.edit { putBoolean(app.caret.latin.settings.Settings.PREF_INLINE_DICTATION, true) }
            voiceOn = true
        }
    }

    // the system does not tell us when the user enables or picks the keyboard, so ask
    LaunchedEffect(Unit) {
        while (true) {
            val (isEnabled, isCurrent) = withContext(Dispatchers.IO) {
                UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) to
                    UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
            }
            enabled = isEnabled
            current = isEnabled && isCurrent
            delay(300)
        }
    }

    val appName = stringResource(ctx.applicationInfo.labelRes)
    val swipeDone = swipeInstalled || swipeSkipped
    val activeStep = when {
        !enabled -> 1
        !current -> 2
        !swipeDone -> 3
        canDictate && !voiceOn && !voiceSkipped -> 4
        else -> 0
    }

    @Composable fun header() {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            AppBadge()
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.setup_welcome_title, appName),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable fun steps() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepCard(
                number = 1,
                title = stringResource(R.string.setup_step1_title, appName),
                instruction = stringResource(R.string.setup_step1_instruction, appName),
                done = enabled,
                active = activeStep == 1,
            ) {
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    intent.addCategory(Intent.CATEGORY_DEFAULT)
                    ctx.startActivity(intent)
                }) { Text(stringResource(R.string.setup_step1_action)) }
            }
            StepCard(
                number = 2,
                title = stringResource(R.string.setup_step2_title, appName),
                instruction = stringResource(R.string.setup_step2_instruction, appName),
                done = current,
                active = activeStep == 2,
            ) {
                Button(onClick = imm::showInputMethodPicker) { Text(stringResource(R.string.setup_step2_action)) }
            }
            StepCard(
                number = 3,
                title = stringResource(R.string.setup_swipe_title),
                instruction = when {
                    swipeError != null -> stringResource(R.string.swipe_models_failed, swipeError ?: "")
                    else -> stringResource(
                        R.string.setup_swipe_instruction,
                        Formatter.formatShortFileSize(ctx, SwipeModels.totalSize)
                    )
                },
                done = swipeInstalled,
                active = activeStep == 3,
            ) {
                val progress = swipeProgress
                if (progress != null) {
                    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            swipeProgress = 0f
                            swipeError = null
                            scope.launch {
                                swipeError = withContext(Dispatchers.IO) {
                                    try {
                                        SwipeModels.download(ctx) { swipeProgress = it }
                                        null
                                    } catch (e: Exception) {
                                        Log.w("WelcomeWizard", "could not get swipe models", e)
                                        e.message ?: e.javaClass.simpleName
                                    }
                                }
                                swipeProgress = null
                                swipeInstalled = FutoSwipe.isInstalled(ctx)
                            }
                        }) { Text(stringResource(R.string.setup_swipe_download)) }
                        TextButton(onClick = { swipeSkipped = true }) { Text(stringResource(R.string.setup_swipe_skip)) }
                    }
                }
            }
            if (canDictate) StepCard(
                number = 4,
                title = stringResource(R.string.setup_voice_title),
                instruction = stringResource(R.string.setup_voice_instruction),
                done = voiceOn,
                active = activeStep == 4,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { askForMicrophone.launch(android.Manifest.permission.RECORD_AUDIO) }) {
                        Text(stringResource(R.string.setup_voice_action))
                    }
                    TextButton(onClick = { voiceSkipped = true }) { Text(stringResource(R.string.setup_swipe_skip)) }
                }
            }
            AnimatedVisibility(activeStep == 0) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.setup_step3_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    var sample by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        value = sample,
                        onValueChange = { sample = it },
                        label = { Text(stringResource(R.string.setup_try_hint)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = close) { Text(stringResource(R.string.setup_step3_action)) }
                        FilledTonalButton(onClick = finish) { Text(stringResource(R.string.setup_finish_action)) }
                    }
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        if (isWideScreen())
            Row(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(0.4f)) { header() }
                Box(Modifier.weight(0.6f).verticalScroll(rememberScrollState())) { steps() }
            }
        else
            Box(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.widthIn(max = 560.dp).padding(horizontal = 20.dp, vertical = 32.dp)) {
                    header()
                    Spacer(Modifier.height(32.dp))
                    steps()
                }
            }
    }
}

/** The carrot on a soft Material shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppBadge() {
    Box(
        Modifier.size(128.dp)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(216.dp))
    }
}

/** One setup step. The active one is larger and tinted and shows its [action]; done ones show a check. */
@Composable
private fun StepCard(
    number: Int,
    title: String,
    instruction: String,
    done: Boolean,
    active: Boolean,
    action: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (active) colors.primaryContainer else colors.surfaceContainerHighest)
    val content = when {
        active -> colors.onPrimaryContainer
        done -> colors.onSurface
        else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(if (active) 28.dp else 20.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(36.dp)
                    .clip(CircleShape)
                    .background(if (done) colors.primary else if (active) colors.onPrimaryContainer.copy(alpha = 0.12f) else colors.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                if (done)
                    Icon(painterResource(R.drawable.ic_setup_check), null, Modifier.size(20.dp), tint = colors.onPrimary)
                else
                    Text(number.toString(), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (active) {
                    Text(instruction, style = MaterialTheme.typography.bodyMedium)
                    action()
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(device = "spec:orientation=landscape,width=400dp,height=780dp")
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}
