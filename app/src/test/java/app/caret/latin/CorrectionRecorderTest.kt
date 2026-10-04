// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.caret.latin.common.InputPointers
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class CorrectionRecorderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @BeforeTest fun start() = CorrectionRecorder.clear(context)
    @AfterTest fun end() = CorrectionRecorder.clear(context)

    private fun taps(n: Int) = InputPointers(48).apply { repeat(n) { addPointer(10 * it, 20, 0, it) } }

    private fun corrections() = CorrectionRecorder.file(context).takeIf { it.exists() }?.readLines()
        ?.map { JSONObject(it) }?.filter { it.has("kind") } ?: emptyList()

    private fun commit(typed: String, out: String, start: Int, wouldHaveBeen: String? = out, picked: Boolean = false) =
        CorrectionRecorder.onCommit(context, null, taps(typed.length), typed, out, wouldHaveBeen, picked, start, "see you at the")

    @Test fun `a word typed again in the same place is a correction of the first`() {
        commit("teh", "tech", 10)
        commit("the", "the", 10)
        val (c) = corrections()
        assertEquals("retyped", c.getString("kind"))
        assertEquals("teh", c.getString("typed"))
        assertEquals("tech", c.getString("cameOut"))
        assertEquals("the", c.getString("meant"))
        assertEquals(3, c.getJSONArray("x").length())
        assertEquals("the", c.getJSONArray("before").getString(3))
    }

    @Test fun `a different word where an old one stood is new text, not a fix`() {
        commit("at", "at", 7)
        commit("from", "from", 7) // the field was cleared and something else typed
        assertEquals(0, corrections().size)
        assertEquals(true, CorrectionRecorder.looksLike("hime", "time"))
        assertEquals(false, CorrectionRecorder.looksLike("at", "from"))
    }

    @Test fun `words that are left alone are not corrections`() {
        commit("hello", "hello", 0)
        commit("there", "there", 6)
        commit("hello", "hello", 0) // the same word again in the same place is not a fix either
        assertEquals(0, corrections().size)
    }

    @Test fun `picking another suggestion than the one space would put in is a correction`() {
        commit("form", "from", 4, wouldHaveBeen = "form", picked = true)
        commit("ok", "ok", 9, wouldHaveBeen = "ok", picked = true) // picking the same word is not
        val (c) = corrections()
        assertEquals("picked", c.getString("kind"))
        assertEquals("form", c.getString("cameOut"))
        assertEquals("from", c.getString("meant"))
    }

    @Test fun `an autocorrection taken back means the typed word was meant`() {
        CorrectionRecorder.onUndone(context, null, taps(4), "Caet", "Cart", "my Cart")
        val (c) = corrections()
        assertEquals("undone", c.getString("kind"))
        assertEquals(listOf("my"), List(c.getJSONArray("before").length()) { c.getJSONArray("before").getString(it) })
        assertEquals("Caet", c.getString("meant"))
        assertEquals(1, CorrectionRecorder.count(context))
    }
}
