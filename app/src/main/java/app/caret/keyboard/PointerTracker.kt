/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import java.lang.Math.abs

import android.content.res.Resources
import android.content.res.TypedArray
import android.os.SystemClock
import android.view.MotionEvent

import app.caret.event.HapticEvent
import app.caret.keyboard.internal.BatchInputArbiter
import app.caret.keyboard.internal.BatchInputArbiter.BatchInputArbiterListener
import app.caret.keyboard.internal.BogusMoveEventDetector
import app.caret.keyboard.internal.DrawingProxy
import app.caret.keyboard.internal.GestureEnabler
import app.caret.keyboard.internal.GestureStrokeDrawingParams
import app.caret.keyboard.internal.GestureStrokeDrawingPoints
import app.caret.keyboard.internal.GestureStrokeRecognitionParams
import app.caret.keyboard.internal.PointerTrackerQueue
import app.caret.keyboard.internal.TimerProxy
import app.caret.keyboard.internal.TypingTimeRecorder
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.R
import app.caret.latin.common.Constants
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.common.InputPointers
import app.caret.latin.define.DebugFlags
import app.caret.latin.settings.Settings
import app.caret.latin.settings.SettingsValues
import app.caret.latin.utils.Log
import app.caret.latin.utils.dpToPx

import java.util.ArrayList
import java.util.Locale
import java.util.WeakHashMap

class PointerTracker private constructor(id: Int) : PointerTrackerQueue.Element,
        BatchInputArbiterListener {

    class PointerTrackerParams(mainKeyboardViewAttr: TypedArray) {
        @JvmField val keySelectionByDraggingFinger: Boolean
        @JvmField val touchNoiseThresholdTime: Int
        @JvmField val touchNoiseThresholdDistance: Int
        @JvmField val suppressKeyPreviewAfterBatchInputDuration: Int
        @JvmField val keyRepeatStartTimeout: Int
        @JvmField val keyRepeatInterval: Int

        init {
            keySelectionByDraggingFinger = mainKeyboardViewAttr.getBoolean(
                    R.styleable.MainKeyboardView_keySelectionByDraggingFinger, false)
            touchNoiseThresholdTime = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_touchNoiseThresholdTime, 0)
            touchNoiseThresholdDistance = mainKeyboardViewAttr.getDimensionPixelSize(
                    R.styleable.MainKeyboardView_touchNoiseThresholdDistance, 0)
            suppressKeyPreviewAfterBatchInputDuration = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_suppressKeyPreviewAfterBatchInputDuration, 0)
            keyRepeatStartTimeout = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_keyRepeatStartTimeout, 0)
            keyRepeatInterval = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_keyRepeatInterval, 0)
        }
    }

    @JvmField
    val pointerId: Int

    // The {@link KeyDetector} is set whenever the down event is processed. Also this is updated
    // when new {@link Keyboard} is set by {@link #setKeyDetector(KeyDetector)}.
    private var keyDetector: KeyDetector = KeyDetector()
    private var keyboard: Keyboard? = null
    private val bogusMoveEventDetector = BogusMoveEventDetector()

    private var isDetectingGesture = false // per PointerTracker.

    // The position and time at which first down event occurred.
    var downTime: Long = 0
        private set
    private val downCoordinates: IntArray = CoordinateUtils.newInstance()
    private var upTime: Long = 0

    // The current key where this pointer is.
    private var currentKey: Key? = null
    // The position where the current key was recognized for the first time.
    private var keyX: Int = 0
    private var keyY: Int = 0

    // Last pointer position.
    private var lastX: Int = 0
    private var lastY: Int = 0
    private var startX: Int = 0
    private var startY: Int = 0
    /** Where this touch went down; [startY] moves along during a swipe on the space bar. */
    private var downY: Int = 0
    private var startTime: Long = 0
    private var inHorizontalSwipe = false
    private var inVerticalSwipe = false

    // true if keyboard layout has been changed.
    private var keyboardLayoutHasBeenChanged: Boolean = false
    private var keyboardChangeOccupiedHeightDifference: Int = 0

    // true if this pointer is no longer triggering any action because it has been canceled.
    private var isTrackingForActionDisabled: Boolean = false

    // the popup keys panel currently being shown. equals null if no panel is active.
    private var popupKeysPanel: PopupKeysPanel? = null

    // true if this pointer is in the dragging finger mode.
    private var inDraggingFinger = false
    // true if this pointer is sliding from a modifier key and in the sliding key input mode,
    // so that further modifier keys should be ignored.
    private var isInSlidingKeyInput = false
    // if not a NOT_A_CODE, the key of this code is repeating
    private var currentRepeatingKeyCode: Int = Constants.NOT_A_CODE

    // true if dragging finger is allowed.
    private var isAllowedDraggingFinger: Boolean = false
    // true if a keyswipe gesture is enabled and warranted.
    private var keySwipeAllowed = false

    // Touchpad mode for cursor control
    private val touchpadHandler = TouchpadHandler()

    private val batchInputArbiter: BatchInputArbiter
    val gestureStrokeDrawingPoints: GestureStrokeDrawingPoints

    init {
        pointerId = id
        batchInputArbiter = BatchInputArbiter(id, gestureStrokeRecognitionParams)
        gestureStrokeDrawingPoints = GestureStrokeDrawingPoints(gestureStrokeDrawingParams)
    }

    // Returns true if keyboard has been changed by this callback.
    private fun callListenerOnPressAndCheckKeyboardLayoutChange(key: Key, repeatCount: Int): Boolean {
        // While gesture input is going on, this method should be a no-operation. But when gesture
        // input has been canceled, <code>inGesture</code> and <code>isDetectingGesture</code>
        // are set to false. To keep this method is a no-operation,
        // <code>isTrackingForActionDisabled</code> should also be taken account of.
        if (inGesture || isDetectingGesture || isTrackingForActionDisabled) {
            return false
        }
        val ignoreModifierKey = inDraggingFinger && key.isModifier()
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onPress    : %s%s%s%s", pointerId,
                Constants.printableCode(key.code),
                if (ignoreModifierKey) " ignoreModifier" else "",
                if (key.isEnabled) "" else " disabled",
                if (repeatCount > 0) " repeatCount=" + repeatCount else "")
            )
        }
        if (ignoreModifierKey) {
            return false
        }
        if (key.isEnabled) {
            val hapticEvent = if (repeatCount == 0) HapticEvent.KEY_PRESS else HapticEvent.KEY_REPEAT
            listener.onPressKey(key.code, repeatCount, getActivePointerTrackerCount(), hapticEvent)
            val layoutHasBeenChanged = keyboardLayoutHasBeenChanged
            keyboardLayoutHasBeenChanged = false
            timerProxy.startTypingStateTimer(key)
            return layoutHasBeenChanged
        }
        return false
    }

    // Note that we need primaryCode argument because the keyboard may in shifted state and the
    // primaryCode is different from {@link Key#mKeyCode}.
    private fun callListenerOnCodeInput(key: Key, primaryCode: Int, x: Int,
            y: Int, eventTime: Long, isKeyRepeat: Boolean) {
        val keyCode = key.code
        val ignoreModifierKey = inDraggingFinger && key.isModifier()
                && keyCode != KeyCode.NUMPAD && keyCode != KeyCode.DPAD // we allow these to be activated from sliding input
        val altersCode = key.altCodeWhileTyping() && timerProxy.isTypingState() && !isClearlyInsideKey(key, x, y)
        val code = if (altersCode) key.altCode else primaryCode
        if (DEBUG_LISTENER) {
            val output = if (code == KeyCode.MULTIPLE_CODE_POINTS)
                    key.outputText else Constants.printableCode(code)
            Log.d(TAG, String.format(Locale.US, "[%d] onCodeInput: %4d %4d %s%s%s%s", pointerId, x, y,
                    output, if (ignoreModifierKey) " ignoreModifier" else "",
                    if (altersCode) " altersCode" else "", if (key.isEnabled) "" else " disabled"))
        }
        if (ignoreModifierKey) {
            return
        }
        // Even if the key is disabled, it should respond if it is in the altCodeWhileTyping state.
        if (key.isEnabled || altersCode) {
            typingTimeRecorder.onCodeInput(code, eventTime)
            if (code == KeyCode.MULTIPLE_CODE_POINTS) {
                listener.onTextInput(key.outputText)
            } else if (code != KeyCode.NOT_SPECIFIED) {
                val kb = keyboard ?: return
                if (kb.hasProximityCharsCorrection(code)) {
                    listener.onCodeInput(code, x, y, isKeyRepeat)
                } else {
                    listener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isKeyRepeat)
                }
            }
        }
    }

    // Note that we need primaryCode argument because the keyboard may be in shifted state and the
    // primaryCode is different from {@link Key#mKeyCode}.
    private fun callListenerOnRelease(key: Key, primaryCode: Int, withSliding: Boolean) {
        // See the comment at {@link #callListenerOnPressAndCheckKeyboardLayoutChange(Key}}.
        if (inGesture || isDetectingGesture || isTrackingForActionDisabled) {
            return
        }
        val ignoreModifierKey = inDraggingFinger && key.isModifier()
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onRelease  : %s%s%s%s", pointerId,
                    Constants.printableCode(primaryCode),
                    if (withSliding) " sliding" else "", if (ignoreModifierKey) " ignoreModifier" else "",
                    if (key.isEnabled) "" else " disabled"))
        }
        if (ignoreModifierKey) {
            return
        }
        if (key.isEnabled) {
            listener.onReleaseKey(primaryCode, withSliding)
        }
    }

    private fun callListenerOnFinishSlidingInput() {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onFinishSlidingInput", pointerId))
        }
        listener.onFinishSlidingInput()
    }

    private fun callListenerOnCancelInput() {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onCancelInput", pointerId))
        }
        listener.onCancelInput()
    }

    private fun setKeyDetectorInner(newKeyDetector: KeyDetector) {
        val newKeyboard = newKeyDetector.keyboard
        if (newKeyboard == null) {
            return
        }
        val oldKeyboard = keyboard
        if (newKeyDetector === keyDetector && newKeyboard === oldKeyboard) {
            return
        }
        if (oldKeyboard != null) {
            // changing keyboards may change height
            // since y is measured from top of view, this change needs to be considered in some places
            keyboardChangeOccupiedHeightDifference = newKeyboard.occupiedHeight - oldKeyboard.occupiedHeight
        }
        keyDetector = newKeyDetector
        keyboard = newKeyboard
        // Mark that keyboard layout has been changed.
        keyboardLayoutHasBeenChanged = true
        val keyWidth = newKeyboard.mostCommonKeyWidth
        val keyHeight = newKeyboard.mostCommonKeyHeight
        batchInputArbiter.setKeyboardGeometry(keyWidth, newKeyboard.occupiedHeight)
        // Keep {@link #currentKey} that comes from previous keyboard. The key preview of
        // {@link #currentKey} will be dismissed by {@setReleasedKeyGraphics(Key)} via
        // {@link onMoveEventInternal(int,int,long)} or {@link #onUpEventInternal(int,int,long)}.
        bogusMoveEventDetector.setKeyboardGeometry(keyWidth, keyHeight)
    }

    override fun isInDraggingFinger(): Boolean {
        return inDraggingFinger
    }

    val key: Key?
        get() = currentKey

    override fun isModifier(): Boolean {
        val key = currentKey
        return key != null && key.isModifier()
    }

    // Caret: a tap near the edge of a key that was taken for a much likelier neighbor (see LikelyKeys);
    // while the finger stays on the key it landed on, it keeps counting as that neighbor
    private var landedOn: Key? = null
    private var takenFor: Key? = null

    fun getKeyOn(x: Int, y: Int): Key? {
        val key = keyDetector.detectHitKey(x, y)
        landedOn = null
        takenFor = null
        val kb = keyboard
        if (key == null || kb == null || inGesture) return key
        val likely = LikelyKeys.adjust(kb, key, keyDetector.getTouchX(x), keyDetector.getTouchY(y))
        if (likely !== key) {
            landedOn = key
            takenFor = likely
        }
        return likely
    }

    private fun setReleasedKeyGraphics(key: Key?, withAnimation: Boolean) {
        if (key == null) {
            return
        }

        drawingProxy.onKeyReleased(key, withAnimation)

        // A non-null key always comes from a keyboard that was set on this tracker.
        val kb = keyboard ?: return
        if (key.isShift) {
            for (shiftKey in kb.shiftKeys) {
                if (shiftKey !== key) {
                    drawingProxy.onKeyReleased(shiftKey, false)
                }
            }
        }

        if (key.altCodeWhileTyping()) {
            val altCode = key.altCode
            val altKey = kb.getKey(altCode)
            if (altKey != null) {
                drawingProxy.onKeyReleased(altKey, false)
            }
            for (k in kb.altCodeKeysWhileTyping) {
                if (k !== key && k.altCode == altCode) {
                    drawingProxy.onKeyReleased(k, false)
                }
            }
        }
    }

    private fun setPressedKeyGraphics(key: Key?, eventTime: Long) {
        if (key == null) {
            return
        }

        // Even if the key is disabled, it should respond if it is in the altCodeWhileTyping state.
        val altersCode = key.altCodeWhileTyping() && timerProxy.isTypingState()
        val needsToUpdateGraphics = key.isEnabled || altersCode
        if (!needsToUpdateGraphics) {
            return
        }

        val noKeyPreview = inGesture || needsToSuppressKeyPreviewPopup(eventTime)
        drawingProxy.onKeyPressed(key, !noKeyPreview)

        // A non-null key always comes from a keyboard that was set on this tracker.
        val kb = keyboard ?: return
        if (key.isShift) {
            for (shiftKey in kb.shiftKeys) {
                if (shiftKey !== key) {
                    drawingProxy.onKeyPressed(shiftKey, false)
                }
            }
        }

        if (altersCode) {
            val altCode = key.altCode
            val altKey = kb.getKey(altCode)
            if (altKey != null) {
                drawingProxy.onKeyPressed(altKey, false)
            }
            for (k in kb.altCodeKeysWhileTyping) {
                if (k !== key && k.altCode == altCode) {
                    drawingProxy.onKeyPressed(k, false)
                }
            }
        }
    }



    fun getLastCoordinates(outCoords: IntArray) {
        CoordinateUtils.set(outCoords, lastX, lastY)
    }


    fun getDownCoordinates(outCoords: IntArray) {
        CoordinateUtils.copy(outCoords, downCoordinates)
    }

    private fun onDownKey(x: Int, y: Int, eventTime: Long): Key? {
        downTime = eventTime
        CoordinateUtils.set(downCoordinates, x, y)
        bogusMoveEventDetector.onDownKey()
        return onMoveToNewKey(onMoveKeyInternal(x, y), x, y)
    }

    private fun onMoveKeyInternal(x: Int, y: Int): Key? {
        bogusMoveEventDetector.onMoveKey(getDistance(x, y, lastX, lastY))
        lastX = x
        lastY = y
        val key = keyDetector.detectHitKey(x, y)
        return if (key != null && key === landedOn) takenFor else key
    }

    private fun onMoveKey(x: Int, y: Int): Key? {
        return onMoveKeyInternal(x, y)
    }

    private fun onMoveToNewKey(newKey: Key?, x: Int, y: Int): Key? {
        currentKey = newKey
        keyX = x
        keyY = y
        return newKey
    }

    private fun isOldestTrackerInQueue(): Boolean {
        return pointerTrackerQueue.getOldestElement() === this
    }

    // Implements {@link BatchInputArbiterListener}.
    override fun onStartBatchInput() {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onStartBatchInput", pointerId))
        }
        listener.onStartBatchInput()
        dismissAllPopupKeysPanels()
        timerProxy.cancelLongPressTimersOf(this)
    }

    private fun showGestureTrail() {
        if (isTrackingForActionDisabled) {
            return
        }
        // A gesture floating preview text will be shown at the oldest pointer/finger on the screen.
        drawingProxy.showGestureTrail(this, isOldestTrackerInQueue())
    }

    fun updateBatchInputByTimer(syntheticMoveEventTime: Long) {
        batchInputArbiter.updateBatchInputByTimer(syntheticMoveEventTime, this)
    }

    // Implements {@link BatchInputArbiterListener}.
    override fun onUpdateBatchInput(aggregatedPointers: InputPointers, eventTime: Long) {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onUpdateBatchInput: batchPoints=%d", pointerId,
                    aggregatedPointers.pointerSize))
        }
        listener.onUpdateBatchInput(aggregatedPointers)
    }

    // Implements {@link BatchInputArbiterListener}.
    override fun onStartUpdateBatchInputTimer() {
        timerProxy.startUpdateBatchInputTimer(this)
    }

    // Implements {@link BatchInputArbiterListener}.
    override fun onEndBatchInput(aggregatedPointers: InputPointers, eventTime: Long) {
        typingTimeRecorder.onEndBatchInput(eventTime)
        timerProxy.cancelAllUpdateBatchInputTimers()
        if (isTrackingForActionDisabled) {
            return
        }
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onEndBatchInput   : batchPoints=%d",
                    pointerId, aggregatedPointers.pointerSize))
        }
        listener.onEndBatchInput(aggregatedPointers)
    }

    private fun cancelBatchInput() {
        cancelAllPointerTrackers()
        isDetectingGesture = false
        if (!inGesture) {
            return
        }
        inGesture = false
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onCancelBatchInput", pointerId))
        }
        listener.onCancelBatchInput()
    }

    fun processMotionEvent(me: MotionEvent, keyDetector: KeyDetector) {
        val action = me.getActionMasked()
        val eventTime = me.getEventTime()
        if (action == MotionEvent.ACTION_MOVE) {
            // When this pointer is the only active pointer and is showing a popup keys panel,
            // we should ignore other pointers' motion event.
            val shouldIgnoreOtherPointers =
                    isShowingPopupKeysPanel && getActivePointerTrackerCount() == 1
            val pointerCount = me.getPointerCount()
            for (index in 0 until pointerCount) {
                val id = me.getPointerId(index)
                if (shouldIgnoreOtherPointers && id != pointerId) {
                    continue
                }
                val x = me.getX(index).toInt()
                val y = me.getY(index).toInt()
                val tracker = getPointerTracker(id)
                tracker.onMoveEvent(x, y, eventTime, me)
            }
            return
        }
        val index = me.getActionIndex()
        val x = me.getX(index).toInt()
        val y = me.getY(index).toInt()
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onDownEvent(x, y, eventTime, keyDetector)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUpEvent(x, y, eventTime)
            MotionEvent.ACTION_CANCEL -> onCancelEvent(x, y, eventTime)
        }
    }

    private fun onDownEvent(x: Int, y: Int, eventTime: Long,
            newKeyDetector: KeyDetector) {
        setKeyDetectorInner(newKeyDetector)
        if (DEBUG_EVENT) {
            printTouchEvent("onDownEvent:", x, y, eventTime)
        }
        // Naive up-to-down noise filter.
        val deltaT = eventTime - upTime
        if (deltaT < params.touchNoiseThresholdTime) {
            val distance = getDistance(x, y, lastX, lastY)
            if (distance < params.touchNoiseThresholdDistance) {
                if (DEBUG_MODE)
                    Log.w(TAG, String.format(Locale.US, "[%d] onDownEvent:"
                            + " ignore potential noise: time=%d distance=%d",
                            pointerId, deltaT, distance))
                cancelTrackingForAction()
                return
            }
        }

        val key = getKeyOn(x, y)
        bogusMoveEventDetector.onActualDownEvent(x, y)
        if (key != null && key.isModifier()) {
            if (inGesture) {
                // Make sure not to interrupt an active gesture
                return
            } else {
                // Before processing a down event of modifier key, all pointers
                // already being tracked should be released.
                pointerTrackerQueue.releaseAllPointers(eventTime)
            }
        }
        pointerTrackerQueue.add(this)
        onDownEventInternal(x, y, eventTime)
        if (!gestureEnabler.shouldHandleGesture()) {
            return
        }
        // A gesture should start only from a non-modifier key. Note that the gesture detection is
        // disabled when the key is repeating.
        val kb = keyboard
        isDetectingGesture = (kb != null) && kb.id.element.isAlphabet
                && key != null && !key.isModifier() && !keySwipeAllowed && !inKeySwipe
        if (isDetectingGesture) {
            batchInputArbiter.addDownEventPoint(x, y, eventTime,
                    typingTimeRecorder.lastLetterTypingTime, getActivePointerTrackerCount())
            gestureStrokeDrawingPoints.onDownEvent(
                    x, y, batchInputArbiter.getElapsedTimeSinceFirstDown(eventTime))
        }
    }

    /* package */ val isShowingPopupKeysPanel: Boolean
        get() = (popupKeysPanel != null)

    private fun dismissPopupKeysPanel() {
        val panel = popupKeysPanel
        if (panel != null) {
            panel.dismissPopupKeysPanel()
            popupKeysPanel = null
        }
    }

    private fun onDownEventInternal(x: Int, y: Int, eventTime: Long) {
        var key = onDownKey(x, y, eventTime)
        // Key selection by dragging finger is allowed when 1) key selection by dragging finger is
        // enabled by configuration, 2) this pointer starts dragging from modifier key, or 3) this
        // pointer's KeyDetector always allows key selection by dragging finger, such as
        // {@link PopupKeysKeyboard}.
        isAllowedDraggingFinger = params.keySelectionByDraggingFinger
                || (key != null && key.isModifier())
                || keyDetector.alwaysAllowsKeySelectionByDraggingFinger()
        if (key != null && isSwiper(key.code) && !inGesture) {
            keySwipeAllowed = true
            inKeySwipe = true
        }
        keyboardLayoutHasBeenChanged = false
        isTrackingForActionDisabled = false
        resetKeySelectionByDraggingFinger()
        if (key != null) {
            // This onPress call may have changed keyboard layout. Those cases are detected at
            // {@link #setKeyboard}. In those cases, we should update key according to the new
            // keyboard layout.
            // Also height difference between keyboards needs to be considered.
            if (callListenerOnPressAndCheckKeyboardLayoutChange(key, 0)) {
                val yOffset = keyboardChangeOccupiedHeightDifference
                keyboardChangeOccupiedHeightDifference = 0
                CoordinateUtils.set(downCoordinates, x, y + yOffset)
                key = onDownKey(x, y + yOffset, eventTime)
            }

            startRepeatKey(key)
            startLongPressTimer(key)
            setPressedKeyGraphics(key, eventTime)
            startX = x
            startY = y
            downY = y
            startTime = SystemClock.elapsedRealtime()
        }
    }

    private fun startKeySelectionByDraggingFinger(key: Key) {
        if (!inDraggingFinger) {
            // the meta lock keys stay enabled after sliding input, but should not
            // (even without sliding input they actually behave the same... this is just about the graphics)
            val code = key.code
            if (key.isModifier() && code != KeyCode.CTRL_LOCK && code != KeyCode.ALT_LOCK && code != KeyCode.FN_LOCK && code != KeyCode.META_LOCK) {
                // A non-null key always comes from a keyboard that was set on this tracker.
                val kb = keyboard ?: return
                val element = kb.id.element
                isInSlidingKeyInput = !(code == KeyCode.SHIFT && (element == KeyboardElement.ALPHABET_SHIFT_LOCKED || element == KeyboardElement.DPAD))
            } else {
                isInSlidingKeyInput = false
            }
        }
        inDraggingFinger = true
    }

    private fun resetKeySelectionByDraggingFinger() {
        inDraggingFinger = false
        isInSlidingKeyInput = false
        drawingProxy.showSlidingKeyInputPreview(null)
    }

    private fun isSwiper(code: Int): Boolean {
        val sv = Settings.values
        return when (code) {
            Constants.CODE_SPACE -> sv.spaceSwipeHorizontal != KeyboardActionListener.SwipeAction.NONE
                    || sv.spaceSwipeVertical != KeyboardActionListener.SwipeAction.NONE
            KeyCode.DELETE -> sv.deleteSwipeEnabled
            else -> false
        }
    }

    private fun onGestureMoveEvent(x: Int, y: Int, eventTime: Long,
            isMajorEvent: Boolean, key: Key?) {
        if (!isDetectingGesture || inKeySwipe) {
            return
        }
        val onValidArea = batchInputArbiter.addMoveEventPoint(
                x, y, eventTime, isMajorEvent, this)
        // If the move event goes out from valid batch input area, cancel batch input.
        if (!onValidArea) {
            cancelBatchInput()
            return
        }
        gestureStrokeDrawingPoints.onMoveEvent(
                x, y, batchInputArbiter.getElapsedTimeSinceFirstDown(eventTime))
        // If the PopupKeysPanel is showing then do not attempt to enter gesture mode. However,
        // the gestured touch points are still being recorded in case the panel is dismissed.
        if (isShowingPopupKeysPanel) {
            return
        }
        if (!inGesture && key != null && Character.isLetter(key.code)
                && batchInputArbiter.mayStartBatchInput(this)) {
            listener.resetMetaState() // avoid metaState getting stuck, doesn't work with gesture typing anyway
            inGesture = true
        }
        if (inGesture) {
            if (key != null) {
                batchInputArbiter.updateBatchInput(eventTime, this)
            }
            showGestureTrail()
        }
    }

    private fun onMoveEvent(x: Int, y: Int, eventTime: Long, me: MotionEvent?) {
        if (DEBUG_MOVE_EVENT) {
            printTouchEvent("onMoveEvent:", x, y, eventTime)
        }
        if (isTrackingForActionDisabled) {
            return
        }

        if (gestureEnabler.shouldHandleGesture() && me != null) {
            // Add historical points to gesture path.
            val pointerIndex = me.findPointerIndex(pointerId)
            val historicalSize = me.getHistorySize()
            for (h in 0 until historicalSize) {
                val historicalX = me.getHistoricalX(pointerIndex, h).toInt()
                val historicalY = me.getHistoricalY(pointerIndex, h).toInt()
                val historicalTime = me.getHistoricalEventTime(h)
                onGestureMoveEvent(historicalX, historicalY, historicalTime, false, null)
            }
        }

        val panel = popupKeysPanel
        if (panel != null) {
            val translatedX = panel.translateX(x)
            val translatedY = panel.translateY(y)
            panel.onMoveEvent(translatedX, translatedY, pointerId, eventTime)
            onMoveKey(x, y)
            if (isInSlidingKeyInput) {
                drawingProxy.showSlidingKeyInputPreview(this)
            }
            return
        }
        onMoveEventInternal(x, y, eventTime)
    }

    private fun processDraggingFingerInToNewKey(newKey: Key, x: Int, y: Int, eventTime: Long) {
        // This onPress call may have changed keyboard layout. Those cases are detected
        // at {@link #setKeyboard}. In those cases, we should update key according
        // to the new keyboard layout.
        var key: Key? = newKey
        if (callListenerOnPressAndCheckKeyboardLayoutChange(newKey, 0)) {
            key = onMoveKey(x, y)
        }
        onMoveToNewKey(key, x, y)
        if (isTrackingForActionDisabled) {
            return
        }
        startLongPressTimer(key)
        setPressedKeyGraphics(key, eventTime)
    }

    private fun processProximateBogusDownMoveUpEventHack(key: Key, x: Int, y: Int,
            eventTime: Long, oldKey: Key, prevLastX: Int, prevLastY: Int) {
        val kb = keyboard
        if (DEBUG_MODE && kb != null) {
            val keyDiagonal = Math.hypot(
                    kb.mostCommonKeyWidth.toDouble(), kb.mostCommonKeyHeight.toDouble()).toFloat()
            val radiusRatio =
                    bogusMoveEventDetector.getDistanceFromDownEvent(x, y) /
                    keyDiagonal
            Log.w(TAG, String.format(Locale.US, "[%d] onMoveEvent:"
                    + " bogus down-move-up event (raidus=%.2f key diagonal) is "
                    + " translated to up[%d,%d,%s]/down[%d,%d,%s] events",
                    pointerId, radiusRatio,
                    prevLastX, prevLastY, Constants.printableCode(oldKey.code),
                    x, y, Constants.printableCode(key.code)))
        }
        onUpEventInternal(x, y, eventTime)
        onDownEventInternal(x, y, eventTime)
    }

    private fun processDraggingFingerOutFromOldKey(oldKey: Key) {
        setReleasedKeyGraphics(oldKey, true)
        callListenerOnRelease(oldKey, oldKey.code, true)
        startKeySelectionByDraggingFinger(oldKey)
        timerProxy.cancelKeyTimersOf(this)
    }

    private fun dragFingerFromOldKeyToNewKey(key: Key, x: Int, y: Int,
            eventTime: Long, oldKey: Key, prevLastX: Int, prevLastY: Int) {
        // The pointer has been slid in to the new key from the previous key, we must call
        // onRelease() first to notify that the previous key has been released, then call
        // onPress() to notify that the new key is being pressed.
        processDraggingFingerOutFromOldKey(oldKey)
        startRepeatKey(key)
        if (isAllowedDraggingFinger) {
            processDraggingFingerInToNewKey(key, x, y, eventTime)
        }
        // HACK: On some devices, quick successive proximate touches may be reported as a bogus
        // down-move-up event by touch panel firmware. This hack detects such cases and breaks
        // these events into separate up and down events.
        else if (typingTimeRecorder.isInFastTyping(eventTime)
                && bogusMoveEventDetector.isCloseToActualDownEvent(x, y)) {
            processProximateBogusDownMoveUpEventHack(key, x, y, eventTime, oldKey, prevLastX, prevLastY)
        }
        // HACK: If there are currently multiple touches, register the key even if the finger
        // slides off the key. This defends against noise from some touch panels when there are
        // close multiple touches.
        // Caveat: When in chording input mode with a modifier key, we don't use this hack.
        else if (getActivePointerTrackerCount() > 1
                && !pointerTrackerQueue.hasModifierKeyOlderThan(this)) {
            if (DEBUG_MODE) {
                Log.w(TAG, String.format(Locale.US, "[%d] onMoveEvent:"
                        + " detected sliding finger while multi touching", pointerId))
            }
            onUpEvent(x, y, eventTime)
            cancelTrackingForAction()
            setReleasedKeyGraphics(oldKey, true)
        } else {
            if (!isDetectingGesture) {
                cancelTrackingForAction()
            }
            setReleasedKeyGraphics(oldKey, true)
        }
    }

    private fun dragFingerOutFromOldKey(oldKey: Key, x: Int, y: Int) {
        // The pointer has been slid out from the previous key, we must call onRelease() to
        // notify that the previous key has been released.
        processDraggingFingerOutFromOldKey(oldKey)
        if (isAllowedDraggingFinger) {
            onMoveToNewKey(null, x, y)
        } else {
            if (!isDetectingGesture) {
                cancelTrackingForAction()
            }
        }
    }


    private fun oneShotSwipe(swipeSetting: KeyboardActionListener.SwipeAction): Boolean {
        return when (swipeSetting) {
            KeyboardActionListener.SwipeAction.NONE, KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD,
            KeyboardActionListener.SwipeAction.TOGGLE_DPAD, KeyboardActionListener.SwipeAction.HIDE_KEYBOARD -> true
            else -> false
        }
    }

    private fun onKeySwipe(code: Int, x: Int, y: Int, eventTime: Long) {
        val sv = Settings.values
        val fastTypingTimeout = 2 * sv.keyLongpressTimeout / 3
        // we don't want keyswipes to start immediately if the user is fast-typing,
        // see https://github.com/openboard-team/openboard/issues/411
        // delete swipe is excluded because it already has a distance threshold,
        // see https://github.com/openboard-team/openboard/pull/566
        if (code != KeyCode.DELETE && SystemClock.elapsedRealtime() < startTime + fastTypingTimeout && typingTimeRecorder.isInFastTyping(eventTime))
            return
        if (code == Constants.CODE_SPACE) {
            val dX = x - startX
            val dY = y - startY

            // Touchpad mode
            touchpadHandler.enableTouchpadMove(x, y, listener)

            // Vertical movement
            val stepsY = dY / pointerStep
            if (stepsY != 0 && abs(dX) < abs(dY) && !inHorizontalSwipe) {
                if (!inVerticalSwipe) {
                    timerProxy.cancelKeyTimersOf(this)
                    inVerticalSwipe = true
                } else if (oneShotSwipe(sv.spaceSwipeVertical)) return
                if (listener.onVerticalSpaceSwipe(stepsY)) {
                    startY += stepsY * pointerStep
                }
                return
            }

            // Horizontal movement
            val stepsX = dX / pointerStep
            if (stepsX != 0 && !inVerticalSwipe) {
                if (!inHorizontalSwipe) {
                    timerProxy.cancelKeyTimersOf(this)
                    inHorizontalSwipe = true
                } else if (oneShotSwipe(sv.spaceSwipeHorizontal)) return
                if (listener.onHorizontalSpaceSwipe(stepsX)) {
                    startX += stepsX * pointerStep
                }
            }
        } else if (code == KeyCode.DELETE) {
            // Delete slider
            val steps = (x - startX) / pointerStep
            if (steps != 0) {
                if (!inHorizontalSwipe) {
                    timerProxy.cancelKeyTimersOf(this)
                    inHorizontalSwipe = true
                }
                startX += steps * pointerStep
                listener.onMoveDeletePointer(steps)
            }
        }
    }

    private fun onMoveEventInternal(x: Int, y: Int, eventTime: Long) {
        val oldKey = currentKey

        // todo (later): move key swipe stuff to KeyboardActionListener (and finally extend it)
        if (keySwipeAllowed) {
            onKeySwipe(oldKey!!.code, x, y, eventTime)
            return
        }

        val newKey = onMoveKey(x, y)
        val prevLastX = lastX
        val prevLastY = lastY

        if (gestureEnabler.shouldHandleGesture()) {
            // Register move event on gesture tracker.
            onGestureMoveEvent(x, y, eventTime, true, newKey)
            if (inGesture) {
                currentKey = null
                setReleasedKeyGraphics(oldKey, true)
                return
            }
        }

        if (newKey != null) {
            if (oldKey != null && isMajorEnoughMoveToBeOnNewKey(x, y, eventTime, newKey)) {
                dragFingerFromOldKeyToNewKey(newKey, x, y, eventTime, oldKey, prevLastX, prevLastY)
            } else if (oldKey == null) {
                // The pointer has been slid in to the new key, but the finger was not on any keys.
                // In this case, we must call onPress() to notify that the new key is being pressed.
                processDraggingFingerInToNewKey(newKey, x, y, eventTime)
            }
        } else { // newKey == null
            if (oldKey != null && isMajorEnoughMoveToBeOnNewKey(x, y, eventTime, newKey)) {
                dragFingerOutFromOldKey(oldKey, x, y)
            }
        }
        if (isInSlidingKeyInput) {
            drawingProxy.showSlidingKeyInputPreview(this)
        }
    }

    private fun onUpEvent(x: Int, y: Int, eventTime: Long) {
        if (DEBUG_EVENT) {
            printTouchEvent("onUpEvent  :", x, y, eventTime)
        }

        timerProxy.cancelUpdateBatchInputTimer(this)
        if (!inGesture) {
            val key = currentKey
            if (key != null && key.isModifier()) {
                // Before processing an up event of modifier key, all pointers already being
                // tracked should be released.
                pointerTrackerQueue.releaseAllPointersExcept(this, eventTime)
            } else {
                pointerTrackerQueue.releaseAllPointersOlderThan(this, eventTime)
            }
        }
        onUpEventInternal(x, y, eventTime)
        pointerTrackerQueue.remove(this)
    }

    // Let this pointer tracker know that one of newer-than-this pointer trackers got an up event.
    // This pointer tracker needs to keep the key top graphics "pressed", but needs to get a
    // "virtual" up event.
    override fun onPhantomUpEvent(eventTime: Long) {
        if (DEBUG_EVENT) {
            printTouchEvent("onPhntEvent:", lastX, lastY, eventTime)
        }
        onUpEventInternal(lastX, lastY, eventTime)
        cancelTrackingForAction()
    }

    private fun onUpEventInternal(x: Int, y: Int, eventTime: Long) {
        timerProxy.cancelKeyTimersOf(this)
        val wasInDraggingFinger = inDraggingFinger
        val wasInSlidingKeyInput = isInSlidingKeyInput
        resetKeySelectionByDraggingFinger()
        isDetectingGesture = false
        val releasedKey = currentKey
        currentKey = null
        val repeatingKeyCode = currentRepeatingKeyCode
        currentRepeatingKeyCode = Constants.NOT_A_CODE
        // Release the last pressed key.
        setReleasedKeyGraphics(releasedKey, true)

        if (inHorizontalSwipe && releasedKey!!.code == KeyCode.DELETE) {
            listener.onUpWithDeletePointerActive()
        }

        val panel = popupKeysPanel
        if (panel != null) {
            if (!isTrackingForActionDisabled) {
                // For KeyCode.TOGGLE_FLOATING_KEYBOARD, popupKeysPanel.onUpEvent will trigger a cancel MoveEvent, which will result
                // in PointerTrackerQueue.releaseAllPointersOlderThan, which calls onUpEventInternal, thus dispatching the key twice.
                // To prevent the duplicate input, we set popupKeysPanel null before calling onUpEvent, so isShowingPopupKeysPanel returns false
                popupKeysPanel = null
                val translatedX = panel.translateX(x)
                val translatedY = panel.translateY(y)
                panel.onUpEvent(translatedX, translatedY, pointerId, eventTime)
                panel.dismissPopupKeysPanel()
            }
            dismissPopupKeysPanel()
            if (wasInSlidingKeyInput)
                callListenerOnFinishSlidingInput()
            return
        }

        if (keySwipeAllowed) {
            keySwipeAllowed = false
            inKeySwipe = false

            // Touchpad mode
            touchpadHandler.disableTouchpadMode()

            if (inHorizontalSwipe || inVerticalSwipe) {
                inHorizontalSwipe = false
                inVerticalSwipe = false
                listener.onEndSpaceSwipe()
                return
            }
        }

        if (inGesture) {
            if (releasedKey != null) {
                callListenerOnRelease(releasedKey, releasedKey.code, true)
            }
            if (batchInputArbiter.mayEndBatchInput(
                    eventTime, getActivePointerTrackerCount(), this)) {
                inGesture = false
            }
            showGestureTrail()
            return
        }

        if (isTrackingForActionDisabled) {
            return
        }
        if (releasedKey != null && releasedKey.isRepeatable
                && (releasedKey.code == repeatingKeyCode) && !wasInDraggingFinger) {
            return
        }
        // Caret: a touch that starts on the space bar and leaves it upwards is the system's swipe home
        // (or a slip), not a space. Typed anyway it shows the other side of a chat that one is typing.
        if (releasedKey != null && releasedKey.code == Constants.CODE_SPACE && downY - y > releasedKey.height / 2)
            return
        detectAndSendKey(releasedKey, keyX, keyY, eventTime)
        if (wasInSlidingKeyInput) {
            callListenerOnFinishSlidingInput()
        }
    }

    override fun cancelTrackingForAction() {
        if (isShowingPopupKeysPanel) {
            return
        }
        isTrackingForActionDisabled = true
    }

    val isInOperation: Boolean
        get() {
            return !isTrackingForActionDisabled
        }

    fun onLongPressed() {
        timerProxy.cancelLongPressTimersOf(this)
        if (isShowingPopupKeysPanel) {
            return
        }
        val key = currentKey
        if (key == null) {
            return
        }
        val code = key.code
        listener.onLongPressKey(code)
        if (key.hasNoPanelAutoPopupKey()) {
            cancelKeyTracking()
            val popupKeyCode = key.popupKeys!![0].code
            listener.onPressKey(popupKeyCode, 0, 1, HapticEvent.NO_HAPTICS)
            listener.onCodeInput(popupKeyCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            listener.onReleaseKey(popupKeyCode, false)
            return
        }
        if (code == KeyCode.LANGUAGE_SWITCH
                || (code == Constants.CODE_SPACE && key.popupKeys == null && Settings.values.spaceForLangChange)
        ) {
            // Long pressing the space key invokes IME switcher dialog.
            if (listener.onCustomRequest(KeyboardActionListener.CustomAction.SHOW_INPUT_METHOD_PICKER)) {
                cancelKeyTracking()
                listener.onReleaseKey(code, false)
                return
            }
        }
        if (code == KeyCode.SYMBOL_ALPHA) {
            listener.onLongPressAlphaSymbolForNumpad()
            return
        }

        setReleasedKeyGraphics(key, false)
        val panel = drawingProxy.showPopupKeysKeyboard(key, this)
        if (panel == null) {
            return
        }
        val translatedX = panel.translateX(lastX)
        val translatedY = panel.translateY(lastY)
        panel.onDownEvent(translatedX, translatedY, pointerId, SystemClock.uptimeMillis())
        popupKeysPanel = panel
        if (keySwipeAllowed) {
            keySwipeAllowed = false
            inKeySwipe = false
        }
    }

    private fun cancelKeyTracking() {
        resetKeySelectionByDraggingFinger()
        cancelTrackingForAction()
        setReleasedKeyGraphics(currentKey, true)
        pointerTrackerQueue.remove(this)
    }

    private fun onCancelEvent(x: Int, y: Int, eventTime: Long) {
        if (DEBUG_EVENT) {
            printTouchEvent("onCancelEvt:", x, y, eventTime)
        }

        cancelBatchInput()
        cancelAllPointerTrackers()
        pointerTrackerQueue.releaseAllPointers(eventTime)
        onCancelEventInternal()
    }

    private fun onCancelEventInternal() {
        timerProxy.cancelKeyTimersOf(this)
        setReleasedKeyGraphics(currentKey, true)
        resetKeySelectionByDraggingFinger()
        dismissPopupKeysPanel()
    }

    private fun isMajorEnoughMoveToBeOnNewKey(x: Int, y: Int, eventTime: Long,
            newKey: Key?): Boolean {
        val curKey = currentKey
        if (newKey === curKey) {
            return false
        }
        if (curKey == null /* && newKey != null */) {
            return true
        }
        // Here curKey points to the different key from newKey.
        val keyHysteresisDistanceSquared = keyDetector.getKeyHysteresisDistanceSquared(isInSlidingKeyInput)
        val distanceFromKeyEdgeSquared = curKey.squaredDistanceToEdge(x, y)
        if (distanceFromKeyEdgeSquared >= keyHysteresisDistanceSquared) {
            val kb = keyboard
            if (DEBUG_MODE && kb != null) {
                val distanceToEdgeRatio = Math.sqrt(distanceFromKeyEdgeSquared.toDouble()).toFloat() / kb.mostCommonKeyWidth
                Log.d(TAG, String.format(Locale.US, "[%d] isMajorEnoughMoveToBeOnNewKey:"
                        +" %.2f key width from key edge", pointerId, distanceToEdgeRatio))
            }
            return true
        }
        if (!isAllowedDraggingFinger && typingTimeRecorder.isInFastTyping(eventTime)
                && bogusMoveEventDetector.hasTraveledLongDistance(x, y)) {
            val kb = keyboard
            if (DEBUG_MODE && kb != null) {
                val keyDiagonal = Math.hypot(kb.mostCommonKeyWidth.toDouble(), kb.mostCommonKeyHeight.toDouble()).toFloat()
                val lengthFromDownRatio = bogusMoveEventDetector.accumulatedDistanceFromDownKey / keyDiagonal
                Log.d(TAG, String.format(Locale.US, "[%d] isMajorEnoughMoveToBeOnNewKey:"
                        + " %.2f key diagonal from virtual down point", pointerId, lengthFromDownRatio))
            }
            return true
        }
        return false
    }

    private fun startLongPressTimer(key: Key?) {
        // Note that we need to cancel all active long press shift and symbol key timers if
        // any whenever we start a new long press timer for both non-shift and shift keys.
        timerProxy.cancelLongPressShiftKeyTimer()
        timerProxy.cancelLongPressAlphaSymbolKeyTimer()
        if (inGesture) return
        if (key == null) return
        if (!key.isLongPressEnabled) return
        // Caveat: Please note that isLongPressEnabled() can be true even if the current key
        // doesn't have its popup keys. (e.g. spacebar, globe key) If we are in the dragging finger
        // mode, we will disable long press timer of such key.
        // We always need to start the long press timer if the key has its popup keys regardless of
        // whether or not we are in the dragging finger mode.
        val code = key.code
        if (inDraggingFinger && (code == KeyCode.SHIFT || key.popupKeys == null)) return
        if (code == KeyCode.SHIFT && isShiftLongPressSuppressed) {
            isShiftLongPressSuppressed = false
            return
        }
        if (code == KeyCode.SYMBOL_ALPHA
            && (!Settings.values.longPressSymbolsForNumpad
                || keyboard?.id?.element != KeyboardElement.SYMBOLS
            )
        ) {
            return
        }

        val delay = getLongPressTimeout(code)
        if (delay <= 0) return
        timerProxy.startLongPressTimerOf(this, delay)
    }

    private fun getLongPressTimeout(code: Int): Int {
        val longpressTimeout = Settings.values.keyLongpressTimeout
        return when (code) {
            Constants.CODE_SPACE, KeyCode.SHIFT, KeyCode.SYMBOL_ALPHA
                // We use slightly longer timeout for space, shift-lock, and the numpad long-press.
                -> longpressTimeout * 3 / 2

            else -> if (isInSlidingKeyInput)
                // We use longer timeout for sliding finger input started from a modifier key.
                longpressTimeout * 3
            else
                longpressTimeout
        }
    }

    private fun isClearlyInsideKey(key: Key, x: Int, y: Int): Boolean {
        // less than 15% of width from edge
        return x > key.getX() + key.width * 0.15 && x < key.getX() + key.width * 0.85
                && y > key.getY() + key.height * 0.15 && y < key.getY() + key.height * 0.85
    }

    private fun detectAndSendKey(key: Key?, x: Int, y: Int, eventTime: Long) {
        if (key == null) {
            callListenerOnCancelInput()
            return
        }

        val code = key.code
        callListenerOnCodeInput(key, code, x, y, eventTime, false)
        callListenerOnRelease(key, code, false)
    }

    private fun startRepeatKey(key: Key?) {
        if (inGesture) return
        if (key == null) return
        if (!key.isRepeatable) return
        // Don't start key repeat when we are in the dragging finger mode.
        if (inDraggingFinger) return
        val startRepeatCount = 1
        startKeyRepeatTimer(startRepeatCount)
    }

    fun onKeyRepeat(code: Int, repeatCount: Int) {
        val key = currentKey
        if (key == null || key.code != code) {
            currentRepeatingKeyCode = Constants.NOT_A_CODE
            return
        }
        currentRepeatingKeyCode = code
        if (keySwipeAllowed) {
            keySwipeAllowed = false
            inKeySwipe = false
        }
        isDetectingGesture = false
        val nextRepeatCount = repeatCount + 1
        startKeyRepeatTimer(nextRepeatCount)
        callListenerOnPressAndCheckKeyboardLayoutChange(key, repeatCount)
        callListenerOnCodeInput(key, code, keyX, keyY, SystemClock.uptimeMillis(), true)
    }

    private fun startKeyRepeatTimer(repeatCount: Int) {
        val delay = if (repeatCount == 1) params.keyRepeatStartTimeout else params.keyRepeatInterval
        timerProxy.startKeyRepeatTimerOf(this, repeatCount, delay)
    }

    private fun printTouchEvent(title: String, x: Int, y: Int,
            eventTime: Long) {
        val key = keyDetector.detectHitKey(x, y)
        val code = (if (key == null) "none" else Constants.printableCode(key.code))
        Log.d(TAG, String.format(Locale.US, "[%d]%s%s %4d %4d %5d %s", pointerId,
                (if (isTrackingForActionDisabled) "-" else " "), title, x, y, eventTime, code))
    }

    companion object {
        private val TAG = PointerTracker::class.java.simpleName
        private const val DEBUG_EVENT = false
        private const val DEBUG_MOVE_EVENT = false
        private const val DEBUG_LISTENER = false
        private val DEBUG_MODE = DebugFlags.DEBUG_ENABLED || DEBUG_EVENT

        // map to store static objects that should be unique for each DrawingProxy (i.e. MainKeyboardView as of now)
        // this is a workaround, so we can have a MainKeyboardView in emoji and clipboard views too
        // but it will not allow two simultaneously displayed MainKeyboardViews
        private val proxyMap = WeakHashMap<DrawingProxy, Array<Any>>(4)

        // called when creating a new InputView
        // not sure why this is necessary... maybe misunderstanding regarding WeakHashMap?
        @JvmStatic
        fun clearOldViewData() {
            proxyMap.clear()
        }

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun switchTo(drawingProxy: DrawingProxy) {
            this.drawingProxy = drawingProxy
            val thatArray = proxyMap.get(drawingProxy)!! // if it's null, the view we're switching to should not exist
            params = thatArray[0] as PointerTrackerParams
            gestureStrokeRecognitionParams = thatArray[1] as GestureStrokeRecognitionParams
            gestureStrokeDrawingParams = thatArray[2] as GestureStrokeDrawingParams
            typingTimeRecorder = thatArray[3] as TypingTimeRecorder
            timerProxy = thatArray[4] as TimerProxy
            //noinspection unchecked
            trackers = thatArray[5] as ArrayList<PointerTracker>
        }

        private val gestureEnabler = GestureEnabler()

        // Parameters for pointer handling.
        private lateinit var params: PointerTrackerParams
        private val pointerStep = 10.dpToPx(Resources.getSystem())
        private lateinit var gestureStrokeRecognitionParams: GestureStrokeRecognitionParams
        private lateinit var gestureStrokeDrawingParams: GestureStrokeDrawingParams

        private var trackers = ArrayList<PointerTracker>()
        private val pointerTrackerQueue = PointerTrackerQueue()

        private lateinit var drawingProxy: DrawingProxy
        private lateinit var timerProxy: TimerProxy
        private var listener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER

        private var inGesture = false
        private lateinit var typingTimeRecorder: TypingTimeRecorder

        private var isShiftLongPressSuppressed = false

        private var inKeySwipe = false

        // TODO: Add PointerTrackerFactory singleton and move some class static methods into it.
        @JvmStatic
        fun init(mainKeyboardViewAttr: TypedArray, timerProxy: TimerProxy,
                drawingProxy: DrawingProxy) {
            params = PointerTrackerParams(mainKeyboardViewAttr)
            gestureStrokeRecognitionParams = GestureStrokeRecognitionParams(mainKeyboardViewAttr)
            gestureStrokeDrawingParams = GestureStrokeDrawingParams(mainKeyboardViewAttr)
            typingTimeRecorder = TypingTimeRecorder(
                    gestureStrokeRecognitionParams.staticTimeThresholdAfterFastTyping,
                    params.suppressKeyPreviewAfterBatchInputDuration)

            val res = mainKeyboardViewAttr.getResources()
            BogusMoveEventDetector.init(res)

            this.timerProxy = timerProxy
            this.drawingProxy = drawingProxy
            trackers = ArrayList()

            proxyMap.put(drawingProxy, arrayOf<Any>(
                    params,
                    gestureStrokeRecognitionParams,
                    gestureStrokeDrawingParams,
                    typingTimeRecorder,
                    timerProxy,
                    trackers
            ))
        }

        // Note that this method is called from a non-UI thread.
        @JvmStatic
        fun setMainDictionaryAvailability(mainDictionaryAvailable: Boolean) {
            gestureEnabler.setMainDictionaryAvailability(mainDictionaryAvailable)
        }

        @JvmStatic
        fun setGestureHandlingEnabledByUser(gestureHandlingEnabledByUser: Boolean) {
            gestureEnabler.setGestureHandlingEnabledByUser(gestureHandlingEnabledByUser)
        }

        @JvmStatic
        fun getPointerTracker(id: Int): PointerTracker {
            val trackerList = trackers

            // Create pointer trackers until we can get 'id+1'-th tracker, if needed.
            var i = trackerList.size
            while (i <= id) {
                val tracker = PointerTracker(i)
                trackerList.add(tracker)
                i++
            }

            return trackerList.get(id)
        }

        @JvmStatic
        fun isAnyInDraggingFinger(): Boolean {
            return pointerTrackerQueue.isAnyInDraggingFinger()
        }

        @JvmStatic
        fun cancelAllPointerTrackers() {
            pointerTrackerQueue.cancelAllPointerTrackers()
        }

        @JvmStatic
        fun suppressShiftLongPress() {
            isShiftLongPressSuppressed = true
        }

        @JvmStatic
        fun setKeyboardActionListener(listener: KeyboardActionListener) {
            this.listener = listener
        }

        @JvmStatic
        fun setKeyDetector(keyDetector: KeyDetector) {
            val keyboard = keyDetector.keyboard
            if (keyboard == null) {
                return
            }
            val trackersSize = trackers.size
            for (i in 0 until trackersSize) {
                val tracker = trackers.get(i)
                tracker.setKeyDetectorInner(keyDetector)
            }
            gestureEnabler.setPasswordMode(keyboard.id.isPasswordInput)
        }

        @JvmStatic
        fun setReleasedKeyGraphicsToAllKeys() {
            val trackersSize = trackers.size
            for (i in 0 until trackersSize) {
                val tracker = trackers.get(i)
                tracker.setReleasedKeyGraphics(tracker.key, true)
            }
        }

        @JvmStatic
        fun dismissAllPopupKeysPanels() {
            val trackersSize = trackers.size
            for (i in 0 until trackersSize) {
                val tracker = trackers.get(i)
                tracker.dismissPopupKeysPanel()
            }
        }

        private fun needsToSuppressKeyPreviewPopup(eventTime: Long): Boolean {
            if (!gestureEnabler.shouldHandleGesture()) return false
            return typingTimeRecorder.needsToSuppressKeyPreviewPopup(eventTime)
        }


        private fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
            return Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toInt()
        }

        /* package */ @JvmStatic
        fun getActivePointerTrackerCount(): Int {
            return pointerTrackerQueue.size()
        }
    }
}
