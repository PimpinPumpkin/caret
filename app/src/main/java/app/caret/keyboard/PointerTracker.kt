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
        @JvmField val mKeySelectionByDraggingFinger: Boolean
        @JvmField val mTouchNoiseThresholdTime: Int
        @JvmField val mTouchNoiseThresholdDistance: Int
        @JvmField val mSuppressKeyPreviewAfterBatchInputDuration: Int
        @JvmField val mKeyRepeatStartTimeout: Int
        @JvmField val mKeyRepeatInterval: Int

        init {
            mKeySelectionByDraggingFinger = mainKeyboardViewAttr.getBoolean(
                    R.styleable.MainKeyboardView_keySelectionByDraggingFinger, false)
            mTouchNoiseThresholdTime = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_touchNoiseThresholdTime, 0)
            mTouchNoiseThresholdDistance = mainKeyboardViewAttr.getDimensionPixelSize(
                    R.styleable.MainKeyboardView_touchNoiseThresholdDistance, 0)
            mSuppressKeyPreviewAfterBatchInputDuration = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_suppressKeyPreviewAfterBatchInputDuration, 0)
            mKeyRepeatStartTimeout = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_keyRepeatStartTimeout, 0)
            mKeyRepeatInterval = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_keyRepeatInterval, 0)
        }
    }

    @JvmField
    val mPointerId: Int

    // The {@link KeyDetector} is set whenever the down event is processed. Also this is updated
    // when new {@link Keyboard} is set by {@link #setKeyDetector(KeyDetector)}.
    private var mKeyDetector: KeyDetector = KeyDetector()
    private var mKeyboard: Keyboard? = null
    private val mBogusMoveEventDetector = BogusMoveEventDetector()

    private var mIsDetectingGesture = false // per PointerTracker.

    // The position and time at which first down event occurred.
    private var mDownTime: Long = 0
    private val mDownCoordinates: IntArray = CoordinateUtils.newInstance()
    private var mUpTime: Long = 0

    // The current key where this pointer is.
    private var mCurrentKey: Key? = null
    // The position where the current key was recognized for the first time.
    private var mKeyX: Int = 0
    private var mKeyY: Int = 0

    // Last pointer position.
    private var mLastX: Int = 0
    private var mLastY: Int = 0
    private var mStartX: Int = 0
    private var mStartY: Int = 0
    private var mStartTime: Long = 0
    private var mInHorizontalSwipe = false
    private var mInVerticalSwipe = false

    // true if keyboard layout has been changed.
    private var mKeyboardLayoutHasBeenChanged: Boolean = false
    private var keyboardChangeOccupiedHeightDifference: Int = 0

    // true if this pointer is no longer triggering any action because it has been canceled.
    private var mIsTrackingForActionDisabled: Boolean = false

    // the popup keys panel currently being shown. equals null if no panel is active.
    private var mPopupKeysPanel: PopupKeysPanel? = null

    // true if this pointer is in the dragging finger mode.
    private var mIsInDraggingFinger = false
    // true if this pointer is sliding from a modifier key and in the sliding key input mode,
    // so that further modifier keys should be ignored.
    private var mIsInSlidingKeyInput = false
    // if not a NOT_A_CODE, the key of this code is repeating
    private var mCurrentRepeatingKeyCode: Int = Constants.NOT_A_CODE

    // true if dragging finger is allowed.
    private var mIsAllowedDraggingFinger: Boolean = false
    // true if a keyswipe gesture is enabled and warranted.
    private var mKeySwipeAllowed = false

    // Touchpad mode for cursor control
    private val mTouchpadHandler = TouchpadHandler()

    private val mBatchInputArbiter: BatchInputArbiter
    private val mGestureStrokeDrawingPoints: GestureStrokeDrawingPoints

    init {
        mPointerId = id
        mBatchInputArbiter = BatchInputArbiter(id, sGestureStrokeRecognitionParams)
        mGestureStrokeDrawingPoints = GestureStrokeDrawingPoints(sGestureStrokeDrawingParams)
    }

    // Returns true if keyboard has been changed by this callback.
    private fun callListenerOnPressAndCheckKeyboardLayoutChange(key: Key, repeatCount: Int): Boolean {
        // While gesture input is going on, this method should be a no-operation. But when gesture
        // input has been canceled, <code>sInGesture</code> and <code>mIsDetectingGesture</code>
        // are set to false. To keep this method is a no-operation,
        // <code>mIsTrackingForActionDisabled</code> should also be taken account of.
        if (sInGesture || mIsDetectingGesture || mIsTrackingForActionDisabled) {
            return false
        }
        val ignoreModifierKey = mIsInDraggingFinger && key.isModifier()
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onPress    : %s%s%s%s", mPointerId,
                Constants.printableCode(key.getCode()),
                if (ignoreModifierKey) " ignoreModifier" else "",
                if (key.isEnabled()) "" else " disabled",
                if (repeatCount > 0) " repeatCount=" + repeatCount else "")
            )
        }
        if (ignoreModifierKey) {
            return false
        }
        if (key.isEnabled()) {
            val hapticEvent = if (repeatCount == 0) HapticEvent.KEY_PRESS else HapticEvent.KEY_REPEAT
            sListener.onPressKey(key.getCode(), repeatCount, getActivePointerTrackerCount(), hapticEvent)
            val keyboardLayoutHasBeenChanged = mKeyboardLayoutHasBeenChanged
            mKeyboardLayoutHasBeenChanged = false
            sTimerProxy.startTypingStateTimer(key)
            return keyboardLayoutHasBeenChanged
        }
        return false
    }

    // Note that we need primaryCode argument because the keyboard may in shifted state and the
    // primaryCode is different from {@link Key#mKeyCode}.
    private fun callListenerOnCodeInput(key: Key, primaryCode: Int, x: Int,
            y: Int, eventTime: Long, isKeyRepeat: Boolean) {
        val keyCode = key.getCode()
        val ignoreModifierKey = mIsInDraggingFinger && key.isModifier()
                && keyCode != KeyCode.NUMPAD && keyCode != KeyCode.DPAD // we allow these to be activated from sliding input
        val altersCode = key.altCodeWhileTyping() && sTimerProxy.isTypingState() && !isClearlyInsideKey(key, x, y)
        val code = if (altersCode) key.getAltCode() else primaryCode
        if (DEBUG_LISTENER) {
            val output = if (code == KeyCode.MULTIPLE_CODE_POINTS)
                    key.getOutputText() else Constants.printableCode(code)
            Log.d(TAG, String.format(Locale.US, "[%d] onCodeInput: %4d %4d %s%s%s%s", mPointerId, x, y,
                    output, if (ignoreModifierKey) " ignoreModifier" else "",
                    if (altersCode) " altersCode" else "", if (key.isEnabled()) "" else " disabled"))
        }
        if (ignoreModifierKey) {
            return
        }
        // Even if the key is disabled, it should respond if it is in the altCodeWhileTyping state.
        if (key.isEnabled() || altersCode) {
            sTypingTimeRecorder.onCodeInput(code, eventTime)
            if (code == KeyCode.MULTIPLE_CODE_POINTS) {
                sListener.onTextInput(key.getOutputText())
            } else if (code != KeyCode.NOT_SPECIFIED) {
                if (mKeyboard!!.hasProximityCharsCorrection(code)) {
                    sListener.onCodeInput(code, x, y, isKeyRepeat)
                } else {
                    sListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isKeyRepeat)
                }
            }
        }
    }

    // Note that we need primaryCode argument because the keyboard may be in shifted state and the
    // primaryCode is different from {@link Key#mKeyCode}.
    private fun callListenerOnRelease(key: Key, primaryCode: Int, withSliding: Boolean) {
        // See the comment at {@link #callListenerOnPressAndCheckKeyboardLayoutChange(Key}}.
        if (sInGesture || mIsDetectingGesture || mIsTrackingForActionDisabled) {
            return
        }
        val ignoreModifierKey = mIsInDraggingFinger && key.isModifier()
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onRelease  : %s%s%s%s", mPointerId,
                    Constants.printableCode(primaryCode),
                    if (withSliding) " sliding" else "", if (ignoreModifierKey) " ignoreModifier" else "",
                    if (key.isEnabled()) "" else " disabled"))
        }
        if (ignoreModifierKey) {
            return
        }
        if (key.isEnabled()) {
            sListener.onReleaseKey(primaryCode, withSliding)
        }
    }

    private fun callListenerOnFinishSlidingInput() {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onFinishSlidingInput", mPointerId))
        }
        sListener.onFinishSlidingInput()
    }

    private fun callListenerOnCancelInput() {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onCancelInput", mPointerId))
        }
        sListener.onCancelInput()
    }

    private fun setKeyDetectorInner(keyDetector: KeyDetector) {
        val keyboard = keyDetector.getKeyboard()
        if (keyboard == null) {
            return
        }
        if (keyDetector === mKeyDetector && keyboard === mKeyboard) {
            return
        }
        if (mKeyboard != null) {
            // changing keyboards may change height
            // since y is measured from top of view, this change needs to be considered in some places
            keyboardChangeOccupiedHeightDifference = keyboard.mOccupiedHeight - mKeyboard!!.mOccupiedHeight
        }
        mKeyDetector = keyDetector
        mKeyboard = keyboard
        // Mark that keyboard layout has been changed.
        mKeyboardLayoutHasBeenChanged = true
        val keyWidth = mKeyboard!!.mMostCommonKeyWidth
        val keyHeight = mKeyboard!!.mMostCommonKeyHeight
        mBatchInputArbiter.setKeyboardGeometry(keyWidth, mKeyboard!!.mOccupiedHeight)
        // Keep {@link #mCurrentKey} that comes from previous keyboard. The key preview of
        // {@link #mCurrentKey} will be dismissed by {@setReleasedKeyGraphics(Key)} via
        // {@link onMoveEventInternal(int,int,long)} or {@link #onUpEventInternal(int,int,long)}.
        mBogusMoveEventDetector.setKeyboardGeometry(keyWidth, keyHeight)
    }

    override fun isInDraggingFinger(): Boolean {
        return mIsInDraggingFinger
    }

    fun getKey(): Key? {
        return mCurrentKey
    }

    override fun isModifier(): Boolean {
        return mCurrentKey != null && mCurrentKey!!.isModifier()
    }

    fun getKeyOn(x: Int, y: Int): Key? {
        return mKeyDetector.detectHitKey(x, y)
    }

    private fun setReleasedKeyGraphics(key: Key?, withAnimation: Boolean) {
        if (key == null) {
            return
        }

        sDrawingProxy.onKeyReleased(key, withAnimation)

        if (key.isShift()) {
            for (shiftKey in mKeyboard!!.mShiftKeys) {
                if (shiftKey !== key) {
                    sDrawingProxy.onKeyReleased(shiftKey, false)
                }
            }
        }

        if (key.altCodeWhileTyping()) {
            val altCode = key.getAltCode()
            val altKey = mKeyboard!!.getKey(altCode)
            if (altKey != null) {
                sDrawingProxy.onKeyReleased(altKey, false)
            }
            for (k in mKeyboard!!.mAltCodeKeysWhileTyping) {
                if (k !== key && k.getAltCode() == altCode) {
                    sDrawingProxy.onKeyReleased(k, false)
                }
            }
        }
    }

    private fun setPressedKeyGraphics(key: Key?, eventTime: Long) {
        if (key == null) {
            return
        }

        // Even if the key is disabled, it should respond if it is in the altCodeWhileTyping state.
        val altersCode = key.altCodeWhileTyping() && sTimerProxy.isTypingState()
        val needsToUpdateGraphics = key.isEnabled() || altersCode
        if (!needsToUpdateGraphics) {
            return
        }

        val noKeyPreview = sInGesture || needsToSuppressKeyPreviewPopup(eventTime)
        sDrawingProxy.onKeyPressed(key, !noKeyPreview)

        if (key.isShift()) {
            for (shiftKey in mKeyboard!!.mShiftKeys) {
                if (shiftKey !== key) {
                    sDrawingProxy.onKeyPressed(shiftKey, false)
                }
            }
        }

        if (altersCode) {
            val altCode = key.getAltCode()
            val altKey = mKeyboard!!.getKey(altCode)
            if (altKey != null) {
                sDrawingProxy.onKeyPressed(altKey, false)
            }
            for (k in mKeyboard!!.mAltCodeKeysWhileTyping) {
                if (k !== key && k.getAltCode() == altCode) {
                    sDrawingProxy.onKeyPressed(k, false)
                }
            }
        }
    }


    fun getGestureStrokeDrawingPoints(): GestureStrokeDrawingPoints {
        return mGestureStrokeDrawingPoints
    }

    fun getLastCoordinates(outCoords: IntArray) {
        CoordinateUtils.set(outCoords, mLastX, mLastY)
    }

    fun getDownTime(): Long {
        return mDownTime
    }

    fun getDownCoordinates(outCoords: IntArray) {
        CoordinateUtils.copy(outCoords, mDownCoordinates)
    }

    private fun onDownKey(x: Int, y: Int, eventTime: Long): Key? {
        mDownTime = eventTime
        CoordinateUtils.set(mDownCoordinates, x, y)
        mBogusMoveEventDetector.onDownKey()
        return onMoveToNewKey(onMoveKeyInternal(x, y), x, y)
    }

    private fun onMoveKeyInternal(x: Int, y: Int): Key? {
        mBogusMoveEventDetector.onMoveKey(getDistance(x, y, mLastX, mLastY))
        mLastX = x
        mLastY = y
        return mKeyDetector.detectHitKey(x, y)
    }

    private fun onMoveKey(x: Int, y: Int): Key? {
        return onMoveKeyInternal(x, y)
    }

    private fun onMoveToNewKey(newKey: Key?, x: Int, y: Int): Key? {
        mCurrentKey = newKey
        mKeyX = x
        mKeyY = y
        return newKey
    }

    private fun isOldestTrackerInQueue(): Boolean {
        return sPointerTrackerQueue.getOldestElement() === this
    }

    // Implements {@link BatchInputArbiterListener}.
    override fun onStartBatchInput() {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onStartBatchInput", mPointerId))
        }
        sListener.onStartBatchInput()
        dismissAllPopupKeysPanels()
        sTimerProxy.cancelLongPressTimersOf(this)
    }

    private fun showGestureTrail() {
        if (mIsTrackingForActionDisabled) {
            return
        }
        // A gesture floating preview text will be shown at the oldest pointer/finger on the screen.
        sDrawingProxy.showGestureTrail(this, isOldestTrackerInQueue())
    }

    fun updateBatchInputByTimer(syntheticMoveEventTime: Long) {
        mBatchInputArbiter.updateBatchInputByTimer(syntheticMoveEventTime, this)
    }

    // Implements {@link BatchInputArbiterListener}.
    override fun onUpdateBatchInput(aggregatedPointers: InputPointers, eventTime: Long) {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onUpdateBatchInput: batchPoints=%d", mPointerId,
                    aggregatedPointers.getPointerSize()))
        }
        sListener.onUpdateBatchInput(aggregatedPointers)
    }

    // Implements {@link BatchInputArbiterListener}.
    override fun onStartUpdateBatchInputTimer() {
        sTimerProxy.startUpdateBatchInputTimer(this)
    }

    // Implements {@link BatchInputArbiterListener}.
    override fun onEndBatchInput(aggregatedPointers: InputPointers, eventTime: Long) {
        sTypingTimeRecorder.onEndBatchInput(eventTime)
        sTimerProxy.cancelAllUpdateBatchInputTimers()
        if (mIsTrackingForActionDisabled) {
            return
        }
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onEndBatchInput   : batchPoints=%d",
                    mPointerId, aggregatedPointers.getPointerSize()))
        }
        sListener.onEndBatchInput(aggregatedPointers)
    }

    private fun cancelBatchInput() {
        cancelAllPointerTrackers()
        mIsDetectingGesture = false
        if (!sInGesture) {
            return
        }
        sInGesture = false
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format(Locale.US, "[%d] onCancelBatchInput", mPointerId))
        }
        sListener.onCancelBatchInput()
    }

    fun processMotionEvent(me: MotionEvent, keyDetector: KeyDetector) {
        val action = me.getActionMasked()
        val eventTime = me.getEventTime()
        if (action == MotionEvent.ACTION_MOVE) {
            // When this pointer is the only active pointer and is showing a popup keys panel,
            // we should ignore other pointers' motion event.
            val shouldIgnoreOtherPointers =
                    isShowingPopupKeysPanel() && getActivePointerTrackerCount() == 1
            val pointerCount = me.getPointerCount()
            for (index in 0 until pointerCount) {
                val id = me.getPointerId(index)
                if (shouldIgnoreOtherPointers && id != mPointerId) {
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
            keyDetector: KeyDetector) {
        setKeyDetectorInner(keyDetector)
        if (DEBUG_EVENT) {
            printTouchEvent("onDownEvent:", x, y, eventTime)
        }
        // Naive up-to-down noise filter.
        val deltaT = eventTime - mUpTime
        if (deltaT < sParams.mTouchNoiseThresholdTime) {
            val distance = getDistance(x, y, mLastX, mLastY)
            if (distance < sParams.mTouchNoiseThresholdDistance) {
                if (DEBUG_MODE)
                    Log.w(TAG, String.format(Locale.US, "[%d] onDownEvent:"
                            + " ignore potential noise: time=%d distance=%d",
                            mPointerId, deltaT, distance))
                cancelTrackingForAction()
                return
            }
        }

        val key = getKeyOn(x, y)
        mBogusMoveEventDetector.onActualDownEvent(x, y)
        if (key != null && key.isModifier()) {
            if (sInGesture) {
                // Make sure not to interrupt an active gesture
                return
            } else {
                // Before processing a down event of modifier key, all pointers
                // already being tracked should be released.
                sPointerTrackerQueue.releaseAllPointers(eventTime)
            }
        }
        sPointerTrackerQueue.add(this)
        onDownEventInternal(x, y, eventTime)
        if (!sGestureEnabler.shouldHandleGesture()) {
            return
        }
        // A gesture should start only from a non-modifier key. Note that the gesture detection is
        // disabled when the key is repeating.
        mIsDetectingGesture = (mKeyboard != null) && mKeyboard!!.mId.element.isAlphabet
                && key != null && !key.isModifier() && !mKeySwipeAllowed && !sInKeySwipe
        if (mIsDetectingGesture) {
            mBatchInputArbiter.addDownEventPoint(x, y, eventTime,
                    sTypingTimeRecorder.getLastLetterTypingTime(), getActivePointerTrackerCount())
            mGestureStrokeDrawingPoints.onDownEvent(
                    x, y, mBatchInputArbiter.getElapsedTimeSinceFirstDown(eventTime))
        }
    }

    /* package */ fun isShowingPopupKeysPanel(): Boolean {
        return (mPopupKeysPanel != null)
    }

    private fun dismissPopupKeysPanel() {
        if (isShowingPopupKeysPanel()) {
            mPopupKeysPanel!!.dismissPopupKeysPanel()
            mPopupKeysPanel = null
        }
    }

    private fun onDownEventInternal(x: Int, y: Int, eventTime: Long) {
        var key = onDownKey(x, y, eventTime)
        // Key selection by dragging finger is allowed when 1) key selection by dragging finger is
        // enabled by configuration, 2) this pointer starts dragging from modifier key, or 3) this
        // pointer's KeyDetector always allows key selection by dragging finger, such as
        // {@link PopupKeysKeyboard}.
        mIsAllowedDraggingFinger = sParams.mKeySelectionByDraggingFinger
                || (key != null && key.isModifier())
                || mKeyDetector.alwaysAllowsKeySelectionByDraggingFinger()
        if (key != null && isSwiper(key.getCode()) && !sInGesture) {
            mKeySwipeAllowed = true
            sInKeySwipe = true
        }
        mKeyboardLayoutHasBeenChanged = false
        mIsTrackingForActionDisabled = false
        resetKeySelectionByDraggingFinger()
        if (key != null) {
            // This onPress call may have changed keyboard layout. Those cases are detected at
            // {@link #setKeyboard}. In those cases, we should update key according to the new
            // keyboard layout.
            // Also height difference between keyboards needs to be considered.
            if (callListenerOnPressAndCheckKeyboardLayoutChange(key, 0)) {
                val yOffset = keyboardChangeOccupiedHeightDifference
                keyboardChangeOccupiedHeightDifference = 0
                CoordinateUtils.set(mDownCoordinates, x, y + yOffset)
                key = onDownKey(x, y + yOffset, eventTime)
            }

            startRepeatKey(key)
            startLongPressTimer(key)
            setPressedKeyGraphics(key, eventTime)
            mStartX = x
            mStartY = y
            mStartTime = SystemClock.elapsedRealtime()
        }
    }

    private fun startKeySelectionByDraggingFinger(key: Key) {
        if (!mIsInDraggingFinger) {
            // the meta lock keys stay enabled after sliding input, but should not
            // (even without sliding input they actually behave the same... this is just about the graphics)
            val code = key.getCode()
            if (key.isModifier() && code != KeyCode.CTRL_LOCK && code != KeyCode.ALT_LOCK && code != KeyCode.FN_LOCK && code != KeyCode.META_LOCK) {
                val element = mKeyboard!!.mId.element
                mIsInSlidingKeyInput = !(code == KeyCode.SHIFT && (element == KeyboardElement.ALPHABET_SHIFT_LOCKED || element == KeyboardElement.DPAD))
            } else {
                mIsInSlidingKeyInput = false
            }
        }
        mIsInDraggingFinger = true
    }

    private fun resetKeySelectionByDraggingFinger() {
        mIsInDraggingFinger = false
        mIsInSlidingKeyInput = false
        sDrawingProxy.showSlidingKeyInputPreview(null)
    }

    private fun isSwiper(code: Int): Boolean {
        val sv = Settings.getValues()
        return when (code) {
            Constants.CODE_SPACE -> sv.mSpaceSwipeHorizontal != KeyboardActionListener.SwipeAction.NONE
                    || sv.mSpaceSwipeVertical != KeyboardActionListener.SwipeAction.NONE
            KeyCode.DELETE -> sv.mDeleteSwipeEnabled
            else -> false
        }
    }

    private fun onGestureMoveEvent(x: Int, y: Int, eventTime: Long,
            isMajorEvent: Boolean, key: Key?) {
        if (!mIsDetectingGesture || sInKeySwipe) {
            return
        }
        val onValidArea = mBatchInputArbiter.addMoveEventPoint(
                x, y, eventTime, isMajorEvent, this)
        // If the move event goes out from valid batch input area, cancel batch input.
        if (!onValidArea) {
            cancelBatchInput()
            return
        }
        mGestureStrokeDrawingPoints.onMoveEvent(
                x, y, mBatchInputArbiter.getElapsedTimeSinceFirstDown(eventTime))
        // If the PopupKeysPanel is showing then do not attempt to enter gesture mode. However,
        // the gestured touch points are still being recorded in case the panel is dismissed.
        if (isShowingPopupKeysPanel()) {
            return
        }
        if (!sInGesture && key != null && Character.isLetter(key.getCode())
                && mBatchInputArbiter.mayStartBatchInput(this)) {
            sListener.resetMetaState() // avoid metaState getting stuck, doesn't work with gesture typing anyway
            sInGesture = true
        }
        if (sInGesture) {
            if (key != null) {
                mBatchInputArbiter.updateBatchInput(eventTime, this)
            }
            showGestureTrail()
        }
    }

    private fun onMoveEvent(x: Int, y: Int, eventTime: Long, me: MotionEvent?) {
        if (DEBUG_MOVE_EVENT) {
            printTouchEvent("onMoveEvent:", x, y, eventTime)
        }
        if (mIsTrackingForActionDisabled) {
            return
        }

        if (sGestureEnabler.shouldHandleGesture() && me != null) {
            // Add historical points to gesture path.
            val pointerIndex = me.findPointerIndex(mPointerId)
            val historicalSize = me.getHistorySize()
            for (h in 0 until historicalSize) {
                val historicalX = me.getHistoricalX(pointerIndex, h).toInt()
                val historicalY = me.getHistoricalY(pointerIndex, h).toInt()
                val historicalTime = me.getHistoricalEventTime(h)
                onGestureMoveEvent(historicalX, historicalY, historicalTime, false, null)
            }
        }

        if (isShowingPopupKeysPanel()) {
            val translatedX = mPopupKeysPanel!!.translateX(x)
            val translatedY = mPopupKeysPanel!!.translateY(y)
            mPopupKeysPanel!!.onMoveEvent(translatedX, translatedY, mPointerId, eventTime)
            onMoveKey(x, y)
            if (mIsInSlidingKeyInput) {
                sDrawingProxy.showSlidingKeyInputPreview(this)
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
        if (mIsTrackingForActionDisabled) {
            return
        }
        startLongPressTimer(key)
        setPressedKeyGraphics(key, eventTime)
    }

    private fun processProximateBogusDownMoveUpEventHack(key: Key, x: Int, y: Int,
            eventTime: Long, oldKey: Key, lastX: Int, lastY: Int) {
        if (DEBUG_MODE) {
            val keyDiagonal = Math.hypot(
                    mKeyboard!!.mMostCommonKeyWidth.toDouble(), mKeyboard!!.mMostCommonKeyHeight.toDouble()).toFloat()
            val radiusRatio =
                    mBogusMoveEventDetector.getDistanceFromDownEvent(x, y) /
                    keyDiagonal
            Log.w(TAG, String.format(Locale.US, "[%d] onMoveEvent:"
                    + " bogus down-move-up event (raidus=%.2f key diagonal) is "
                    + " translated to up[%d,%d,%s]/down[%d,%d,%s] events",
                    mPointerId, radiusRatio,
                    lastX, lastY, Constants.printableCode(oldKey.getCode()),
                    x, y, Constants.printableCode(key.getCode())))
        }
        onUpEventInternal(x, y, eventTime)
        onDownEventInternal(x, y, eventTime)
    }

    private fun processDraggingFingerOutFromOldKey(oldKey: Key) {
        setReleasedKeyGraphics(oldKey, true)
        callListenerOnRelease(oldKey, oldKey.getCode(), true)
        startKeySelectionByDraggingFinger(oldKey)
        sTimerProxy.cancelKeyTimersOf(this)
    }

    private fun dragFingerFromOldKeyToNewKey(key: Key, x: Int, y: Int,
            eventTime: Long, oldKey: Key, lastX: Int, lastY: Int) {
        // The pointer has been slid in to the new key from the previous key, we must call
        // onRelease() first to notify that the previous key has been released, then call
        // onPress() to notify that the new key is being pressed.
        processDraggingFingerOutFromOldKey(oldKey)
        startRepeatKey(key)
        if (mIsAllowedDraggingFinger) {
            processDraggingFingerInToNewKey(key, x, y, eventTime)
        }
        // HACK: On some devices, quick successive proximate touches may be reported as a bogus
        // down-move-up event by touch panel firmware. This hack detects such cases and breaks
        // these events into separate up and down events.
        else if (sTypingTimeRecorder.isInFastTyping(eventTime)
                && mBogusMoveEventDetector.isCloseToActualDownEvent(x, y)) {
            processProximateBogusDownMoveUpEventHack(key, x, y, eventTime, oldKey, lastX, lastY)
        }
        // HACK: If there are currently multiple touches, register the key even if the finger
        // slides off the key. This defends against noise from some touch panels when there are
        // close multiple touches.
        // Caveat: When in chording input mode with a modifier key, we don't use this hack.
        else if (getActivePointerTrackerCount() > 1
                && !sPointerTrackerQueue.hasModifierKeyOlderThan(this)) {
            if (DEBUG_MODE) {
                Log.w(TAG, String.format(Locale.US, "[%d] onMoveEvent:"
                        + " detected sliding finger while multi touching", mPointerId))
            }
            onUpEvent(x, y, eventTime)
            cancelTrackingForAction()
            setReleasedKeyGraphics(oldKey, true)
        } else {
            if (!mIsDetectingGesture) {
                cancelTrackingForAction()
            }
            setReleasedKeyGraphics(oldKey, true)
        }
    }

    private fun dragFingerOutFromOldKey(oldKey: Key, x: Int, y: Int) {
        // The pointer has been slid out from the previous key, we must call onRelease() to
        // notify that the previous key has been released.
        processDraggingFingerOutFromOldKey(oldKey)
        if (mIsAllowedDraggingFinger) {
            onMoveToNewKey(null, x, y)
        } else {
            if (!mIsDetectingGesture) {
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
        val sv = Settings.getValues()
        val fastTypingTimeout = 2 * sv.mKeyLongpressTimeout / 3
        // we don't want keyswipes to start immediately if the user is fast-typing,
        // see https://github.com/openboard-team/openboard/issues/411
        // delete swipe is excluded because it already has a distance threshold,
        // see https://github.com/openboard-team/openboard/pull/566
        if (code != KeyCode.DELETE && SystemClock.elapsedRealtime() < mStartTime + fastTypingTimeout && sTypingTimeRecorder.isInFastTyping(eventTime))
            return
        if (code == Constants.CODE_SPACE) {
            val dX = x - mStartX
            val dY = y - mStartY

            // Touchpad mode
            mTouchpadHandler.enableTouchpadMove(x, y, sListener)

            // Vertical movement
            val stepsY = dY / sPointerStep
            if (stepsY != 0 && abs(dX) < abs(dY) && !mInHorizontalSwipe) {
                if (!mInVerticalSwipe) {
                    sTimerProxy.cancelKeyTimersOf(this)
                    mInVerticalSwipe = true
                } else if (oneShotSwipe(sv.mSpaceSwipeVertical)) return
                if (sListener.onVerticalSpaceSwipe(stepsY)) {
                    mStartY += stepsY * sPointerStep
                }
                return
            }

            // Horizontal movement
            val stepsX = dX / sPointerStep
            if (stepsX != 0 && !mInVerticalSwipe) {
                if (!mInHorizontalSwipe) {
                    sTimerProxy.cancelKeyTimersOf(this)
                    mInHorizontalSwipe = true
                } else if (oneShotSwipe(sv.mSpaceSwipeHorizontal)) return
                if (sListener.onHorizontalSpaceSwipe(stepsX)) {
                    mStartX += stepsX * sPointerStep
                }
            }
        } else if (code == KeyCode.DELETE) {
            // Delete slider
            val steps = (x - mStartX) / sPointerStep
            if (steps != 0) {
                if (!mInHorizontalSwipe) {
                    sTimerProxy.cancelKeyTimersOf(this)
                    mInHorizontalSwipe = true
                }
                mStartX += steps * sPointerStep
                sListener.onMoveDeletePointer(steps)
            }
        }
    }

    private fun onMoveEventInternal(x: Int, y: Int, eventTime: Long) {
        val oldKey = mCurrentKey

        // todo (later): move key swipe stuff to KeyboardActionListener (and finally extend it)
        if (mKeySwipeAllowed) {
            onKeySwipe(oldKey!!.getCode(), x, y, eventTime)
            return
        }

        val newKey = onMoveKey(x, y)
        val lastX = mLastX
        val lastY = mLastY

        if (sGestureEnabler.shouldHandleGesture()) {
            // Register move event on gesture tracker.
            onGestureMoveEvent(x, y, eventTime, true, newKey)
            if (sInGesture) {
                mCurrentKey = null
                setReleasedKeyGraphics(oldKey, true)
                return
            }
        }

        if (newKey != null) {
            if (oldKey != null && isMajorEnoughMoveToBeOnNewKey(x, y, eventTime, newKey)) {
                dragFingerFromOldKeyToNewKey(newKey, x, y, eventTime, oldKey, lastX, lastY)
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
        if (mIsInSlidingKeyInput) {
            sDrawingProxy.showSlidingKeyInputPreview(this)
        }
    }

    private fun onUpEvent(x: Int, y: Int, eventTime: Long) {
        if (DEBUG_EVENT) {
            printTouchEvent("onUpEvent  :", x, y, eventTime)
        }

        sTimerProxy.cancelUpdateBatchInputTimer(this)
        if (!sInGesture) {
            if (mCurrentKey != null && mCurrentKey!!.isModifier()) {
                // Before processing an up event of modifier key, all pointers already being
                // tracked should be released.
                sPointerTrackerQueue.releaseAllPointersExcept(this, eventTime)
            } else {
                sPointerTrackerQueue.releaseAllPointersOlderThan(this, eventTime)
            }
        }
        onUpEventInternal(x, y, eventTime)
        sPointerTrackerQueue.remove(this)
    }

    // Let this pointer tracker know that one of newer-than-this pointer trackers got an up event.
    // This pointer tracker needs to keep the key top graphics "pressed", but needs to get a
    // "virtual" up event.
    override fun onPhantomUpEvent(eventTime: Long) {
        if (DEBUG_EVENT) {
            printTouchEvent("onPhntEvent:", mLastX, mLastY, eventTime)
        }
        onUpEventInternal(mLastX, mLastY, eventTime)
        cancelTrackingForAction()
    }

    private fun onUpEventInternal(x: Int, y: Int, eventTime: Long) {
        sTimerProxy.cancelKeyTimersOf(this)
        val isInDraggingFinger = mIsInDraggingFinger
        val isInSlidingKeyInput = mIsInSlidingKeyInput
        resetKeySelectionByDraggingFinger()
        mIsDetectingGesture = false
        val currentKey = mCurrentKey
        mCurrentKey = null
        val currentRepeatingKeyCode = mCurrentRepeatingKeyCode
        mCurrentRepeatingKeyCode = Constants.NOT_A_CODE
        // Release the last pressed key.
        setReleasedKeyGraphics(currentKey, true)

        if (mInHorizontalSwipe && currentKey!!.getCode() == KeyCode.DELETE) {
            sListener.onUpWithDeletePointerActive()
        }

        if (isShowingPopupKeysPanel()) {
            if (!mIsTrackingForActionDisabled) {
                // For KeyCode.TOGGLE_FLOATING_KEYBOARD, mPopupKeysPanel.onUpEvent will trigger a cancel MoveEvent, which will result
                // in PointerTrackerQueue.releaseAllPointersOlderThan, which calls onUpEventInternal, thus dispatching the key twice.
                // To prevent the duplicate input, we set mPopupKeysPanel null before calling onUpEvent, so isShowingPopupKeysPanel returns false
                val panel = mPopupKeysPanel
                mPopupKeysPanel = null
                val translatedX = panel!!.translateX(x)
                val translatedY = panel.translateY(y)
                panel.onUpEvent(translatedX, translatedY, mPointerId, eventTime)
                panel.dismissPopupKeysPanel()
            }
            dismissPopupKeysPanel()
            if (isInSlidingKeyInput)
                callListenerOnFinishSlidingInput()
            return
        }

        if (mKeySwipeAllowed) {
            mKeySwipeAllowed = false
            sInKeySwipe = false

            // Touchpad mode
            mTouchpadHandler.disableTouchpadMode()

            if (mInHorizontalSwipe || mInVerticalSwipe) {
                mInHorizontalSwipe = false
                mInVerticalSwipe = false
                sListener.onEndSpaceSwipe()
                return
            }
        }

        if (sInGesture) {
            if (currentKey != null) {
                callListenerOnRelease(currentKey, currentKey.getCode(), true)
            }
            if (mBatchInputArbiter.mayEndBatchInput(
                    eventTime, getActivePointerTrackerCount(), this)) {
                sInGesture = false
            }
            showGestureTrail()
            return
        }

        if (mIsTrackingForActionDisabled) {
            return
        }
        if (currentKey != null && currentKey.isRepeatable()
                && (currentKey.getCode() == currentRepeatingKeyCode) && !isInDraggingFinger) {
            return
        }
        detectAndSendKey(currentKey, mKeyX, mKeyY, eventTime)
        if (isInSlidingKeyInput) {
            callListenerOnFinishSlidingInput()
        }
    }

    override fun cancelTrackingForAction() {
        if (isShowingPopupKeysPanel()) {
            return
        }
        mIsTrackingForActionDisabled = true
    }

    val isInOperation: Boolean
        get() {
            return !mIsTrackingForActionDisabled
        }

    fun onLongPressed() {
        sTimerProxy.cancelLongPressTimersOf(this)
        if (isShowingPopupKeysPanel()) {
            return
        }
        val key = getKey()
        if (key == null) {
            return
        }
        val code = key.getCode()
        sListener.onLongPressKey(code)
        if (key.hasNoPanelAutoPopupKey()) {
            cancelKeyTracking()
            val popupKeyCode = key.getPopupKeys()!![0].mCode
            sListener.onPressKey(popupKeyCode, 0, 1, HapticEvent.NO_HAPTICS)
            sListener.onCodeInput(popupKeyCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            sListener.onReleaseKey(popupKeyCode, false)
            return
        }
        if (code == KeyCode.LANGUAGE_SWITCH
                || (code == Constants.CODE_SPACE && key.getPopupKeys() == null && Settings.getValues().mSpaceForLangChange)
        ) {
            // Long pressing the space key invokes IME switcher dialog.
            if (sListener.onCustomRequest(KeyboardActionListener.CustomAction.SHOW_INPUT_METHOD_PICKER)) {
                cancelKeyTracking()
                sListener.onReleaseKey(code, false)
                return
            }
        }
        if (code == KeyCode.SYMBOL_ALPHA) {
            sListener.onLongPressAlphaSymbolForNumpad()
            return
        }

        setReleasedKeyGraphics(key, false)
        val popupKeysPanel = sDrawingProxy.showPopupKeysKeyboard(key, this)
        if (popupKeysPanel == null) {
            return
        }
        val translatedX = popupKeysPanel.translateX(mLastX)
        val translatedY = popupKeysPanel.translateY(mLastY)
        popupKeysPanel.onDownEvent(translatedX, translatedY, mPointerId, SystemClock.uptimeMillis())
        mPopupKeysPanel = popupKeysPanel
        if (mKeySwipeAllowed) {
            mKeySwipeAllowed = false
            sInKeySwipe = false
        }
    }

    private fun cancelKeyTracking() {
        resetKeySelectionByDraggingFinger()
        cancelTrackingForAction()
        setReleasedKeyGraphics(mCurrentKey, true)
        sPointerTrackerQueue.remove(this)
    }

    private fun onCancelEvent(x: Int, y: Int, eventTime: Long) {
        if (DEBUG_EVENT) {
            printTouchEvent("onCancelEvt:", x, y, eventTime)
        }

        cancelBatchInput()
        cancelAllPointerTrackers()
        sPointerTrackerQueue.releaseAllPointers(eventTime)
        onCancelEventInternal()
    }

    private fun onCancelEventInternal() {
        sTimerProxy.cancelKeyTimersOf(this)
        setReleasedKeyGraphics(mCurrentKey, true)
        resetKeySelectionByDraggingFinger()
        dismissPopupKeysPanel()
    }

    private fun isMajorEnoughMoveToBeOnNewKey(x: Int, y: Int, eventTime: Long,
            newKey: Key?): Boolean {
        val curKey = mCurrentKey
        if (newKey === curKey) {
            return false
        }
        if (curKey == null /* && newKey != null */) {
            return true
        }
        // Here curKey points to the different key from newKey.
        val keyHysteresisDistanceSquared = mKeyDetector.getKeyHysteresisDistanceSquared(mIsInSlidingKeyInput)
        val distanceFromKeyEdgeSquared = curKey.squaredDistanceToEdge(x, y)
        if (distanceFromKeyEdgeSquared >= keyHysteresisDistanceSquared) {
            if (DEBUG_MODE) {
                val distanceToEdgeRatio = Math.sqrt(distanceFromKeyEdgeSquared.toDouble()).toFloat() / mKeyboard!!.mMostCommonKeyWidth
                Log.d(TAG, String.format(Locale.US, "[%d] isMajorEnoughMoveToBeOnNewKey:"
                        +" %.2f key width from key edge", mPointerId, distanceToEdgeRatio))
            }
            return true
        }
        if (!mIsAllowedDraggingFinger && sTypingTimeRecorder.isInFastTyping(eventTime)
                && mBogusMoveEventDetector.hasTraveledLongDistance(x, y)) {
            if (DEBUG_MODE) {
                val keyDiagonal = Math.hypot(mKeyboard!!.mMostCommonKeyWidth.toDouble(), mKeyboard!!.mMostCommonKeyHeight.toDouble()).toFloat()
                val lengthFromDownRatio = mBogusMoveEventDetector.getAccumulatedDistanceFromDownKey() / keyDiagonal
                Log.d(TAG, String.format(Locale.US, "[%d] isMajorEnoughMoveToBeOnNewKey:"
                        + " %.2f key diagonal from virtual down point", mPointerId, lengthFromDownRatio))
            }
            return true
        }
        return false
    }

    private fun startLongPressTimer(key: Key?) {
        // Note that we need to cancel all active long press shift and symbol key timers if
        // any whenever we start a new long press timer for both non-shift and shift keys.
        sTimerProxy.cancelLongPressShiftKeyTimer()
        sTimerProxy.cancelLongPressAlphaSymbolKeyTimer()
        if (sInGesture) return
        if (key == null) return
        if (!key.isLongPressEnabled()) return
        // Caveat: Please note that isLongPressEnabled() can be true even if the current key
        // doesn't have its popup keys. (e.g. spacebar, globe key) If we are in the dragging finger
        // mode, we will disable long press timer of such key.
        // We always need to start the long press timer if the key has its popup keys regardless of
        // whether or not we are in the dragging finger mode.
        val code = key.getCode()
        if (mIsInDraggingFinger && (code == KeyCode.SHIFT || key.getPopupKeys() == null)) return
        if (code == KeyCode.SHIFT && sIsShiftLongPressSuppressed) {
            sIsShiftLongPressSuppressed = false
            return
        }
        if (code == KeyCode.SYMBOL_ALPHA
            && (!Settings.getValues().mLongPressSymbolsForNumpad
                || mKeyboard!!.mId.element != KeyboardElement.SYMBOLS
            )
        ) {
            return
        }

        val delay = getLongPressTimeout(code)
        if (delay <= 0) return
        sTimerProxy.startLongPressTimerOf(this, delay)
    }

    private fun getLongPressTimeout(code: Int): Int {
        val longpressTimeout = Settings.getValues().mKeyLongpressTimeout
        return when (code) {
            Constants.CODE_SPACE, KeyCode.SHIFT, KeyCode.SYMBOL_ALPHA
                // We use slightly longer timeout for space, shift-lock, and the numpad long-press.
                -> longpressTimeout * 3 / 2

            else -> if (mIsInSlidingKeyInput)
                // We use longer timeout for sliding finger input started from a modifier key.
                longpressTimeout * 3
            else
                longpressTimeout
        }
    }

    private fun isClearlyInsideKey(key: Key, x: Int, y: Int): Boolean {
        // less than 15% of width from edge
        return x > key.getX() + key.getWidth() * 0.15 && x < key.getX() + key.getWidth() * 0.85
                && y > key.getY() + key.getHeight() * 0.15 && y < key.getY() + key.getHeight() * 0.85
    }

    private fun detectAndSendKey(key: Key?, x: Int, y: Int, eventTime: Long) {
        if (key == null) {
            callListenerOnCancelInput()
            return
        }

        val code = key.getCode()
        callListenerOnCodeInput(key, code, x, y, eventTime, false)
        callListenerOnRelease(key, code, false)
    }

    private fun startRepeatKey(key: Key?) {
        if (sInGesture) return
        if (key == null) return
        if (!key.isRepeatable()) return
        // Don't start key repeat when we are in the dragging finger mode.
        if (mIsInDraggingFinger) return
        val startRepeatCount = 1
        startKeyRepeatTimer(startRepeatCount)
    }

    fun onKeyRepeat(code: Int, repeatCount: Int) {
        val key = getKey()
        if (key == null || key.getCode() != code) {
            mCurrentRepeatingKeyCode = Constants.NOT_A_CODE
            return
        }
        mCurrentRepeatingKeyCode = code
        if (mKeySwipeAllowed) {
            mKeySwipeAllowed = false
            sInKeySwipe = false
        }
        mIsDetectingGesture = false
        val nextRepeatCount = repeatCount + 1
        startKeyRepeatTimer(nextRepeatCount)
        callListenerOnPressAndCheckKeyboardLayoutChange(key, repeatCount)
        callListenerOnCodeInput(key, code, mKeyX, mKeyY, SystemClock.uptimeMillis(), true)
    }

    private fun startKeyRepeatTimer(repeatCount: Int) {
        val delay = if (repeatCount == 1) sParams.mKeyRepeatStartTimeout else sParams.mKeyRepeatInterval
        sTimerProxy.startKeyRepeatTimerOf(this, repeatCount, delay)
    }

    private fun printTouchEvent(title: String, x: Int, y: Int,
            eventTime: Long) {
        val key = mKeyDetector.detectHitKey(x, y)
        val code = (if (key == null) "none" else Constants.printableCode(key.getCode()))
        Log.d(TAG, String.format(Locale.US, "[%d]%s%s %4d %4d %5d %s", mPointerId,
                (if (mIsTrackingForActionDisabled) "-" else " "), title, x, y, eventTime, code))
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
        private val sProxyMap = WeakHashMap<DrawingProxy, Array<Any>>(4)

        // called when creating a new InputView
        // not sure why this is necessary... maybe misunderstanding regarding WeakHashMap?
        @JvmStatic
        fun clearOldViewData() {
            sProxyMap.clear()
        }

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun switchTo(drawingProxy: DrawingProxy) {
            sDrawingProxy = drawingProxy
            val thatArray = sProxyMap.get(drawingProxy) // if it's null, the view we're switching to should not exist
            sParams = thatArray!![0] as PointerTrackerParams
            sGestureStrokeRecognitionParams = thatArray[1] as GestureStrokeRecognitionParams
            sGestureStrokeDrawingParams = thatArray[2] as GestureStrokeDrawingParams
            sTypingTimeRecorder = thatArray[3] as TypingTimeRecorder
            sTimerProxy = thatArray[4] as TimerProxy
            //noinspection unchecked
            sTrackers = thatArray[5] as ArrayList<PointerTracker>
        }

        private val sGestureEnabler = GestureEnabler()

        // Parameters for pointer handling.
        private lateinit var sParams: PointerTrackerParams
        private val sPointerStep = 10.dpToPx(Resources.getSystem())
        private lateinit var sGestureStrokeRecognitionParams: GestureStrokeRecognitionParams
        private lateinit var sGestureStrokeDrawingParams: GestureStrokeDrawingParams

        private var sTrackers = ArrayList<PointerTracker>()
        private val sPointerTrackerQueue = PointerTrackerQueue()

        private lateinit var sDrawingProxy: DrawingProxy
        private lateinit var sTimerProxy: TimerProxy
        private var sListener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER

        private var sInGesture = false
        private lateinit var sTypingTimeRecorder: TypingTimeRecorder

        private var sIsShiftLongPressSuppressed = false

        private var sInKeySwipe = false

        // TODO: Add PointerTrackerFactory singleton and move some class static methods into it.
        @JvmStatic
        fun init(mainKeyboardViewAttr: TypedArray, timerProxy: TimerProxy,
                drawingProxy: DrawingProxy) {
            sParams = PointerTrackerParams(mainKeyboardViewAttr)
            sGestureStrokeRecognitionParams = GestureStrokeRecognitionParams(mainKeyboardViewAttr)
            sGestureStrokeDrawingParams = GestureStrokeDrawingParams(mainKeyboardViewAttr)
            sTypingTimeRecorder = TypingTimeRecorder(
                    sGestureStrokeRecognitionParams.mStaticTimeThresholdAfterFastTyping,
                    sParams.mSuppressKeyPreviewAfterBatchInputDuration)

            val res = mainKeyboardViewAttr.getResources()
            BogusMoveEventDetector.init(res)

            sTimerProxy = timerProxy
            sDrawingProxy = drawingProxy
            sTrackers = ArrayList()

            sProxyMap.put(drawingProxy, arrayOf<Any>(
                    sParams,
                    sGestureStrokeRecognitionParams,
                    sGestureStrokeDrawingParams,
                    sTypingTimeRecorder,
                    sTimerProxy,
                    sTrackers
            ))
        }

        // Note that this method is called from a non-UI thread.
        @JvmStatic
        fun setMainDictionaryAvailability(mainDictionaryAvailable: Boolean) {
            sGestureEnabler.setMainDictionaryAvailability(mainDictionaryAvailable)
        }

        @JvmStatic
        fun setGestureHandlingEnabledByUser(gestureHandlingEnabledByUser: Boolean) {
            sGestureEnabler.setGestureHandlingEnabledByUser(gestureHandlingEnabledByUser)
        }

        @JvmStatic
        fun getPointerTracker(id: Int): PointerTracker {
            val trackers = sTrackers

            // Create pointer trackers until we can get 'id+1'-th tracker, if needed.
            var i = trackers.size
            while (i <= id) {
                val tracker = PointerTracker(i)
                trackers.add(tracker)
                i++
            }

            return trackers.get(id)
        }

        @JvmStatic
        fun isAnyInDraggingFinger(): Boolean {
            return sPointerTrackerQueue.isAnyInDraggingFinger()
        }

        @JvmStatic
        fun cancelAllPointerTrackers() {
            sPointerTrackerQueue.cancelAllPointerTrackers()
        }

        @JvmStatic
        fun suppressShiftLongPress() {
            sIsShiftLongPressSuppressed = true
        }

        @JvmStatic
        fun setKeyboardActionListener(listener: KeyboardActionListener) {
            sListener = listener
        }

        @JvmStatic
        fun setKeyDetector(keyDetector: KeyDetector) {
            val keyboard = keyDetector.getKeyboard()
            if (keyboard == null) {
                return
            }
            val trackersSize = sTrackers.size
            for (i in 0 until trackersSize) {
                val tracker = sTrackers.get(i)
                tracker.setKeyDetectorInner(keyDetector)
            }
            sGestureEnabler.setPasswordMode(keyboard.mId.isPasswordInput)
        }

        @JvmStatic
        fun setReleasedKeyGraphicsToAllKeys() {
            val trackersSize = sTrackers.size
            for (i in 0 until trackersSize) {
                val tracker = sTrackers.get(i)
                tracker.setReleasedKeyGraphics(tracker.getKey(), true)
            }
        }

        @JvmStatic
        fun dismissAllPopupKeysPanels() {
            val trackersSize = sTrackers.size
            for (i in 0 until trackersSize) {
                val tracker = sTrackers.get(i)
                tracker.dismissPopupKeysPanel()
            }
        }

        private fun needsToSuppressKeyPreviewPopup(eventTime: Long): Boolean {
            if (!sGestureEnabler.shouldHandleGesture()) return false
            return sTypingTimeRecorder.needsToSuppressKeyPreviewPopup(eventTime)
        }


        private fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
            return Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toInt()
        }

        /* package */ @JvmStatic
        fun getActivePointerTrackerCount(): Int {
            return sPointerTrackerQueue.size()
        }
    }
}
