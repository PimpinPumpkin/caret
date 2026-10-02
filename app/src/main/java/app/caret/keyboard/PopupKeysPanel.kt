/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.view.View
import android.view.ViewGroup
import app.caret.keyboard.emoji.EmojiViewCallback

interface PopupKeysPanel {
    interface Controller {
        /**
         * Set the layout gravity.
         * @param layoutGravity requested by the popup
         */
        fun setLayoutGravity(layoutGravity: Int) {
        }

        /**
         * Add the {@link PopupKeysPanel} to the target view.
         * @param panel the panel to be shown.
         */
        fun onShowPopupKeysPanel(panel: PopupKeysPanel)

        /**
         * Remove the current {@link PopupKeysPanel} from the target view.
         */
        fun onDismissPopupKeysPanel()

        /**
         * Instructs the parent to cancel the panel (e.g., when entering a different input mode).
         */
        fun onCancelPopupKeysPanel()
    }

    /**
     * Initializes the layout and event handling of this {@link PopupKeysPanel} and calls the
     * controller's onShowPopupKeysPanel to add the panel's container view.
     *
     * @param parentView the parent view of this {@link PopupKeysPanel}
     * @param controller the controller that can dismiss this {@link PopupKeysPanel}
     * @param pointX x coordinate of this {@link PopupKeysPanel}
     * @param pointY y coordinate of this {@link PopupKeysPanel}
     * @param listener the listener that will receive keyboard action from this
     * {@link PopupKeysPanel}.
     */
    // TODO: Currently the PopupKeysPanel is inside a container view that is added to the parent.
    // Consider the simpler approach of placing the PopupKeysPanel itself into the parent view.
    fun showPopupKeysPanel(parentView: View, controller: Controller, pointX: Int,
                           pointY: Int, listener: KeyboardActionListener)

    /**
     *
     * Initializes the layout and event handling of this {@link PopupKeysPanel} and calls the
     * controller's onShowPopupKeysPanel to add the panel's container view.
     * Same as {@link PopupKeysPanel#showPopupKeysPanel(View, Controller, int, int, KeyboardActionListener)},
     * but with a {@link EmojiViewCallback}.
     *
     * @param parentView the parent view of this {@link PopupKeysPanel}
     * @param controller the controller that can dismiss this {@link PopupKeysPanel}
     * @param pointX x coordinate of this {@link PopupKeysPanel}
     * @param pointY y coordinate of this {@link PopupKeysPanel}
     * @param onReleaseKey to receive keyboard actions from this {@link PopupKeysPanel}.
     */
    // TODO: Currently the PopupKeysPanel is inside a container view that is added to the parent.
    // Consider the simpler approach of placing the PopupKeysPanel itself into the parent view.
    fun showPopupKeysPanel(parentView: View, controller: Controller, pointX: Int, pointY: Int, onReleaseKey: (Key) -> Unit)

    /**
     * Dismisses the popup keys panel and calls the controller's onDismissPopupKeysPanel to remove
     * the panel's container view.
     */
    fun dismissPopupKeysPanel()

    /**
     * Process a move event on the popup keys panel.
     *
     * @param x translated x coordinate of the touch point
     * @param y translated y coordinate of the touch point
     * @param pointerId pointer id touch point
     * @param eventTime timestamp of touch point
     */
    fun onMoveEvent(x: Int, y: Int, pointerId: Int, eventTime: Long)

    /**
     * Process a down event on the popup keys panel.
     *
     * @param x translated x coordinate of the touch point
     * @param y translated y coordinate of the touch point
     * @param pointerId pointer id touch point
     * @param eventTime timestamp of touch point
     */
    fun onDownEvent(x: Int, y: Int, pointerId: Int, eventTime: Long)

    /**
     * Process an up event on the popup keys panel.
     *
     * @param x translated x coordinate of the touch point
     * @param y translated y coordinate of the touch point
     * @param pointerId pointer id touch point
     * @param eventTime timestamp of touch point
     */
    fun onUpEvent(x: Int, y: Int, pointerId: Int, eventTime: Long)

    /**
     * Translate X-coordinate of touch event to the local X-coordinate of this
     * {@link PopupKeysPanel}.
     *
     * @param x the global X-coordinate
     * @return the local X-coordinate to this {@link PopupKeysPanel}
     */
    fun translateX(x: Int): Int

    /**
     * Translate Y-coordinate of touch event to the local Y-coordinate of this
     * {@link PopupKeysPanel}.
     *
     * @param y the global Y-coordinate
     * @return the local Y-coordinate to this {@link PopupKeysPanel}
     */
    fun translateY(y: Int): Int

    fun getContainerView(): View {
        return (this as View).parent as View
    }

    /**
     * Show this {@link PopupKeysPanel} in the parent view.
     *
     * @param parentView the {@link ViewGroup} that hosts this {@link PopupKeysPanel}.
     */
    fun showInParent(parentView: ViewGroup) {
        removeFromParent()
        parentView.addView(getContainerView())
    }

    /**
     * Remove this {@link PopupKeysPanel} from the parent view.
     */
    fun removeFromParent() {
        val containerView = getContainerView()
        val currentParent = containerView.parent as ViewGroup?
        if (currentParent != null) {
            currentParent.removeView(containerView)
        }
    }

    /**
     * Return whether the panel is currently being shown.
     */
    fun isShowingInParent(): Boolean {
        return getContainerView().parent != null
    }

    companion object {
        @JvmField
        val EMPTY_CONTROLLER: Controller = object : Controller {
            override fun onShowPopupKeysPanel(panel: PopupKeysPanel) {}
            override fun onDismissPopupKeysPanel() {}
            override fun onCancelPopupKeysPanel() {}
        }
    }
}
