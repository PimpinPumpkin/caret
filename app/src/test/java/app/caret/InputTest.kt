package app.caret

import android.view.MotionEvent
import app.caret.keyboard.KeyboardElement
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.LatinIME
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// todo: expand with swipe & touch stuff
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class InputTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        // create keyboardView
        keyboardSwitcher.onCreateInputView(latinIME, true)
        // create and set keyboard
        keyboardSwitcher.reloadMainKeyboard()
    }

    @Test fun pressShift() {
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.getKeyboard()?.id?.element)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.getKeyboard()?.id?.element)
    }

    @Test fun keyInput() {
        touchKey('a'.code, MotionEvent.ACTION_DOWN)
        touchKey('a'.code, MotionEvent.ACTION_UP)
        assertEquals("a", ShadowInputMethodService.text)
    }

    @Test fun holdShift() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.getKeyboard()?.id?.element)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks() // this doesn't actually wait, just results in handling the delayed message immediately
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.getKeyboard()?.id?.element)
    }

    @Test fun slidingInput() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        touchKey('F'.code, MotionEvent.ACTION_MOVE)
        touchKey('F'.code, MotionEvent.ACTION_UP)
        assertEquals("F", ShadowInputMethodService.text)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.getKeyboard()?.id?.element)
    }

    @Test fun endCapsLockWithShift() {
        // keyboardSwitcher.setAlphabetShiftLockedKeyboard() isn't enough because it doesn't set the fully correct state
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP)

        // need to set event time to prevent mTouchNoiseThresholdTime thing triggering (todo: should be done automatically in the test)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 50L)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP, 50L)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.getKeyboard()?.id?.element)
    }

    @Test fun slidingInputFromCapsLock() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP)

        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 50L)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.getKeyboard()?.id?.element)
        touchKey('f'.code, MotionEvent.ACTION_MOVE)
        touchKey('f'.code, MotionEvent.ACTION_UP)
        assertEquals("f", ShadowInputMethodService.text)
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.getKeyboard()?.id?.element)
    }

    // ------- Caret: taps, layout switching, key repeat, two fingers -------

    private var clock = 1000L

    /** Down and up in the middle of a key, with time moving on so nothing counts as touch noise. */
    private fun tap(code: Int) {
        clock += 200
        touchCenter(code, MotionEvent.ACTION_DOWN, clock)
        clock += 40
        touchCenter(code, MotionEvent.ACTION_UP, clock)
    }

    private fun touchCenter(code: Int, action: Int, eventTime: Long) {
        val key = keyboardSwitcher.getKeyboard()!!.getKey(code)!!
        val me = MotionEvent.obtain(0L, eventTime, action, key.getX() + key.getWidth() / 2f, key.getY() + key.getHeight() / 2f, 0)
        keyboardSwitcher.getMainKeyboardView()!!.onTouchEvent(me)
    }

    @Test fun `tapping letters types them`() {
        "hi".forEach { tap(it.code) }
        assertEquals("hi", ShadowInputMethodService.text)
    }

    @Test fun `tapping space and more letters types two words`() {
        "hi".forEach { tap(it.code) }
        tap(' '.code)
        "yo".forEach { tap(it.code) }
        assertEquals("hi yo", ShadowInputMethodService.text)
    }

    @Test fun `tapping backspace removes the last letter`() {
        "hey".forEach { tap(it.code) }
        tap(KeyCode.DELETE)
        assertEquals("he", ShadowInputMethodService.text)
    }

    @Test fun `the symbols key switches to symbols and back`() {
        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(KeyboardElement.SYMBOLS, keyboardSwitcher.getKeyboard()?.id?.element)
        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.getKeyboard()?.id?.element)
    }

    @Test fun `a digit typed on the symbols page arrives`() {
        tap(KeyCode.SYMBOL_ALPHA)
        tap('1'.code)
        assertEquals("1", ShadowInputMethodService.text)
    }

    @Test fun `shift then a letter types one capital and returns to lowercase`() {
        tap(KeyCode.SHIFT)
        tap('A'.code) // the shifted keyboard has the capital on that key
        tap('b'.code) // and this key only exists again once the keyboard is back in lowercase
        assertEquals("Ab", ShadowInputMethodService.text)
    }

    @Test fun `sliding from one letter to another types the one the finger left on`() {
        clock += 200
        touchCenter('a'.code, MotionEvent.ACTION_DOWN, clock)
        clock += 30
        touchCenter('s'.code, MotionEvent.ACTION_MOVE, clock)
        clock += 30
        touchCenter('s'.code, MotionEvent.ACTION_UP, clock)
        assertEquals("s", ShadowInputMethodService.text)
    }

    @Test fun `holding backspace keeps deleting`() {
        "hello there".forEach { tap(it.code) }
        clock += 200
        touchCenter(KeyCode.DELETE, MotionEvent.ACTION_DOWN, clock)
        ShadowLooper.idleMainLooper(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
        clock += 1200
        touchCenter(KeyCode.DELETE, MotionEvent.ACTION_UP, clock)
        val left = ShadowInputMethodService.text
        assert("hello there".startsWith(left) && left.length <= 8) { "expected several characters gone, left '$left'" }
    }

    private fun touchKey(code: Int, action: Int, eventTime: Long = 0L) {
        val kb = keyboardSwitcher.getKeyboard()!!
        val key = kb.getKey(code)!!
        val x = key.getX() + key.getHeight() / 2
        val y = key.getY() + key.getWidth() / 2
        val me = MotionEvent.obtain( // todo: there are many more parameters, also related to pointers
            0L,
            eventTime,
            action,
            x.toFloat(),
            y.toFloat(),
            0
        )
        keyboardSwitcher.getMainKeyboardView()!!.onTouchEvent(me)
    }

    @BeforeTest
    fun reset() {
        keyboardSwitcher.reloadMainKeyboard()
        ShadowInputMethodService.reset()
    }
}
