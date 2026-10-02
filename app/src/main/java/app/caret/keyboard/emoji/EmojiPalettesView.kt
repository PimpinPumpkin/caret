/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.keyboard.emoji

import java.util.HashMap

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

import app.caret.event.HapticEvent
import app.caret.keyboard.Key
import app.caret.keyboard.KeyboardActionListener
import app.caret.keyboard.KeyboardElement
import app.caret.keyboard.KeyboardLayoutSet
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.MainKeyboardView
import app.caret.keyboard.PointerTracker
import app.caret.keyboard.internal.KeyDrawParams
import app.caret.keyboard.internal.KeyVisualAttributes
import app.caret.keyboard.internal.keyboard_parser.getEmojiNeutralVersion
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.AudioAndHapticFeedbackManager
import app.caret.latin.dictionary.Dictionary
import app.caret.latin.dictionary.DictionaryFactory
import app.caret.latin.R
import app.caret.latin.RichInputMethodManager
import app.caret.latin.RichInputMethodSubtype
import app.caret.latin.SingleDictionaryFacilitator
import app.caret.latin.common.ColorType
import app.caret.latin.common.Colors
import app.caret.latin.settings.Settings
import app.caret.latin.utils.DictionaryInfoUtils
import app.caret.latin.utils.ResourceUtils

import app.caret.latin.common.Constants.NOT_A_COORDINATE

/**
 * View class to implement Emoji palettes.
 * The Emoji keyboard consists of group of views layout/emoji_palettes_view.
 * <ol>
 * <li> Emoji category tabs.
 * <li> Delete button.
 * <li> Emoji keyboard pages that can be scrolled by swiping horizontally or by selecting a tab.
 * <li> Back to main keyboard button and enter button.
 * </ol>
 * Because of the above reasons, this class doesn't extend {@link KeyboardView}.
 */
class EmojiPalettesView(context: Context, attrs: AttributeSet?, defStyle: Int) :
        LinearLayout(context, attrs, defStyle), View.OnClickListener, EmojiViewCallback {
    private class PagerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var mCategoryId: Long = 0
    }

    private inner class PagerAdapter(pager: ViewPager2) : RecyclerView.Adapter<PagerViewHolder>() {
        private var mInitialized = false
        private val mViews: MutableMap<Int, RecyclerView> = HashMap(emojiCategory.shownCategories.size)

        init {
            setHasStableIds(true)
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val categoryId = getItemId(position).toInt()
                    setCurrentCategory(EmojiCategory.Category.entries[categoryId], false)
                    val recyclerView = mViews[position]
                    if (recyclerView != null) {
                        updateState(recyclerView, categoryId.toLong())
                    }
                }
            })
        }

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            recyclerView.setItemViewCacheSize(emojiCategory.shownCategories.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagerViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.emoji_category_view, parent, false)
            val viewHolder = PagerViewHolder(view)
            val emojiRecyclerView = getRecyclerView(view)

            emojiRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    // Ignore this message. Only want the actual page selected.
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateState(recyclerView, viewHolder.mCategoryId)
                }
            })

            emojiRecyclerView.setPersistentDrawingCache(ViewGroup.PERSISTENT_NO_CACHE)
            return viewHolder
        }

        override fun onBindViewHolder(holder: PagerViewHolder, position: Int) {
            holder.mCategoryId = getItemId(position)
            val recyclerView = getRecyclerView(holder.itemView)
            mViews.put(position, recyclerView)
            recyclerView.setAdapter(EmojiPalettesAdapter(
                emojiCategory,
                EmojiCategory.Category.entries[holder.mCategoryId.toInt()],
                this@EmojiPalettesView
            ))

            if (!mInitialized) {
                recyclerView.scrollToPosition(emojiCategory.currentCategoryPageId)
                mInitialized = true
            }
        }

        override fun getItemCount(): Int {
            return emojiCategory.shownCategories.size
        }

        override fun onViewDetachedFromWindow(holder: PagerViewHolder) {
            if (holder.mCategoryId == EmojiCategory.Category.RECENTS.ordinal.toLong()) {
                // Needs to save pending updates for recent keys when we get out of the recents
                // category because we don't want to move the recent emojis around while the user
                // is in the recents category.
                getRecentsKeyboard().flushPendingRecentKeys()
                getRecyclerView(holder.itemView).getAdapter()!!.notifyDataSetChanged()
            }
        }

        override fun getItemId(position: Int): Long {
            return emojiCategory.shownCategories[position].category.ordinal.toLong()
        }

        private fun getRecyclerView(view: View): RecyclerView {
            return view.findViewById(R.id.emoji_keyboard_list)
        }

        private fun updateState(recyclerView: RecyclerView, categoryId: Long) {
            if (categoryId != emojiCategory.currentCategory.ordinal.toLong()) {
                return
            }

            val offset = recyclerView.computeVerticalScrollOffset()
            val extent = recyclerView.computeVerticalScrollExtent()
            val range = recyclerView.computeVerticalScrollRange()
            val percentage = offset / (range - extent).toFloat()

            val currentCategorySize = emojiCategory.currentCategoryPageCount
            val a = (percentage * currentCategorySize).toInt()
            val b = percentage * currentCategorySize - a
            emojiCategoryPageIndicatorView!!.setCategoryPageId(currentCategorySize, a, b)

            val layoutManager = recyclerView.getLayoutManager() as LinearLayoutManager?
            val firstCompleteVisibleBoard = layoutManager!!.findFirstCompletelyVisibleItemPosition()
            val firstVisibleBoard = layoutManager.findFirstVisibleItemPosition()
            emojiCategory.currentCategoryPageId =
                    if (firstCompleteVisibleBoard > 0) firstCompleteVisibleBoard else firstVisibleBoard
        }
    }

    private var initialized = false
    private val colors: Colors
    private val emojiLayoutParams: EmojiLayoutParams
    private var tabStrip: LinearLayout? = null
    private var emojiCategoryPageIndicatorView: EmojiCategoryPageIndicatorView? = null
    private var mKeyboardActionListener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER
    private val emojiCategory: EmojiCategory
    private lateinit var mPager: ViewPager2

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.emojiPalettesViewStyle)

    init {
        colors = Settings.getValues().mColors
        val builder = KeyboardLayoutSet.Builder(context, null, null)
        val res = context.resources
        emojiLayoutParams = EmojiLayoutParams(res)
        builder.setSubtype(RichInputMethodSubtype.emojiSubtype)
        builder.setKeyboardGeometry(ResourceUtils.getKeyboardWidth(context, Settings.getValues()),
                emojiLayoutParams.emojiKeyboardHeight)
        val layoutSet = builder.build()
        val emojiPalettesViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.EmojiPalettesView, defStyle, R.style.EmojiPalettesView)
        emojiCategory = EmojiCategory(context, layoutSet, emojiPalettesViewAttr)
        emojiPalettesViewAttr.recycle()
        setFitsSystemWindows(true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = getContext().resources
        // The main keyboard expands to the entire this {@link KeyboardView}.
        val width = (ResourceUtils.getKeyboardWidth(getContext(), Settings.getValues())
                + paddingLeft + paddingRight)
        val height = (ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues())
                + paddingTop + paddingBottom)
        emojiCategoryPageIndicatorView!!.mWidth = width
        setMeasuredDimension(width, height)
    }

    private fun addTab(host: LinearLayout?, category: EmojiCategory.Category) {
        val iconView = ImageView(getContext())
        colors.setBackground(iconView, ColorType.STRIP_BACKGROUND)
        colors.setColor(iconView, ColorType.EMOJI_CATEGORY)
        iconView.setScaleType(ImageView.ScaleType.CENTER)
        iconView.setImageResource(emojiCategory.getCategoryTabIcon(category))
        iconView.setContentDescription(emojiCategory.getAccessibilityDescription(category))
        iconView.setTag(category)
        host!!.addView(iconView)
        iconView.setLayoutParams(LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        iconView.setOnClickListener(this)
        if (category == EmojiCategory.Category.RECENTS) {
            iconView.setOnLongClickListener { v ->
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_LONG_PRESS)
                clearRecentKeys()

                true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initialize() { // needs to be delayed for access to EmojiTabStrip, which is not a child of this view
        if (initialized) return
        emojiCategory.initialize()
        tabStrip = KeyboardSwitcher.getInstance().getEmojiTabStrip() as LinearLayout?
        if (Settings.getValues().isSecondaryStripVisible()) {
            for (properties in emojiCategory.shownCategories) {
                addTab(tabStrip, properties.category)
            }
        }

        mPager = findViewById(R.id.emoji_pager)
        mPager.setAdapter(PagerAdapter(mPager))
        emojiLayoutParams.setEmojiListProperties(mPager)
        emojiCategoryPageIndicatorView = findViewById(R.id.emoji_category_page_id_view)
        emojiLayoutParams.setCategoryPageIdViewProperties(emojiCategoryPageIndicatorView!!)
        setCurrentCategory(emojiCategory.currentCategory, true)
        emojiCategoryPageIndicatorView!!.setColors(colors.get(ColorType.EMOJI_CATEGORY_SELECTED), colors.get(ColorType.STRIP_BACKGROUND))
        initialized = true
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link android.view.View.OnClickListener}
     * interface to handle non-canceled touch-up events from View-based elements such as the space
     * bar.
     */
    override fun onClick(v: View?) {
        val tag = v!!.getTag()
        if (tag is EmojiCategory.Category) {
            val category: EmojiCategory.Category = tag
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            if (category != emojiCategory.currentCategory) {
                setCurrentCategory(category, false)
                updateEmojiCategoryPageIdView()
            }
        }
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     */
    override fun onPressKey(key: Key) {
        val code = key.getCode()
        mKeyboardActionListener.onPressKey(code, 0, 1, HapticEvent.KEY_PRESS)
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     * This may be called without any prior call to {@link EmojiViewCallback#onPressKey(Key)}.
     */
    override fun onReleaseKey(key: Key) {
        addRecentKey(key)
        val code = key.getCode()
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            // todo: when we enter some emoticons, e.g. :-D, inline emoji search is triggered and emoji view is closed
            //  (one more instance of "emoji search should recognize emoticons", but in this case could be fixed in other ways)
            mKeyboardActionListener.onTextInput(key.getOutputText())
        } else if (code != KeyCode.UNSPECIFIED) {
            mKeyboardActionListener.onCodeInput(code, NOT_A_COORDINATE, NOT_A_COORDINATE, false)
        }
        mKeyboardActionListener.onReleaseKey(code, false)
        if (Settings.getValues().alphaAfterEmojiInEmojiView)
            mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false)
    }

    override fun onRemoveRecentsKey(key: Key)
    {
        getRecentsKeyboard().removeRecentsKey(key)
    }

    private fun clearRecentKeys() {
        if (RecentEmojis.get().isEmpty()) return
        RecentEmojis.clear()
        getRecentsKeyboard().removeAllKeys()
        mPager.getAdapter()!!.notifyItemChanged(emojiCategory.recentTabId)
        KeyboardSwitcher.getInstance().showToast(getResources().getString(R.string.recent_emojis_cleared), true)
    }

    override fun getDescription(emoji: String?): String? {
        if (dictionaryFacilitator == null) {
            return null
        }

        val wordProperty = dictionaryFacilitator!!.getWordProperty(getEmojiNeutralVersion(emoji!!))
        if (wordProperty == null || !wordProperty.mHasShortcuts) {
            return null
        }

        return wordProperty.mShortcutTargets.get(0).mWord
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    fun startEmojiPalettes(keyVisualAttr: KeyVisualAttributes?,
               editorInfo: EditorInfo?, keyboardActionListener: KeyboardActionListener) {
        initialize()

        setupBottomRowKeyboard(editorInfo, keyboardActionListener)
        val params = KeyDrawParams()
        params.updateParams(emojiLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        EmojiLayoutParams(getResources()).setEmojiListProperties(mPager) // necessary when floating
        emojiCategory.reloadRecents() // in case recents changed from outside emoji keyboards
        setupSidePadding()
        initDictionaryFacilitator()
    }

    private fun addRecentKey(key: Key) {
        if (Settings.getValues().incognitoModeEnabled) {
            // We do not want to log recent keys while being in incognito
            return
        }
        if (emojiCategory.isInRecentTab) {
            getRecentsKeyboard().addPendingKey(key)
            return
        }
        getRecentsKeyboard().addKeyFirst(key)
        mPager.getAdapter()!!.notifyItemChanged(emojiCategory.recentTabId)
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo?, keyboardActionListener: KeyboardActionListener) {
        val keyboardView: MainKeyboardView = findViewById(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(keyboardActionListener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(getContext(), editorInfo)
        val keyboard = kls.getKeyboard(KeyboardElement.EMOJI_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
    }

    private fun setupSidePadding() {
        val sv = Settings.getValues()
        val keyboardWidth = ResourceUtils.getKeyboardWidth(getContext(), sv)
        val keyboardAttr = getContext().obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
        val leftPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.sidePaddingScale
        val rightPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.sidePaddingScale
        keyboardAttr.recycle()
        mPager.setPadding(
                leftPadding.toInt(),
                mPager.getPaddingTop(),
                rightPadding.toInt(),
                mPager.getPaddingBottom()
        )
        emojiCategoryPageIndicatorView!!.setPadding(
                leftPadding.toInt(),
                emojiCategoryPageIndicatorView!!.getPaddingTop(),
                rightPadding.toInt(),
                emojiCategoryPageIndicatorView!!.getPaddingBottom()
        )
        // setting width does not do anything, so we have some workaround in EmojiCategoryPageIndicatorView
    }

    fun stopEmojiPalettes() {
        if (!initialized) return
        getRecentsKeyboard().flushPendingRecentKeys()
    }

    private fun getRecentsKeyboard(): DynamicGridKeyboard {
        return emojiCategory.getKeyboard(EmojiCategory.Category.RECENTS, 0)
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        mKeyboardActionListener = listener
    }

    private fun updateEmojiCategoryPageIdView() {
        if (emojiCategoryPageIndicatorView == null) {
            return
        }
        emojiCategoryPageIndicatorView!!.setCategoryPageId(
                emojiCategory.currentCategoryPageCount,
                emojiCategory.currentCategoryPageId, 0.0f)
    }

    private fun setCurrentCategory(category: EmojiCategory.Category, initial: Boolean) {
        val oldCategory = emojiCategory.currentCategory
        if (initial || oldCategory != category) {
            emojiCategory.currentCategory = category

            if (mPager.getScrollState() != ViewPager2.SCROLL_STATE_DRAGGING) {
                // Not swiping
                mPager.setCurrentItem(emojiCategory.getTabIdFromCategoryId(
                                emojiCategory.currentCategory), !initial && !isAnimationsDisabled())
            }

            if (Settings.getValues().isSecondaryStripVisible()) {
                val old: View? = tabStrip!!.findViewWithTag(oldCategory)
                val current: View? = tabStrip!!.findViewWithTag(category)

                if (old is ImageView)
                    Settings.getValues().mColors.setColor(old, ColorType.EMOJI_CATEGORY)
                if (current is ImageView)
                    Settings.getValues().mColors.setColor(current, ColorType.EMOJI_CATEGORY_SELECTED)
            }
        }
    }

    private fun isAnimationsDisabled(): Boolean {
        return android.provider.Settings.Global.getFloat(getContext().contentResolver,
                                                         android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0.0f
    }

    fun clearKeyboardCache() {
        if (!initialized) {
            return
        }

        emojiCategory.clearKeyboardCache()
        mPager.getAdapter()!!.notifyDataSetChanged()
        closeDictionaryFacilitator()
    }

    private fun initDictionaryFacilitator() {
        if (Settings.getValues().showEmojiDescriptions) {
            val locale = RichInputMethodManager.getInstance().currentSubtype.locale
            if (dictionaryFacilitator == null || !dictionaryFacilitator!!.isForLocale(locale)) {
                closeDictionaryFacilitator()
                val dictFile = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, getContext())
                val dictionary = if (dictFile != null) DictionaryFactory.getDictionary(dictFile, locale) else null
                dictionaryFacilitator = if (dictionary != null) SingleDictionaryFacilitator(dictionary) else null
            }
        } else {
            closeDictionaryFacilitator()
        }
    }

    companion object {
        private var dictionaryFacilitator: SingleDictionaryFacilitator? = null

        @JvmStatic
        fun closeDictionaryFacilitator() {
            if (dictionaryFacilitator != null) {
                dictionaryFacilitator!!.closeDictionaries()
                dictionaryFacilitator = null
            }
        }
    }
}
