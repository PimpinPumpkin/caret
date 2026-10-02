// SPDX-License-Identifier: GPL-3.0-only
package app.caret.debug

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pretends to hear two sentences, word by word with a moving loudness, and then silence. Used
 * only when no other recognizer is on the device (dictation prefers Alyo's and then the first
 * one it finds), which is the case on the test emulator.
 */
class ScriptedRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private var utterance = 0

    override fun onStartListening(intent: Intent?, callback: Callback) {
        main.removeCallbacksAndMessages(null)
        val words = SCRIPT.getOrNull(utterance++)?.split(' ')
        runCatching { callback.readyForSpeech(Bundle()) }
        if (words == null) {
            // nothing more to say: a real recognizer reports this after a few seconds of silence
            utterance = 0
            main.postDelayed({ runCatching { callback.error(SpeechRecognizer.ERROR_NO_MATCH) } }, 1500)
            return
        }
        for (tick in 0 until words.size * TICKS_PER_WORD) {
            main.postDelayed({
                runCatching {
                    callback.rmsChanged(-2f + 12f * abs(sin(tick / 3.0)).toFloat())
                    if (tick % TICKS_PER_WORD == 0)
                        callback.partialResults(results(words.take(tick / TICKS_PER_WORD + 1).joinToString(" ")))
                }
            }, tick * TICK_MILLIS)
        }
        main.postDelayed({ runCatching { callback.results(results(words.joinToString(" "))) } }, words.size * TICKS_PER_WORD * TICK_MILLIS)
    }

    override fun onStopListening(callback: Callback) {
        main.removeCallbacksAndMessages(null)
        utterance = 0
        runCatching { callback.error(SpeechRecognizer.ERROR_NO_MATCH) }
    }

    override fun onCancel(callback: Callback) {
        main.removeCallbacksAndMessages(null)
        utterance = 0
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private companion object {
        const val TICK_MILLIS = 80L
        const val TICKS_PER_WORD = 4
        val SCRIPT = listOf(
            "this is the first sentence that the scripted recognizer says so the line fills up and has to scroll",
            "and this is the second one",
        )
    }
}
