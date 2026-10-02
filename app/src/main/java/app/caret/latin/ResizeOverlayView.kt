// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.content.edit
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.KeyboardTypeface
import app.caret.latin.common.ColorType
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.settings.createPrefKeyForBooleanSettings
import app.caret.latin.settings.findIndexOfDefaultSetting
import app.caret.latin.utils.FoldableUtils
import app.caret.latin.utils.prefs
import kotlin.math.abs

/**
 * Covers the keyboard while it is being resized. Drag the top handle for height, a side handle for
 * side padding, the bottom handle for bottom padding. Sizes are stored in the same preferences as
 * the sliders in Appearance settings, per orientation.
 */
@SuppressLint("ViewConstructor")
class ResizeOverlayView(context: Context, private val onDone: () -> Unit) : View(context) {
    private enum class Target { HEIGHT, SIDE_LEFT, SIDE_RIGHT, BOTTOM, RESET, DONE }

    private val density = resources.displayMetrics.density
    private val colors = Settings.getValues().mColors
    private val scrim = Paint().apply { color = colors.get(ColorType.MAIN_BACKGROUND); alpha = 170 }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.get(ColorType.ACTION_KEY_BACKGROUND) }
    private val button = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.get(ColorType.KEY_TEXT)
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
        typeface = KeyboardTypeface.resolve(null)
    }
    private val rect = RectF()
    private val resetRect = RectF()
    private val doneRect = RectF()
    private var target: Target? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // never make the keyboard bigger: only take the size the wrapper settled on for the keyboard
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else 0
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, scrim)
        val long = 56 * density
        val thick = 6 * density
        val inset = 10 * density
        fun pill(cx: Float, cy: Float, horizontal: Boolean) {
            if (horizontal) rect.set(cx - long / 2, cy - thick / 2, cx + long / 2, cy + thick / 2)
            else rect.set(cx - thick / 2, cy - long / 2, cx + thick / 2, cy + long / 2)
            canvas.drawRoundRect(rect, thick, thick, handle)
        }
        pill(w / 2, inset, true)
        pill(w / 2, h - inset, true)
        pill(inset, h / 2, false)
        pill(w - inset, h / 2, false)

        val buttonWidth = 104 * density
        val buttonHeight = 44 * density
        val gap = 12 * density
        resetRect.set(w / 2 - gap / 2 - buttonWidth, h / 2 - buttonHeight / 2, w / 2 - gap / 2, h / 2 + buttonHeight / 2)
        doneRect.set(w / 2 + gap / 2, h / 2 - buttonHeight / 2, w / 2 + gap / 2 + buttonWidth, h / 2 + buttonHeight / 2)
        val baseline = h / 2 - (text.descent() + text.ascent()) / 2
        canvas.drawRoundRect(resetRect, buttonHeight, buttonHeight, button)
        canvas.drawRoundRect(doneRect, buttonHeight, buttonHeight, button)
        canvas.drawText(context.getString(R.string.resize_reset), resetRect.centerX(), baseline, text)
        canvas.drawText(context.getString(R.string.resize_done), doneRect.centerX(), baseline, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target = targetAt(event.x, event.y)
                lastX = event.rawX
                lastY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastX) / density
                val dy = (event.rawY - lastY) / density
                when (target) {
                    Target.HEIGHT -> if (abs(dy) >= 4) {
                        // the keyboard grows upward, so dragging up makes it taller
                        change(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, 2, -dy / (height / density), 0.3f, 1.5f)
                        lastY = event.rawY
                    }
                    Target.BOTTOM -> if (abs(dy) >= 4) {
                        change(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, 2, -dy / 20f, 0f, 5f)
                        lastY = event.rawY
                    }
                    Target.SIDE_LEFT, Target.SIDE_RIGHT -> if (abs(dx) >= 4) {
                        val inward = if (target == Target.SIDE_LEFT) dx else -dx
                        change(Settings.PREF_SIDE_PADDING_SCALE_PREFIX, 3, inward / 40f, 0f, 3f)
                        lastX = event.rawX
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                if (target == Target.RESET && resetRect.contains(event.x, event.y)) reset()
                if (target == Target.DONE && doneRect.contains(event.x, event.y)) onDone()
                target = null
            }
            MotionEvent.ACTION_CANCEL -> target = null
        }
        return true
    }

    private fun targetAt(x: Float, y: Float): Target? = when {
        resetRect.contains(x, y) -> Target.RESET
        doneRect.contains(x, y) -> Target.DONE
        y < height * 0.25f -> Target.HEIGHT
        y > height * 0.75f -> Target.BOTTOM
        x < width * 0.2f -> Target.SIDE_LEFT
        x > width * 0.8f -> Target.SIDE_RIGHT
        else -> null
    }

    private fun prefKey(prefix: String, dimensions: Int): Pair<String, Int> {
        val values = Settings.getValues()
        val landscape = values.displayOrientation == Configuration.ORIENTATION_LANDSCAPE
        val index = if (dimensions == 3) findIndexOfDefaultSetting(landscape, values.isSplitKeyboardEnabled, FoldableUtils.isFolded)
            else findIndexOfDefaultSetting(landscape, FoldableUtils.isFolded)
        return createPrefKeyForBooleanSettings(prefix, index, dimensions) to index
    }

    private fun defaultFor(prefix: String, index: Int): Float = when (prefix) {
        Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX -> Defaults.PREF_KEYBOARD_HEIGHT_SCALE[index]
        Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX -> Defaults.PREF_BOTTOM_PADDING_SCALE[index]
        else -> Defaults.PREF_SIDE_PADDING_SCALE[index]
    }

    private fun change(prefix: String, dimensions: Int, delta: Float, min: Float, max: Float) {
        val (key, index) = prefKey(prefix, dimensions)
        val prefs = context.prefs()
        val old = prefs.getFloat(key, defaultFor(prefix, index))
        val new = (old + delta).coerceIn(min, max)
        if (new == old) return
        prefs.edit { putFloat(key, new) }
        reload()
    }

    private fun reset() {
        context.prefs().edit {
            remove(prefKey(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, 2).first)
            remove(prefKey(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, 2).first)
            remove(prefKey(Settings.PREF_SIDE_PADDING_SCALE_PREFIX, 3).first)
        }
        reload()
    }

    private fun reload() {
        // settings reload themselves when a preference changes, the keyboard needs to be told
        post { KeyboardSwitcher.getInstance().reloadKeyboard() }
    }
}
