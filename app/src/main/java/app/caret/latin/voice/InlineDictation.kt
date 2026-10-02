// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import app.caret.latin.utils.Log
import java.util.Locale

/**
 * Dictation without leaving the keyboard: the microphone key starts a speech recognition service
 * on the phone (Alyo's when it offers one), the words show in the suggestion strip while they are
 * spoken, and each finished utterance is typed into the field. Listening goes on, one utterance
 * after the other, until the key is tapped again, nothing is heard, or the field goes away.
 */
class InlineDictation(private val context: Context, private val host: Host) {
    /** What dictation needs from the keyboard. */
    interface Host {
        /** Types [text] at the cursor. */
        fun insertText(text: String)
        /** Shows [text] in place of the suggestions; null puts the suggestions back. */
        fun showStatus(text: String?)
        /** The character before the cursor, or a value below zero when there is none or it is unknown. */
        fun codePointBeforeCursor(): Int
        /** Language to listen for. */
        fun locale(): Locale
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** True from the tap on the microphone key until listening has ended. */
    var isActive = false
        private set
    /** Set by a tap while listening: finish this utterance, then stop. */
    private var stopAfterResult = false

    /**
     * Starts listening, or stops when it is listening already. False when dictation cannot run
     * here (no permission, no recognition service), so the caller can do what the key did before.
     */
    fun toggle(): Boolean {
        if (isActive) {
            stop()
            return true
        }
        if (!hasPermission(context)) return false
        val service = findService(context) ?: return false
        return start(service)
    }

    private fun start(service: ComponentName): Boolean {
        val speech = try {
            SpeechRecognizer.createSpeechRecognizer(context, service)
        } catch (e: Exception) {
            Log.w(TAG, "cannot create a speech recognizer for $service", e)
            return false
        }
        speech.setRecognitionListener(listener)
        recognizer = speech
        isActive = true
        stopAfterResult = false
        host.showStatus(context.getString(app.caret.latin.R.string.dictation_starting))
        listen()
        return true
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, host.locale().toLanguageTag())
        recognizer?.startListening(intent)
    }

    /** Finishes the current utterance and stops after it. */
    fun stop() {
        if (!isActive) return
        stopAfterResult = true
        recognizer?.stopListening()
    }

    /** Stops at once and drops what was being said. For when the field or the keyboard goes away. */
    fun cancel() {
        if (!isActive && recognizer == null) return
        recognizer?.cancel()
        end()
    }

    private fun end() {
        isActive = false
        recognizer?.destroy()
        recognizer = null
        host.showStatus(null)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (isActive) host.showStatus(context.getString(app.caret.latin.R.string.dictation_listening))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            // the end of a long utterance is what the speaker wants to check
            if (isActive && text.isNotEmpty()) host.showStatus(text.takeLast(MAX_STATUS_LENGTH))
        }

        override fun onResults(results: Bundle?) {
            if (!isActive) return
            val text = firstResult(results).trim()
            if (text.isNotEmpty()) host.insertText(withSpaceBefore(text))
            if (stopAfterResult) {
                end()
            } else {
                host.showStatus(context.getString(app.caret.latin.R.string.dictation_listening))
                listen()
            }
        }

        override fun onError(error: Int) {
            if (!isActive) return
            // nothing said, or the tap that ended it came before any speech: just stop
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                end()
                return
            }
            Log.w(TAG, "speech recognition ended with error $error")
            end()
            // say why nothing happened, for a moment
            val message = if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) app.caret.latin.R.string.dictation_error_permission
                else app.caret.latin.R.string.dictation_error
            host.showStatus(context.getString(message))
            handler.postDelayed({ if (!isActive) host.showStatus(null) }, ERROR_MESSAGE_MILLIS)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    /** A space in front unless the text starts a line or follows a space or an opening bracket or quote. */
    private fun withSpaceBefore(text: String): String {
        val before = host.codePointBeforeCursor()
        if (before < 0 || Character.isWhitespace(before) || before in NO_SPACE_AFTER) return text
        return " $text"
    }

    companion object {
        private const val TAG = "InlineDictation"
        private const val MAX_STATUS_LENGTH = 60
        private const val ERROR_MESSAGE_MILLIS = 3000L
        private const val ALYO_PACKAGE = "app.alyo"
        private val NO_SPACE_AFTER = setOf('('.code, '['.code, '{'.code, '"'.code, '\''.code, '/'.code, '@'.code)

        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** The speech recognition service to use: Alyo's when it is switched on, else the first one the phone has. */
        fun findService(context: Context): ComponentName? {
            val services = try {
                context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            } catch (e: Exception) {
                Log.w(TAG, "cannot look for speech recognition services", e)
                return null
            }
            val info = services.firstOrNull { it.serviceInfo.packageName == ALYO_PACKAGE } ?: services.firstOrNull() ?: return null
            return ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
        }
    }
}
