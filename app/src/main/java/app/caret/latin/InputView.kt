/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

import androidx.core.view.doOnNextLayout

import app.caret.accessibility.AccessibilityUtils
import app.caret.keyboard.MainKeyboardView
import app.caret.latin.common.ColorType
import app.caret.latin.settings.Settings
import app.caret.latin.suggestions.SuggestionStripView
import app.caret.latin.utils.FloatingKeyboardUtils


class InputView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    private val inputViewRect = Rect()
    private lateinit var mMainKeyboardView: MainKeyboardView
    private lateinit var keyboardTopPaddingForwarder: KeyboardTopPaddingForwarder
    private lateinit var moreSuggestionsViewCanceler: MoreSuggestionsViewCanceler
    private var activeForwarder: MotionEventForwarder<*, *>? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        val suggestionStripView: SuggestionStripView =
                findViewById(R.id.suggestion_strip_view)
        mMainKeyboardView = findViewById(R.id.keyboard_view)
        keyboardTopPaddingForwarder = KeyboardTopPaddingForwarder(
                mMainKeyboardView, suggestionStripView)
        moreSuggestionsViewCanceler = MoreSuggestionsViewCanceler(
                mMainKeyboardView, suggestionStripView)
        doOnNextLayout(this::onNextLayout)
    }

    fun setKeyboardTopPadding(keyboardTopPadding: Int) {
        keyboardTopPaddingForwarder.setKeyboardTopPadding(keyboardTopPadding)
    }

    override fun dispatchHoverEvent(event: MotionEvent?): Boolean {
        if (AccessibilityUtils.instance.isTouchExplorationEnabled
                && mMainKeyboardView.isShowingPopupKeysPanel) {
            // With accessibility mode on, discard hover events while a popup keys keyboard is shown.
            // The {@link PopupKeysKeyboard} receives hover events directly from the platform.
            return true
        }
        return super.dispatchHoverEvent(event)
    }

    override fun onInterceptTouchEvent(me: MotionEvent?): Boolean {
        val rect = inputViewRect
        getGlobalVisibleRect(rect)
        val index = me!!.actionIndex
        val x = me.getX(index).toInt() + rect.left
        val y = me.getY(index).toInt() + rect.top

        // The touch events that hit the top padding of keyboard should be forwarded to
        // {@link SuggestionStripView}.
        if (keyboardTopPaddingForwarder.onInterceptTouchEvent(x, y, me)) {
            activeForwarder = keyboardTopPaddingForwarder
            return true
        }

        // To cancel {@link MoreSuggestionsView}, we should intercept a touch event to
        // {@link MainKeyboardView} and dismiss the {@link MoreSuggestionsView}.
        if (moreSuggestionsViewCanceler.onInterceptTouchEvent(x, y, me)) {
            activeForwarder = moreSuggestionsViewCanceler
            return true
        }

        activeForwarder = null
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent?): Boolean {
        val forwarder = activeForwarder ?: return super.onTouchEvent(me)

        val rect = inputViewRect
        getGlobalVisibleRect(rect)
        val index = me!!.actionIndex
        val x = me.getX(index).toInt() + rect.left
        val y = me.getY(index).toInt() + rect.top
        return forwarder.onTouchEvent(x, y, me)
    }

    private fun onNextLayout(v: View) {
        Settings.getValues().mColors.setBackground(findViewById<View>(R.id.main_keyboard_frame), ColorType.MAIN_BACKGROUND)

        // Work around inset application being unreliable
        requestApplyInsets()

        // need to update the floating keyboard position after applying insets
        if (Settings.getValues().isFloatingKeyboard)
            FloatingKeyboardUtils.setFloating(this)
    }

    /**
     * This class forwards series of {@link MotionEvent}s from <code>SenderView</code> to
     * <code>ReceiverView</code>.
     *
     * @param <SenderView> a {@link View} that may send a {@link MotionEvent} to <ReceiverView>.
     * @param <ReceiverView> a {@link View} that receives forwarded {@link MotionEvent} from
     *     <SenderView>.
     */
    private abstract class MotionEventForwarder<SenderView : View, ReceiverView : View>(
            senderView: SenderView, receiverView: ReceiverView) {
        protected val mSenderView: SenderView = senderView
        protected val mReceiverView: ReceiverView = receiverView

        protected val eventSendingRect = Rect()
        protected val eventReceivingRect = Rect()

        // Return true if a touch event of global coordinate x, y needs to be forwarded.
        protected abstract fun needsToForward(x: Int, y: Int): Boolean

        // Translate global x-coordinate to <code>ReceiverView</code> local coordinate.
        protected fun translateX(x: Int): Int {
            return x - eventReceivingRect.left
        }

        // Translate global y-coordinate to <code>ReceiverView</code> local coordinate.
        protected open fun translateY(y: Int): Int {
            return y - eventReceivingRect.top
        }

        /**
         * Callback when a {@link MotionEvent} is forwarded.
         * @param me the motion event to be forwarded.
         */
        protected open fun onForwardingEvent(me: MotionEvent) {}

        // Returns true if a {@link MotionEvent} is needed to be forwarded to
        // <code>ReceiverView</code>. Otherwise returns false.
        fun onInterceptTouchEvent(x: Int, y: Int, me: MotionEvent): Boolean {
            // Forwards a {link MotionEvent} only if both <code>SenderView</code> and
            // <code>ReceiverView</code> are visible.
            if (mSenderView.visibility != View.VISIBLE ||
                    mReceiverView.visibility != View.VISIBLE) {
                return false
            }
            mSenderView.getGlobalVisibleRect(eventSendingRect)
            if (!eventSendingRect.contains(x, y)) {
                return false
            }

            if (me.actionMasked == MotionEvent.ACTION_DOWN) {
                // If the down event happens in the forwarding area, successive
                // {@link MotionEvent}s should be forwarded to <code>ReceiverView</code>.
                return needsToForward(x, y)
            }

            return false
        }

        // Returns true if a {@link MotionEvent} is forwarded to <code>ReceiverView</code>.
        // Otherwise returns false.
        fun onTouchEvent(x: Int, y: Int, me: MotionEvent): Boolean {
            mReceiverView.getGlobalVisibleRect(eventReceivingRect)
            // Translate global coordinates to <code>ReceiverView</code> local coordinates.
            me.setLocation(translateX(x).toFloat(), translateY(y).toFloat())
            mReceiverView.dispatchTouchEvent(me)
            onForwardingEvent(me)
            return true
        }
    }

    /**
     * This class forwards {@link MotionEvent}s happened in the top padding of
     * {@link MainKeyboardView} to {@link SuggestionStripView}.
     */
    private class KeyboardTopPaddingForwarder(mainKeyboardView: MainKeyboardView,
            suggestionStripView: SuggestionStripView)
            : MotionEventForwarder<MainKeyboardView, SuggestionStripView>(mainKeyboardView, suggestionStripView) {
        private var mKeyboardTopPadding = 0

        fun setKeyboardTopPadding(keyboardTopPadding: Int) {
            mKeyboardTopPadding = keyboardTopPadding
        }

        private fun isInKeyboardTopPadding(y: Int): Boolean {
            return y < eventSendingRect.top + mKeyboardTopPadding
        }

        override fun needsToForward(x: Int, y: Int): Boolean {
            // Forwarding an event only when {@link MainKeyboardView} is visible.
            // Because the visibility of {@link MainKeyboardView} is controlled by its parent
            // view in {@link KeyboardSwitcher#setMainKeyboardFrame()}, we should check the
            // visibility of the parent view.
            val mainKeyboardFrame = mSenderView.parent as View
            return mainKeyboardFrame.visibility == View.VISIBLE && isInKeyboardTopPadding(y)
        }

        override fun translateY(y: Int): Int {
            val translatedY = super.translateY(y)
            if (isInKeyboardTopPadding(y)) {
                // The forwarded event should have coordinates that are inside of the target.
                return Math.min(translatedY, eventReceivingRect.height() - 1)
            }
            return translatedY
        }
    }

    /**
     * This class forwards {@link MotionEvent}s happened in the {@link MainKeyboardView} to
     * {@link SuggestionStripView} when the {@link MoreSuggestionsView} is showing.
     * {@link SuggestionStripView} dismisses {@link MoreSuggestionsView} when it receives any event
     * outside of it.
     */
    private class MoreSuggestionsViewCanceler(mainKeyboardView: MainKeyboardView,
            suggestionStripView: SuggestionStripView)
            : MotionEventForwarder<View, SuggestionStripView>(mainKeyboardView.rootView, suggestionStripView) {

        override fun needsToForward(x: Int, y: Int): Boolean {
            return mReceiverView.isShowingMoreSuggestionPanel && eventSendingRect.contains(x, y)
        }

        override fun onForwardingEvent(me: MotionEvent) {
            if (me.actionMasked == MotionEvent.ACTION_DOWN) {
                mReceiverView.dismissMoreSuggestionsPanel()
            }
        }
    }
}
