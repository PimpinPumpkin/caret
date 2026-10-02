// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.caret.keyboard.KeyboardSwitcher
import app.caret.latin.R
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.utils.JniUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.getActivity
import app.caret.latin.utils.prefs
import app.caret.settings.Setting
import app.caret.settings.SettingsWithoutKey
import app.caret.settings.preferences.SwipeModelsPreference
import app.caret.latin.swipe.FutoSwipe
import app.caret.latin.swipe.SwipeRecorder
import app.caret.settings.preferences.SwipePracticePreference
import app.caret.settings.preferences.SwipeRecordingDeletePreference
import app.caret.settings.preferences.SwipeRecordingSavePreference
import app.caret.settings.SearchSettingsScreen
import app.caret.settings.SettingsActivity
import app.caret.settings.preferences.SliderPreference
import app.caret.settings.preferences.SwitchPreference
import app.caret.latin.utils.Theme
import app.caret.settings.initPreview
import app.caret.latin.utils.previewDark
import androidx.core.content.edit

@Composable
fun GestureTypingScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val gestureFloatingPreviewEnabled = prefs.getBoolean(Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT, Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
    val gestureEnabled = prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
    val ctx = LocalContext.current
    val haveGestures = JniUtils.haveGestureLib
    val items = listOf(
        if (FutoSwipe.hasLibrary()) SettingsWithoutKey.SWIPE_MODELS else null,
        if (FutoSwipe.isInstalled(ctx) && FutoSwipe.hasOtherLibrary) FutoSwipe.PREF_ENABLED else null,
    ) + (if (!haveGestures) emptyList() else listOf(
        Settings.PREF_GESTURE_INPUT,
        if (gestureEnabled)
            Settings.PREF_GESTURE_PREVIEW_TRAIL else null,
        if (gestureEnabled)
            Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT else null,
        if (gestureEnabled && gestureFloatingPreviewEnabled)
            Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC else null,
        if (gestureEnabled)
            Settings.PREF_GESTURE_SPACE_AWARE else null,
        if (gestureEnabled)
            Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN else null,
        if (gestureEnabled &&
            (prefs.getBoolean(Settings.PREF_GESTURE_PREVIEW_TRAIL, Defaults.PREF_GESTURE_PREVIEW_TRAIL) || gestureFloatingPreviewEnabled))
            Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION else null
        )) + if (!FutoSwipe.isActive(ctx)) emptyList() else listOf(
            R.string.swipe_recording_category,
            SwipeRecorder.PREF_ENABLED,
            SettingsWithoutKey.SWIPE_PRACTICE,
            SettingsWithoutKey.SWIPE_RECORDING_SAVE,
            SettingsWithoutKey.SWIPE_RECORDING_DELETE,
        )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_gesture),
        settings = items
    )
}

fun createGestureTypingSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.SWIPE_MODELS, R.string.swipe_models_download, R.string.swipe_models_import_summary) {
        SwipeModelsPreference { }
    },
    Setting(context, FutoSwipe.PREF_ENABLED, R.string.swipe_engine_futo, R.string.swipe_engine_futo_summary) {
        val ctx = LocalContext.current
        SwitchPreference(it, true) { FutoSwipe.refreshAvailability(ctx) }
    },
    Setting(context, SwipeRecorder.PREF_ENABLED, R.string.swipe_recording, R.string.swipe_recording_summary) {
        SwitchPreference(it, false)
    },
    Setting(context, SettingsWithoutKey.SWIPE_PRACTICE, R.string.swipe_practice, R.string.swipe_practice_summary) {
        SwipePracticePreference(it)
    },
    Setting(context, SettingsWithoutKey.SWIPE_RECORDING_SAVE, R.string.swipe_recording_save) {
        SwipeRecordingSavePreference(it)
    },
    Setting(context, SettingsWithoutKey.SWIPE_RECORDING_DELETE, R.string.swipe_recording_delete) {
        SwipeRecordingDeletePreference(it)
    },
    Setting(context, Settings.PREF_GESTURE_INPUT, R.string.gesture_input, R.string.gesture_input_summary) {
        SwitchPreference(it, Defaults.PREF_GESTURE_INPUT)
    },
    Setting(context, Settings.PREF_GESTURE_PREVIEW_TRAIL, R.string.gesture_preview_trail) {
        SwitchPreference(it, Defaults.PREF_GESTURE_PREVIEW_TRAIL)
    },
    Setting(context, Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
        R.string.gesture_floating_preview_static, R.string.gesture_floating_preview_static_summary)
    {
        SwitchPreference(it, Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
    },
    Setting(context, Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC,
        R.string.gesture_floating_preview_text, R.string.gesture_floating_preview_dynamic_summary)
    { def ->
        val ctx = LocalContext.current
        SwitchPreference(def, Defaults.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC) {
            // is this complexity and 2 pref keys for one setting really needed?
            // default value is based on system reduced motion
            val default = Settings.readGestureDynamicPreviewDefault(ctx)
            val followingSystem = it == default
            // allow the default to be overridden
            ctx.prefs().edit { putBoolean(Settings.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM, followingSystem) }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_GESTURE_SPACE_AWARE, R.string.gesture_space_aware, R.string.gesture_space_aware_summary) {
        SwitchPreference(it, Defaults.PREF_GESTURE_SPACE_AWARE)
    },
    Setting(context, Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN, R.string.gesture_fast_typing_cooldown) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_FAST_TYPING_COOLDOWN,
            range = 0f..500f,
            description = {
                if (it <= 0) stringResource(R.string.gesture_fast_typing_cooldown_instant)
                else stringResource(R.string.abbreviation_unit_milliseconds, it.toString())
            }
        )
    },
    Setting(context, Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, R.string.gesture_trail_fadeout_duration) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_TRAIL_FADEOUT_DURATION,
            range = 100f..1900f,
            description = { stringResource(R.string.abbreviation_unit_milliseconds, (it + 100).toString()) },
            stepSize = 10,
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
)

@Preview
@Composable
private fun Preview() {
    JniUtils.haveGestureLib = true
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            GestureTypingScreen { }
        }
    }
}
