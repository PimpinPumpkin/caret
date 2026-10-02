// SPDX-License-Identifier: GPL-3.0-only

package app.caret.compat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build

class ClipboardManagerCompat {

    companion object {
        @JvmStatic
        fun clearPrimaryClip(cm: ClipboardManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    cm.clearPrimaryClip()
                } catch (e: Exception) {
                    // workaround for system-caused crash in https://github.com/HeliBorg/HeliBoard/issues/203
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }

        @JvmStatic
        fun getClipTimestamp(cd: ClipData): Long {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timestamp = cd.getDescription().getTimestamp()
                if (timestamp > 0) // timestamp is 0 if not set
                    return timestamp
            }
            return System.currentTimeMillis()
        }

        @JvmStatic
        fun getClipSensitivity(cd: ClipDescription?): Boolean? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return cd != null && cd.getExtras() != null && cd.getExtras().getBoolean("android.content.extra.IS_SENSITIVE")
            }
            return null // can't determine
        }
    }
}
