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
    private var mKeyboardActionListener: KeyboardActionListener

    /* Space key and its icon and background. */
    private var mSpaceKey: Key? = null
    // Stuff to draw language name on spacebar.
    private val mLanguageOnSpacebarFinalAlpha: Int
    private val mLanguageOnSpacebarFadeoutAnimator: ObjectAnimator?
    private var mLanguageOnSpacebarFormatType: Int = 0
    private var mHasMultipleEnabledIMEsOrSubtypes: Boolean = false
    private var mLanguageOnSpacebarAnimAlpha: Int = Constants.Color.ALPHA_OPAQUE
    private val mLanguageOnSpacebarTextRatio: Float
    private var mLanguageOnSpacebarTextSize: Float = 0f
    private val mLanguageOnSpacebarTextColor: Int
    private val mLanguageOnSpacebarTextShadowRadius: Float
    private val mLanguageOnSpacebarTextShadowColor: Int

    // Stuff to draw altCodeWhileTyping keys.
    private val mAltCodeKeyWhileTypingFadeoutAnimator: ObjectAnimator?
    private val mAltCodeKeyWhileTypingFadeinAnimator: ObjectAnimator?

    // Drawing preview placer view
    private val mDrawingPreviewPlacerView: DrawingPreviewPlacerView
    private val mOriginCoords: IntArray = CoordinateUtils.newInstance()
    private val mGestureFloatingTextDrawingPreview: GestureFloatingTextDrawingPreview
    private val mGestureTrailsDrawingPreview: GestureTrailsDrawingPreview
    private val mSlidingKeyInputDrawingPreview: SlidingKeyInputDrawingPreview

    // Key preview
    private val mKeyPreviewDrawParams: KeyPreviewDrawParams
    private val mKeyPreviewChoreographer: KeyPreviewChoreographer

    // More keys keyboard
    private val mBackgroundDimAlphaPaint: Paint = Paint() // todo: not used at all
    private val mPopupKeysKeyboardContainer: View
    private val mPopupKeysKeyboardForActionContainer: View
    private val mPopupKeysKeyboardCache: WeakHashMap<Key, Keyboard> = WeakHashMap()
    private val mConfigShowPopupKeysKeyboardAtTouchedPoint: Boolean
    // More keys panel (used by both popup keys keyboard and more suggestions view)
    // TODO: Consider extending to support multiple popup keys panels
    private var mPopupKeysPanel: PopupKeysPanel? = null

    // Gesture floating preview text
    private val mGestureFloatingPreviewTextLingerTimeout: Int

    private val mKeyDetector: KeyDetector
    private val mNonDistinctMultitouchHelper: NonDistinctMultitouchHelper?

    private val mTimerHandler: TimerHandler
    private val mLanguageOnSpacebarHorizontalMargin: Int

    private var mAccessibilityDelegate: MainKeyboardAccessibilityDelegate? = null

    init {

        val drawingPreviewPlacerView =
                DrawingPreviewPlacerView(ContextThemeWrapper(context, R.style.platformActivityTheme), attrs)

        val mainKeyboardViewAttr = context.obtainStyledAttributes(
                attrs, R.styleable.MainKeyboardView, defStyle, R.style.MainKeyboardView)
        val ignoreAltCodeKeyTimeout = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_ignoreAltCodeKeyTimeout, 0)
        val gestureRecognitionUpdateTime = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureRecognitionUpdateTime, 0)
        mTimerHandler = TimerHandler(this, ignoreAltCodeKeyTimeout, gestureRecognitionUpdateTime)

        val keyHysteresisDistance = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_keyHysteresisDistance, 0.0f)
        val keyHysteresisDistanceForSlidingModifier = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_keyHysteresisDistanceForSlidingModifier, 0.0f)
        mKeyDetector = KeyDetector(keyHysteresisDistance, keyHysteresisDistanceForSlidingModifier)

        PointerTracker.init(mainKeyboardViewAttr, mTimerHandler, this /* DrawingProxy */)

        val prefs: SharedPreferences = context.prefs()
        val forceNonDistinctMultitouch = prefs.getBoolean(
                DebugSettings.PREF_FORCE_NON_DISTINCT_MULTITOUCH, Defaults.PREF_FORCE_NON_DISTINCT_MULTITOUCH)
        val hasDistinctMultitouch = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT)
                && !forceNonDistinctMultitouch
        mNonDistinctMultitouchHelper = if (hasDistinctMultitouch) null else NonDistinctMultitouchHelper()

        val backgroundDimAlpha = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_backgroundDimAlpha, 0)
        mBackgroundDimAlphaPaint.setColor(Color.BLACK)
        mBackgroundDimAlphaPaint.setAlpha(backgroundDimAlpha)
        mLanguageOnSpacebarTextRatio = mainKeyboardViewAttr.getFraction(
                R.styleable.MainKeyboardView_languageOnSpacebarTextRatio, 1, 1, 1.0f) *
                Settings.getValues().mFontSizeMultiplier
        val colors: Colors = Settings.getValues().mColors
        mLanguageOnSpacebarTextColor = colors.get(ColorType.SPACE_BAR_TEXT)
        mLanguageOnSpacebarTextShadowRadius = mainKeyboardViewAttr.getFloat(
                R.styleable.MainKeyboardView_languageOnSpacebarTextShadowRadius,
                LANGUAGE_ON_SPACEBAR_TEXT_SHADOW_RADIUS_DISABLED)
        mLanguageOnSpacebarTextShadowColor = mainKeyboardViewAttr.getColor(
                R.styleable.MainKeyboardView_languageOnSpacebarTextShadowColor, 0)
        mLanguageOnSpacebarFinalAlpha = Color.alpha(mLanguageOnSpacebarTextColor)
        val languageOnSpacebarFadeoutAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_languageOnSpacebarFadeoutAnimator, 0)
        val altCodeKeyWhileTypingFadeoutAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeoutAnimator, 0)
        val altCodeKeyWhileTypingFadeinAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeinAnimator, 0)

        mKeyPreviewDrawParams = KeyPreviewDrawParams(mainKeyboardViewAttr)
        mKeyPreviewChoreographer = KeyPreviewChoreographer(mKeyPreviewDrawParams)

        val popupKeysKeyboardLayoutId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_popupKeysKeyboardLayout, 0)
        val popupKeysKeyboardForActionLayoutId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_popupKeysKeyboardForActionLayout,
                popupKeysKeyboardLayoutId)
        mConfigShowPopupKeysKeyboardAtTouchedPoint = mainKeyboardViewAttr.getBoolean(
                R.styleable.MainKeyboardView_showPopupKeysKeyboardAtTouchedPoint, false)

        val gestureTrailFadeoutDuration = Settings.getValues().mGestureTrailFadeoutDuration
        mGestureFloatingPreviewTextLingerTimeout = gestureTrailFadeoutDuration / 4

        mGestureFloatingTextDrawingPreview = GestureFloatingTextDrawingPreview(mainKeyboardViewAttr)
        mGestureFloatingTextDrawingPreview.setDrawingView(drawingPreviewPlacerView)

        mGestureTrailsDrawingPreview = GestureTrailsDrawingPreview(mainKeyboardViewAttr)
        mGestureTrailsDrawingPreview.setDrawingView(drawingPreviewPlacerView)

        mSlidingKeyInputDrawingPreview = SlidingKeyInputDrawingPreview(mainKeyboardViewAttr)
        mSlidingKeyInputDrawingPreview.setDrawingView(drawingPreviewPlacerView)
        mainKeyboardViewAttr.recycle()

        mDrawingPreviewPlacerView = drawingPreviewPlacerView

        val inflater = LayoutInflater.from(getContext())
        mPopupKeysKeyboardContainer = inflater.inflate(popupKeysKeyboardLayoutId, null)
        mPopupKeysKeyboardForActionContainer = inflater.inflate(popupKeysKeyboardForActionLayoutId, null)
        mLanguageOnSpacebarFadeoutAnimator = loadObjectAnimator(languageOnSpacebarFadeoutAnimatorResId, this)
        if (mLanguageOnSpacebarFadeoutAnimator != null)
            mLanguageOnSpacebarFadeoutAnimator.setIntValues(255, mLanguageOnSpacebarFinalAlpha)
        mAltCodeKeyWhileTypingFadeoutAnimator = loadObjectAnimator(altCodeKeyWhileTypingFadeoutAnimatorResId, this)
        mAltCodeKeyWhileTypingFadeinAnimator = loadObjectAnimator(altCodeKeyWhileTypingFadeinAnimatorResId, this)

        mKeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER

        mLanguageOnSpacebarHorizontalMargin = getResources().getDimension(
                R.dimen.config_language_on_spacebar_horizontal_margin).toInt()
    }

    override fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        super.setHardwareAcceleratedDrawingEnabled(enabled)
        mDrawingPreviewPlacerView.setHardwareAcceleratedDrawingEnabled(enabled)
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
                    mAltCodeKeyWhileTypingFadeoutAnimator, mAltCodeKeyWhileTypingFadeinAnimator)
            DrawingProxy.FADE_OUT -> cancelAndStartAnimators(
                    mAltCodeKeyWhileTypingFadeinAnimator, mAltCodeKeyWhileTypingFadeoutAnimator)
        }
    }

    fun setLanguageOnSpacebarAnimAlpha(alpha: Int) {
        mLanguageOnSpacebarAnimAlpha = alpha
        invalidateKey(mSpaceKey)
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        mKeyboardActionListener = listener
        PointerTracker.setKeyboardActionListener(listener)
    }

    // TODO: We should reconsider which coordinate system should be used to represent keyboard event.
    fun getKeyX(x: Int): Int {
        return if (Constants.isValidCoordinate(x)) mKeyDetector.getTouchX(x) else x
    }

    // TODO: We should reconsider which coordinate system should be used to represent keyboard event.
    fun getKeyY(y: Int): Int {
        return if (Constants.isValidCoordinate(y)) mKeyDetector.getTouchY(y) else y
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
        mTimerHandler.cancelLongPressTimers()
        super.setKeyboard(keyboard)
        app.caret.latin.swipe.FutoSwipe.warmUp(keyboard)
        mKeyDetector.setKeyboard(
                keyboard, (-getPaddingLeft()).toFloat(), -getPaddingTop() + getVerticalCorrection())
        PointerTracker.setKeyDetector(mKeyDetector)
        mPopupKeysKeyboardCache.clear()

        mSpaceKey = keyboard.getKey(Constants.CODE_SPACE)
        val keyHeight = keyboard.mMostCommonKeyHeight - keyboard.mVerticalGap
        mLanguageOnSpacebarTextSize = keyHeight * mLanguageOnSpacebarTextRatio

        if (AccessibilityUtils.instance.isAccessibilityEnabled) {
            if (mAccessibilityDelegate == null) {
                mAccessibilityDelegate = MainKeyboardAccessibilityDelegate(this, mKeyDetector)
            }
            mAccessibilityDelegate!!.keyboard = keyboard
        } else {
            mAccessibilityDelegate = null
        }
    }

    /**
     * Enables or disables the key preview popup. This is a popup that shows a magnified
     * version of the depressed key. By default the preview is enabled.
     * @param previewEnabled whether or not to enable the key feedback preview
     */
    fun setKeyPreviewPopupEnabled(previewEnabled: Boolean) {
        mKeyPreviewDrawParams.setPopupEnabled(previewEnabled)
    }

    private fun locatePreviewPlacerView() {
        getLocationInWindow(mOriginCoords)
        mDrawingPreviewPlacerView.setKeyboardViewGeometry(mOriginCoords, getWidth(), getHeight())
    }

    private fun installPreviewPlacerView() {
        val rootView: View? = getRootView()
        if (rootView == null) {
            Log.w(TAG, "Cannot find root view")
            return
        }
        val windowContentView: ViewGroup? = rootView.findViewById(android.R.id.content)
        val vg = mDrawingPreviewPlacerView.getParent()
        if (vg is ViewGroup)
            vg.removeView(mDrawingPreviewPlacerView) // when moving keyboard from input method content view to floating container
        // Note: It'd be very weird if we get null by android.R.id.content.
        if (windowContentView == null) {
            Log.w(TAG, "Cannot find android.R.id.content view to add DrawingPreviewPlacerView")
            return
        }
        windowContentView.addView(mDrawingPreviewPlacerView)
    }

    // Implements {@link DrawingProxy#onKeyPressed(Key,boolean)}.
    override fun onKeyPressed(key: Key, withPreview: Boolean) {
        key.onPressed()
        invalidateKey(key)

        val keyboard = getKeyboard()
        if (keyboard == null) {
            return
        }
        mKeyPreviewDrawParams.setVisibleOffset(-keyboard.mVerticalGap)
        if (withPreview && key.hasPreview() && mKeyPreviewDrawParams.isPopupEnabled()) {
            showKeyPreview(key)
        }
    }

    private fun showKeyPreview(key: Key) {
        locatePreviewPlacerView()
        getLocationInWindow(mOriginCoords)
        mKeyPreviewChoreographer.placeAndShowKeyPreview(key, getKeyboard()!!.mIconsSet, getKeyDrawParams(),
                KeyboardSwitcher.getInstance().getWrapperView().getWidth(), mOriginCoords, mDrawingPreviewPlacerView)
    }

    private fun dismissKeyPreviewWithoutDelay(key: Key) {
        mKeyPreviewChoreographer.dismissKeyPreview(key)
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
            mKeyPreviewChoreographer.dismissKeyPreview(key)
        }
    }

    fun setSlidingKeyInputPreviewEnabled(enabled: Boolean) {
        mSlidingKeyInputDrawingPreview.setPreviewEnabled(enabled)
    }

    override fun showSlidingKeyInputPreview(tracker: PointerTracker?) {
        locatePreviewPlacerView()
        if (tracker != null) {
            mSlidingKeyInputDrawingPreview.setPreviewPosition(tracker)
        } else {
            mSlidingKeyInputDrawingPreview.dismissSlidingKeyInputPreview()
        }
    }

    private fun setGesturePreviewMode(isGestureTrailEnabled: Boolean,
            isGestureFloatingPreviewTextEnabled: Boolean) {
        mGestureFloatingTextDrawingPreview.setPreviewEnabled(isGestureFloatingPreviewTextEnabled)
        mGestureTrailsDrawingPreview.setPreviewEnabled(isGestureTrailEnabled)
    }

    fun showGestureFloatingPreviewText(suggestedWords: SuggestedWords,
            dismissDelayed: Boolean) {
        locatePreviewPlacerView()
        mGestureFloatingTextDrawingPreview.setSuggestedWords(suggestedWords)
        if (dismissDelayed) {
            mTimerHandler.postDismissGestureFloatingPreviewText(
                    mGestureFloatingPreviewTextLingerTimeout.toLong())
        }
    }

    // Implements {@link DrawingProxy#dismissGestureFloatingPreviewTextWithoutDelay()}.
    override fun dismissGestureFloatingPreviewTextWithoutDelay() {
        mGestureFloatingTextDrawingPreview.dismissGestureFloatingPreviewText()
    }

    override fun showGestureTrail(tracker: PointerTracker,
            showsFloatingPreviewText: Boolean) {
        locatePreviewPlacerView()
        if (showsFloatingPreviewText) {
            mGestureFloatingTextDrawingPreview.setPreviewPosition(tracker)
        }
        mGestureTrailsDrawingPreview.setPreviewPosition(tracker)
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
        mDrawingPreviewPlacerView.removeAllViews()
    }

    // Implements {@link DrawingProxy@showPopupKeysKeyboard(Key,PointerTracker)}.
    override fun showPopupKeysKeyboard(key: Key,
                                       tracker: PointerTracker): PopupKeysPanel? {
        val popupKeys = key.getPopupKeys()
        if (popupKeys == null) {
            return null
        }
        var popupKeysKeyboard: Keyboard? = mPopupKeysKeyboardCache.get(key)
        if (popupKeysKeyboard == null) {
            // {@link KeyPreviewDrawParams#mPreviewVisibleWidth} should have been set at
            // {@link KeyPreviewChoreographer#placeKeyPreview(Key,TextView,KeyboardIconsSet,KeyDrawParams,int,int[]},
            // though there may be some chances that the value is zero. <code>width == 0</code>
            // will cause zero-division error at
            // {@link PopupKeysKeyboardParams#setParameters(int,int,int,int,int,int,boolean,int)}.
            val isSinglePopupKeyWithPreview = mKeyPreviewDrawParams.isPopupEnabled()
                    && key.hasPreview() && popupKeys.size == 1
                    && mKeyPreviewDrawParams.getVisibleWidth() > 0
            val builder = PopupKeysKeyboard.Builder(
                    getContext(), key, getKeyboard(), isSinglePopupKeyWithPreview,
                    mKeyPreviewDrawParams.getVisibleWidth(),
                    mKeyPreviewDrawParams.getVisibleHeight(), newLabelPaint(key))
            popupKeysKeyboard = builder.build()
            mPopupKeysKeyboardCache.put(key, popupKeysKeyboard)
        }

        val container = if (key.hasActionKeyPopups()) mPopupKeysKeyboardForActionContainer
                else mPopupKeysKeyboardContainer
        val popupKeysKeyboardView: PopupKeysKeyboardView =
                container.findViewById(R.id.popup_keys_keyboard_view)
        popupKeysKeyboardView.setKeyboard(popupKeysKeyboard)
        container.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val lastCoords = CoordinateUtils.newInstance()
        tracker.getLastCoordinates(lastCoords)
        val keyPreviewEnabled = mKeyPreviewDrawParams.isPopupEnabled() && key.hasPreview()
        // The popup keys keyboard is usually horizontally aligned with the center of the parent key.
        // If showPopupKeysKeyboardAtTouchedPoint is true and the key preview is disabled, the more
        // keys keyboard is placed at the touch point of the parent key.
        val pointX = if (mConfigShowPopupKeysKeyboardAtTouchedPoint && !keyPreviewEnabled)
                CoordinateUtils.x(lastCoords)
                else key.getX() + key.getWidth() / 2
        // The popup keys keyboard is usually vertically aligned with the top edge of the parent key
        // (plus vertical gap). If the key preview is enabled, the popup keys keyboard is vertically
        // aligned with the bottom edge of the visible part of the key preview.
        // {@code mPreviewVisibleOffset} has been set appropriately in
        // {@link KeyboardView#showKeyPreview(PointerTracker)}.
        val pointY = key.getY() + mKeyPreviewDrawParams.getVisibleOffset()
        popupKeysKeyboardView.showPopupKeysPanel(this, this, pointX, pointY, mKeyboardActionListener)
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
        mSlidingKeyInputDrawingPreview.dismissSlidingKeyInputPreview()
        panel.showInParent(mDrawingPreviewPlacerView)
        mPopupKeysPanel = panel
    }

    fun isShowingPopupKeysPanel(): Boolean {
        return mPopupKeysPanel != null && mPopupKeysPanel!!.isShowingInParent()
    }

    override fun onCancelPopupKeysPanel() {
        PointerTracker.dismissAllPopupKeysPanels()
    }

    override fun onDismissPopupKeysPanel() {
        if (isShowingPopupKeysPanel()) {
            mPopupKeysPanel!!.removeFromParent()
            mPopupKeysPanel = null
        }
    }

    fun startDoubleTapShiftKeyTimer() {
        mTimerHandler.startDoubleTapShiftKeyTimer()
    }

    fun cancelDoubleTapShiftKeyTimer() {
        mTimerHandler.cancelDoubleTapShiftKeyTimer()
    }

    fun popDoubleTapShiftKeyTimer(): Boolean {
        return mTimerHandler.popDoubleTapShiftKeyTimer()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (getKeyboard() == null) {
            return false
        }
        if (mNonDistinctMultitouchHelper != null) {
            if (event!!.getPointerCount() > 1 && mTimerHandler.isInKeyRepeat()) {
                // Key repeating timer will be canceled if 2 or popup keys are in action.
                mTimerHandler.cancelKeyRepeatTimers()
            }
            // Non distinct multitouch screen support
            mNonDistinctMultitouchHelper.processMotionEvent(event, mKeyDetector)
            return true
        }
        return processMotionEvent(event!!)
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
        tracker.processMotionEvent(event, mKeyDetector)
        return true
    }

    fun cancelAllOngoingEvents() {
        mTimerHandler.cancelAllMessages()
        PointerTracker.setReleasedKeyGraphicsToAllKeys()
        mGestureFloatingTextDrawingPreview.dismissGestureFloatingPreviewText()
        mSlidingKeyInputDrawingPreview.dismissSlidingKeyInputPreview()
        PointerTracker.dismissAllPopupKeysPanels()
        PointerTracker.cancelAllPointerTrackers()
    }

    fun closing() {
        cancelAllOngoingEvents()
        mPopupKeysKeyboardCache.clear()
    }

    fun onHideWindow() {
        onDismissPopupKeysPanel()
        val accessibilityDelegate = mAccessibilityDelegate
        if (accessibilityDelegate != null
                && AccessibilityUtils.instance.isAccessibilityEnabled) {
            accessibilityDelegate.onHideWindow()
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun onHoverEvent(event: MotionEvent?): Boolean {
        val accessibilityDelegate = mAccessibilityDelegate
        if (accessibilityDelegate != null
                && AccessibilityUtils.instance.isTouchExplorationEnabled) {
            return accessibilityDelegate.onHoverEvent(event!!)
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
        mLanguageOnSpacebarFormatType = languageOnSpacebarFormatType
        mHasMultipleEnabledIMEsOrSubtypes = hasMultipleEnabledIMEsOrSubtypes
        val animator = mLanguageOnSpacebarFadeoutAnimator
        if (animator == null) {
            mLanguageOnSpacebarFormatType = LanguageOnSpacebarUtils.FORMAT_TYPE_NONE
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
                    mLanguageOnSpacebarAnimAlpha = mLanguageOnSpacebarFinalAlpha
                }
            }
        }
        invalidateKey(mSpaceKey)
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
            if (mLanguageOnSpacebarFormatType != LanguageOnSpacebarUtils.FORMAT_TYPE_NONE) {
                drawLanguageOnSpacebar(key, canvas, paint)
            }
            // Whether space key needs to show the "..." popup hint for special purposes
            if (key.isLongPressEnabled() && mHasMultipleEnabledIMEsOrSubtypes && Settings.getValues().mSpaceForLangChange) {
                drawKeyPopupHint(key, canvas, paint, params)
            }
        } else if (code == KeyCode.LANGUAGE_SWITCH) {
            drawKeyPopupHint(key, canvas, paint, params)
        }
    }

    private fun fitsTextIntoWidth(width: Int, text: String, paint: Paint): Boolean {
        val maxTextWidth = width - mLanguageOnSpacebarHorizontalMargin * 2
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

        if (mLanguageOnSpacebarFormatType == LanguageOnSpacebarUtils.FORMAT_TYPE_FULL_LOCALE) {
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
        paint.setTextSize(mLanguageOnSpacebarTextSize)
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
        if (mLanguageOnSpacebarTextShadowRadius > 0.0f) {
            paint.setShadowLayer(mLanguageOnSpacebarTextShadowRadius, 0f, 0f,
                    mLanguageOnSpacebarTextShadowColor)
        } else {
            paint.clearShadowLayer()
        }
        paint.setColor(mLanguageOnSpacebarTextColor)
        paint.setAlpha(mLanguageOnSpacebarAnimAlpha)
        if (!fitsTextIntoWidth(width, spaceText, paint)) {
            val textWidth = TypefaceUtils.getStringWidth(spaceText, paint)
            paint.setTextScaleX((width - mLanguageOnSpacebarHorizontalMargin * 2) / textWidth)
        }
        canvas.drawText(spaceText, width / 2f, baseline - descent, paint)
        paint.clearShadowLayer()
        paint.setTextScaleX(1.0f)
    }

    override fun deallocateMemory() {
        super.deallocateMemory()
        mDrawingPreviewPlacerView.deallocateMemory()
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
