// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.caret.settings.preferences.PreferenceGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import app.caret.latin.swipe.FutoSwipe
import app.caret.latin.R
import app.caret.latin.utils.prefs
import app.caret.latin.utils.GestureDataGatheringSettings
import app.caret.latin.utils.JniUtils
import app.caret.latin.utils.SubtypeLocaleUtils.displayName
import app.caret.latin.utils.SubtypeSettings
import app.caret.latin.utils.NextScreenIcon
import app.caret.settings.SearchSettingsScreen
import app.caret.latin.utils.Theme
import app.caret.settings.initPreview
import app.caret.settings.preferences.Preference
import app.caret.latin.utils.previewDark
import app.caret.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import app.caret.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                val showGesture = JniUtils.haveGestureLib || FutoSwipe.hasLibrary()
                // we don't even show the menu if data gathering phase ended more than 2 weeks ago
                val showDataGathering = JniUtils.haveGestureLib && GestureDataGatheringSettings.isContributionEnabled(LocalContext.current.prefs())
                    && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS
                @Composable
                fun Entry(name: Int, icon: Int, description: String? = null, onClick: () -> Unit) =
                    Preference(name = stringResource(name), description = description, onClick = onClick, icon = icon) { NextScreenIcon() }
                Spacer(Modifier.height(8.dp))
                PreferenceGroup(
                    { Entry(R.string.language_and_layouts_title, R.drawable.ic_settings_languages,
                        enabledSubtypes.joinToString(", ") { it.displayName() }, onClickLanguage) },
                    { Entry(R.string.settings_screen_secondary_layouts, R.drawable.ic_settings_layout, onClick = onClickLayouts) },
                    { Entry(R.string.dictionary_settings_category, R.drawable.ic_dictionary, onClick = onClickDictionaries) },
                )
                PreferenceGroup(
                    { Entry(R.string.settings_screen_appearance, R.drawable.ic_settings_appearance, onClick = onClickAppearance) },
                    { Entry(R.string.settings_screen_toolbar, R.drawable.ic_settings_toolbar, onClick = onClickToolbar) },
                    { Entry(R.string.settings_screen_preferences, R.drawable.ic_settings_preferences, onClick = onClickPreferences) },
                )
                PreferenceGroup(
                    { Entry(R.string.settings_screen_correction, R.drawable.ic_settings_correction, onClick = onClickTextCorrection) },
                    if (showGesture) { { Entry(R.string.settings_screen_gesture, R.drawable.ic_settings_gesture, onClick = onClickGestureTyping) } } else null,
                    if (showDataGathering) { { Entry(R.string.gesture_data_screen, R.drawable.ic_settings_gesture, onClick = onClickDataGathering) } } else null,
                )
                PreferenceGroup(
                    { Entry(R.string.settings_screen_advanced, R.drawable.ic_settings_advanced, onClick = onClickAdvanced) },
                    { Entry(R.string.settings_screen_about, R.drawable.ic_settings_about, onClick = onClickAbout) },
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
