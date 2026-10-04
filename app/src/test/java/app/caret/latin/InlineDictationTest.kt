// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import app.caret.latin.voice.InlineDictation
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class InlineDictationTest {
    private val context: Context = ApplicationProvider.getApplicationContext<App>()
    private val typed = StringBuilder()
    private var status: String? = null
    private var partial = ""
    private var listening = false
    private var level = -1f
    private val host = object : InlineDictation.Host {
        override fun insertText(text: String) { typed.append(text); partial = "" }
        override fun showPartial(text: String) { partial = text }
        override fun showStatus(text: String?) { status = text }
        override fun codePointBeforeCursor() = typed.lastOrNull()?.code ?: -1
        override fun locale(): Locale = Locale.US
        override fun onListening(listening: Boolean) { this@InlineDictationTest.listening = listening }
        override fun onLevel(level: Float) { this@InlineDictationTest.level = level }
        override fun soundsEnabled() = false
    }
    private var now = 1000L
    private var pausePeriods = false
    private val dictation = InlineDictation(context, object : InlineDictation.Host by host {
        override fun pausePeriods() = pausePeriods
    }) { now }

    private fun results(text: String, spokenEnd: Boolean) = results(text).apply { putBoolean("app.alyo.SPOKEN_END", spokenEnd) }

    @Test fun `a pause does not end the sentence when the speaker goes on`() {
        dictation.toggle()
        recognizer().triggerOnResults(results("I went to the store."))
        assertEquals("I went to the store", typed.toString())
        now += 800
        recognizer().triggerOnPartialResults(results("and bought"))
        assertEquals(" and bought", partial)
        recognizer().triggerOnResults(results("And bought some milk."))
        assertEquals("I went to the store and bought some milk", typed.toString())
    }

    @Test fun `going on after a long pause starts a new sentence`() {
        dictation.toggle()
        recognizer().triggerOnResults(results("I went to the store."))
        now += InlineDictation.LONG_PAUSE_MILLIS + 500
        recognizer().triggerOnResults(results("It was closed."))
        assertEquals("I went to the store. It was closed", typed.toString())
    }

    @Test fun `a period that was said stays, and so do other marks and names`() {
        dictation.toggle()
        recognizer().triggerOnResults(results("That is all.", spokenEnd = true))
        now += 500
        recognizer().triggerOnResults(results("Are you there?"))
        now += 500
        recognizer().triggerOnResults(results("I think so."))
        now += 500
        recognizer().triggerOnResults(results("I'm sure."))
        assertEquals("That is all. Are you there? I think so I'm sure", typed.toString())
    }

    @Test fun `with the setting on every pause keeps its period`() {
        pausePeriods = true
        dictation.toggle()
        recognizer().triggerOnResults(results("One."))
        now += 500
        recognizer().triggerOnResults(results("Two."))
        assertEquals("One. Two.", typed.toString())
    }

    private fun addService(packageName: String) {
        val info = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { this.packageName = packageName; name = "$packageName.Recognizer" }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), info)
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun recognizer(): ShadowSpeechRecognizer {
        ShadowLooper.idleMainLooper()
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    @BeforeTest fun setUp() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        addService("some.recognizer")
    }

    @Test fun `without the microphone permission the key does what it did before`() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(dictation.toggle())
        assertFalse(dictation.isActive)
    }

    @Test fun `alyo is preferred over another recognizer`() {
        assertEquals("some.recognizer", InlineDictation.findService(context)?.packageName)
        addService("app.alyo")
        assertEquals(ComponentName("app.alyo", "app.alyo.Recognizer"), InlineDictation.findService(context))
    }

    @Test fun `spoken words show at the cursor while speaking and are typed when the utterance ends`() {
        assertTrue(dictation.toggle())
        val speech = recognizer()
        speech.triggerOnReadyForSpeech(Bundle())
        speech.triggerOnPartialResults(results("hello wor"))
        assertEquals("hello wor", partial)
        assertEquals("", typed.toString())
        speech.triggerOnResults(results("hello world"))
        assertEquals("hello world", typed.toString())
        assertEquals("", partial)
        // the next utterance is set off from the last by a space, from its first word on
        recognizer().triggerOnPartialResults(results("and"))
        assertEquals(" and", partial)
        recognizer().triggerOnResults(results("and more"))
        assertEquals("hello world and more", typed.toString())
        assertTrue(dictation.isActive, "it keeps listening for the next utterance")
    }

    @Test fun `the next utterance gets a space in front`() {
        dictation.toggle()
        recognizer().triggerOnResults(results("hello"))
        recognizer().triggerOnResults(results("again"))
        assertEquals("hello again", typed.toString())
    }

    @Test fun `a second tap finishes the utterance and stops`() {
        dictation.toggle()
        recognizer()
        dictation.toggle()
        recognizer().triggerOnResults(results("done"))
        assertEquals("done", typed.toString())
        assertFalse(dictation.isActive)
        assertNull(status)
    }

    @Test fun `silence ends dictation without typing`() {
        dictation.toggle()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        assertFalse(dictation.isActive)
        assertEquals("", typed.toString())
        assertNull(status)
    }

    @Test fun `a service that cannot listen says so for a moment`() {
        dictation.toggle()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertFalse(dictation.isActive)
        assertEquals(context.getString(app.caret.latin.R.string.dictation_error_permission), status)
        ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNull(status)
    }

    @Test fun `the microphone circle is on while it listens and follows the voice`() {
        dictation.toggle()
        assertFalse(listening, "not before the service has opened the microphone")
        val speech = recognizer()
        speech.triggerOnReadyForSpeech(Bundle())
        assertTrue(listening)
        speech.triggerOnRmsChanged(4f)
        assertEquals(0.5f, level)
        speech.triggerOnRmsChanged(40f)
        assertEquals(1f, level)
        speech.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        assertFalse(listening)
    }

    @Test fun `leaving the field drops what was being said`() {
        dictation.toggle()
        recognizer().triggerOnPartialResults(results("half a sent"))
        assertEquals("half a sent", partial)
        dictation.cancel()
        assertFalse(dictation.isActive)
        assertNull(status)
        assertEquals("", typed.toString())
    }
}

class DictationChimeTest {
    @Test fun `the chime is short, quiet and ends in silence`() {
        val samples = app.caret.latin.voice.DictationChime.notes(660.0, 990.0)
        assertTrue(samples.size < 22050 / 4, "under a quarter of a second")
        assertTrue(samples.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE / 3)
        assertEquals(0, samples.first().toInt())
        assertTrue(kotlin.math.abs(samples.last().toInt()) < 400)
    }
}
