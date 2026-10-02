// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.screens

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.caret.latin.R
import app.caret.latin.common.Constants.Separators
import app.caret.latin.common.Constants.Subtype.ExtraValue
import app.caret.latin.common.LocaleUtils
import app.caret.latin.common.LocaleUtils.constructLocale
import app.caret.latin.common.LocaleUtils.localizedDisplayName
import app.caret.latin.common.splitOnWhitespace
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import app.caret.latin.utils.DictionaryInfoUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.MissingDictionaryDialog
import app.caret.latin.utils.SubtypeLocaleUtils
import app.caret.latin.utils.SubtypeLocaleUtils.displayName
import app.caret.latin.utils.SubtypeSettings
import app.caret.latin.utils.getActivity
import app.caret.latin.utils.locale
import app.caret.latin.utils.prefs
import app.caret.settings.SearchScreen
import app.caret.settings.SettingsActivity
import app.caret.settings.SettingsDestination
import app.caret.latin.utils.Theme
import app.caret.settings.initPreview
import app.caret.latin.utils.previewDark
import java.util.Locale

@Composable
fun LanguageScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val sortedSubtypes by remember { mutableStateOf(getSortedSubtypes(ctx)) }
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val enabledSubtypes = SubtypeSettings.getEnabledSubtypes()
    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.language_and_layouts_title))
                Text(stringResource(
                    R.string.text_tap_languages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        filteredItems = { term ->
            sortedSubtypes.filter { subtype ->
                subtype.displayName().replace("(", "")
                    .splitOnWhitespace().any { it.startsWith(term, true) }
            }
        },
        itemContent = { SubtypeRow(it, it in enabledSubtypes) }
    )
}

@Composable
private fun SubtypeRow(subtype: InputMethodSubtype, isEnabled: Boolean) {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                SettingsDestination.navigateTo(SettingsDestination.Subtype + subtype.toSettingsSubtype().toPref())
            }
            .padding(vertical = 6.dp, horizontal = 16.dp)
    ) {
        var showNoDictDialog by remember { mutableStateOf(false) }
        Column(modifier = Modifier.weight(1f)) {
            Text(subtype.displayName(), style = MaterialTheme.typography.bodyLarge)
            val description = if (SubtypeSettings.isAdditionalSubtype(subtype)) {
                val secondaryLocales = subtype.getExtraValueOf(ExtraValue.SECONDARY_LOCALES)?.split(Separators.KV)
                    ?.joinToString(", ") { it.constructLocale().localizedDisplayName(ctx.resources) }
                stringResource(R.string.custom_subtype) + (secondaryLocales?.let { "\n$it" } ?: "")
            } else null
            if (description != null)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = {
                if (it && !dictsAvailable(subtype.locale(), ctx))
                    showNoDictDialog = true
                if (it) SubtypeSettings.addEnabledSubtype(ctx.prefs(), subtype)
                else SubtypeSettings.removeEnabledSubtype(ctx, subtype)
            }
        )
        if (showNoDictDialog)
            MissingDictionaryDialog({ showNoDictDialog = false }, subtype.locale())
    }
}

private fun dictsAvailable(locale: Locale, context: Context): Boolean {
    if (locale.language == SubtypeLocaleUtils.NO_LANGUAGE) return true // incorrect, but we don't want to show the dialog for "no language"
    val (dicts, hasInternal) = getUserAndInternalDictionaries(context, locale)
    return hasInternal || dicts.isNotEmpty()
}

// sorting by display name is still slow, even with the cache... but probably good enough
private fun getSortedSubtypes(context: Context): List<InputMethodSubtype> {
    val systemLocales = SubtypeSettings.getSystemLocales()
    val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
    val localesWithDictionary = DictionaryInfoUtils.getCacheDirectories(context).mapNotNull { dir ->
        if (dir.list()?.any { it.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) } == true)
            dir.name.constructLocale()
        else null
    }

    val defaultAdditionalSubtypes = Defaults.PREF_ADDITIONAL_SUBTYPES.split(Separators.SETS).map {
        it.substringBefore(Separators.SET) to (it.substringAfter(Separators.SET) + ",AsciiCapable,EmojiCapable,isAdditionalSubtype")
    }
    fun isDefaultSubtype(subtype: InputMethodSubtype): Boolean =
        defaultAdditionalSubtypes.any { it.first == subtype.locale().language && it.second == subtype.extraValue }

    val subtypeSortComparator = compareBy<InputMethodSubtype>(
        { it !in enabledSubtypes },
        { it.locale() !in localesWithDictionary },
        { it.locale() !in systemLocales},
        { !(SubtypeSettings.isAdditionalSubtype(it) && !isDefaultSubtype(it) ) },
        {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) it.languageTag == SubtypeLocaleUtils.NO_LANGUAGE
            else it.locale == SubtypeLocaleUtils.NO_LANGUAGE
        },
        { it.displayName() }
    )
    return SubtypeSettings.getAllAvailableSubtypes().sortedWith(subtypeSortComparator)
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            LanguageScreen { }
        }
    }
}
