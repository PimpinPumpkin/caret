// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings

import android.content.Context
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.latin.settings.Settings
import app.caret.latin.utils.SubtypeSettings

// file is meant for making compose previews work

fun initPreview(context: Context) {
    Settings.init(context)
    SubtypeSettings.init(context)
    Settings.getInstance().loadSettings(context)
    SettingsActivity.settingsContainer = SettingsContainer(context)
    KeyboardIconsSet.instance.loadIcons(context)
}
