// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.utils

import android.content.Context
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import java.text.SimpleDateFormat
import java.util.Calendar

fun getTimestamp(context: Context): String = getTimestampFormatter(context).format(Calendar.getInstance().time)

fun getTimestampFormatter(context: Context): SimpleDateFormat {
    val format = context.prefs().getString(Settings.PREF_TIMESTAMP_FORMAT, Defaults.PREF_TIMESTAMP_FORMAT)
    return runCatching<SimpleDateFormat> { SimpleDateFormat(format, Settings.values.locale) }.getOrNull()
        ?: SimpleDateFormat(Defaults.PREF_TIMESTAMP_FORMAT, Settings.values.locale)
}

fun checkTimestampFormat(format: String) = runCatching { SimpleDateFormat(format, Settings.values.locale) }.isSuccess
