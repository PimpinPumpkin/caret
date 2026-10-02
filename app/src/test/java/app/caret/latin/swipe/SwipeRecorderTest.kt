// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.swipe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.futo.ml.inference.SwipeDecoder
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SwipeRecorderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val layout = SwipeLayout("qw", floatArrayOf(0.25f, 0.75f), floatArrayOf(0.5f, 0.5f), 1080f, 160f, 480f)

    @BeforeTest fun start() {
        SwipeRecorder.clear(context)
        SwipeRecorder.practice = "we are going".split(' ')
    }

    @AfterTest fun end() {
        SwipeRecorder.practice = null
        SwipeRecorder.clear(context)
    }

    private fun swipe(before: List<String>, answer: String) {
        // the engine is asked several times during a swipe, each time with a longer path
        for (points in 2..3)
            SwipeRecorder.onRecognized(context, layout, Locale.US, intArrayOf(10, 20, 30, 0), intArrayOf(200, 210, 220, 0),
                intArrayOf(0, 16, 32, 0), points, before, listOf(SwipeDecoder.Result(answer, -1f, -0.5f, -0.5f)))
        SwipeRecorder.onCommitted(answer)
    }

    private fun lines() = SwipeRecorder.file(context).readLines().map { JSONObject(it) }

    @Test fun `a swipe is one line with its path, the keys before it and the word that was meant`() {
        swipe(listOf("we"), "ate")
        val (keys, recorded) = lines()
        assertEquals("qw", keys.getJSONObject("layout").getString("letters"))
        assertEquals(3, recorded.getJSONArray("x").length())
        assertEquals("ate", recorded.getString("typed"))
        assertEquals("are", recorded.getString("target"))
        assertEquals("ate", recorded.getJSONArray("results").getJSONObject(0).getString("word"))
        assertEquals(1, SwipeRecorder.count(context))
    }

    @Test fun `the keys are written once, and a deleted word points at its swipe`() {
        swipe(emptyList(), "we")
        swipe(listOf("we"), "ate")
        SwipeRecorder.onDeleted(context)
        val all = lines()
        assertEquals(1, all.count { it.has("layout") })
        assertEquals(all.last { it.has("swipe") }.getLong("swipe"), all.last().getLong("deleted"))
    }

    @Test fun `nothing is kept when recording is off`() {
        SwipeRecorder.practice = null
        swipe(emptyList(), "we")
        assertFalse(SwipeRecorder.file(context).exists())
        assertTrue(SwipeRecorder.count(context) == 0)
    }
}
