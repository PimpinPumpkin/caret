// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
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
        /** The microphone is open (true) or has been closed again (false). */
        fun onListening(listening: Boolean)
        /** How loud the voice is right now, 0 to 1. Only called when the speech service reports it. */
        fun onLevel(level: Float)
        /** Whether to play a short sound when listening starts and stops. */
        fun soundsEnabled(): Boolean
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** True from the tap on the microphone key until listening has ended. */
    var isActive = false
        private set
    /** Set by a tap while listening: finish this utterance, then stop. */
    private var stopAfterResult = false
    /** True once the service has said the microphone is open, until dictation ends. */
    private var listening = false

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

    private var warmConnection: ServiceConnection? = null

    /**
     * Holds on to the speech service while the keyboard is up, without listening. That starts its
     * app, which can then load its model, so the first tap on the microphone does not wait for it.
     */
    fun warmUp() {
        if (warmConnection != null || !hasPermission(context)) return
        val service = findService(context) ?: return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        try {
            val intent = Intent(RecognitionService.SERVICE_INTERFACE).setComponent(service)
            if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) warmConnection = connection
            else context.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "cannot warm up $service", e)
        }
    }

    /** Lets go of the speech service again. It decides for itself how long to keep its model. */
    fun coolDown() {
        val connection = warmConnection ?: return
        warmConnection = null
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "cannot let go of the speech service", e)
        }
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
        if (listening) {
            listening = false
            host.onListening(false)
            if (host.soundsEnabled()) DictationChime.stop()
        }
        isActive = false
        recognizer?.destroy()
        recognizer = null
        host.showStatus(null)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isActive) return
            host.showStatus(context.getString(app.caret.latin.R.string.dictation_listening))
            // the service is asked again for every utterance; only the first time is a start
            if (!listening) {
                listening = true
                host.onListening(true)
                if (host.soundsEnabled()) DictationChime.start()
            }
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
        override fun onRmsChanged(rmsdB: Float) {
            // recognizers report roughly -2 (silence) to 10 (loud)
            if (isActive) host.onLevel(((rmsdB - RMS_SILENCE) / (RMS_LOUD - RMS_SILENCE)).coerceIn(0f, 1f))
        }
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
        private const val RMS_SILENCE = -2f
        private const val RMS_LOUD = 10f
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
