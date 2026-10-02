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
    private val host = object : InlineDictation.Host {
        override fun insertText(text: String) { typed.append(text) }
        override fun showStatus(text: String?) { status = text }
        override fun codePointBeforeCursor() = typed.lastOrNull()?.code ?: -1
        override fun locale(): Locale = Locale.US
    }
    private val dictation = InlineDictation(context, host)

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

    @Test fun `spoken words show while speaking and are typed when the utterance ends`() {
        assertTrue(dictation.toggle())
        val speech = recognizer()
        speech.triggerOnReadyForSpeech(Bundle())
        speech.triggerOnPartialResults(results("hello wor"))
        assertEquals("hello wor", status)
        assertEquals("", typed.toString())
        speech.triggerOnResults(results("hello world"))
        assertEquals("hello world", typed.toString())
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

    @Test fun `leaving the field drops what was being said`() {
        dictation.toggle()
        recognizer().triggerOnPartialResults(results("half a sent"))
        dictation.cancel()
        assertFalse(dictation.isActive)
        assertNull(status)
        assertEquals("", typed.toString())
    }
}
