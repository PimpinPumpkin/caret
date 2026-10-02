/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.suggestions

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.CharacterStyle
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.caret.accessibility.AccessibilityUtils
import app.caret.keyboard.KeyboardTypeface
import app.caret.latin.PunctuationSuggestions
import app.caret.latin.R
import app.caret.latin.SuggestedWords
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.ColorType
import app.caret.latin.settings.Settings
import app.caret.latin.utils.ResourceUtils
import app.caret.latin.utils.ViewLayoutUtils
import java.util.ArrayList

class SuggestionStripLayoutHelper(
    context: Context, attrs: AttributeSet?,
    defStyle: Int, wordViews: ArrayList<TextView>,
    dividerViews: ArrayList<View>, debugInfoViews: ArrayList<TextView>
) {
    @JvmField val padding: Int
    @JvmField val dividerWidth: Int
    @JvmField val suggestionsStripHeight: Int
    private val suggestionsCountInStrip: Int
    @JvmField val moreSuggestionsRowHeight: Int
    private var mMaxMoreSuggestionsRow: Int
    @JvmField val minMoreSuggestionsWidth: Float
    @JvmField val moreSuggestionsBottomGap: Int
    private var moreSuggestionsAvailable = false

    // The index of these {@link ArrayList} is the position in the suggestion strip. The indices
    // increase towards the right for LTR scripts and the left for RTL scripts, starting with 0.
    // The position of the most important suggestion is in {@link #mCenterPositionInStrip}
    private val mWordViews: ArrayList<TextView>
    private val mDividerViews: ArrayList<View>
    private val mDebugInfoViews: ArrayList<TextView>

    private val colorValidTypedWord: Int
    private val colorTypedWord: Int
    private val colorAutoCorrect: Int
    private val colorSuggested: Int
    private val alphaObsoleted: Float
    private val centerSuggestionWeight: Float
    private val mCenterPositionInStrip: Int
    private val typedWordPositionWhenAutocorrect: Int
    private val mMoreSuggestionsHint: Drawable

    private val suggestionStripOptions: Int

    init {
        mWordViews = wordViews
        mDividerViews = dividerViews
        mDebugInfoViews = debugInfoViews

        val wordView = wordViews.get(0)
        val dividerView = dividerViews.get(0)
        padding = wordView.getCompoundPaddingLeft() + wordView.getCompoundPaddingRight()
        dividerView.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dividerWidth = dividerView.getMeasuredWidth()

        val res = wordView.getResources()
        suggestionsStripHeight = res.getDimensionPixelSize(
            R.dimen.config_suggestions_strip_height
        )

        val a = context.obtainStyledAttributes(
            attrs,
            R.styleable.SuggestionStripView, defStyle, R.style.SuggestionStripView
        )
        suggestionStripOptions = a.getInt(R.styleable.SuggestionStripView_suggestionStripOptions, 0)
        alphaObsoleted = ResourceUtils.getFraction(a, R.styleable.SuggestionStripView_alphaObsoleted, 1.0f)

        val colors = Settings.getValues().mColors
        colorValidTypedWord = colors.get(ColorType.SUGGESTION_VALID_WORD)
        colorTypedWord = colors.get(ColorType.SUGGESTION_TYPED_WORD)
        colorAutoCorrect = colors.get(ColorType.SUGGESTION_AUTO_CORRECT)
        colorSuggested = colors.get(ColorType.SUGGESTED_WORD)
        val colorMoreSuggestionsHint = colors.get(ColorType.MORE_SUGGESTIONS_HINT)

        suggestionsCountInStrip = a.getInt(
            R.styleable.SuggestionStripView_suggestionsCountInStrip,
            DEFAULT_SUGGESTIONS_COUNT_IN_STRIP
        )
        centerSuggestionWeight = ResourceUtils.getFraction(
            a,
            R.styleable.SuggestionStripView_centerSuggestionPercentile,
            DEFAULT_CENTER_SUGGESTION_PERCENTILE
        )
        mMaxMoreSuggestionsRow = a.getInt(
            R.styleable.SuggestionStripView_maxMoreSuggestionsRow,
            DEFAULT_MAX_MORE_SUGGESTIONS_ROW
        )
        minMoreSuggestionsWidth = ResourceUtils.getFraction(
            a,
            R.styleable.SuggestionStripView_minMoreSuggestionsWidth, 1.0f
        )
        a.recycle()

        mMoreSuggestionsHint = getMoreSuggestionsHint(
            res,
            res.getDimension(R.dimen.config_more_suggestions_hint_text_size),
            colorMoreSuggestionsHint
        )
        mCenterPositionInStrip = suggestionsCountInStrip / 2
        // Assuming there are at least three suggestions. Also, note that the suggestions are
        // laid out according to script direction, so this is left of the center for LTR scripts
        // and right of the center for RTL scripts.
        typedWordPositionWhenAutocorrect = mCenterPositionInStrip - 1
        moreSuggestionsBottomGap = res.getDimensionPixelOffset(
            R.dimen.config_more_suggestions_bottom_gap
        )
        moreSuggestionsRowHeight = res.getDimensionPixelSize(
            R.dimen.config_more_suggestions_row_height
        )
    }

    fun getMaxMoreSuggestionsRow(): Int {
        return mMaxMoreSuggestionsRow
    }

    private fun getMoreSuggestionsHeight(): Int {
        return mMaxMoreSuggestionsRow * moreSuggestionsRowHeight + moreSuggestionsBottomGap
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        val currentHeight = getMoreSuggestionsHeight()
        if (currentHeight <= remainingHeight) {
            return
        }
        mMaxMoreSuggestionsRow = (remainingHeight - moreSuggestionsBottomGap) / moreSuggestionsRowHeight
    }

    private fun getStyledSuggestedWord(
        suggestedWords: SuggestedWords,
        indexInSuggestedWords: Int
    ): CharSequence? {
        if (indexInSuggestedWords >= suggestedWords.size()) {
            return null
        }
        val word = suggestedWords.getLabel(indexInSuggestedWords)
        // TODO: don't use the index to decide whether this is the auto-correction/typed word, as
        // this is brittle
        val isAutoCorrection = suggestedWords.willAutoCorrect
                && indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION
        val isTypedWordValid = suggestedWords.typedWordValid
                && indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD
        if (!isAutoCorrection && !isTypedWordValid) {
            return word
        }

        val spannedWord: Spannable = SpannableString(word)
        val options = suggestionStripOptions
        if ((isAutoCorrection && (options and AUTO_CORRECT_BOLD) != 0)
            || (isTypedWordValid && (options and VALID_TYPED_WORD_BOLD) != 0)
        ) {
            addStyleSpan(spannedWord, BOLD_SPAN)
        }
        if (isAutoCorrection && (options and AUTO_CORRECT_UNDERLINE) != 0) {
            addStyleSpan(spannedWord, UNDERLINE_SPAN)
        }
        return spannedWord
    }

    /**
     * Convert an index of {@link SuggestedWords} to position in the suggestion strip.
     * @param indexInSuggestedWords the index of {@link SuggestedWords}.
     * @param suggestedWords the suggested words list
     * @return Non-negative integer of the position in the suggestion strip.
     *         Negative integer if the word of the index shouldn't be shown on the suggestion strip.
     */
    private fun getPositionInSuggestionStrip(
        indexInSuggestedWords: Int,
        suggestedWords: SuggestedWords
    ): Int {
        val settingsValues = Settings.getValues()
        val shouldOmitTypedWord = shouldOmitTypedWord(
            suggestedWords.inputStyle,
            settingsValues.gestureFloatingPreviewTextEnabled, true
        )
        return getPositionInSuggestionStrip(
            indexInSuggestedWords, suggestedWords.willAutoCorrect,
            shouldOmitTypedWord, mCenterPositionInStrip, typedWordPositionWhenAutocorrect
        )
    }

    private fun getSuggestionTextColor(
        suggestedWords: SuggestedWords,
        indexInSuggestedWords: Int
    ): Int {
        // Use identity for strings, not #equals : it's the typed word if it's the same object
        val isTypedWord = suggestedWords.getInfo(indexInSuggestedWords).isKindOf(SuggestedWordInfo.KIND_TYPED)

        val color: Int
        if (indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION && suggestedWords.willAutoCorrect) {
            color = colorAutoCorrect
        } else if (isTypedWord && suggestedWords.typedWordValid) {
            color = colorValidTypedWord
        } else if (isTypedWord) {
            color = colorTypedWord
        } else {
            color = colorSuggested
        }
        if (suggestedWords.isObsoleteSuggestions && !isTypedWord) {
            return applyAlpha(color, alphaObsoleted)
        }
        return color
    }

    /**
     * Layout suggestions to the suggestions strip. And returns the start index of more
     * suggestions.
     *
     * @param suggestedWords suggestions to be shown in the suggestions strip.
     * @param stripView the suggestions strip view.
     * @param placerView the view where the debug info will be placed.
     * @return the start index of more suggestions.
     */
    fun layoutAndReturnStartIndexOfMoreSuggestions(
        context: Context,
        suggestedWords: SuggestedWords,
        stripView: ViewGroup,
        placerView: ViewGroup
    ): Int {
        if (suggestedWords.isPunctuationSuggestions()) {
            return layoutPunctuationsAndReturnStartIndexOfMoreSuggestions(
                suggestedWords as PunctuationSuggestions, stripView
            )
        }

        val wordCountToShow = suggestedWords.getWordCountToShow()
        val startIndexOfMoreSuggestions = setupWordViewsAndReturnStartIndexOfMoreSuggestions(
            suggestedWords, suggestionsCountInStrip
        )
        val centerWordView = mWordViews.get(mCenterPositionInStrip)
        val stripWidth = stripView.getWidth()
        val centerWidth = getSuggestionWidth(mCenterPositionInStrip, stripWidth)
        if (wordCountToShow == 1 || getTextScaleX(
                centerWordView.getText(), centerWidth,
                centerWordView.getPaint()
            ) < MIN_TEXT_XSCALE
        ) {
            // Layout only the most relevant suggested word at the center of the suggestion strip
            // by consolidating all slots in the strip.
            val countInStrip = 1
            moreSuggestionsAvailable = (wordCountToShow > countInStrip)
            layoutWord(context, mCenterPositionInStrip, stripWidth - padding)
            stripView.addView(centerWordView)
            setLayoutWeight(centerWordView, 1.0f, ViewGroup.LayoutParams.MATCH_PARENT)
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                layoutDebugInfo(mCenterPositionInStrip, placerView, stripWidth)
            }
            val lastIndex = centerWordView.getTag() as Int?
            return (if (lastIndex == null) 0 else lastIndex) + 1
        }

        val countInStrip = suggestionsCountInStrip
        moreSuggestionsAvailable = (wordCountToShow > countInStrip)
        @Suppress("unused")
        var x = 0
        for (positionInStrip in 0 until countInStrip) {
            if (positionInStrip != 0) {
                val divider = mDividerViews.get(positionInStrip)
                // Add divider if this isn't the left most suggestion in suggestions strip.
                addDivider(stripView, divider)
                x += divider.getMeasuredWidth()
            }

            val width = getSuggestionWidth(positionInStrip, stripWidth)
            val wordView = layoutWord(context, positionInStrip, width)
            stripView.addView(wordView)
            setLayoutWeight(wordView, getSuggestionWeight(positionInStrip), ViewGroup.LayoutParams.MATCH_PARENT)
            x += wordView.getMeasuredWidth()

            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                layoutDebugInfo(positionInStrip, placerView, stripView.getX().toInt() + x)
            }
        }
        return startIndexOfMoreSuggestions
    }

    /**
     * Format appropriately the suggested word in {@link #mWordViews} specified by
     * <code>positionInStrip</code>. When the suggested word doesn't exist, the corresponding
     * {@link TextView} will be disabled and never respond to user interaction. The suggested word
     * may be shrunk or ellipsized to fit in the specified width.
     * <p>
     * The <code>positionInStrip</code> argument is the index in the suggestion strip. The indices
     * increase towards the right for LTR scripts and the left for RTL scripts, starting with 0.
     * The position of the most important suggestion is in {@link #mCenterPositionInStrip}. This
     * usually doesn't match the index in <code>suggedtedWords</code> -- see
     * {@link #getPositionInSuggestionStrip(int,SuggestedWords)}.
     *
     * @param positionInStrip the position in the suggestion strip.
     * @param width the maximum width for layout in pixels.
     * @return the {@link TextView} containing the suggested word appropriately formatted.
     */
    private fun layoutWord(context: Context, positionInStrip: Int, width: Int): TextView {
        val wordView = mWordViews.get(positionInStrip)
        val word: CharSequence? = wordView.getText()
        if (positionInStrip == mCenterPositionInStrip && moreSuggestionsAvailable) {
            // TODO: This "more suggestions hint" should have a nicely designed icon.
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, mMoreSuggestionsHint)
            // HACK: Align with other TextViews that have no compound drawables.
            wordView.setCompoundDrawablePadding(-mMoreSuggestionsHint.getIntrinsicHeight())
        } else {
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        }
        // {@link StyleSpan} in a content description may cause an issue of TTS/TalkBack.
        // Use a simple {@link String} to avoid the issue.
        wordView.setContentDescription(
            if (TextUtils.isEmpty(word))
                context.getResources().getString(R.string.spoken_empty_suggestion)
            else
                word.toString()
        )
        val text = getEllipsizedTextWithSettingScaleX(
            word, width, wordView.getPaint()
        )
        val scaleX = wordView.getTextScaleX()
        wordView.setText(text) // TextView.setText() resets text scale x to 1.0.
        wordView.setTextScaleX(scaleX)
        // A <code>wordView</code> should be disabled when <code>word</code> is empty in order to
        // make it unclickable.
        // With accessibility touch exploration on, <code>wordView</code> should be enabled even
        // when it is empty to avoid announcing as "disabled".
        wordView.setEnabled(
            !TextUtils.isEmpty(word)
                    || AccessibilityUtils.instance.isTouchExplorationEnabled
        )
        return wordView
    }

    private fun layoutDebugInfo(
        positionInStrip: Int, placerView: ViewGroup,
        x: Int
    ) {
        val debugInfoView = mDebugInfoViews.get(positionInStrip)
        val debugInfo: CharSequence? = debugInfoView.getText()
        if (debugInfo == null) {
            return
        }
        placerView.addView(debugInfoView)
        debugInfoView.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val infoWidth = debugInfoView.getMeasuredWidth()
        ViewLayoutUtils.placeViewAt(debugInfoView, x - infoWidth, 0, infoWidth, debugInfoView.getMeasuredHeight())
    }

    private fun getSuggestionWidth(positionInStrip: Int, maxWidth: Int): Int {
        val paddings = padding * suggestionsCountInStrip
        val dividers = dividerWidth * (suggestionsCountInStrip - 1)
        val availableWidth = maxWidth - paddings - dividers
        return (availableWidth * getSuggestionWeight(positionInStrip)).toInt()
    }

    private fun getSuggestionWeight(positionInStrip: Int): Float {
        if (positionInStrip == mCenterPositionInStrip) {
            return centerSuggestionWeight
        }
        // TODO: Revisit this for cases of 5 or more suggestions
        return (1.0f - centerSuggestionWeight) / (suggestionsCountInStrip - 1)
    }

    private fun setupWordViewsAndReturnStartIndexOfMoreSuggestions(
        suggestedWords: SuggestedWords, maxSuggestionInStrip: Int
    ): Int {
        // Clear all suggestions first
        for (positionInStrip in 0 until maxSuggestionInStrip) {
            val wordView = mWordViews.get(positionInStrip)
            wordView.setText(null as CharSequence?)
            wordView.setTag(null)
            // Make this inactive for touches in {@link #layoutWord(int,int)}.
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(null as CharSequence?)
            }
        }
        var count = 0
        var indexInSuggestedWords = 0
        while (indexInSuggestedWords < suggestedWords.size()
            && count < maxSuggestionInStrip
        ) {
            val positionInStrip =
                getPositionInSuggestionStrip(indexInSuggestedWords, suggestedWords)
            if (positionInStrip < 0) {
                indexInSuggestedWords++
                continue
            }
            val wordView = mWordViews.get(positionInStrip)
            // {@link TextView#getTag()} is used to get the index in suggestedWords at
            // {@link SuggestionStripView#onClick(View)}.
            wordView.setTag(indexInSuggestedWords)
            wordView.setText(getStyledSuggestedWord(suggestedWords, indexInSuggestedWords))
            wordView.setTextColor(getSuggestionTextColor(suggestedWords, indexInSuggestedWords))
            KeyboardTypeface.applyToTextView(wordView)
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(suggestedWords.getDebugString(indexInSuggestedWords))
            }
            count++
            indexInSuggestedWords++
        }
        return indexInSuggestedWords
    }

    private fun layoutPunctuationsAndReturnStartIndexOfMoreSuggestions(
        punctuationSuggestions: PunctuationSuggestions, stripView: ViewGroup
    ): Int {
        val countInStrip = Math.min(punctuationSuggestions.size(), PUNCTUATIONS_IN_STRIP)
        for (positionInStrip in 0 until countInStrip) {
            if (positionInStrip != 0) {
                // Add divider if this isn't the left most suggestion in suggestions strip.
                addDivider(stripView, mDividerViews.get(positionInStrip))
            }

            val wordView = mWordViews.get(positionInStrip)
            val punctuation = punctuationSuggestions.getLabel(positionInStrip)
            // {@link TextView#getTag()} is used to get the index in suggestedWords at
            // {@link SuggestionStripView#onClick(View)}.
            wordView.setTag(positionInStrip)
            wordView.setText(punctuation)
            wordView.setContentDescription(punctuation)
            wordView.setTextScaleX(1.0f)
            wordView.setCompoundDrawables(null, null, null, null)
            wordView.setTextColor(colorAutoCorrect)
            KeyboardTypeface.applyToTextView(wordView)
            stripView.addView(wordView)
            setLayoutWeight(wordView, 1.0f, suggestionsStripHeight)
        }
        moreSuggestionsAvailable = (punctuationSuggestions.size() > countInStrip)
        return countInStrip
    }

    companion object {
        private const val DEFAULT_SUGGESTIONS_COUNT_IN_STRIP = 3
        private const val DEFAULT_CENTER_SUGGESTION_PERCENTILE = 0.40f
        private const val DEFAULT_MAX_MORE_SUGGESTIONS_ROW = 2
        private const val PUNCTUATIONS_IN_STRIP = 5
        private const val MIN_TEXT_XSCALE = 0.70f

        private const val MORE_SUGGESTIONS_HINT = "…"

        private val BOLD_SPAN: CharacterStyle = StyleSpan(Typeface.BOLD)
        private val UNDERLINE_SPAN: CharacterStyle = UnderlineSpan()

        // These constants are the flag values of
        // {@link R.styleable#SuggestionStripView_suggestionStripOptions} attribute.
        private const val AUTO_CORRECT_BOLD = 0x01
        private const val AUTO_CORRECT_UNDERLINE = 0x02
        private const val VALID_TYPED_WORD_BOLD = 0x04

        private fun getMoreSuggestionsHint(res: Resources, textSize: Float, color: Int): Drawable {
            val paint = Paint()
            paint.setAntiAlias(true)
            paint.setTextAlign(Align.CENTER)
            paint.setTextSize(textSize)
            paint.setColor(color)
            val bounds = Rect()
            paint.getTextBounds(MORE_SUGGESTIONS_HINT, 0, MORE_SUGGESTIONS_HINT.length, bounds)
            val width = Math.round(bounds.width() + 0.5f)
            val height = Math.round(bounds.height() + 0.5f)
            val buffer = Bitmap.createBitmap(width, (height * 3 / 2), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(buffer)
            canvas.drawText(MORE_SUGGESTIONS_HINT, (width / 2).toFloat(), height.toFloat(), paint)
            val bitmapDrawable = BitmapDrawable(res, buffer)
            bitmapDrawable.setTargetDensity(canvas)
            return bitmapDrawable
        }

        @JvmStatic
        fun shouldOmitTypedWord(
            inputStyle: Int,
            gestureFloatingPreviewTextEnabled: Boolean,
            shouldShowUiToAcceptTypedWord: Boolean
        ): Boolean {
            val omitTypedWord = (inputStyle == SuggestedWords.INPUT_STYLE_TYPING)
                    || (inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH)
                    || (inputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH && gestureFloatingPreviewTextEnabled)
            return shouldShowUiToAcceptTypedWord && omitTypedWord
        }

        @JvmStatic
        fun getPositionInSuggestionStrip(
            indexInSuggestedWords: Int,
            willAutoCorrect: Boolean, omitTypedWord: Boolean,
            centerPositionInStrip: Int, typedWordPositionWhenAutoCorrect: Int
        ): Int {
            if (omitTypedWord) {
                if (indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD) {
                    // Ignore.
                    return -1
                }
                if (indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION) {
                    // Center in the suggestion strip.
                    return centerPositionInStrip
                }
                // If neither of those, the order in the suggestion strip is left of the center first
                // then right of the center, to both edges of the suggestion strip.
                // For example, center-1, center+1, center-2, center+2, and so on.
                val offsetFromCenter = if ((indexInSuggestedWords % 2) == 0) -(indexInSuggestedWords / 2) else (indexInSuggestedWords / 2)
                return centerPositionInStrip + offsetFromCenter
            }
            val indexToDisplayMostImportantSuggestion: Int
            val indexToDisplaySecondMostImportantSuggestion: Int
            if (willAutoCorrect) {
                indexToDisplayMostImportantSuggestion = SuggestedWords.INDEX_OF_AUTO_CORRECTION
                indexToDisplaySecondMostImportantSuggestion = SuggestedWords.INDEX_OF_TYPED_WORD
            } else {
                indexToDisplayMostImportantSuggestion = SuggestedWords.INDEX_OF_TYPED_WORD
                indexToDisplaySecondMostImportantSuggestion = SuggestedWords.INDEX_OF_AUTO_CORRECTION
            }
            if (indexInSuggestedWords == indexToDisplayMostImportantSuggestion) {
                // Center in the suggestion strip.
                return centerPositionInStrip
            }
            if (indexInSuggestedWords == indexToDisplaySecondMostImportantSuggestion) {
                // Center-1.
                return typedWordPositionWhenAutoCorrect
            }
            // If neither of those, the order in the suggestion strip is right of the center first
            // then left of the center, to both edges of the suggestion strip.
            // For example, Center+1, center-2, center+2, center-3, and so on.
            val n = indexInSuggestedWords + 1
            val offsetFromCenter = if ((n % 2) == 0) -(n / 2) else (n / 2)
            return centerPositionInStrip + offsetFromCenter
        }

        private fun applyAlpha(color: Int, alpha: Float): Int {
            val newAlpha = (Color.alpha(color) * alpha).toInt()
            return Color.argb(newAlpha, Color.red(color), Color.green(color), Color.blue(color))
        }

        private fun addDivider(stripView: ViewGroup, dividerView: View) {
            stripView.addView(dividerView)
            val params = dividerView.getLayoutParams() as LinearLayout.LayoutParams
            params.gravity = Gravity.CENTER
        }

        @JvmStatic
        fun setLayoutWeight(v: View, weight: Float, height: Int) {
            val lp = v.getLayoutParams()
            if (lp is LinearLayout.LayoutParams) {
                val llp: LinearLayout.LayoutParams = lp
                llp.weight = weight
                llp.width = 0
                llp.height = height
            }
        }

        private fun getTextScaleX(text: CharSequence?, maxWidth: Int, paint: TextPaint): Float {
            paint.setTextScaleX(1.0f)
            val width = getTextWidth(text, paint)
            if (width <= maxWidth || maxWidth <= 0) {
                return 1.0f
            }
            return maxWidth / width.toFloat()
        }

        private fun getEllipsizedTextWithSettingScaleX(
            text: CharSequence?, maxWidth: Int, paint: TextPaint
        ): CharSequence? {
            if (text == null) {
                return null
            }
            val scaleX = getTextScaleX(text, maxWidth, paint)
            if (scaleX >= MIN_TEXT_XSCALE) {
                paint.setTextScaleX(scaleX)
                return text
            }

            // <code>text</code> must be ellipsized with minimum text scale x.
            paint.setTextScaleX(MIN_TEXT_XSCALE)
            val hasBoldStyle = hasStyleSpan(text, BOLD_SPAN)
            val hasUnderlineStyle = hasStyleSpan(text, UNDERLINE_SPAN)
            // TextUtils.ellipsize erases any span object existed after ellipsized point.
            // We have to restore these spans afterward.
            val ellipsizedText = TextUtils.ellipsize(text, paint, maxWidth.toFloat(), TextUtils.TruncateAt.MIDDLE)
            if (!hasBoldStyle && !hasUnderlineStyle) {
                return ellipsizedText
            }
            val spannableText: Spannable = if (ellipsizedText is Spannable)
                ellipsizedText else SpannableString(ellipsizedText)
            if (hasBoldStyle) {
                addStyleSpan(spannableText, BOLD_SPAN)
            }
            if (hasUnderlineStyle) {
                addStyleSpan(spannableText, UNDERLINE_SPAN)
            }
            return spannableText
        }

        private fun hasStyleSpan(
            text: CharSequence?,
            style: CharacterStyle
        ): Boolean {
            if (text is Spanned) {
                return text.getSpanStart(style) >= 0
            }
            return false
        }

        private fun addStyleSpan(text: Spannable, style: CharacterStyle) {
            text.removeSpan(style)
            text.setSpan(style, 0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

        private fun getTextWidth(text: CharSequence?, paint: TextPaint): Int {
            if (TextUtils.isEmpty(text)) {
                return 0
            }
            val length = text!!.length
            val widths = FloatArray(length)
            val savedTypeface = paint.getTypeface()
            val count: Int = try {
                paint.setTypeface(getTextTypeface(text))
                paint.getTextWidths(text, 0, length, widths)
            } finally {
                paint.setTypeface(savedTypeface)
            }
            var width = 0
            for (i in 0 until count) {
                width += Math.round(widths[i] + 0.5f)
            }
            return width
        }

        private fun getTextTypeface(text: CharSequence?): Typeface {
            return if (hasStyleSpan(text, BOLD_SPAN)) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
}
