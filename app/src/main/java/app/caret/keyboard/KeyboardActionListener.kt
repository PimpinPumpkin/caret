/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.view.KeyEvent

import androidx.core.view.inputmethod.InputContentInfoCompat

import app.caret.event.HapticEvent
import app.caret.keyboard.internal.LayoutDirective
import app.caret.latin.common.Constants
import app.caret.latin.common.InputPointers
import app.caret.latin.utils.RecapitalizeMode

interface KeyboardActionListener {
    /**
     * Called when the user presses a key. This is sent before the {@link #onCodeInput} is called.
     * For keys that repeat, this is only called once.
     *
     * @param primaryCode the unicode of the key being pressed. If the touch is not on a valid key,
     *            the value will be zero.
     * @param repeatCount how many times the key was repeated. Zero if it is the first press.
     * @param pointerCount the number of pointers being tracked, including this one.
     * @param hapticEvent the type of haptic feedback to perform.
     */
    fun onPressKey(primaryCode: Int, repeatCount: Int, pointerCount: Int, hapticEvent: HapticEvent)

    fun onLongPressKey(primaryCode: Int)

    /**
     * Called when the user releases a key. This is sent after the {@link #onCodeInput} is called.
     * For keys that repeat, this is only called once.
     *
     * @param primaryCode the code of the key that was released
     * @param withSliding true if releasing has occurred because the user slid finger from the key
     *             to other key without releasing the finger.
     */
    fun onReleaseKey(primaryCode: Int, withSliding: Boolean)

    /** For handling hardware key presses. Returns whether the event was handled. */
    fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean

    /** For handling hardware key presses. Returns whether the event was handled. */
    fun onKeyUp(keyCode: Int, keyEvent: KeyEvent): Boolean

    /**
     * Send a key code to the listener.
     *
     * @param primaryCode this is the code of the key that was pressed
     * @param x x-coordinate pixel of touched event. If onCodeInput is not called by
     *            {@link PointerTracker} or so, the value should be
     *            {@link Constants#NOT_A_COORDINATE}. If it's called on insertion from the
     *            suggestion strip, it should be {@link Constants#SUGGESTION_STRIP_COORDINATE}.
     * @param y y-coordinate pixel of touched event. If #onCodeInput is not called by
     *            {@link PointerTracker} or so, the value should be
     *            {@link Constants#NOT_A_COORDINATE}.If it's called on insertion from the
     *            suggestion strip, it should be {@link Constants#SUGGESTION_STRIP_COORDINATE}.
     * @param isKeyRepeat true if this is a key repeat, false otherwise
     */
    // TODO: change this to send an Event object instead
    fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)

    /**
     * Sends a string of characters to the listener.
     *
     * @param text the string of characters to be registered.
     */
    fun onTextInput(text: String?)

    /** sends content (URI and description) */
    fun onContent(content: InputContentInfoCompat)

    /**
     * Called when user started batch input.
     */
    fun onStartBatchInput()

    /**
     * Sends the ongoing batch input points data.
     * @param batchPointers the batch input points representing the user input
     */
    fun onUpdateBatchInput(batchPointers: InputPointers?)

    /**
     * Sends the final batch input points data.
     *
     * @param batchPointers the batch input points representing the user input
     */
    fun onEndBatchInput(batchPointers: InputPointers?)

    fun onCancelBatchInput()

    /**
     * Called when user released a finger outside any key.
     */
    fun onCancelInput()

    /**
     * Called when user finished sliding key input.
     */
    fun onFinishSlidingInput()

    /**
     * Send a non-"code input" custom request to the listener.
     * @return true if the request has been consumed, false otherwise.
     */
    fun onCustomRequest(request: CustomAction): Boolean

    /**
     * Called when the user performs a horizontal or vertical swipe gesture
     * on the space bar.
     */
    fun onHorizontalSpaceSwipe(steps: Int): Boolean
    fun onVerticalSpaceSwipe(steps: Int): Boolean
    fun onEndSpaceSwipe()
    fun toggleLayout(layout: LayoutDirective.Utility, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?)
    fun onLongPressAlphaSymbolForNumpad()

    fun onMoveDeletePointer(steps: Int)
    fun onUpWithDeletePointerActive()
    fun resetMetaState()

    enum class SwipeAction { NONE, MOVE_CURSOR, SWITCH_LANGUAGE, TOGGLE_NUMPAD, TOGGLE_DPAD, HIDE_KEYBOARD, TOUCHPAD_MODE }
    enum class CustomAction { SHOW_INPUT_METHOD_PICKER, TOUCHPAD_ON, TOUCHPAD_OFF, PERFORM_HAPTIC }

    open class Adapter : KeyboardActionListener {
        override fun onPressKey(primaryCode: Int, repeatCount: Int, pointerCount: Int, hapticEvent: HapticEvent) {}
        override fun onLongPressKey(primaryCode: Int) {}
        override fun onReleaseKey(primaryCode: Int, withSliding: Boolean) {}
        override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean { return false }
        override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent): Boolean { return false }
        override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {}
        override fun onTextInput(text: String?) {}
        override fun onContent(content: InputContentInfoCompat) {}
        override fun onStartBatchInput() {}
        override fun onUpdateBatchInput(batchPointers: InputPointers?) {}
        override fun onEndBatchInput(batchPointers: InputPointers?) {}
        override fun onCancelBatchInput() {}
        override fun onCancelInput() {}
        override fun onFinishSlidingInput() {}
        override fun onCustomRequest(request: CustomAction): Boolean {
            return false
        }
        override fun onHorizontalSpaceSwipe(steps: Int): Boolean {
            return false
        }
        override fun onVerticalSpaceSwipe(steps: Int): Boolean {
            return false
        }
        override fun toggleLayout(layout: LayoutDirective.Utility, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {}
        override fun onLongPressAlphaSymbolForNumpad() {}
        override fun onEndSpaceSwipe() {}
        override fun onMoveDeletePointer(steps: Int) {}
        override fun onUpWithDeletePointerActive() {}
        override fun resetMetaState() {}
    }

    companion object {
        @JvmField
        val EMPTY_LISTENER: KeyboardActionListener = Adapter()
    }
}
