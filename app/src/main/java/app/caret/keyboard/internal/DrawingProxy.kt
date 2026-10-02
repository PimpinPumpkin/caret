/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.internal

import app.caret.keyboard.Key
import app.caret.keyboard.PopupKeysPanel
import app.caret.keyboard.PointerTracker

interface DrawingProxy {
    /**
     * Called when a key is being pressed.
     * @param key the {@link Key} that is being pressed.
     * @param withPreview true if key popup preview should be displayed.
     */
    fun onKeyPressed(key: Key, withPreview: Boolean)

    /**
     * Called when a key is being released.
     * @param key the {@link Key} that is being released.
     * @param withAnimation when true, key popup preview should be dismissed with animation.
     */
    fun onKeyReleased(key: Key, withAnimation: Boolean)

    /**
     * Start showing popup keys keyboard of a key that is being long pressed.
     * @param key the {@link Key} that is being long pressed and showing popup keys keyboard.
     * @param tracker the {@link PointerTracker} that detects this long pressing.
     * @return {@link PopupKeysPanel} that is being shown. null if there is no need to show popup keys keyboard.
     */
    fun showPopupKeysKeyboard(key: Key, tracker: PointerTracker): PopupKeysPanel?

    /**
     * Start a while-typing-animation.
     * @param fadeInOrOut {@link #FADE_IN} starts while-typing-fade-in animation.
     * {@link #FADE_OUT} starts while-typing-fade-out animation.
     */
    fun startWhileTypingAnimation(fadeInOrOut: Int)

    /**
     * Show sliding-key input preview.
     * @param tracker the {@link PointerTracker} that is currently doing the sliding-key input.
     * null to dismiss the sliding-key input preview.
     */
    fun showSlidingKeyInputPreview(tracker: PointerTracker?)

    /**
     * Show gesture trails.
     * @param tracker the {@link PointerTracker} whose gesture trail will be shown.
     * @param showsFloatingPreviewText when true, a gesture floating preview text will be shown
     * with this <code>tracker</code>'s trail.
     */
    fun showGestureTrail(tracker: PointerTracker, showsFloatingPreviewText: Boolean)

    /**
     * Dismiss a gesture floating preview text without delay.
     */
    fun dismissGestureFloatingPreviewTextWithoutDelay()

    companion object {
        const val FADE_IN = 0
        const val FADE_OUT = 1
    }
}
