// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings.screens

import android.content.SharedPreferences
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.latin.R
import app.caret.latin.common.Constants.Separators
import app.caret.latin.settings.Settings
import app.caret.latin.utils.GestureDataGatheringSettings
import app.caret.latin.utils.ToolbarKey
import app.caret.latin.utils.getEnabledToolbarKeys
import app.caret.latin.utils.getPinnedToolbarKeys
import app.caret.latin.utils.getStringResourceOrName
import app.caret.latin.utils.prefs
import app.caret.settings.GetIconOrEmpty
import app.caret.settings.SearchSettingsScreen
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** Where a toolbar key shows up. Every key is in exactly one of these. */
private enum class Place(val title: Int, val summary: Int) {
    PINNED(R.string.toolbar_place_pinned, R.string.toolbar_place_pinned_summary),
    TOOLBAR(R.string.toolbar_place_toolbar, R.string.toolbar_place_toolbar_summary),
    REST(R.string.toolbar_place_rest, R.string.toolbar_place_rest_summary),
}

/** One cell of the grid: the heading of a place, or a key. The keys after a heading are in its place. */
private data class Cell(val place: Place?, val key: ToolbarKey?) {
    val id: String get() = key?.name ?: "place_${place?.name}"
}

/**
 * All toolbar keys on one page, in the three places they can be. A key is held and dragged to
 * where it belongs, within its place to change the order or past a heading to change the place.
 */
@Composable
fun ToolbarArrangeScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var cells by remember { mutableStateOf(readCells(prefs)) }
    remember { KeyboardIconsSet.instance.loadIcons(ctx) } // the keys are shown by their icons
    val gridState = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
        // nothing goes above the first heading
        if (to.index == 0 || from.index !in cells.indices || to.index !in cells.indices) return@rememberReorderableLazyGridState
        cells = cells.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.toolbar_arrange),
        settings = emptyList(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cells, key = { it.id }, span = { if (it.key == null) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { cell ->
                val key = cell.key
                if (key == null) {
                    Column(Modifier.animateItem().padding(top = 12.dp, start = 4.dp, end = 4.dp)) {
                        Text(stringResource(cell.place!!.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(cell.place.summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    ReorderableItem(reorder, key = cell.id) { dragging ->
                        val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = elevation,
                            modifier = Modifier.longPressDraggableHandle(onDragStopped = { writeCells(prefs, cells) }),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                KeyboardIconsSet.instance.GetIconOrEmpty(key.name)
                                Text(
                                    key.name.lowercase().getStringResourceOrName("", ctx),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "reset", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    onClick = {
                        prefs.edit { remove(Settings.PREF_TOOLBAR_KEYS).remove(Settings.PREF_PINNED_TOOLBAR_KEYS) }
                        KeyboardSwitcher.getInstance().setThemeNeedsReload()
                        cells = readCells(prefs)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.button_default)) }
            }
        }
    }
}

private fun readCells(prefs: SharedPreferences): List<Cell> {
    val all = ToolbarKey.entries.filter {
        it != ToolbarKey.CLOSE_HISTORY
            && (it != ToolbarKey.BACKGROUND_GATHERING || GestureDataGatheringSettings.hasBackgroundGatheringPref(prefs))
    }
    val pinned = getPinnedToolbarKeys(prefs).filter { it in all }
    val toolbar = getEnabledToolbarKeys(prefs).filter { it in all && it !in pinned }
    val rest = all.filter { it !in pinned && it !in toolbar }
    return listOf(Cell(Place.PINNED, null)) + pinned.map { Cell(null, it) } +
        Cell(Place.TOOLBAR, null) + toolbar.map { Cell(null, it) } +
        Cell(Place.REST, null) + rest.map { Cell(null, it) }
}

private fun writeCells(prefs: SharedPreferences, cells: List<Cell>) {
    val keys = Place.entries.associateWith { mutableListOf<ToolbarKey>() }
    var place = Place.PINNED
    for (cell in cells) {
        if (cell.place != null) place = cell.place
        else if (cell.key != null) keys.getValue(place).add(cell.key)
    }
    // each setting lists every key: the ones of its place switched on and in order, then the others
    fun listing(on: Place) = (keys.getValue(on).map { it.name + Separators.KV + true } +
        Place.entries.filter { it != on }.flatMap { keys.getValue(it) }.map { it.name + Separators.KV + false })
        .joinToString(Separators.ENTRY)
    prefs.edit {
        putString(Settings.PREF_PINNED_TOOLBAR_KEYS, listing(Place.PINNED))
        putString(Settings.PREF_TOOLBAR_KEYS, listing(Place.TOOLBAR))
    }
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}
