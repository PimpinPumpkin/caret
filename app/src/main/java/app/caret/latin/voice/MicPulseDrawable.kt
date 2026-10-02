// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin.voice

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.min
import kotlin.math.sin

/**
 * The circle behind the microphone key while dictation listens. It grows and shrinks with the
 * loudness of the voice. When the speech service does not report loudness it breathes slowly
 * instead, so it still shows that the microphone is open.
 */
class MicPulseDrawable(color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var target = 0f
    private var shown = 0f
    private var lastLevelTime = 0L

    /** Loudness right now, 0 (silence) to 1. */
    fun setLevel(level: Float) {
        target = level.coerceIn(0f, 1f)
        lastLevelTime = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLevelTime > LEVEL_TIMEOUT_MILLIS)
            target = 0.3f + 0.25f * sin(now / 350.0).toFloat()
        // ease towards the target so the circle moves, not jumps
        shown += (target - shown) * 0.25f
        val half = min(bounds.width(), bounds.height()) / 2f
        val radius = half * (MIN_RADIUS + (1f - MIN_RADIUS) * shown)
        paint.alpha = (70 + 110 * shown).toInt()
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint)
        // keeps drawing while it is attached to a visible view; it is removed when listening ends
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val MIN_RADIUS = 0.55f
        private const val LEVEL_TIMEOUT_MILLIS = 600L
    }
}
