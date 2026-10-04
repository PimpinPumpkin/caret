// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.screens

import app.caret.latin.voice.InlineDictation
import android.widget.Toast
import androidx.core.content.edit
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.caret.latin.utils.getCodeForToolbarKey
import app.caret.latin.utils.ToolbarKey
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.keyboard.internal.keyboard_parser.floris.KeyLabel
import app.caret.settings.preferences.SliderPreference
import app.caret.latin.suggestions.SuggestionStripView
import app.caret.keyboard.KeyboardSwitcher
import app.caret.latin.R
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.utils.GestureDataGatheringSettings.filterBackgroundGatheringToolbarKeys
import app.caret.latin.utils.Log
import app.caret.latin.utils.ToolbarMode
import app.caret.latin.utils.getActivity
import app.caret.latin.utils.getStringResourceOrName
import app.caret.latin.utils.prefs
import app.caret.settings.SearchSettingsScreen
import app.caret.settings.SettingsFold
import app.caret.settings.Setting
import app.caret.settings.SettingsActivity
import app.caret.settings.SettingsDestination
import app.caret.latin.utils.NextScreenIcon
import app.caret.latin.utils.Theme
import app.caret.settings.dialogs.ToolbarKeysCustomizer
import app.caret.settings.initPreview
import app.caret.settings.preferences.ListPreference
import app.caret.settings.preferences.Preference
import app.caret.settings.preferences.ReorderSwitchPreference
import app.caret.settings.preferences.SwitchPreference
import app.caret.latin.utils.previewDark

@Composable
fun ToolbarScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val toolbarMode = Settings.readToolbarMode(prefs)
    val clipboardToolbarVisible = toolbarMode != ToolbarMode.HIDDEN
        || !prefs.getBoolean(Settings.PREF_TOOLBAR_HIDING_GLOBAL, Defaults.PREF_TOOLBAR_HIDING_GLOBAL)
    val items = listOf(
        Settings.PREF_TOOLBAR_MODE,
        if (toolbarMode == ToolbarMode.HIDDEN) Settings.PREF_TOOLBAR_HIDING_GLOBAL else null,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_TOOLBAR_KEYS else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_TOOLBAR_ABOVE_SUGGESTIONS else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE && prefs.getBoolean(Settings.PREF_TOOLBAR_ABOVE_SUGGESTIONS, Defaults.PREF_TOOLBAR_ABOVE_SUGGESTIONS))
            Settings.PREF_TOOLBAR_OPENS_ACTIONS else null,
        SettingsFold,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_QUICK_PIN_TOOLBAR_KEYS else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_AUTO_SHOW_TOOLBAR else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_AUTO_HIDE_TOOLBAR else null,
        if (clipboardToolbarVisible) Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES else null,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD else null,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_VARIABLE_TOOLBAR_DIRECTION else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_toolbar),
        settings = items
    )
}

fun createToolbarSettings(context: Context) = listOf(
    Setting(context, KeyLabel.PREF_ACTION_KEY, R.string.action_key, R.string.action_key_summary) { setting ->
        val ctx = LocalContext.current
        // every toolbar action that works as a key
        val items = ToolbarKey.entries
            .filter { getCodeForToolbarKey(it) != KeyCode.UNSPECIFIED && it != ToolbarKey.CLOSE_HISTORY && it != ToolbarKey.BACKGROUND_GATHERING }
            .map { it.name.lowercase().getStringResourceOrName("", ctx) to it.name.lowercase() }
        ListPreference(setting, items, KeyLabel.DEFAULT_ACTION_KEY) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, SuggestionStripView.PREF_MIC_ON_LEFT, R.string.mic_on_left, R.string.mic_on_left_summary) {
        SwitchPreference(it, false) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_INLINE_DICTATION, R.string.inline_dictation, R.string.inline_dictation_summary) {
        val ctx = LocalContext.current
        // the keyboard itself cannot ask for a permission, so it is asked for here when the switch goes on
        val askForMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) ctx.prefs().edit { putBoolean(Settings.PREF_INLINE_DICTATION, false) }
        }
        SwitchPreference(it, Defaults.PREF_INLINE_DICTATION) { on ->
            if (!on) return@SwitchPreference
            if (!InlineDictation.hasPermission(ctx)) askForMicrophone.launch(android.Manifest.permission.RECORD_AUDIO)
            if (InlineDictation.findService(ctx) == null)
                Toast.makeText(ctx, R.string.inline_dictation_no_service, Toast.LENGTH_LONG).show()
        }
    },
    Setting(context, Settings.PREF_DICTATION_PAUSE_PERIODS, R.string.dictation_pause_periods, R.string.dictation_pause_periods_summary) {
        SwitchPreference(it, Defaults.PREF_DICTATION_PAUSE_PERIODS)
    },
    Setting(context, Settings.PREF_DICTATION_SOUND, R.string.dictation_sound, R.string.dictation_sound_summary) {
        SwitchPreference(it, Defaults.PREF_DICTATION_SOUND)
    },
    Setting(context, SuggestionStripView.PREF_MIC_SIZE, R.string.mic_size) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = SuggestionStripView.DEFAULT_MIC_SIZE,
            range = 0.8f..1.6f,
            description = { "${(it * 100).toInt()}%" },
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_TOOLBAR_MODE, R.string.toolbar_mode) { setting ->
        val ctx = LocalContext.current
        val items =
            ToolbarMode.entries.map { it.name.lowercase().getStringResourceOrName("toolbar_mode_", ctx) to it.name }
        ListPreference(
            setting,
            items,
            Defaults.PREF_TOOLBAR_MODE
        ) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_TOOLBAR_HIDING_GLOBAL, R.string.toolbar_hiding_global) {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_HIDING_GLOBAL) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE, R.string.toolbar_swipe_down_to_hide, R.string.toolbar_swipe_down_to_hide_summary) {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE)
    },
    Setting(context, Settings.PREF_TOOLBAR_KEYS, R.string.toolbar_arrange, R.string.toolbar_arrange_summary) {
        Preference(
            name = it.title,
            description = it.description,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.ToolbarArrange) },
        ) { NextScreenIcon() }
    },
    Setting(context, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, R.string.clipboard_toolbar_keys) {
        val keys = Defaults.PREF_CLIPBOARD_TOOLBAR_KEYS.filterBackgroundGatheringToolbarKeys(LocalContext.current.prefs())
        ReorderSwitchPreference(it, keys)
    },
    Setting(context, Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, R.string.customize_toolbar_key_codes) {
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = it.title,
            onClick = { showDialog = true },
        )
        if (showDialog)
            ToolbarKeysCustomizer(
                key = it.key,
                onDismissRequest = { showDialog = false }
            )
    },
    Setting(context, Settings.PREF_TOOLBAR_OPENS_ACTIONS,
        R.string.toolbar_opens_actions, R.string.toolbar_opens_actions_summary)
    {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_OPENS_ACTIONS)
    },
    Setting(context, Settings.PREF_TOOLBAR_ABOVE_SUGGESTIONS,
        R.string.toolbar_above_suggestions, R.string.toolbar_above_suggestions_summary)
    {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_ABOVE_SUGGESTIONS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_QUICK_PIN_TOOLBAR_KEYS,
        R.string.quick_pin_toolbar_keys, R.string.quick_pin_toolbar_keys_summary)
    {
        SwitchPreference(it, Defaults.PREF_QUICK_PIN_TOOLBAR_KEYS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_AUTO_SHOW_TOOLBAR, R.string.auto_show_toolbar, R.string.auto_show_toolbar_summary)
    {
        SwitchPreference(it, Defaults.PREF_AUTO_SHOW_TOOLBAR)
    },
    Setting(context, Settings.PREF_AUTO_HIDE_TOOLBAR, R.string.auto_hide_toolbar, R.string.auto_hide_toolbar_summary)
    {
        SwitchPreference(it, Defaults.PREF_AUTO_HIDE_TOOLBAR)
    },
    Setting(context, Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD,
        R.string.toolbar_only_with_hw_keyboard, R.string.toolbar_only_with_hw_keyboard_summary)
    {
        SwitchPreference(it, Defaults.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload() // necessary for updating insets
        }
    },
    Setting(context, Settings.PREF_VARIABLE_TOOLBAR_DIRECTION,
        R.string.var_toolbar_direction, R.string.var_toolbar_direction_summary)
    {
        SwitchPreference(it, Defaults.PREF_VARIABLE_TOOLBAR_DIRECTION)
    }
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            ToolbarScreen { }
        }
    }
}
