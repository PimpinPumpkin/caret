/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import app.caret.keyboard.KeyboardTheme.Companion.STYLE_ROUNDED
import app.caret.keyboard.emoji.EmojiPageKeyboardView
import app.caret.keyboard.internal.KeyDrawParams
import app.caret.keyboard.internal.KeyVisualAttributes
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.R
import app.caret.latin.common.ColorType
import app.caret.latin.common.Colors
import app.caret.latin.common.Constants
import app.caret.latin.common.isEmoji
import app.caret.latin.settings.Settings
import app.caret.latin.suggestions.MoreSuggestions
import app.caret.latin.suggestions.MoreSuggestionsView
import app.caret.latin.utils.TypefaceUtils
import java.util.HashSet

/** A view that renders a virtual {@link Keyboard}. */
// todo: this ThemeStyle-dependent stuff really should not be in here!
open class KeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet?,
        defStyle: Int = R.attr.keyboardViewStyle) : View(context, attrs, defStyle) {
    // XML attributes
    private val mKeyVisualAttributes: KeyVisualAttributes?
    // Default keyLabelFlags from {@link KeyboardTheme}.
    // Currently only "alignHintLabelToBottom" is supported.
    private val defaultKeyLabelFlags: Int
    private val keyHintLetterPadding: Float
    private val keyPopupHintLetter: String?
    private val keyPopupHintLetterPadding: Float
    private val keyShiftedLetterHintPadding: Float
    private val keyTextShadowRadius: Float
    private val mVerticalCorrection: Float
    private val keyBackground: Drawable
    private val functionalKeyBackground: Drawable
    private val actionKeyBackground: Drawable
    private val spacebarBackground: Drawable
    private val spacebarIconWidthRatio: Float
    private val keyBackgroundPadding = Rect()
    private val colors: Colors
    private var keyScaleForText = 0f
    protected var mFontSizeMultiplier = 0f
    protected var mHintFontSizeMultiplier = 0f

    // Main keyboard
    // TODO: Consider having a dummy keyboard object to make this @NonNull
    private var mKeyboard: Keyboard? = null
    private val mKeyDrawParams = KeyDrawParams()

    // Drawing
    /** True if all keys should be drawn */
    private var mInvalidateAllKeys = false
    /** The keys that should be drawn */
    private val invalidatedKeys = HashSet<Key>()
    /** The working rectangle for clipping */
    private val clipRect = Rect()
    /** The keyboard bitmap buffer for faster updates */
    private var offscreenBuffer: Bitmap? = null
    /** Flag for whether the key hints should be displayed */
    private var showsHints = false
    /** Scale for downscaling icons and fixed size backgrounds if keyboard height is set below 80% */
    private var iconScaleFactor = 0f
    /** The canvas for the above mutable keyboard bitmap */
    private val offscreenCanvas = Canvas()
    private val mPaint = Paint()
    private val fontMetrics = Paint.FontMetrics()

    init {
        colors = Settings.getValues().mColors

        val keyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        if (this is MoreSuggestionsView)
            keyBackground = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.MORE_SUGGESTIONS_WORD_BACKGROUND)
        else if (this is PopupKeysKeyboardView)
            keyBackground = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.POPUP_KEYS_BACKGROUND)
        else
            keyBackground = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.KEY_BACKGROUND)
        keyBackground.getPadding(keyBackgroundPadding)
        functionalKeyBackground = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.FUNCTIONAL_KEY_BACKGROUND)
        spacebarBackground = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.SPACE_BAR_BACKGROUND)
        if (this is PopupKeysKeyboardView)
            actionKeyBackground = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.ACTION_KEY_POPUP_KEYS_BACKGROUND)
        else
            actionKeyBackground = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.ACTION_KEY_BACKGROUND)

        spacebarIconWidthRatio = keyboardViewAttr.getFloat(
                R.styleable.KeyboardView_spacebarIconWidthRatio, 1.0f)
        keyHintLetterPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyHintLetterPadding, 0.0f)
        keyPopupHintLetter = if (Settings.getValues().mShowsPopupHints)
                keyboardViewAttr.getString(R.styleable.KeyboardView_keyPopupHintLetter)
                else ""
        keyPopupHintLetterPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyPopupHintLetterPadding, 0.0f)
        keyShiftedLetterHintPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyShiftedLetterHintPadding, 0.0f)
        keyTextShadowRadius = keyboardViewAttr.getFloat(
                R.styleable.KeyboardView_keyTextShadowRadius, KET_TEXT_SHADOW_RADIUS_DISABLED)
        mVerticalCorrection = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_verticalCorrection, 0.0f)
        keyboardViewAttr.recycle()

        val keyAttr = context.obtainStyledAttributes(attrs,
                R.styleable.Keyboard_Key, defStyle, R.style.KeyboardView)
        defaultKeyLabelFlags = keyAttr.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0)
        mKeyVisualAttributes = KeyVisualAttributes.newInstance(keyAttr)
        keyAttr.recycle()

        mPaint.setAntiAlias(true)
        setFitsSystemWindows(true)
    }

    fun getKeyVisualAttribute(): KeyVisualAttributes? {
        return mKeyVisualAttributes
    }

    open fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see Keyboard
     * @see #getKeyboard()
     * @param keyboard the keyboard to display in this view
     */
    open fun setKeyboard(keyboard: Keyboard) {
        if (keyboard is MoreSuggestions) {
            colors.setBackground(this, ColorType.MORE_SUGGESTIONS_BACKGROUND)
        } else if (keyboard is PopupKeysKeyboard) {
            colors.setBackground(this, ColorType.POPUP_KEYS_BACKGROUND)
        } else {
            // actual background color/drawable is applied to main_keyboard_frame
            setBackgroundColor(Color.TRANSPARENT)
        }

        mKeyboard = keyboard
        keyScaleForText = Math.sqrt((1 / Settings.getValues().mKeyboardHeightScale).toDouble()).toFloat()
        val scale = Math.min(2 * keyboard.mMostCommonKeyWidth, keyboard.mMostCommonKeyHeight - keyboard.mVerticalGap)
        val scaledKeySize = (scale * keyScaleForText).toInt()
        mKeyDrawParams.updateParams(scaledKeySize, mKeyVisualAttributes)
        mKeyDrawParams.updateParams(scaledKeySize, keyboard.mKeyVisualAttributes)
        invalidateAllKeys()
        requestLayout()
        mFontSizeMultiplier = if (mKeyboard!!.mId.element.isEmojiLayout)
                // In the case of EmojiKeyFit, the size of emojis is taken care of by the size of the keys
                (if (Settings.getValues().mEmojiKeyFit) 1f else Settings.getValues().mFontSizeMultiplierEmoji)
                else Settings.getValues().mFontSizeMultiplier
        mHintFontSizeMultiplier = Settings.getValues().mHintFontSizeMultiplier
    }

    /**
     * Returns the current keyboard being displayed by this view.
     * @return the currently attached keyboard
     * @see #setKeyboard(Keyboard)
     */
    fun getKeyboard(): Keyboard? {
        return mKeyboard
    }

    protected fun getVerticalCorrection(): Float {
        return mVerticalCorrection
    }

    protected fun getKeyDrawParams(): KeyDrawParams {
        return mKeyDrawParams
    }

    protected fun updateKeyDrawParams(keyHeight: Int) {
        mKeyDrawParams.updateParams(keyHeight, mKeyVisualAttributes)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keyboard = getKeyboard()
        if (keyboard == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // The main keyboard expands to the entire this {@link KeyboardView}.
        val width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight()
        val height = keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (canvas.isHardwareAccelerated()) {
            onDrawKeyboard(canvas)
            return
        }

        val bufferNeedsUpdates = mInvalidateAllKeys || !invalidatedKeys.isEmpty()
        if (bufferNeedsUpdates || offscreenBuffer == null) {
            if (maybeAllocateOffscreenBuffer()) {
                mInvalidateAllKeys = true
                // TODO: Stop using the offscreen canvas even when in software rendering
                offscreenCanvas.setBitmap(offscreenBuffer)
            }
            onDrawKeyboard(offscreenCanvas)
        }
        canvas.drawBitmap(offscreenBuffer!!, 0.0f, 0.0f, null)
    }

    private fun maybeAllocateOffscreenBuffer(): Boolean {
        val width = getWidth()
        val height = getHeight()
        if (width == 0 || height == 0) {
            return false
        }
        val buffer = offscreenBuffer
        if (buffer != null && buffer.getWidth() == width
                && buffer.getHeight() == height) {
            return false
        }
        freeOffscreenBuffer()
        offscreenBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return true
    }

    private fun freeOffscreenBuffer() {
        offscreenCanvas.setBitmap(null)
        offscreenCanvas.setMatrix(null)
        val buffer = offscreenBuffer
        if (buffer != null) {
            buffer.recycle()
            offscreenBuffer = null
        }
    }

    private fun onDrawKeyboard(canvas: Canvas) {
        val keyboard = getKeyboard()
        if (keyboard == null) {
            return
        }

        showsHints = Settings.getValues().mShowsHints
        val scale = Settings.getValues().mKeyboardHeightScale
        iconScaleFactor = if (scale < 0.8f) scale + 0.2f else 1f
        val paint = mPaint
        val background = getBackground()
        // Calculate clip region and set.
        val drawAllKeys = mInvalidateAllKeys || invalidatedKeys.isEmpty()
        val isHardwareAccelerated = canvas.isHardwareAccelerated()
        // TODO: Confirm if it's really required to draw all keys when hardware acceleration is on.
        if (drawAllKeys || isHardwareAccelerated) {
            if (!isHardwareAccelerated && background != null) {
                // Need to draw keyboard background on {@link #mOffscreenBuffer}.
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
                background.draw(canvas)
            }
            // Draw all keys.
            for (key in keyboard.getSortedKeys()) {
                onDrawKey(key, canvas, paint)
            }
        } else {
            for (key in invalidatedKeys) {
                if (!keyboard.hasKey(key)) {
                    continue
                }
                if (background != null) {
                    // Need to redraw key's background on {@link #mOffscreenBuffer}.
                    val x = key.getX() + getPaddingLeft()
                    val y = key.getY() + getPaddingTop()
                    clipRect.set(x, y, x + key.getWidth(), y + key.getHeight())
                    canvas.save()
                    canvas.clipRect(clipRect)
                    canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
                    background.draw(canvas)
                    canvas.restore()
                }
                onDrawKey(key, canvas, paint)
            }
        }

        invalidatedKeys.clear()
        mInvalidateAllKeys = false
    }

    private fun onDrawKey(key: Key, canvas: Canvas,
            paint: Paint) {
        val keyDrawX = key.getDrawX() + getPaddingLeft()
        val keyDrawY = key.getY() + getPaddingTop()
        canvas.translate(keyDrawX.toFloat(), keyDrawY.toFloat())

        val attr = key.getVisualAttributes()
        // don't use the raw key height, linear font scaling with height is too extreme
        val params = mKeyDrawParams.mayCloneAndUpdateParams((key.getHeight() * keyScaleForText).toInt(), attr)
        params.mAnimAlpha = Constants.Color.ALPHA_OPAQUE

        if (!key.isSpacer()) {
            val background = key.selectBackgroundDrawable(
                    keyBackground, functionalKeyBackground, spacebarBackground, actionKeyBackground)
            onDrawKeyBackground(key, canvas, background)
        }
        onDrawKeyTopVisuals(key, canvas, paint, params)

        canvas.translate((-keyDrawX).toFloat(), (-keyDrawY).toFloat())
    }

    // Draw key background.
    protected fun onDrawKeyBackground(key: Key, canvas: Canvas,
            background: Drawable) {
        val keyWidth = key.getDrawWidth()
        val keyHeight = key.getHeight()
        val bgWidth: Int
        val bgHeight: Int
        val bgX: Int
        val bgY: Int
        if (key.needsToKeepBackgroundAspectRatio(defaultKeyLabelFlags)
                // HACK: To disable expanding normal/functional key background.
                && !key.hasCustomActionLabel()) {
            bgWidth = (background.getIntrinsicWidth() * iconScaleFactor).toInt()
            bgHeight = (background.getIntrinsicHeight() * iconScaleFactor).toInt()
            bgX = (keyWidth - bgWidth) / 2
            bgY = (keyHeight - bgHeight) / 2
        } else {
            val padding = keyBackgroundPadding
            bgWidth = keyWidth + padding.left + padding.right
            bgHeight = keyHeight + padding.top + padding.bottom
            bgY = -padding.top
            bgX = -padding.left
        }
        background.setBounds(0, 0, bgWidth, bgHeight)
        canvas.translate(bgX.toFloat(), bgY.toFloat())
        background.draw(canvas)
        canvas.translate((-bgX).toFloat(), (-bgY).toFloat())
    }

    // Draw key top visuals.
    protected open fun onDrawKeyTopVisuals(key: Key, canvas: Canvas,
            paint: Paint, params: KeyDrawParams) {
        val keyWidth = key.getDrawWidth()
        val keyHeight = key.getHeight()
        val centerX = keyWidth * 0.5f
        val centerY = keyHeight * 0.5f

        // Draw key label.
        val keyboard = getKeyboard()
        val icon = if (keyboard == null) null
                else key.getIcon(keyboard.mIconsSet, params.mAnimAlpha)
        var labelX = centerX
        var labelBaseline = centerY
        val label = key.getLabel()
        if (label != null) {
            paint.setTypeface(KeyboardTypeface.resolve(label, key.selectTypeface(params)))
            paint.setTextSize(key.selectTextSize(params) * mFontSizeMultiplier)
            val labelCharHeight = TypefaceUtils.getReferenceCharHeight(paint)
            val labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint)

            // Vertical label text alignment.
            labelBaseline = centerY + labelCharHeight / 2.0f

            // Horizontal label text alignment
            if (key.isAlignLabelOffCenter() && showsHints) {
                // The label is placed off center of the key. Currently used only on "phone number" layout
                // to have letter hints shown nicely. We don't want to align it off center if hints are off.
                // use a non-negative number to avoid label starting left of the letter for high keyboard scale on holo phone layout
                labelX = Math.max(0f, centerX + params.mLabelOffCenterRatio * labelCharWidth)
                paint.setTextAlign(Align.LEFT)
            } else {
                labelX = centerX
                paint.setTextAlign(Align.CENTER)
            }
            if (key.needsAutoXScale()) {
                val width: Int
                if (key.needsToKeepBackgroundAspectRatio(defaultKeyLabelFlags)) {
                    // make sure the text stays inside bounds of background drawable
                    val bg = key.selectBackgroundDrawable(keyBackground, functionalKeyBackground, spacebarBackground, actionKeyBackground)
                    width = Math.min(bg.getBounds().bottom, bg.getBounds().right)
                } else width = keyWidth
                val ratio = Math.min(1.0f, (width * MAX_LABEL_RATIO) / TypefaceUtils.getStringWidth(label, paint))
                if (key.needsAutoScale()) {
                    val autoSize = paint.getTextSize() * ratio
                    paint.setTextSize(autoSize)
                } else {
                    paint.setTextScaleX(ratio)
                }
            }

            if (key.isEnabled()) {
                if (isEmoji(label))
                    paint.setColor(key.selectTextColor(params) or 0xFF000000.toInt()) // ignore alpha for emojis (though actually color isn't applied anyway and we could just set white)
                else if (key.hasActionKeyBackground())
                    paint.setColor(colors.get(ColorType.ACTION_KEY_ICON))
                else if (this is EmojiPageKeyboardView)
                    paint.setColor(colors.get(ColorType.EMOJI_KEY_TEXT))
                else if (this is PopupKeysKeyboardView)
                    paint.setColor(colors.get(ColorType.POPUP_KEY_TEXT))
                else
                    paint.setColor(key.selectTextColor(params))
                // Set a drop shadow for the text if the shadow radius is positive value.
                if (keyTextShadowRadius > 0.0f) {
                    paint.setShadowLayer(keyTextShadowRadius, 0.0f, 0.0f, params.mTextShadowColor)
                } else {
                    paint.clearShadowLayer()
                }
            } else {
                // Make label invisible
                paint.setColor(Color.TRANSPARENT)
                paint.clearShadowLayer()
            }
            blendAlpha(paint, params.mAnimAlpha)
            canvas.drawText(label, 0, label.length, labelX, labelBaseline, paint)
            // Turn off drop shadow and reset x-scale.
            paint.clearShadowLayer()
            paint.setTextScaleX(1.0f)
        }

        // Draw hint label.
        val hintLabel = key.getHintLabel()
        val hintIcon = if (keyboard == null || !showsHints || hintLabel != null) null
                        else key.getHintIcon(keyboard.mIconsSet, params.mAnimAlpha)
        if (hintLabel != null && showsHints) {
            paint.setTextSize(key.selectHintTextSize(params) * mHintFontSizeMultiplier)
            paint.setColor(key.selectHintTextColor(params))
            // TODO: Should add a way to specify type face for hint letters
            paint.setTypeface(KeyboardTypeface.resolve(hintLabel, Typeface.DEFAULT_BOLD))
            blendAlpha(paint, params.mAnimAlpha)
            val labelCharHeight = TypefaceUtils.getReferenceCharHeight(paint)
            val labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint)
            val isFunctionalKeyAndRoundedStyle = colors.themeStyle == STYLE_ROUNDED && key.hasFunctionalBackground()
            val hintX: Float
            val hintBaseline: Float
            if (key.hasHintLabel()) {
                // The hint label is placed just right of the key label. Used mainly on
                // "phone number" layout.
                hintX = labelX + params.mHintLabelOffCenterRatio * labelCharWidth
                if (key.isAlignHintLabelToBottom(defaultKeyLabelFlags)) {
                    hintBaseline = labelBaseline
                } else {
                    hintBaseline = centerY + labelCharHeight / 2.0f
                }
                paint.setTextAlign(Align.LEFT)
                // shrink hint label before it's off the key
                // looks bad, but still better than the alternative
                val ratio = Math.min(1.0f, (keyWidth - hintX) * 0.95f / TypefaceUtils.getStringWidth(hintLabel, paint))
                val autoSize = paint.getTextSize() * ratio
                paint.setTextSize(autoSize)
            } else if (key.hasShiftedLetterHint()) {
                // The hint label is placed at top-right corner of the key. Used mainly on tablet.
                hintX = keyWidth - keyShiftedLetterHintPadding - labelCharWidth / 2.0f
                paint.getFontMetrics(fontMetrics)
                hintBaseline = -fontMetrics.top
                paint.setTextAlign(Align.CENTER)
            } else { // key.hasHintLetter()
                // The hint letter is placed at top-right corner of the key. Used mainly on phone.
                val hintDigitWidth = TypefaceUtils.getReferenceDigitWidth(paint)
                val hintLabelWidth = TypefaceUtils.getStringWidth(hintLabel, paint)
                hintBaseline = -paint.ascent()
                hintX = if (isFunctionalKeyAndRoundedStyle)
                        keyWidth - hintBaseline
                        else keyWidth - keyHintLetterPadding - Math.max(hintDigitWidth, hintLabelWidth) / 2.0f
                paint.setTextAlign(Align.CENTER)
            }
            val adjustmentY = if (isFunctionalKeyAndRoundedStyle)
                    hintBaseline * 0.5f
                    else params.mHintLabelVerticalAdjustment * labelCharHeight
            canvas.drawText(hintLabel, 0, hintLabel.length, hintX, hintBaseline + adjustmentY, paint)
        } else if (hintIcon != null) {
            val iconSize = (key.selectHintTextSize(params) * mHintFontSizeMultiplier).toInt()
            val isFunctionalKeyAndRoundedStyle = colors.themeStyle == STYLE_ROUNDED && (key.hasFunctionalBackground() || key.hasActionKeyBackground())
            val hintX: Float
            val hintBaseline: Float
            if (key.hasHintLabel()) {
                // The hint icon is placed just right of the key label. Used mainly on "phone number" layout.
                hintX = labelX + params.mHintLabelOffCenterRatio * iconSize
                hintBaseline = centerY + iconSize / 2.0f
            } else if (key.hasShiftedLetterHintIcon()) {
                // The hint label is placed at top-right corner of the key. Used mainly on tablet.
                hintX = keyWidth - keyShiftedLetterHintPadding - iconSize / 2.0f
                paint.getFontMetrics(fontMetrics)
                hintBaseline = -fontMetrics.top
            } else { // key.hasHintLetter()
                // The hint letter is placed at top-right corner of the key. Used mainly on phone.
                hintBaseline = 0f
                hintX = if (isFunctionalKeyAndRoundedStyle)
                        keyWidth - iconSize * 1.5f
                        else keyWidth - keyHintLetterPadding - iconSize
            }
            val adjustmentY = if (isFunctionalKeyAndRoundedStyle)
                                      iconSize * 0.5f
                                      else params.mHintLabelVerticalAdjustment * iconSize
            hintIcon.setColorFilter(key.selectHintTextColor(params), PorterDuff.Mode.MULTIPLY)
            drawIcon(canvas, hintIcon, hintX.toInt(), (hintBaseline + adjustmentY).toInt(), iconSize, iconSize)
        } else if (key.getPopupKeys() != null && !key.hasNoPanelAutoPopupKey()
                        && (key.hasActionKeyBackground() || key.getBackgroundType() == Key.BACKGROUND_TYPE_FUNCTIONAL)) {
            drawKeyPopupHint(key, canvas, paint, params)
        }

        // Draw key icon.
        if (label == null && icon != null) {
            val iconWidth = if (key.getCode() == Constants.CODE_SPACE && icon is NinePatchDrawable)
                (keyWidth * spacebarIconWidthRatio * iconScaleFactor).toInt()
                else (Math.min(icon.getIntrinsicWidth(), keyWidth) * iconScaleFactor).toInt()
            val iconHeight = (icon.getIntrinsicHeight() * iconScaleFactor).toInt()
            val iconY = if (key.isAlignIconToBottom())
                keyHeight - iconHeight
                else (keyHeight - iconHeight) / 2 // Align vertically center.
            val iconX = (keyWidth - iconWidth) / 2 // Align horizontally center.
            setKeyIconColor(key, icon, keyboard)
            drawIcon(canvas, icon, iconX, iconY, iconWidth, iconHeight)
        }
    }

    // Draw popup hint "..." at the center or bottom right corner of the key, depending on style.
    protected fun drawKeyPopupHint(key: Key, canvas: Canvas,
            paint: Paint, params: KeyDrawParams) {
        if (TextUtils.isEmpty(keyPopupHintLetter)) {
            return
        }
        val keyWidth = key.getDrawWidth()
        val keyHeight = key.getHeight()
        val labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint)
        val hintX: Float
        val hintBaseline = paint.ascent()
        paint.setTypeface(params.mTypeface)
        paint.setTextSize(params.mHintLetterSize.toFloat())
        paint.setColor(params.mHintLabelColor)
        paint.setTextAlign(Align.CENTER)
        if (colors.themeStyle == STYLE_ROUNDED) {
            if (key.getBackgroundType() == Key.BACKGROUND_TYPE_SPACEBAR)
                hintX = keyWidth + hintBaseline + labelCharWidth * 0.1f
            else
                hintX = if (key.hasFunctionalBackground() || key.hasActionKeyBackground()) keyWidth / 2.0f else keyWidth - keyHintLetterPadding - labelCharWidth / 2.0f
        } else {
            hintX = keyWidth - keyHintLetterPadding - TypefaceUtils.getReferenceCharWidth(paint) / 2.0f
        }
        val hintY = keyHeight - keyPopupHintLetterPadding
        canvas.drawText(keyPopupHintLetter!!, hintX, hintY, paint)
    }

    fun newLabelPaint(key: Key?): Paint {
        val paint = Paint()
        paint.setAntiAlias(true)
        if (key == null) {
            paint.setTypeface(mKeyDrawParams.mTypeface)
            paint.setTextSize(mKeyDrawParams.mLabelSize.toFloat())
        } else {
            paint.setColor(key.selectTextColor(mKeyDrawParams))
            paint.setTypeface(key.selectTypeface(mKeyDrawParams))
            paint.setTextSize(key.selectTextSize(mKeyDrawParams) * mFontSizeMultiplier)
        }
        return paint
    }

    /**
     * Requests a redraw of the entire keyboard. Calling {@link #invalidate} is not sufficient
     * because the keyboard renders the keys to an off-screen buffer and an invalidate() only
     * draws the cached buffer.
     * @see #invalidateKey(Key)
     */
    fun invalidateAllKeys() {
        invalidatedKeys.clear()
        mInvalidateAllKeys = true
        invalidate()
    }

    /**
     * Invalidates a key so that it will be redrawn on the next repaint. Use this method if only
     * one key is changing it's content. Any changes that affect the position or size of the key
     * may not be honored.
     * @param key key in the attached {@link Keyboard}.
     * @see #invalidateAllKeys
     */
    fun invalidateKey(key: Key?) {
        if (mInvalidateAllKeys || key == null) {
            return
        }
        invalidatedKeys.add(key)
        val x = key.getX() + getPaddingLeft()
        val y = key.getY() + getPaddingTop()
        invalidate(x, y, x + key.getWidth(), y + key.getHeight())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        freeOffscreenBuffer()
    }

    open fun deallocateMemory() {
        freeOffscreenBuffer()
    }

    private fun setKeyIconColor(key: Key, icon: Drawable, keyboard: Keyboard?) {
        if (key.hasActionKeyBackground()) {
            colors.setColor(icon, ColorType.ACTION_KEY_ICON)
        } else if (key.isShift() && keyboard != null) {
            if (keyboard.mId.element.isAlphabetShifted)
                colors.setColor(icon, ColorType.SHIFT_KEY_ICON)
            else
                colors.setColor(icon, ColorType.KEY_ICON) // normal key if not shifted
        } else if (key.getBackgroundType() != Key.BACKGROUND_TYPE_NORMAL) {
            colors.setColor(icon, ColorType.KEY_ICON)
        } else if (this is PopupKeysKeyboardView) {
            colors.setColor(icon, ColorType.POPUP_KEY_ICON)
        } else if (key.getCode() == Constants.CODE_SPACE || key.getCode() == KeyCode.ZWNJ) {
            // set color of default number pad space bar icon for Holo style, or for zero-width non-joiner (zwnj) on some layouts like nepal
            colors.setColor(icon, ColorType.KEY_ICON)
        } else {
            colors.setColor(icon, ColorType.KEY_TEXT)
        }
    }

    companion object {
        private const val KET_TEXT_SHADOW_RADIUS_DISABLED = -1.0f

        // The maximum key label width in the proportion to the key width.
        private const val MAX_LABEL_RATIO = 0.90f

        private fun blendAlpha(paint: Paint, alpha: Int) {
            val color = paint.getColor()
            paint.setARGB((paint.getAlpha() * alpha) / Constants.Color.ALPHA_OPAQUE,
                    Color.red(color), Color.green(color), Color.blue(color))
        }

        @JvmStatic
        protected fun drawIcon(canvas: Canvas, icon: Drawable,
                x: Int, y: Int, width: Int, height: Int) {
            canvas.translate(x.toFloat(), y.toFloat())
            icon.setBounds(0, 0, width, height)
            icon.draw(canvas)
            canvas.translate((-x).toFloat(), (-y).toFloat())
        }
    }
}
