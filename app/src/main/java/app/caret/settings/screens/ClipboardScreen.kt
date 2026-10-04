// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.caret.latin.R
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.utils.Log
import app.caret.latin.utils.ToolbarMode
import app.caret.latin.utils.getActivity
import app.caret.latin.utils.prefs
import app.caret.settings.SearchSettingsScreen
import app.caret.settings.SettingsActivity

/** Everything about the clipboard in one place. The settings themselves are defined on the pages they came from. */
@Composable
fun ClipboardScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val history = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    val toolbarVisible = Settings.readToolbarMode(prefs) != ToolbarMode.HIDDEN
        || !prefs.getBoolean(Settings.PREF_TOOLBAR_HIDING_GLOBAL, Defaults.PREF_TOOLBAR_HIDING_GLOBAL)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_clipboard),
        settings = listOf(
            Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
            Settings.PREF_MASK_CLIPBOARD_SECRETS,
            Settings.PREF_SUGGEST_CLIPBOARD_CONTENT,
            if (history) Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME else null,
            if (history) Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST else null,
            if (history) Settings.PREF_CLIPBOARD_USE_FILES else null,
            if (history && prefs.getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES))
                Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT else null,
            if (toolbarVisible) Settings.PREF_CLIPBOARD_TOOLBAR_KEYS else null,
        )
    )
}
