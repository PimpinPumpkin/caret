// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.caret.latin.R
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.suggestions.SuggestionStripView
import app.caret.latin.utils.Log
import app.caret.latin.utils.ToolbarMode
import app.caret.latin.utils.getActivity
import app.caret.latin.utils.prefs
import app.caret.settings.SearchSettingsScreen
import app.caret.settings.SettingsActivity
import app.caret.settings.SettingsWithoutKey

/** Dictation: what the microphone key does and how it looks. The settings themselves are defined with the toolbar's. */
@Composable
fun VoiceTypingScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val toolbarMode = Settings.readToolbarMode(prefs)
    val micOnStrip = toolbarMode == ToolbarMode.EXPANDABLE || toolbarMode == ToolbarMode.SUGGESTION_STRIP
    val dictation = prefs.getBoolean(Settings.PREF_INLINE_DICTATION, Defaults.PREF_INLINE_DICTATION)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice),
        settings = listOf(
            Settings.PREF_INLINE_DICTATION,
            if (dictation) SettingsWithoutKey.DICTATION_STATUS else null,
            if (dictation) Settings.PREF_DICTATION_SOUND else null,
            if (dictation) Settings.PREF_DICTATION_PAUSE_PERIODS else null,
            if (micOnStrip) SuggestionStripView.PREF_MIC_ON_LEFT else null,
            if (micOnStrip) SuggestionStripView.PREF_MIC_SIZE else null,
        )
    )
}
