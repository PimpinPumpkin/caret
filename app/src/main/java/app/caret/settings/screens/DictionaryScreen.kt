// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.R
import app.caret.latin.common.Links
import app.caret.latin.common.LocaleUtils
import app.caret.latin.common.LocaleUtils.localizedDisplayName
import app.caret.latin.common.splitOnWhitespace
import app.caret.latin.utils.DictionaryInfoUtils
import app.caret.latin.utils.IntentUtils
import app.caret.latin.utils.SubtypeLocaleUtils
import app.caret.latin.utils.SubtypeSettings
import app.caret.latin.utils.getDictionaryLocales
import app.caret.latin.utils.htmlToAnnotated
import app.caret.latin.utils.locale
import app.caret.latin.utils.withHtmlLink
import app.caret.settings.SearchScreen
import app.caret.latin.utils.Theme
import app.caret.settings.dialogs.ConfirmationDialog
import app.caret.settings.dialogs.DictionaryDialog
import app.caret.settings.dictionaryFilePicker
import app.caret.settings.initPreview
import app.caret.latin.utils.previewDark
import java.io.File
import java.util.Locale

@Composable
fun DictionaryScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val enabledLanguages = SubtypeSettings.getEnabledSubtypes(true).map { it.locale().language }
    val cachedDictFolders = DictionaryInfoUtils.getCacheDirectories(ctx).map { it.name }
    val comparer = compareBy<Locale>({ it.language !in enabledLanguages }, { it.toLanguageTag() !in cachedDictFolders }, { it.displayName })
    val dictionaryLocales = listOf<Locale?>(null) + getDictionaryLocales(ctx).sortedWith(comparer)
    var selectedLocale: Locale? by remember { mutableStateOf(null) }
    var showAddDictDialog by remember { mutableStateOf(false) }
    val dictPicker = dictionaryFilePicker(selectedLocale)
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.dictionary_settings_category)) },
        filteredItems = { term ->
            if (term.isBlank()) dictionaryLocales
            else dictionaryLocales.filter { loc ->
                    loc != null
                    && loc.localizedDisplayName(ctx.resources).replace("(", "")
                        .splitOnWhitespace().any { it.startsWith(term, true) }
                }
        },
        itemContent = { locale ->
            if (locale == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .clickable { showAddDictDialog = true }
                        .padding(vertical = 4.dp, horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.add_new_dictionary_title),
                    )
                    Icon(painterResource(R.drawable.ic_plus), stringResource(R.string.add_new_dictionary_title))
                }
            } else {
                Column(
                    Modifier
                        .clickable { selectedLocale = locale }
                        .padding(vertical = 6.dp, horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    val (dicts, hasInternal) = getUserAndInternalDictionaries(ctx, locale)
                    val types = dicts.mapTo(mutableListOf()) { it.name.substringBefore("_${DictionaryInfoUtils.USER_DICTIONARY_SUFFIX}") }
                    if (hasInternal && !types.contains(Dictionary.TYPE_MAIN))
                        types.add(0, stringResource(R.string.internal_dictionary_summary))
                    Text(locale.localizedDisplayName(LocalResources.current))
                    Text(
                        types.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
    if (showAddDictDialog) {
        ConfirmationDialog(
            onDismissRequest = { showAddDictDialog = false },
            onConfirmed = {
                val intent = IntentUtils.getResolvableTypeIntent(ctx, "application/octet-stream")
                    .setAction(Intent.ACTION_OPEN_DOCUMENT)
                dictPicker.launch(intent)
            },
            title = { Text(stringResource(R.string.add_new_dictionary_title)) },
            content = {
                val link = stringResource(R.string.dictionary_link_text).withHtmlLink(Links.DICTIONARY_URL)
                val addDictString = stringResource(R.string.add_dictionary, link)
                Text(addDictString.htmlToAnnotated())
            }
        )
    }
    selectedLocale?.let { locale ->
        DictionaryDialog(
            onDismissRequest = { selectedLocale = null },
            locale = locale
        )
    }
}

/** @return list of user dictionary files and whether an internal dictionary exists */
fun getUserAndInternalDictionaries(context: Context, locale: Locale): Pair<List<File>, Boolean> {
    val userDicts = mutableListOf<File>()
    var hasInternalDict = false
    val userLocaleDir = DictionaryInfoUtils.getCacheDirectoryForLocale(locale, context)?.let { File(it) }
    if (userLocaleDir?.exists() == true && userLocaleDir.isDirectory) {
        userLocaleDir.listFiles()?.forEach {
            if (it.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX))
                userDicts.add(it)
            else if (it.name.startsWith(DictionaryInfoUtils.MAIN_DICT_PREFIX))
                hasInternalDict = true
        }
    }
    if (hasInternalDict)
        return userDicts to true
    val internalDicts = DictionaryInfoUtils.getAssetsDictionaryList(context) ?: return userDicts to false
    val best = LocaleUtils.getBestMatch(locale, internalDicts.toList()) {
        DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile(it)
    }
    return userDicts to (best != null)
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            DictionaryScreen { }
        }
    }
}
