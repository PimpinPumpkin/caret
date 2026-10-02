/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.animation.AnimatorInflater
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

import app.caret.accessibility.AccessibilityUtils
import app.caret.accessibility.MainKeyboardAccessibilityDelegate
import app.caret.compat.locale
import app.caret.keyboard.internal.DrawingPreviewPlacerView
import app.caret.keyboard.internal.DrawingProxy
import app.caret.keyboard.internal.GestureFloatingTextDrawingPreview
import app.caret.keyboard.internal.GestureTrailsDrawingPreview
import app.caret.keyboard.internal.KeyDrawParams
import app.caret.keyboard.internal.KeyPreviewChoreographer
import app.caret.keyboard.internal.KeyPreviewDrawParams
import app.caret.keyboard.internal.KeyPreviewView
import app.caret.keyboard.internal.PopupKeySpec
import app.caret.keyboard.internal.NonDistinctMultitouchHelper
import app.caret.keyboard.internal.SlidingKeyInputDrawingPreview
import app.caret.keyboard.internal.TimerHandler
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.R
import app.caret.latin.RichInputMethodSubtype
import app.caret.latin.SuggestedWords
import app.caret.latin.common.ColorType
import app.caret.latin.common.Colors
import app.caret.latin.common.Constants
import app.caret.latin.common.CoordinateUtils
import app.caret.latin.define.DebugFlags
import app.caret.latin.settings.DebugSettings
import app.caret.latin.settings.Defaults
import app.caret.latin.settings.Settings
import app.caret.latin.utils.prefs
import app.caret.latin.utils.LanguageOnSpacebarUtils
import app.caret.latin.utils.Log
import app.caret.latin.utils.TypefaceUtils

import java.util.ArrayList
import java.util.Locale
import java.util.WeakHashMap

/** A view that is responsible for detecting key presses and touch movements. */
class MainKeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet?,
        defStyle: Int = R.attr.mainKeyboardViewStyle) : KeyboardView(context, attrs, defStyle), DrawingProxy,
        PopupKeysPanel.Controller {

    /** Listener for {@link KeyboardActionListener}. */
    private var keyboardActionListener: KeyboardActionListener

    /* Space key and its icon and background. */
    private var spaceKey: Key? = null
    // Stuff to draw language name on spacebar.
    private val languageOnSpacebarFinalAlpha: Int
    private val languageOnSpacebarFadeoutAnimator: ObjectAnimator?
    private var languageOnSpacebarFormatType: Int = 0
    private var hasMultipleEnabledIMEsOrSubtypes: Boolean = false
    private var languageOnSpacebarAnimAlpha: Int = Constants.Color.ALPHA_OPAQUE
    private val languageOnSpacebarTextRatio: Float
    private var languageOnSpacebarTextSize: Float = 0f
    private val languageOnSpacebarTextColor: Int
    private val languageOnSpacebarTextShadowRadius: Float
    private val languageOnSpacebarTextShadowColor: Int

    // Stuff to draw altCodeWhileTyping keys.
    private val altCodeKeyWhileTypingFadeoutAnimator: ObjectAnimator?
    private val altCodeKeyWhileTypingFadeinAnimator: ObjectAnimator?

    // Drawing preview placer view
    private val drawingPreviewPlacerView: DrawingPreviewPlacerView
    private val originCoords: IntArray = CoordinateUtils.newInstance()
    private val gestureFloatingTextDrawingPreview: GestureFloatingTextDrawingPreview
    private val gestureTrailsDrawingPreview: GestureTrailsDrawingPreview
    private val slidingKeyInputDrawingPreview: SlidingKeyInputDrawingPreview

    // Key preview
    private val keyPreviewDrawParams: KeyPreviewDrawParams
    private val keyPreviewChoreographer: KeyPreviewChoreographer

    // More keys keyboard
    private val backgroundDimAlphaPaint: Paint = Paint() // todo: not used at all
    private val popupKeysKeyboardContainer: View
    private val popupKeysKeyboardForActionContainer: View
    private val popupKeysKeyboardCache: WeakHashMap<Key, Keyboard> = WeakHashMap()
    private val configShowPopupKeysKeyboardAtTouchedPoint: Boolean
    // More keys panel (used by both popup keys keyboard and more suggestions view)
    // TODO: Consider extending to support multiple popup keys panels
    private var popupKeysPanel: PopupKeysPanel? = null

    // Gesture floating preview text
    private val gestureFloatingPreviewTextLingerTimeout: Int

    private val keyDetector: KeyDetector
    private val nonDistinctMultitouchHelper: NonDistinctMultitouchHelper?

    private val timerHandler: TimerHandler
    private val languageOnSpacebarHorizontalMargin: Int

    private var accessibilityDelegate: MainKeyboardAccessibilityDelegate? = null

    init {

        drawingPreviewPlacerView =
                DrawingPreviewPlacerView(ContextThemeWrapper(context, R.style.platformActivityTheme), attrs)

        val mainKeyboardViewAttr = context.obtainStyledAttributes(
                attrs, R.styleable.MainKeyboardView, defStyle, R.style.MainKeyboardView)
        val ignoreAltCodeKeyTimeout = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_ignoreAltCodeKeyTimeout, 0)
        val gestureRecognitionUpdateTime = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureRecognitionUpdateTime, 0)
        timerHandler = TimerHandler(this, ignoreAltCodeKeyTimeout, gestureRecognitionUpdateTime)

        val keyHysteresisDistance = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_keyHysteresisDistance, 0.0f)
        val keyHysteresisDistanceForSlidingModifier = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_keyHysteresisDistanceForSlidingModifier, 0.0f)
        keyDetector = KeyDetector(keyHysteresisDistance, keyHysteresisDistanceForSlidingModifier)

        PointerTracker.init(mainKeyboardViewAttr, timerHandler, this /* DrawingProxy */)

        val prefs: SharedPreferences = context.prefs()
        val forceNonDistinctMultitouch = prefs.getBoolean(
                DebugSettings.PREF_FORCE_NON_DISTINCT_MULTITOUCH, Defaults.PREF_FORCE_NON_DISTINCT_MULTITOUCH)
        val hasDistinctMultitouch = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT)
                && !forceNonDistinctMultitouch
        nonDistinctMultitouchHelper = if (hasDistinctMultitouch) null else NonDistinctMultitouchHelper()

        val backgroundDimAlpha = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_backgroundDimAlpha, 0)
        backgroundDimAlphaPaint.setColor(Color.BLACK)
        backgroundDimAlphaPaint.setAlpha(backgroundDimAlpha)
        languageOnSpacebarTextRatio = mainKeyboardViewAttr.getFraction(
                R.styleable.MainKeyboardView_languageOnSpacebarTextRatio, 1, 1, 1.0f) *
                Settings.getValues().mFontSizeMultiplier
        val colors: Colors = Settings.getValues().mColors
        languageOnSpacebarTextColor = colors.get(ColorType.SPACE_BAR_TEXT)
        languageOnSpacebarTextShadowRadius = mainKeyboardViewAttr.getFloat(
                R.styleable.MainKeyboardView_languageOnSpacebarTextShadowRadius,
                LANGUAGE_ON_SPACEBAR_TEXT_SHADOW_RADIUS_DISABLED)
        languageOnSpacebarTextShadowColor = mainKeyboardViewAttr.getColor(
                R.styleable.MainKeyboardView_languageOnSpacebarTextShadowColor, 0)
        languageOnSpacebarFinalAlpha = Color.alpha(languageOnSpacebarTextColor)
        val languageOnSpacebarFadeoutAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_languageOnSpacebarFadeoutAnimator, 0)
        val altCodeKeyWhileTypingFadeoutAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeoutAnimator, 0)
        val altCodeKeyWhileTypingFadeinAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeinAnimator, 0)

        keyPreviewDrawParams = KeyPreviewDrawParams(mainKeyboardViewAttr)
        keyPreviewChoreographer = KeyPreviewChoreographer(keyPreviewDrawParams)

        val popupKeysKeyboardLayoutId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_popupKeysKeyboardLayout, 0)
        val popupKeysKeyboardForActionLayoutId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_popupKeysKeyboardForActionLayout,
                popupKeysKeyboardLayoutId)
        configShowPopupKeysKeyboardAtTouchedPoint = mainKeyboardViewAttr.getBoolean(
                R.styleable.MainKeyboardView_showPopupKeysKeyboardAtTouchedPoint, false)

        val gestureTrailFadeoutDuration = Settings.getValues().mGestureTrailFadeoutDuration
        gestureFloatingPreviewTextLingerTimeout = gestureTrailFadeoutDuration / 4

        gestureFloatingTextDrawingPreview = GestureFloatingTextDrawingPreview(mainKeyboardViewAttr)
        gestureFloatingTextDrawingPreview.setDrawingView(drawingPreviewPlacerView)

        gestureTrailsDrawingPreview = GestureTrailsDrawingPreview(mainKeyboardViewAttr)
        gestureTrailsDrawingPreview.setDrawingView(drawingPreviewPlacerView)

        slidingKeyInputDrawingPreview = SlidingKeyInputDrawingPreview(mainKeyboardViewAttr)
        slidingKeyInputDrawingPreview.setDrawingView(drawingPreviewPlacerView)
        mainKeyboardViewAttr.recycle()

        val inflater = LayoutInflater.from(getContext())
        popupKeysKeyboardContainer = inflater.inflate(popupKeysKeyboardLayoutId, null)
        popupKeysKeyboardForActionContainer = inflater.inflate(popupKeysKeyboardForActionLayoutId, null)
        languageOnSpacebarFadeoutAnimator = loadObjectAnimator(languageOnSpacebarFadeoutAnimatorResId, this)
        if (languageOnSpacebarFadeoutAnimator != null)
            languageOnSpacebarFadeoutAnimator.setIntValues(255, languageOnSpacebarFinalAlpha)
        altCodeKeyWhileTypingFadeoutAnimator = loadObjectAnimator(altCodeKeyWhileTypingFadeoutAnimatorResId, this)
        altCodeKeyWhileTypingFadeinAnimator = loadObjectAnimator(altCodeKeyWhileTypingFadeinAnimatorResId, this)

        keyboardActionListener = KeyboardActionListener.EMPTY_LISTENER

        languageOnSpacebarHorizontalMargin = getResources().getDimension(
                R.dimen.config_language_on_spacebar_horizontal_margin).toInt()
    }

    override fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        super.setHardwareAcceleratedDrawingEnabled(enabled)
        drawingPreviewPlacerView.setHardwareAcceleratedDrawingEnabled(enabled)
    }

    private fun loadObjectAnimator(resId: Int, target: Any?): ObjectAnimator? {
        if (resId == 0) {
            // TODO: Stop returning null.
            return null
        }
        val animator = AnimatorInflater.loadAnimator(
                getContext(), resId) as ObjectAnimator?
        if (animator != null) {
            animator.setTarget(target)
        }
        return animator
    }

    // Implements {@link DrawingProxy#startWhileTypingAnimation(int)}.
    /**
     * Called when a while-typing-animation should be started.
     * @param fadeInOrOut {@link DrawingProxy#FADE_IN} starts while-typing-fade-in animation.
     * {@link DrawingProxy#FADE_OUT} starts while-typing-fade-out animation.
     */
    override fun startWhileTypingAnimation(fadeInOrOut: Int) {
        when (fadeInOrOut) {
            DrawingProxy.FADE_IN -> cancelAndStartAnimators(
                    altCodeKeyWhileTypingFadeoutAnimator, altCodeKeyWhileTypingFadeinAnimator)
            DrawingProxy.FADE_OUT -> cancelAndStartAnimators(
                    altCodeKeyWhileTypingFadeinAnimator, altCodeKeyWhileTypingFadeoutAnimator)
        }
    }

    fun setLanguageOnSpacebarAnimAlpha(alpha: Int) {
        languageOnSpacebarAnimAlpha = alpha
        invalidateKey(spaceKey)
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
        PointerTracker.setKeyboardActionListener(listener)
    }

    // TODO: We should reconsider which coordinate system should be used to represent keyboard event.
    fun getKeyX(x: Int): Int {
        return if (Constants.isValidCoordinate(x)) keyDetector.getTouchX(x) else x
    }

    // TODO: We should reconsider which coordinate system should be used to represent keyboard event.
    fun getKeyY(y: Int): Int {
        return if (Constants.isValidCoordinate(y)) keyDetector.getTouchY(y) else y
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see Keyboard
     * @see #getKeyboard()
     * @param keyboard the keyboard to display in this view
     */
    override fun setKeyboard(keyboard: Keyboard) {
        // Remove any pending messages, except dismissing preview and key repeat.
        timerHandler.cancelLongPressTimers()
        super.setKeyboard(keyboard)
        app.caret.latin.swipe.FutoSwipe.warmUp(keyboard)
        keyDetector.setKeyboard(
                keyboard, (-getPaddingLeft()).toFloat(), -getPaddingTop() + getVerticalCorrection())
        PointerTracker.setKeyDetector(keyDetector)
        popupKeysKeyboardCache.clear()

        spaceKey = keyboard.getKey(Constants.CODE_SPACE)
        val keyHeight = keyboard.mMostCommonKeyHeight - keyboard.mVerticalGap
        languageOnSpacebarTextSize = keyHeight * languageOnSpacebarTextRatio

        if (AccessibilityUtils.instance.isAccessibilityEnabled) {
            val delegate = accessibilityDelegate
                    ?: MainKeyboardAccessibilityDelegate(this, keyDetector).also { accessibilityDelegate = it }
            delegate.keyboard = keyboard
        } else {
            accessibilityDelegate = null
        }
    }

    /**
     * Enables or disables the key preview popup. This is a popup that shows a magnified
     * version of the depressed key. By default the preview is enabled.
     * @param previewEnabled whether or not to enable the key feedback preview
     */
    fun setKeyPreviewPopupEnabled(previewEnabled: Boolean) {
        keyPreviewDrawParams.setPopupEnabled(previewEnabled)
    }

    private fun locatePreviewPlacerView() {
        getLocationInWindow(originCoords)
        drawingPreviewPlacerView.setKeyboardViewGeometry(originCoords, getWidth(), getHeight())
    }

    private fun installPreviewPlacerView() {
        val rootView: View? = getRootView()
        if (rootView == null) {
            Log.w(TAG, "Cannot find root view")
            return
        }
        val windowContentView: ViewGroup? = rootView.findViewById(android.R.id.content)
        val vg = drawingPreviewPlacerView.getParent()
        if (vg is ViewGroup)
            vg.removeView(drawingPreviewPlacerView) // when moving keyboard from input method content view to floating container
        // Note: It'd be very weird if we get null by android.R.id.content.
        if (windowContentView == null) {
            Log.w(TAG, "Cannot find android.R.id.content view to add DrawingPreviewPlacerView")
            return
        }
        windowContentView.addView(drawingPreviewPlacerView)
    }

    // Implements {@link DrawingProxy#onKeyPressed(Key,boolean)}.
    override fun onKeyPressed(key: Key, withPreview: Boolean) {
        key.onPressed()
        invalidateKey(key)

        val keyboard = getKeyboard()
        if (keyboard == null) {
            return
        }
        keyPreviewDrawParams.setVisibleOffset(-keyboard.mVerticalGap)
        if (withPreview && key.hasPreview() && keyPreviewDrawParams.isPopupEnabled()) {
            showKeyPreview(key, keyboard)
        }
    }

    private fun showKeyPreview(key: Key, keyboard: Keyboard) {
        locatePreviewPlacerView()
        getLocationInWindow(originCoords)
        keyPreviewChoreographer.placeAndShowKeyPreview(key, keyboard.mIconsSet, getKeyDrawParams(),
                KeyboardSwitcher.getInstance().getWrapperView()!!.getWidth(), originCoords, drawingPreviewPlacerView)
    }

    private fun dismissKeyPreviewWithoutDelay(key: Key) {
        keyPreviewChoreographer.dismissKeyPreview(key)
        invalidateKey(key)
    }

    // Implements {@link DrawingProxy#onKeyReleased(Key,boolean)}.
    override fun onKeyReleased(key: Key, withAnimation: Boolean) {
        key.onReleased()
        invalidateKey(key)
        if (key.hasPreview()) {
            if (withAnimation) {
                dismissKeyPreview(key)
            } else {
                dismissKeyPreviewWithoutDelay(key)
            }
        }
    }

    private fun dismissKeyPreview(key: Key) {
        if (isHardwareAccelerated()) {
            keyPreviewChoreographer.dismissKeyPreview(key)
        }
    }

    fun setSlidingKeyInputPreviewEnabled(enabled: Boolean) {
        slidingKeyInputDrawingPreview.setPreviewEnabled(enabled)
    }

    override fun showSlidingKeyInputPreview(tracker: PointerTracker?) {
        locatePreviewPlacerView()
        if (tracker != null) {
            slidingKeyInputDrawingPreview.setPreviewPosition(tracker)
        } else {
            slidingKeyInputDrawingPreview.dismissSlidingKeyInputPreview()
        }
    }

    private fun setGesturePreviewMode(isGestureTrailEnabled: Boolean,
            isGestureFloatingPreviewTextEnabled: Boolean) {
        gestureFloatingTextDrawingPreview.setPreviewEnabled(isGestureFloatingPreviewTextEnabled)
        gestureTrailsDrawingPreview.setPreviewEnabled(isGestureTrailEnabled)
    }

    fun showGestureFloatingPreviewText(suggestedWords: SuggestedWords,
            dismissDelayed: Boolean) {
        locatePreviewPlacerView()
        gestureFloatingTextDrawingPreview.setSuggestedWords(suggestedWords)
        if (dismissDelayed) {
            timerHandler.postDismissGestureFloatingPreviewText(
                    gestureFloatingPreviewTextLingerTimeout.toLong())
        }
    }

    // Implements {@link DrawingProxy#dismissGestureFloatingPreviewTextWithoutDelay()}.
    override fun dismissGestureFloatingPreviewTextWithoutDelay() {
        gestureFloatingTextDrawingPreview.dismissGestureFloatingPreviewText()
    }

    override fun showGestureTrail(tracker: PointerTracker,
            showsFloatingPreviewText: Boolean) {
        locatePreviewPlacerView()
        if (showsFloatingPreviewText) {
            gestureFloatingTextDrawingPreview.setPreviewPosition(tracker)
        }
        gestureTrailsDrawingPreview.setPreviewPosition(tracker)
    }

    // Note that this method is called from a non-UI thread.
    @Suppress("static-method")
    fun setMainDictionaryAvailability(mainDictionaryAvailable: Boolean) {
        PointerTracker.setMainDictionaryAvailability(mainDictionaryAvailable)
    }

    fun setGestureHandlingEnabledByUser(isGestureHandlingEnabledByUser: Boolean,
            isGestureTrailEnabled: Boolean,
            isGestureFloatingPreviewTextEnabled: Boolean) {
        PointerTracker.setGestureHandlingEnabledByUser(isGestureHandlingEnabledByUser)
        setGesturePreviewMode(isGestureHandlingEnabledByUser && isGestureTrailEnabled,
                isGestureHandlingEnabledByUser && isGestureFloatingPreviewTextEnabled)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        installPreviewPlacerView()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drawingPreviewPlacerView.removeAllViews()
    }

    // Implements {@link DrawingProxy@showPopupKeysKeyboard(Key,PointerTracker)}.
    override fun showPopupKeysKeyboard(key: Key,
                                       tracker: PointerTracker): PopupKeysPanel? {
        val popupKeys = key.getPopupKeys()
        if (popupKeys == null) {
            return null
        }
        var popupKeysKeyboard: Keyboard? = popupKeysKeyboardCache.get(key)
        if (popupKeysKeyboard == null) {
            // {@link KeyPreviewDrawParams#mPreviewVisibleWidth} should have been set at
            // {@link KeyPreviewChoreographer#placeKeyPreview(Key,TextView,KeyboardIconsSet,KeyDrawParams,int,int[]},
            // though there may be some chances that the value is zero. <code>width == 0</code>
            // will cause zero-division error at
            // {@link PopupKeysKeyboardParams#setParameters(int,int,int,int,int,int,boolean,int)}.
            val isSinglePopupKeyWithPreview = keyPreviewDrawParams.isPopupEnabled()
                    && key.hasPreview() && popupKeys.size == 1
                    && keyPreviewDrawParams.getVisibleWidth() > 0
            val builder = PopupKeysKeyboard.Builder(
                    getContext(), key, getKeyboard(), isSinglePopupKeyWithPreview,
                    keyPreviewDrawParams.getVisibleWidth(),
                    keyPreviewDrawParams.getVisibleHeight(), newLabelPaint(key))
            popupKeysKeyboard = builder.build()
            popupKeysKeyboardCache.put(key, popupKeysKeyboard)
        }

        val container = if (key.hasActionKeyPopups()) popupKeysKeyboardForActionContainer
                else popupKeysKeyboardContainer
        val popupKeysKeyboardView: PopupKeysKeyboardView =
                container.findViewById(R.id.popup_keys_keyboard_view)
        popupKeysKeyboardView.setKeyboard(popupKeysKeyboard)
        container.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val lastCoords = CoordinateUtils.newInstance()
        tracker.getLastCoordinates(lastCoords)
        val keyPreviewEnabled = keyPreviewDrawParams.isPopupEnabled() && key.hasPreview()
        // The popup keys keyboard is usually horizontally aligned with the center of the parent key.
        // If showPopupKeysKeyboardAtTouchedPoint is true and the key preview is disabled, the more
        // keys keyboard is placed at the touch point of the parent key.
        val pointX = if (configShowPopupKeysKeyboardAtTouchedPoint && !keyPreviewEnabled)
                CoordinateUtils.x(lastCoords)
                else key.getX() + key.getWidth() / 2
        // The popup keys keyboard is usually vertically aligned with the top edge of the parent key
        // (plus vertical gap). If the key preview is enabled, the popup keys keyboard is vertically
        // aligned with the bottom edge of the visible part of the key preview.
        // {@code mPreviewVisibleOffset} has been set appropriately in
        // {@link KeyboardView#showKeyPreview(PointerTracker)}.
        val pointY = key.getY() + keyPreviewDrawParams.getVisibleOffset()
        popupKeysKeyboardView.showPopupKeysPanel(this, this, pointX, pointY, keyboardActionListener)
        return popupKeysKeyboardView
    }

    fun isInDraggingFinger(): Boolean {
        if (isShowingPopupKeysPanel()) {
            return true
        }
        return PointerTracker.isAnyInDraggingFinger()
    }

    override fun onShowPopupKeysPanel(panel: PopupKeysPanel) {
        locatePreviewPlacerView()
        // Dismiss another {@link PopupKeysPanel} that may be being showed.
        onDismissPopupKeysPanel()
        // Dismiss all key previews that may be being showed.
        PointerTracker.setReleasedKeyGraphicsToAllKeys()
        // Dismiss sliding key input preview that may be being showed.
        slidingKeyInputDrawingPreview.dismissSlidingKeyInputPreview()
        panel.showInParent(drawingPreviewPlacerView)
        popupKeysPanel = panel
    }

    fun isShowingPopupKeysPanel(): Boolean {
        return popupKeysPanel?.isShowingInParent() == true
    }

    override fun onCancelPopupKeysPanel() {
        PointerTracker.dismissAllPopupKeysPanels()
    }

    override fun onDismissPopupKeysPanel() {
        val panel = popupKeysPanel
        if (panel != null && panel.isShowingInParent()) {
            panel.removeFromParent()
            popupKeysPanel = null
        }
    }

    fun startDoubleTapShiftKeyTimer() {
        timerHandler.startDoubleTapShiftKeyTimer()
    }

    fun cancelDoubleTapShiftKeyTimer() {
        timerHandler.cancelDoubleTapShiftKeyTimer()
    }

    fun popDoubleTapShiftKeyTimer(): Boolean {
        return timerHandler.popDoubleTapShiftKeyTimer()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (getKeyboard() == null) {
            return false
        }
        val ev = event!!
        if (nonDistinctMultitouchHelper != null) {
            if (ev.getPointerCount() > 1 && timerHandler.isInKeyRepeat()) {
                // Key repeating timer will be canceled if 2 or popup keys are in action.
                timerHandler.cancelKeyRepeatTimers()
            }
            // Non distinct multitouch screen support
            nonDistinctMultitouchHelper.processMotionEvent(ev, keyDetector)
            return true
        }
        return processMotionEvent(ev)
    }

    fun processMotionEvent(event: MotionEvent): Boolean {
        val index = event.getActionIndex()
        val id = event.getPointerId(index)
        val tracker = PointerTracker.getPointerTracker(id)
        // When a popup keys panel is showing, we should ignore other fingers' single touch events
        // other than the finger that is showing the popup keys panel.
        if (isShowingPopupKeysPanel() && !tracker.isShowingPopupKeysPanel()
                && PointerTracker.getActivePointerTrackerCount() == 1) {
            return true
        }
        tracker.processMotionEvent(event, keyDetector)
        return true
    }

    fun cancelAllOngoingEvents() {
        timerHandler.cancelAllMessages()
        PointerTracker.setReleasedKeyGraphicsToAllKeys()
        gestureFloatingTextDrawingPreview.dismissGestureFloatingPreviewText()
        slidingKeyInputDrawingPreview.dismissSlidingKeyInputPreview()
        PointerTracker.dismissAllPopupKeysPanels()
        PointerTracker.cancelAllPointerTrackers()
    }

    fun closing() {
        cancelAllOngoingEvents()
        popupKeysKeyboardCache.clear()
    }

    fun onHideWindow() {
        onDismissPopupKeysPanel()
        val delegate = accessibilityDelegate
        if (delegate != null
                && AccessibilityUtils.instance.isAccessibilityEnabled) {
            delegate.onHideWindow()
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun onHoverEvent(event: MotionEvent?): Boolean {
        val delegate = accessibilityDelegate
        if (delegate != null
                && AccessibilityUtils.instance.isTouchExplorationEnabled) {
            return delegate.onHoverEvent(event!!)
        }
        return super.onHoverEvent(event)
    }

    fun updateShortcutKey(available: Boolean) {
        val keyboard = getKeyboard()
        if (keyboard == null) {
            return
        }
        val shortcutKey = keyboard.getKey(KeyCode.VOICE_INPUT)
        if (shortcutKey == null) {
            return
        }
        shortcutKey.setEnabled(available)
        invalidateKey(shortcutKey)
    }

    fun updateLockState(keyCode: Int, locked: Boolean) {
        val keyboard = getKeyboard()
        if (keyboard == null) {
            return
        }
        val lockKey = keyboard.getKey(keyCode)
        if (lockKey == null) {
            return
        }
        lockKey.setLocked(locked)
        invalidateKey(lockKey)
    }

    // the whole language on spacebar thing could probably be simplified quite a bit
    fun startDisplayLanguageOnSpacebar(subtypeChanged: Boolean,
            languageOnSpacebarFormatType: Int,
            hasMultipleEnabledIMEsOrSubtypes: Boolean) {
        if (subtypeChanged) {
            KeyPreviewView.clearTextCache()
        }
        this.languageOnSpacebarFormatType = languageOnSpacebarFormatType
        this.hasMultipleEnabledIMEsOrSubtypes = hasMultipleEnabledIMEsOrSubtypes
        val animator = languageOnSpacebarFadeoutAnimator
        if (animator == null) {
            this.languageOnSpacebarFormatType = LanguageOnSpacebarUtils.FORMAT_TYPE_NONE
        } else {
            if (subtypeChanged
                    && languageOnSpacebarFormatType != LanguageOnSpacebarUtils.FORMAT_TYPE_NONE) {
                setLanguageOnSpacebarAnimAlpha(Constants.Color.ALPHA_OPAQUE)
                if (animator.isStarted()) {
                    animator.cancel()
                }
                animator.start()
            } else {
                if (!animator.isStarted()) {
                    languageOnSpacebarAnimAlpha = languageOnSpacebarFinalAlpha
                }
            }
        }
        invalidateKey(spaceKey)
    }

    override fun onDrawKeyTopVisuals(key: Key, canvas: Canvas,
            paint: Paint, params: KeyDrawParams) {
        if (key.altCodeWhileTyping() && key.isEnabled()) {
            params.mAnimAlpha = Constants.Color.ALPHA_OPAQUE
        }
        super.onDrawKeyTopVisuals(key, canvas, paint, params)
        val code = key.getCode()
        if (code == Constants.CODE_SPACE) {
            // If input language are explicitly selected.
            if (languageOnSpacebarFormatType != LanguageOnSpacebarUtils.FORMAT_TYPE_NONE) {
                drawLanguageOnSpacebar(key, canvas, paint)
            }
            // Whether space key needs to show the "..." popup hint for special purposes
            if (key.isLongPressEnabled() && hasMultipleEnabledIMEsOrSubtypes && Settings.getValues().mSpaceForLangChange) {
                drawKeyPopupHint(key, canvas, paint, params)
            }
        } else if (code == KeyCode.LANGUAGE_SWITCH) {
            drawKeyPopupHint(key, canvas, paint, params)
        }
    }

    private fun fitsTextIntoWidth(width: Int, text: String, paint: Paint): Boolean {
        val maxTextWidth = width - languageOnSpacebarHorizontalMargin * 2
        paint.setTextScaleX(1.0f)
        val textWidth = TypefaceUtils.getStringWidth(text, paint)
        if (textWidth < width) {
            return true
        }

        val scaleX = maxTextWidth / textWidth
        if (scaleX < MINIMUM_XSCALE_OF_LANGUAGE_NAME) {
            return false
        }

        paint.setTextScaleX(scaleX)
        return TypefaceUtils.getStringWidth(text, paint) < maxTextWidth
    }

    // Layout language name on spacebar.
    private fun layoutLanguageOnSpacebar(paint: Paint,
            subtype: RichInputMethodSubtype, width: Int): String {
        // Choose appropriate language name to fit into the width.

        val secondaryLocales = Settings.getValues().mSecondaryLocales
        // avoid showing same language twice
        val secondaryLocalesToUse = withoutDuplicateLanguages(secondaryLocales, subtype.locale.getLanguage())
        if (!secondaryLocalesToUse.isEmpty()) {
            val sb = StringBuilder(subtype.middleDisplayName)
            val displayLocale = getResources().getConfiguration().locale()
            for (locale in secondaryLocales) {
                sb.append(" - ")
                sb.append(locale.getDisplayLanguage(displayLocale))
            }
            val full = sb.toString()
            if (fitsTextIntoWidth(width, full, paint)) {
                return full
            }
            sb.setLength(0)
            sb.append(subtype.locale.getLanguage().uppercase(displayLocale))
            for (locale in secondaryLocales) {
                sb.append(" - ")
                sb.append(locale.getLanguage().uppercase(displayLocale))
            }
            val middle = sb.toString()
            if (fitsTextIntoWidth(width, middle, paint)) {
                return middle
            }
        }

        if (languageOnSpacebarFormatType == LanguageOnSpacebarUtils.FORMAT_TYPE_FULL_LOCALE) {
            val fullText = subtype.fullDisplayName
            if (fitsTextIntoWidth(width, fullText, paint)) {
                return fullText
            }
        }

        val middleText = subtype.middleDisplayName
        if (fitsTextIntoWidth(width, middleText, paint)) {
            return middleText
        }

        return ""
    }

    private fun withoutDuplicateLanguages(locales: List<Locale>, mainLanguage: String): List<Locale> {
        val languages = object : ArrayList<String>() { init { add(mainLanguage) } }
        val newLocales = ArrayList<Locale>()
        for (locale in locales) {
            var keep = true
            for (language in languages) {
                if (locale.getLanguage() == language)
                    keep = false
            }
            if (!keep)
                continue
            languages.add(locale.getLanguage())
            newLocales.add(locale)
        }
        return newLocales
    }

    private fun drawLanguageOnSpacebar(key: Key, canvas: Canvas, paint: Paint) {
        val keyboard = getKeyboard()
        if (keyboard == null) {
            return
        }
        val width = key.getWidth()
        val height = key.getHeight()
        paint.setTextAlign(Align.CENTER)
        paint.setTextSize(languageOnSpacebarTextSize)
        val customText = Settings.getValues().mSpaceBarText
        val spaceText: String
        if (!customText.isEmpty()) {
            spaceText = customText
        } else if (DebugFlags.DEBUG_ENABLED) {
            val l = KeyboardSwitcher.getInstance().getLocaleAndConfidenceInfo()
            spaceText = if (l != null) l else layoutLanguageOnSpacebar(paint, keyboard.mId.subtype, width)
        }
        else
            spaceText = layoutLanguageOnSpacebar(paint, keyboard.mId.subtype, width)
        paint.setTypeface(KeyboardTypeface.resolve(spaceText, Typeface.DEFAULT))
        // Draw language text with shadow
        val descent = paint.descent()
        val textHeight = -paint.ascent() + descent
        val baseline = height / 2f + textHeight / 2
        if (languageOnSpacebarTextShadowRadius > 0.0f) {
            paint.setShadowLayer(languageOnSpacebarTextShadowRadius, 0f, 0f,
                    languageOnSpacebarTextShadowColor)
        } else {
            paint.clearShadowLayer()
        }
        paint.setColor(languageOnSpacebarTextColor)
        paint.setAlpha(languageOnSpacebarAnimAlpha)
        if (!fitsTextIntoWidth(width, spaceText, paint)) {
            val textWidth = TypefaceUtils.getStringWidth(spaceText, paint)
            paint.setTextScaleX((width - languageOnSpacebarHorizontalMargin * 2) / textWidth)
        }
        canvas.drawText(spaceText, width / 2f, baseline - descent, paint)
        paint.clearShadowLayer()
        paint.setTextScaleX(1.0f)
    }

    override fun deallocateMemory() {
        super.deallocateMemory()
        drawingPreviewPlacerView.deallocateMemory()
    }

    companion object {
        private val TAG = MainKeyboardView::class.java.simpleName

        private const val LANGUAGE_ON_SPACEBAR_TEXT_SHADOW_RADIUS_DISABLED = -1.0f
        // The minimum x-scale to fit the language name on spacebar.
        private const val MINIMUM_XSCALE_OF_LANGUAGE_NAME = 0.8f

        private fun cancelAndStartAnimators(animatorToCancel: ObjectAnimator?,
                animatorToStart: ObjectAnimator?) {
            if (animatorToCancel == null || animatorToStart == null) {
                // TODO: Stop using null as a no-operation animator.
                return
            }
            var startFraction = 0.0f
            if (animatorToCancel.isStarted()) {
                animatorToCancel.cancel()
                startFraction = 1.0f - animatorToCancel.getAnimatedFraction()
            }
            val startTime = (animatorToStart.getDuration() * startFraction).toLong()
            animatorToStart.start()
            animatorToStart.setCurrentPlayTime(startTime)
        }
    }
}
