// SPDX-License-Identifier: GPL-3.0-only
package app.caret.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SettingsFileTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val from = context.getSharedPreferences("from", Context.MODE_PRIVATE)
    private val to = context.getSharedPreferences("to", Context.MODE_PRIVATE)

    @Test fun `every kind of setting comes back with its type, and the keyboard's own state stays out`() {
        from.edit {
            putBoolean("auto_cap", false)
            putInt("key_longpress_timeout", 300)
            putLong("big", 5_000_000_000L)
            putFloat("auto_correct_confidence", 0.65f)
            putFloat("keyboard_height_scale", 1f)
            putString("toolbar_keys", "SETTINGS:true|UNDO:false")
            putStringSet("some_set", setOf("b", "a"))
            putInt("version_code", 5015)
            putString("recent_emojis", "a lot of emoji")
        }
        val text = SettingsFile.export(from, "0.1.15")
        assertFalse("version_code" in text)
        assertFalse("recent_emojis" in text)
        assertTrue(text.length < 1000, "small: ${text.length} bytes")
        assertTrue("0.65" in text)

        SettingsFile.apply(to, SettingsFile.read(text))
        assertEquals(false, to.getBoolean("auto_cap", true))
        assertEquals(300, to.getInt("key_longpress_timeout", 0))
        assertEquals(5_000_000_000L, to.getLong("big", 0))
        assertEquals(0.65f, to.getFloat("auto_correct_confidence", 0f))
        assertEquals(1f, to.getFloat("keyboard_height_scale", 0f))
        assertEquals("SETTINGS:true|UNDO:false", to.getString("toolbar_keys", null))
        assertEquals(setOf("a", "b"), to.getStringSet("some_set", null))
        assertFalse(to.contains("version_code"))
    }

    @Test fun `a file that is not a settings file is refused`() {
        assertFailsWith<Exception> { SettingsFile.read("{\"hello\": 1}") }
        assertFailsWith<Exception> { SettingsFile.read("not json") }
    }
}
