// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.preferences

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.KeyboardTheme
import app.caret.latin.R
import app.caret.latin.common.ColorType
import app.caret.latin.common.Colors
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.utils.DeleteButton
import app.caret.latin.utils.EditButton
import app.caret.latin.utils.getActivity
import app.caret.latin.utils.getStringResourceOrName
import app.caret.latin.utils.prefs
import app.caret.settings.Setting
import app.caret.settings.SettingsActivity
import app.caret.settings.SettingsDestination
import app.caret.settings.dialogs.ConfirmationDialog
import app.caret.settings.dialogs.LoadColorsDialog

/**
 * The color themes as a row of small keyboards, each drawn in its own colors. A tap puts a theme
 * on the keyboard. The first cards make a new theme or load one from a file; themes of the user's
 * own can be changed and deleted from under their picture.
 */
@Composable
fun ThemeGalleryPreference(setting: Setting, isNight: Boolean, default: String) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val changes = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()?.value ?: 0

    val defaultColors = KeyboardTheme.getAvailableDefaultColors(prefs, isNight)
    // prefs.all is null in preview only
    val userColors = (prefs.all ?: emptyMap()).keys.mapNotNull {
        when {
            it.startsWith(Settings.PREF_USER_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_COLORS_PREFIX)
            it.startsWith(Settings.PREF_USER_ALL_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_ALL_COLORS_PREFIX)
            it.startsWith(Settings.PREF_USER_MORE_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_MORE_COLORS_PREFIX)
            else -> null
        }
    }.toSortedSet()
    val selected = prefs.getString(setting.key, default)!!
    if (selected !in defaultColors) userColors.add(selected) // there are cases where we have no settings for a user theme
    val themes = (userColors + defaultColors).distinct()
    val editor = if (isNight) SettingsDestination.ColorsNight else SettingsDestination.Colors

    fun choose(theme: String) {
        prefs.edit { putString(setting.key, theme) }
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    var showLoad by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        // the two cards in front are not themes
        val index = themes.indexOf(selected) + 2
        if (index >= 3) listState.scrollToItem(index - 1)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            setting.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 8.dp),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = " new") {
                val name = KeyboardTheme.getUnusedThemeName(stringResource(R.string.theme_name_user), prefs)
                ActionCard(R.drawable.ic_plus, stringResource(R.string.theme_new)) {
                    choose(name)
                    KeyboardTheme.writeUserMoreColors(prefs, name, Defaults.PREF_USER_MORE_COLORS) // write sth so theme is stored
                    SettingsDestination.navigateTo(editor + name)
                }
            }
            item(key = " load") {
                ActionCard(R.drawable.ic_download, stringResource(R.string.load)) { showLoad = true }
            }
            items(themes, key = { it }) { theme ->
                // drawn again when a setting changes: a theme may just have been edited
                val colors = remember(theme, changes, isNight) {
                    runCatching { KeyboardTheme.getColorsForTheme(ctx, theme, isNight) }.getOrNull()
                }
                Column(Modifier.width(CARD_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
                    val shape = RoundedCornerShape(14.dp)
                    val isSelected = theme == selected
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(CARD_HEIGHT)
                            .border(
                                if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                                else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shape,
                            )
                            .padding(if (isSelected) 5.dp else 1.dp)
                            .clip(RoundedCornerShape(if (isSelected) 9.dp else 13.dp))
                            .clickable { choose(theme) },
                    ) {
                        if (colors != null) KeyboardThumbnail(colors, Modifier.fillMaxSize())
                    }
                    Text(
                        theme.getStringResourceOrName("theme_name_", ctx),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (theme in userColors) {
                        var confirmDelete by remember { mutableStateOf(false) }
                        Row {
                            EditButton { SettingsDestination.navigateTo(editor + theme) }
                            DeleteButton { confirmDelete = true }
                        }
                        if (confirmDelete)
                            ConfirmationDialog(
                                onDismissRequest = { confirmDelete = false },
                                content = { Text(stringResource(R.string.delete_confirmation, theme)) },
                                onConfirmed = {
                                    confirmDelete = false
                                    prefs.edit {
                                        remove(Settings.PREF_USER_COLORS_PREFIX + theme)
                                        remove(Settings.PREF_USER_ALL_COLORS_PREFIX + theme)
                                        remove(Settings.PREF_USER_MORE_COLORS_PREFIX + theme)
                                        if (isSelected) remove(setting.key)
                                    }
                                    KeyboardSwitcher.getInstance().setThemeNeedsReload()
                                }
                            )
                    }
                }
            }
        }
    }
    LoadColorsDialog(showLoad) { showLoad = false }
}

private val CARD_WIDTH = 148.dp
private val CARD_HEIGHT = 104.dp

/** A card the size of a theme that does something else: make a theme, load one. */
@Composable
private fun ActionCard(icon: Int, label: String, onClick: () -> Unit) {
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(CARD_HEIGHT)
                .clip(shape)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(28.dp), MaterialTheme.colorScheme.primary)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A keyboard small enough for a card, drawn with the colors of a theme: the strip, four rows of keys. */
@Composable
fun KeyboardThumbnail(colors: Colors, modifier: Modifier = Modifier) {
    val background = Color(colors.get(ColorType.MAIN_BACKGROUND))
    val strip = Color(colors.get(ColorType.STRIP_BACKGROUND))
    val key = Color(colors.get(ColorType.KEY_BACKGROUND))
    val functional = Color(colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
    val space = Color(colors.get(ColorType.SPACE_BAR_BACKGROUND))
    val action = Color(colors.get(ColorType.ACTION_KEY_BACKGROUND))
    val text = Color(colors.get(ColorType.KEY_TEXT))
    val word = Color(colors.get(ColorType.SUGGESTED_WORD))
    val borders = colors.hasKeyBorders
    val round = colors.themeStyle == KeyboardTheme.STYLE_ROUNDED
    Canvas(modifier) {
        drawRect(background)
        val stripHeight = size.height * 0.17f
        drawRect(strip, size = Size(size.width, stripHeight))
        // three suggestions
        val wordWidth = size.width * 0.16f
        for (i in 0..2) {
            val center = size.width * (0.25f + 0.25f * i)
            drawRoundRect(
                word.copy(alpha = if (i == 1) 0.9f else 0.5f),
                topLeft = Offset(center - wordWidth / 2, stripHeight * 0.4f),
                size = Size(wordWidth, stripHeight * 0.2f),
                cornerRadius = CornerRadius(stripHeight * 0.1f),
            )
        }
        val gap = size.width * 0.012f
        val rowHeight = (size.height - stripHeight - gap) / 4
        val unit = size.width / 10
        val corner = CornerRadius(if (round) rowHeight * 0.3f else rowHeight * 0.14f)

        fun key(color: Color?, left: Float, row: Int, width: Float, label: Boolean = true) {
            val top = stripHeight + gap + row * rowHeight
            if (color != null)
                drawRoundRect(color, Offset(left + gap, top + gap), Size(width - 2 * gap, rowHeight - 2 * gap), corner)
            if (label) dot(text, Offset(left + width / 2, top + rowHeight / 2), rowHeight * 0.09f)
        }

        val plain = if (borders) key else null
        val special = if (borders) functional else null
        for (i in 0 until 10) key(plain, i * unit, 0, unit)
        for (i in 0 until 9) key(plain, unit / 2 + i * unit, 1, unit)
        key(special, 0f, 2, unit * 1.5f)
        for (i in 0 until 7) key(plain, unit * 1.5f + i * unit, 2, unit)
        key(special, unit * 8.5f, 2, unit * 1.5f)
        key(special, 0f, 3, unit * 1.5f)
        key(plain, unit * 1.5f, 3, unit)
        // the space bar and the action key are drawn in every style
        key(space, unit * 2.5f, 3, unit * 5, label = false)
        key(plain, unit * 7.5f, 3, unit)
        key(action, unit * 8.5f, 3, unit * 1.5f, label = false)
    }
}

private fun DrawScope.dot(color: Color, center: Offset, radius: Float) = drawCircle(color, radius, center)
