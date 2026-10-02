/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.util.AttributeSet
import android.widget.LinearLayout
import app.caret.keyboard.PopupTextView
import app.caret.latin.utils.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.accessibility.AccessibilityEvent

import android.widget.FrameLayout

import app.caret.accessibility.AccessibilityUtils
import app.caret.accessibility.KeyboardAccessibilityDelegate
import app.caret.keyboard.Key
import app.caret.keyboard.KeyDetector
import app.caret.keyboard.Keyboard
import app.caret.keyboard.KeyboardView
import app.caret.keyboard.PopupKeysKeyboard
import app.caret.keyboard.PopupKeysKeyboardView
import app.caret.keyboard.PopupKeysPanel
import app.caret.latin.R
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.settings.Settings

import java.util.WeakHashMap

/**
 * This is an extended {@link KeyboardView} class that hosts an emoji page keyboard.
 * Multi-touch unsupported. No gesture support.
 */
class EmojiPageKeyboardView : KeyboardView, PopupKeysPanel.Controller {

    private var mEmojiViewCallback: EmojiViewCallback = EMPTY_EMOJI_VIEW_CALLBACK
    private val keyDetector = KeyDetector()
    private var mAccessibilityDelegate: KeyboardAccessibilityDelegate<EmojiPageKeyboardView>? = null

    // Touch inputs
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0
    private var lastY = 0
    private var mCurrentKey: Key? = null
    private var mPendingKeyDown: Runnable? = null
    private var mPendingLongPress: Runnable? = null
    private val handler: Handler

    // More keys keyboard
    private val popupKeysKeyboardContainer: View
    private val descriptionView: PopupTextView
    private val popupKeysKeyboardView: PopupKeysKeyboardView
    private val popupKeysKeyboardCache = WeakHashMap<Key, Keyboard>()
    private val configShowPopupKeysKeyboardAtTouchedPoint: Boolean
    private val popupKeysPlacerView: ViewGroup
    // More keys panel (used by popup keys keyboard view)
    // TODO: Consider extending to support multiple popup keys panels
    private var mPopupKeysPanel: PopupKeysPanel? = null

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.keyboardViewStyle)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        handler = Handler()

        popupKeysPlacerView = FrameLayout(context, attrs)

        val keyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.MainKeyboardView, defStyle, R.style.MainKeyboardView)
        val popupKeysKeyboardLayoutId = keyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_popupKeysKeyboardLayout, 0)
        configShowPopupKeysKeyboardAtTouchedPoint = keyboardViewAttr.getBoolean(
                R.styleable.MainKeyboardView_showPopupKeysKeyboardAtTouchedPoint, false)
        keyboardViewAttr.recycle()

        val inflater = LayoutInflater.from(getContext())
        popupKeysKeyboardContainer = inflater.inflate(popupKeysKeyboardLayoutId, null)
        descriptionView = popupKeysKeyboardContainer.findViewById(R.id.description_view)
        popupKeysKeyboardView = popupKeysKeyboardContainer.findViewById(R.id.popup_keys_keyboard_view)
        setFitsSystemWindows(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keyboard = getKeyboard()
        if (keyboard is DynamicGridKeyboard) {
            val width = keyboard.occupiedWidth + getPaddingLeft() + getPaddingRight()
            val occupiedHeight = keyboard.getOccupiedHeight()
            val height = occupiedHeight + getPaddingTop() + getPaddingBottom()
            setMeasuredDimension(width, height)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        super.setHardwareAcceleratedDrawingEnabled(enabled)
        if (!enabled) return
        val layerPaint = Paint()
        layerPaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_OVER))
        popupKeysPlacerView.setLayerType(View.LAYER_TYPE_HARDWARE, layerPaint)
    }

    private fun installPopupKeysPlacerView(uninstall: Boolean) {
        val rootView: View? = getRootView()
        if (rootView == null) {
            Log.w(TAG, "Cannot find root view")
            return
        }
        val windowContentView: ViewGroup? = rootView.findViewById(android.R.id.content)
        // Note: It'd be very weird if we get null by android.R.id.content.
        if (windowContentView == null) {
            Log.w(TAG, "Cannot find android.R.id.content view to add DrawingPreviewPlacerView")
            return
        }

        if (uninstall) {
            windowContentView.removeView(popupKeysPlacerView)
        } else {
            windowContentView.addView(popupKeysPlacerView)
        }
    }

    fun setEmojiViewCallback(emojiViewCallback: EmojiViewCallback) {
        mEmojiViewCallback = emojiViewCallback
    }

    /**
     * {@inheritDoc}
     */
    override fun setKeyboard(keyboard: Keyboard) {
        super.setKeyboard(keyboard)
        keyDetector.setKeyboard(keyboard, 0f /* correctionX */, 0f /* correctionY */)
        popupKeysKeyboardCache.clear()
        if (AccessibilityUtils.instance.isAccessibilityEnabled) {
            if (mAccessibilityDelegate == null) {
                mAccessibilityDelegate = KeyboardAccessibilityDelegate(this, keyDetector)
            }
            mAccessibilityDelegate!!.keyboard = keyboard
        } else {
            mAccessibilityDelegate = null
        }
    }

    private fun showPopupKeysKeyboard(key: Key): PopupKeysPanel? {
        popupKeysKeyboardView.setVisibility(View.GONE)
        val popupKeys = key.getPopupKeys()
        if (popupKeys == null) {
            return null
        }
        var popupKeysKeyboard: Keyboard? = popupKeysKeyboardCache.get(key)
        if (popupKeysKeyboard == null) {
            val builder = PopupKeysKeyboard.Builder(
                    getContext(), key, getKeyboard(), false, 0, 0, newLabelPaint(key))
            popupKeysKeyboard = builder.build()
            popupKeysKeyboardCache.put(key, popupKeysKeyboard)
        }

        popupKeysKeyboardView.setKeyboard(popupKeysKeyboard!!)
        popupKeysKeyboardView.setVisibility(View.VISIBLE)
        return popupKeysKeyboardView
    }

    private fun dismissPopupKeysPanel() {
        if (isShowingPopupKeysPanel()) {
            mPopupKeysPanel!!.dismissPopupKeysPanel()
        }
    }

    fun isShowingPopupKeysPanel(): Boolean {
        return mPopupKeysPanel != null
    }

    override fun setLayoutGravity(layoutGravity: Int) {
        val layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layoutParams.gravity = layoutGravity
        popupKeysKeyboardContainer.setLayoutParams(layoutParams)
        descriptionView.setLayoutParams(layoutParams)
    }

    override fun onShowPopupKeysPanel(panel: PopupKeysPanel) {
        // install placer view only when needed instead of when this
        // view is attached to window
        installPopupKeysPlacerView(false /* uninstall */)
        panel.showInParent(popupKeysPlacerView)
        mPopupKeysPanel = panel
    }

    override fun onDismissPopupKeysPanel() {
        if (isShowingPopupKeysPanel()) {
            mPopupKeysPanel!!.removeFromParent()
            mPopupKeysPanel = null
            installPopupKeysPlacerView(true /* uninstall */)
        }
    }

    override fun onCancelPopupKeysPanel() {
        if (isShowingPopupKeysPanel()) {
            dismissPopupKeysPanel()
        }
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean {
        // Don't populate accessibility event with all Emoji keys.
        return true
    }

    private fun getLongPressTimeout(): Int {
        return Settings.getValues().keyLongpressTimeout
    }

    /**
     * {@inheritDoc}
     */
    override fun onHoverEvent(event: MotionEvent?): Boolean {
        val accessibilityDelegate = mAccessibilityDelegate
        if (accessibilityDelegate != null && AccessibilityUtils.instance.isTouchExplorationEnabled) {
            return accessibilityDelegate.onHoverEvent(event!!)
        }
        return super.onHoverEvent(event)
    }

    /**
     * {@inheritDoc}
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent?): Boolean {
        return when (e!!.getActionMasked()) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                onDown(e)
            }
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_CANCEL -> onCancel(e)
            else -> false
        }
    }

    private fun getKey(x: Int, y: Int): Key? {
        return keyDetector.detectHitKey(x, y)
    }

    private fun onLongPressed(key: Key?) {
        if (isShowingPopupKeysPanel()) {
            return
        }

        if (key == null) {
            if (LOG) Log.d(TAG, "Long press ignored because detected key is null")
            return
        }

        val descriptionPanel = showDescription(key)
        val popupKeysPanel = showPopupKeysKeyboard(key)

        val x = lastX
        val y = lastY
        if (popupKeysPanel != null || descriptionPanel != null) {
            popupKeysKeyboardContainer.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            val lastCoords = CoordinateUtils.newCoordinateArray(1, x, y)
            // The popup keys keyboard is usually horizontally aligned with the center of the parent key.
            // If showPopupKeysKeyboardAtTouchedPoint is true and the key preview is disabled, the more
            // keys keyboard is placed at the touch point of the parent key.
            val pointX = if (configShowPopupKeysKeyboardAtTouchedPoint)
                    CoordinateUtils.x(lastCoords)
                    else key.getX() + key.getWidth() / 2
            val keyboard = getKeyboard() as DynamicGridKeyboard
            val pointY = key.getY() - keyboard.mVerticalGap
            (if (popupKeysPanel != null) popupKeysPanel else descriptionPanel)!!
                .showPopupKeysPanel(this, this, pointX, pointY
                ) { popupKey ->
                    if (keyboard.isRecents()) mEmojiViewCallback.onRemoveRecentsKey(key)
                    else mEmojiViewCallback.onReleaseKey(popupKey)
                }
        }

        if (popupKeysPanel != null) {
            val translatedX = popupKeysPanel.translateX(x)
            val translatedY = popupKeysPanel.translateY(y)
            popupKeysPanel.onDownEvent(translatedX, translatedY, pointerId, 0 /* nor used for now */)
            // No need of re-allowing parent later as we don't
            // want any scroll to append during this entire input.
            disallowParentInterceptTouchEvent(true)
        }
    }

    private fun showDescription(key: Key): PopupKeysPanel? {
        descriptionView.setVisibility(View.GONE)
        val description = mEmojiViewCallback.getDescription(key.getLabel())
        if (description == null) {
            return null
        }

        descriptionView.setText(description)
        descriptionView.setKeyDrawParams(key, getKeyDrawParams())
        descriptionView.setVisibility(View.VISIBLE)
        return descriptionView
    }

    private fun registerPress(key: Key) {
        // Do not trigger key-down effect right now in case this is actually a fling action.
        mPendingKeyDown = Runnable { callListenerOnPressKey(key) }
        handler.postDelayed(mPendingKeyDown!!, KEY_PRESS_DELAY_TIME)
    }

    private fun registerLongPress(key: Key) {
        mPendingLongPress = Runnable { onLongPressed(key) }
        handler.postDelayed(mPendingLongPress!!, getLongPressTimeout().toLong())
    }

    fun callListenerOnReleaseKey(releasedKey: Key, withKeyRegistering: Boolean) {
        releasedKey.onReleased()
        invalidateKey(releasedKey)
        if (withKeyRegistering) {
            mEmojiViewCallback.onReleaseKey(releasedKey)
        }
    }

    fun callListenerOnPressKey(pressedKey: Key) {
        mPendingKeyDown = null
        pressedKey.onPressed()
        invalidateKey(pressedKey)
        mEmojiViewCallback.onPressKey(pressedKey)
    }

    fun releaseCurrentKey(withKeyRegistering: Boolean) {
        // Handler.removeCallbacks does nothing for null, and its parameter is declared non-null
        val pendingKeyDown = mPendingKeyDown
        if (pendingKeyDown != null) handler.removeCallbacks(pendingKeyDown)
        mPendingKeyDown = null
        val currentKey = mCurrentKey
        if (currentKey == null) {
            return
        }
        callListenerOnReleaseKey(currentKey, withKeyRegistering)
        mCurrentKey = null
    }

    override fun cancelLongPress() {
        // Handler.removeCallbacks does nothing for null, and its parameter is declared non-null
        val pendingLongPress = mPendingLongPress
        if (pendingLongPress != null) handler.removeCallbacks(pendingLongPress)
        mPendingLongPress = null
    }

    fun onDown(e: MotionEvent): Boolean {
        val x = e.getX().toInt()
        val y = e.getY().toInt()
        val key = getKey(x, y)
        releaseCurrentKey(false /* withKeyRegistering */)
        mCurrentKey = key
        if (key == null) {
            return false
        }
        registerPress(key)

        registerLongPress(key)

        lastX = x
        lastY = y
        return true
    }

    fun onUp(e: MotionEvent): Boolean {
        val x = e.getX().toInt()
        val y = e.getY().toInt()
        val key = getKey(x, y)
        val pendingKeyDown = mPendingKeyDown
        val currentKey = mCurrentKey
        releaseCurrentKey(false /* withKeyRegistering */)

        val isShowingPopupKeysPanel = isShowingPopupKeysPanel()
        if (isShowingPopupKeysPanel) {
            val eventTime = e.getEventTime()
            val translatedX = mPopupKeysPanel!!.translateX(x)
            val translatedY = mPopupKeysPanel!!.translateY(y)
            mPopupKeysPanel!!.onUpEvent(translatedX, translatedY, pointerId, eventTime)
            dismissPopupKeysPanel()
        } else if (key === currentKey && pendingKeyDown != null) {
            pendingKeyDown.run()
            // Trigger key-release event a little later so that a user can see visual feedback.
            handler.postDelayed({ callListenerOnReleaseKey(key!!, true) }, KEY_RELEASE_DELAY_TIME)
        } else if (key != null) {
            callListenerOnReleaseKey(key, true /* withRegistering */)
        }

        cancelLongPress()
        return true
    }

    fun onCancel(e: MotionEvent): Boolean {
        releaseCurrentKey(false)
        dismissPopupKeysPanel()
        cancelLongPress()
        return true
    }

    fun onMove(e: MotionEvent): Boolean {
        val x = e.getX().toInt()
        val y = e.getY().toInt()
        val key = getKey(x, y)
        val isShowingPopupKeyboard = isShowingPopupKeysPanel() && popupKeysKeyboardView.getVisibility() == View.VISIBLE

        // Touched key has changed, release previous key's callbacks and
        // re-register them for the new key.
        if (key !== mCurrentKey && !isShowingPopupKeyboard) {
            releaseCurrentKey(false)
            mCurrentKey = key
            cancelLongPress()
            if (isShowingPopupKeysPanel()) {
                onCancelPopupKeysPanel()
            }
            if (key == null) {
                return false
            }
            registerPress(key)
            registerLongPress(key)
        }

        if (isShowingPopupKeyboard) {
            val eventTime = e.getEventTime()
            val translatedX = mPopupKeysPanel!!.translateX(x)
            val translatedY = mPopupKeysPanel!!.translateY(y)
            mPopupKeysPanel!!.onMoveEvent(translatedX, translatedY, pointerId, eventTime)
        }

        lastX = x
        lastY = y
        return true
    }

    private fun disallowParentInterceptTouchEvent(disallow: Boolean) {
        val parent: ViewParent? = getParent()
        if (parent == null) {
            Log.w(TAG, "Cannot disallow touch event interception, no parent found.")
            return
        }
        parent.requestDisallowInterceptTouchEvent(disallow)
    }

    companion object {
        private const val TAG = "EmojiPageKeyboardView"
        private const val LOG = false
        private const val KEY_PRESS_DELAY_TIME = 250L // msec
        private const val KEY_RELEASE_DELAY_TIME = 30L // msec

        private val EMPTY_EMOJI_VIEW_CALLBACK: EmojiViewCallback = object : EmojiViewCallback {
            override fun onPressKey(key: Key) {}
            override fun onReleaseKey(key: Key) {}
            override fun getDescription(emoji: String?): String? {
                return null
            }
            override fun onRemoveRecentsKey(key: Key) {}
        }
    }
}
