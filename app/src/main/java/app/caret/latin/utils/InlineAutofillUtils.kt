/*
 * Copyright (C) 2019 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0
 */

package app.caret.latin.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.AttributeSet
import android.util.Size
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec

import androidx.annotation.AttrRes
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi

import java.util.ArrayList

import app.caret.latin.R
import app.caret.latin.common.ColorType
import app.caret.latin.settings.Settings

@SuppressLint("RestrictedApi")
@RequiresApi(api = Build.VERSION_CODES.R)
class InlineAutofillUtils {

    /**
     * This class is a container for showing {@link InlineContentView}s for cases
     * where you want to ensure they appear only in a given area in your app. An
     * example is having a scrollable list of items. Note that without this container
     * the InlineContentViews' surfaces would cover parts of your app as these surfaces
     * are owned by another process and always appearing on top of your app.
     */
    class InlineContentClipView : FrameLayout {
        private val onDrawListener =
                ViewTreeObserver.OnDrawListener { clipDescendantInlineContentViews() }
        private val parentBounds = Rect()
        private val contentBounds = Rect()
        constructor(context: Context) : this(context, /*attrs*/ null, /*defStyleAttr*/ 0)
        constructor(context: Context, attrs: AttributeSet?,
                    @AttrRes defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
            val backgroundView = SurfaceView(context)
            backgroundView.setZOrderOnTop(true)
            backgroundView.getHolder().setFormat(PixelFormat.TRANSPARENT)
            addView(backgroundView)
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            getViewTreeObserver().addOnDrawListener(onDrawListener)
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            getViewTreeObserver().removeOnDrawListener(onDrawListener)
        }
        private fun clipDescendantInlineContentViews() {
            parentBounds.right = getWidth()
            parentBounds.bottom = getHeight()
            clipDescendantInlineContentViews(this)
        }
        private fun clipDescendantInlineContentViews(root: View?) {
            if (root == null) return
            if (root is InlineContentView) {
                val inlineContentView: InlineContentView = root
                contentBounds.set(parentBounds)
                offsetRectIntoDescendantCoords(inlineContentView, contentBounds)
                inlineContentView.setClipBounds(contentBounds)
                return
            }
            if (root is ViewGroup) {
                val rootGroup: ViewGroup = root
                val childCount = rootGroup.getChildCount()
                for (i in 0 until childCount) {
                    val child = rootGroup.getChildAt(i)
                    clipDescendantInlineContentViews(child)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun createInlineSuggestionRequest(context: Context): InlineSuggestionsRequest {
            val colors = Settings.values.colors
            val chipBgDrawableId = androidx.autofill.R.drawable.autofill_inline_suggestion_chip_background
            val chipBgColor = colors.get(ColorType.AUTOFILL_BACKGROUND_CHIP)
            val chipTextColor = colors.get(ColorType.KEY_TEXT)
            val chipTextHintColor = colors.get(ColorType.KEY_HINT_TEXT)

            val stylesBuilder = UiVersions.newStylesBuilder()
            val style = InlineSuggestionUi.newStyleBuilder()
                    .setSingleIconChipStyle(
                            ViewStyle.Builder()
                                    .setBackground(Icon.createWithResource(context, chipBgDrawableId).setTint(chipBgColor))
                                    .setPadding(0, 0, 0, 0)
                                    .build())
                    .setChipStyle(
                            ViewStyle.Builder()
                                    .setBackground(Icon.createWithResource(context, chipBgDrawableId).setTint(chipBgColor))
                                    .build())
                    .setStartIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
                    .setTitleStyle(
                            TextViewStyle.Builder()
                                    .setTextColor(chipTextColor)
                                    .setTextSize(12f)
                                    .build())
                    .setSubtitleStyle(
                            TextViewStyle.Builder()
                                    .setTextColor(chipTextHintColor)
                                    .setTextSize(10f)
                                    .build())
                    .setEndIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
                    .build()
            stylesBuilder.addStyle(style)
            val stylesBundle = stylesBuilder.build()

            val height = context.getResources().getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
            val min = Size(100, height)
            val max = Size(740, height)

            // Three InlinePresentationSpec are required for some password managers
            val presentationSpecs = ArrayList<InlinePresentationSpec>()
            presentationSpecs.add(InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build())
            presentationSpecs.add(InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build())
            presentationSpecs.add(InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build())

            return InlineSuggestionsRequest.Builder(presentationSpecs)
                    .setMaxSuggestionCount(6)
                    .build()
        }

        @JvmStatic
        fun createView(inlineSuggestions: List<InlineSuggestion>,
                       context: Context): InlineContentClipView {
            val container = LinearLayout(context)
            for (inlineSuggestion in inlineSuggestions) {
                inlineSuggestion.inflate(context, Size(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT), context.getMainExecutor()) { view ->
                    if (view != null)
                        container.addView(view)
                }
            }

            val inlineSuggestionView = HorizontalScrollView(context)
            inlineSuggestionView.setHorizontalScrollBarEnabled(false)
            inlineSuggestionView.setOverScrollMode(View.OVER_SCROLL_NEVER)
            inlineSuggestionView.addView(container)

            val scrollableSuggestionsClip = InlineContentClipView(context)
            scrollableSuggestionsClip.addView(inlineSuggestionView)
            return scrollableSuggestionsClip
        }
    }
}
