// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.caret.latin.utils.Log
import kotlin.math.PI
import kotlin.math.sin

/** Two short notes, rising when dictation starts to listen and falling when it stops. Made in code, no sound file. */
object DictationChime {
    private const val SAMPLE_RATE = 22050
    private const val NOTE_MILLIS = 70
    private const val LOW = 660.0
    private const val HIGH = 990.0

    fun start() = play(LOW, HIGH)
    fun stop() = play(HIGH, LOW)

    private fun play(first: Double, second: Double) {
        try {
            val samples = notes(first, second)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        } catch (e: Exception) {
            Log.w("DictationChime", "could not play the chime", e)
        }
    }

    /** Two sine notes one after the other, each faded in and out so there is no click, kept quiet. */
    fun notes(first: Double, second: Double): ShortArray {
        val perNote = SAMPLE_RATE * NOTE_MILLIS / 1000
        val out = ShortArray(perNote * 2)
        for (i in out.indices) {
            val frequency = if (i < perNote) first else second
            val inNote = i % perNote
            val envelope = sin(PI * inNote / perNote)
            out[i] = (sin(2 * PI * frequency * i / SAMPLE_RATE) * envelope * 0.25 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
