// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PersonalCorrectionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @BeforeTest fun start() = PersonalCorrections.clear(context)
    @AfterTest fun end() = PersonalCorrections.clear(context)

    @Test fun `a fix made twice is learned`() {
        PersonalCorrections.learn(context, "teh", "the")
        assertEquals(null, PersonalCorrections.meantBy("teh"))
        PersonalCorrections.learn(context, "Teh", "The")
        assertEquals("the", PersonalCorrections.meantBy("teh"))
        assertEquals("The", PersonalCorrections.meantBy("Teh"))
        assertEquals(1, PersonalCorrections.count(context))
    }

    @Test fun `a word taken back twice is left alone`() {
        repeat(2) { PersonalCorrections.learn(context, "yeet", "yeet") }
        assertEquals("yeet", PersonalCorrections.meantBy("yeet"))
        assertEquals("Yeet", PersonalCorrections.meantBy("Yeet"))
    }

    @Test fun `taking a learned fix back unlearns it`() {
        repeat(2) { PersonalCorrections.learn(context, "hte", "the") }
        PersonalCorrections.learn(context, "hte", "hte")
        assertEquals(null, PersonalCorrections.meantBy("hte"))
    }

    @Test fun `names keep their capital`() {
        repeat(2) { PersonalCorrections.learn(context, "anja", "Anja") }
        assertEquals("Anja", PersonalCorrections.meantBy("anja"))
    }

    @Test fun `finishing a word or writing another one is not a fix`() {
        repeat(3) { PersonalCorrections.learn(context, "tom", "tomorrow") }
        repeat(3) { PersonalCorrections.learn(context, "cat", "dog") }
        repeat(3) { PersonalCorrections.learn(context, "a", "s") }
        assertEquals(null, PersonalCorrections.meantBy("tom"))
        assertEquals(null, PersonalCorrections.meantBy("cat"))
        assertEquals(null, PersonalCorrections.meantBy("a"))
    }

    @Test fun `corrections the recorder sees are learned`() {
        CorrectionRecorder.clear(context)
        val taps = app.caret.latin.common.InputPointers(48).apply { repeat(3) { addPointer(10 * it, 20, 0, it) } }
        repeat(2) { CorrectionRecorder.onUndone(context, null, taps, "brb", "bob", "see you ") }
        assertEquals("brb", PersonalCorrections.meantBy("brb"))
        // and when the word was seen going in: learned once the next word shows it stayed
        repeat(2) { round ->
            CorrectionRecorder.onCommit(context, null, taps, "wo", "who", "who", false, round * 10, "")
            CorrectionRecorder.onUndone(context, null, taps, "wo", "who", "who")
            CorrectionRecorder.onCommit(context, null, taps, "is", "is", "is", false, round * 10 + 3, "wo ")
        }
        assertEquals("wo", PersonalCorrections.meantBy("wo"))
        CorrectionRecorder.clear(context)
        assertEquals(null, PersonalCorrections.meantBy("brb"))
    }

    @Test fun `a correction taken back only to type the word anew is not a wish to keep it`() {
        CorrectionRecorder.clear(context)
        val taps = app.caret.latin.common.InputPointers(48).apply { repeat(3) { addPointer(10 * it, 20, 0, it) } }
        repeat(2) { round ->
            val at = round * 20 + 4
            CorrectionRecorder.onCommit(context, null, taps, "yuo", "yup", "yup", false, at, "are ")
            CorrectionRecorder.onUndone(context, null, taps, "yuo", "yup", "are yup")
            CorrectionRecorder.onCommit(context, null, taps, "you", "you", "you", false, at, "are ")
            CorrectionRecorder.onCommit(context, null, taps, "ok", "ok", "ok", false, at + 4, "are you ")
        }
        assertEquals("you", PersonalCorrections.meantBy("yuo"))
        CorrectionRecorder.clear(context)
    }
}
