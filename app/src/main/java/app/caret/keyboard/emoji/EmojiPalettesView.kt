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
        var categoryId: Long = 0
    }

    private inner class PagerAdapter(viewPager: ViewPager2) : RecyclerView.Adapter<PagerViewHolder>() {
        private var pagerInitialized = false
        private val views: MutableMap<Int, RecyclerView> = HashMap(emojiCategory.shownCategories.size)

        init {
            setHasStableIds(true)
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val categoryId = getItemId(position).toInt()
                    setCurrentCategory(EmojiCategory.Category.entries[categoryId], false)
                    val recyclerView = views[position]
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
                    updateState(recyclerView, viewHolder.categoryId)
                }
            })

            emojiRecyclerView.setPersistentDrawingCache(ViewGroup.PERSISTENT_NO_CACHE)
            return viewHolder
        }

        override fun onBindViewHolder(holder: PagerViewHolder, position: Int) {
            holder.categoryId = getItemId(position)
            val recyclerView = getRecyclerView(holder.itemView)
            views.put(position, recyclerView)
            recyclerView.setAdapter(EmojiPalettesAdapter(
                emojiCategory,
                EmojiCategory.Category.entries[holder.categoryId.toInt()],
                this@EmojiPalettesView
            ))

            if (!pagerInitialized) {
                recyclerView.scrollToPosition(emojiCategory.currentCategoryPageId)
                pagerInitialized = true
            }
        }

        override fun getItemCount(): Int {
            return emojiCategory.shownCategories.size
        }

        override fun onViewDetachedFromWindow(holder: PagerViewHolder) {
            if (holder.categoryId == EmojiCategory.Category.RECENTS.ordinal.toLong()) {
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
    private var keyboardActionListener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER
    private val emojiCategory: EmojiCategory
    private lateinit var pager: ViewPager2

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.emojiPalettesViewStyle)

    init {
        colors = Settings.values.colors
        val builder = KeyboardLayoutSet.Builder(context, null, null)
        val res = context.resources
        emojiLayoutParams = EmojiLayoutParams(res)
        builder.setSubtype(RichInputMethodSubtype.emojiSubtype)
        builder.setKeyboardGeometry(ResourceUtils.getKeyboardWidth(context, Settings.values),
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
        val width = (ResourceUtils.getKeyboardWidth(getContext(), Settings.values)
                + paddingLeft + paddingRight)
        val height = (ResourceUtils.getSecondaryKeyboardHeight(res, Settings.values) + emojiLayoutParams.extraHeight
                + paddingTop + paddingBottom)
        emojiCategoryPageIndicatorView!!.indicatorWidth = width
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
        // beside the search field the tabs keep their width and scroll, otherwise they share the row
        iconView.setLayoutParams(
            if (tabScroll != null) LinearLayout.LayoutParams((TAB_WIDTH_DP * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        iconView.setOnClickListener(this)
        if (category == EmojiCategory.Category.RECENTS) {
            iconView.setOnLongClickListener { v ->
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_LONG_PRESS)
                clearRecentKeys()

                true
            }
        }
    }

    /** The row the category tabs scroll in, when the search field stands beside them. */
    private var tabScroll: android.widget.HorizontalScrollView? = null

    /**
     * Caret: a search field at the start of the tab row, as on Gboard, with the tabs scrolling beside it.
     * Returns the row the tabs go into.
     */
    private fun addSearchField(strip: LinearLayout): LinearLayout {
        val density = resources.displayMetrics.density
        val field = android.widget.TextView(context).apply {
            text = context.getString(R.string.emoji_search_field)
            contentDescription = context.getString(R.string.emoji_search_field)
            gravity = android.view.Gravity.CENTER_VERTICAL
            textSize = 14f
            maxLines = 1
            setTextColor(Settings.values.colors.get(ColorType.KEY_HINT_TEXT))
            val icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.sym_keyboard_search_rounded)?.mutate()
            val iconSize = (18 * density).toInt()
            icon?.setBounds(0, 0, iconSize, iconSize)
            icon?.setTint(Settings.values.colors.get(ColorType.EMOJI_CATEGORY))
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = (6 * density).toInt()
            setPaddingRelative((10 * density).toInt(), 0, (14 * density).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(Settings.values.colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
            }
            setOnClickListener { onSearchField(this) }
        }
        strip.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (32 * density).toInt()).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            marginStart = (8 * density).toInt()
            marginEnd = (4 * density).toInt()
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        strip.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        tabScroll = scroll
        return row
    }

    private var searchDownloadOffered = false
    private var searchDownloading = false

    /**
     * Opens emoji search. Without an emoji dictionary there is nothing to search: the first tap then
     * says what it would fetch, the second fetches it and opens the search.
     */
    private fun onSearchField(field: android.widget.TextView) {
        val context = context
        fun search() {
            keyboardActionListener.onPressKey(KeyCode.EMOJI_SEARCH, 0, 1, HapticEvent.KEY_PRESS)
            keyboardActionListener.onCodeInput(KeyCode.EMOJI_SEARCH, NOT_A_COORDINATE, NOT_A_COORDINATE, false)
            keyboardActionListener.onReleaseKey(KeyCode.EMOJI_SEARCH, false)
        }
        if (DictionaryInfoUtils.getLocalesWithEmojiDicts(context).isNotEmpty()) return search()
        if (searchDownloading) return
        if (!searchDownloadOffered) {
            searchDownloadOffered = true
            field.text = context.getString(R.string.emoji_search_download,
                android.text.format.Formatter.formatShortFileSize(context, EmojiDictionaryDownload.TYPICAL_SIZE))
            return
        }
        searchDownloading = true
        field.text = context.getString(R.string.emoji_search_downloading)
        val locale = RichInputMethodManager.getInstance().currentSubtype.locale
        Thread {
            val failure = try {
                EmojiDictionaryDownload.download(context, locale)
                null
            } catch (e: Exception) {
                app.caret.latin.utils.Log.w("EmojiPalettesView", "could not get the emoji dictionary", e)
                e
            }
            post {
                searchDownloading = false
                searchDownloadOffered = false
                if (failure != null) {
                    field.text = context.getString(R.string.emoji_search_download_failed)
                } else {
                    field.text = context.getString(R.string.emoji_search_field)
                    initDictionaryFacilitator()
                    search()
                }
            }
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initialize() { // needs to be delayed for access to EmojiTabStrip, which is not a child of this view
        if (initialized) return
        emojiCategory.initialize()
        tabStrip = KeyboardSwitcher.getInstance().emojiTabStrip as LinearLayout?
        if (Settings.values.isSecondaryStripVisible) {
            val tabs = if (searchInTabRow(context)) addSearchField(tabStrip!!) else tabStrip
            for (properties in emojiCategory.shownCategories) {
                addTab(tabs, properties.category)
            }
        }

        pager = findViewById(R.id.emoji_pager)
        pager.setAdapter(PagerAdapter(pager))
        emojiLayoutParams.setEmojiListProperties(pager)
        val indicatorView: EmojiCategoryPageIndicatorView = findViewById(R.id.emoji_category_page_id_view)
        emojiCategoryPageIndicatorView = indicatorView
        emojiLayoutParams.setCategoryPageIdViewProperties(indicatorView)
        setCurrentCategory(emojiCategory.currentCategory, true)
        indicatorView.setColors(colors.get(ColorType.EMOJI_CATEGORY_SELECTED), colors.get(ColorType.STRIP_BACKGROUND))
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
        val code = key.code
        keyboardActionListener.onPressKey(code, 0, 1, HapticEvent.KEY_PRESS)
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     * This may be called without any prior call to {@link EmojiViewCallback#onPressKey(Key)}.
     */
    override fun onReleaseKey(key: Key) {
        addRecentKey(key)
        val code = key.code
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            // todo: when we enter some emoticons, e.g. :-D, inline emoji search is triggered and emoji view is closed
            //  (one more instance of "emoji search should recognize emoticons", but in this case could be fixed in other ways)
            keyboardActionListener.onTextInput(key.outputText)
        } else if (code != KeyCode.UNSPECIFIED) {
            keyboardActionListener.onCodeInput(code, NOT_A_COORDINATE, NOT_A_COORDINATE, false)
        }
        keyboardActionListener.onReleaseKey(code, false)
        if (Settings.values.alphaAfterEmojiInEmojiView)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false)
    }

    override fun onRemoveRecentsKey(key: Key)
    {
        getRecentsKeyboard().removeRecentsKey(key)
    }

    private fun clearRecentKeys() {
        if (RecentEmojis.get().isEmpty()) return
        RecentEmojis.clear()
        getRecentsKeyboard().removeAllKeys()
        pager.getAdapter()!!.notifyItemChanged(emojiCategory.recentTabId)
        KeyboardSwitcher.getInstance().showToast(getResources().getString(R.string.recent_emojis_cleared), true)
    }

    override fun getDescription(emoji: String?): String? {
        val facilitator = dictionaryFacilitator
        if (facilitator == null) {
            return null
        }

        val wordProperty = facilitator.getWordProperty(getEmojiNeutralVersion(emoji!!))
        if (wordProperty == null || !wordProperty.hasShortcuts) {
            return null
        }

        return wordProperty.shortcutTargets.get(0).word
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    fun startEmojiPalettes(keyVisualAttr: KeyVisualAttributes?,
               editorInfo: EditorInfo?, listener: KeyboardActionListener) {
        initialize()

        setupBottomRowKeyboard(editorInfo, listener)
        val params = KeyDrawParams()
        params.updateParams(emojiLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        EmojiLayoutParams(getResources()).setEmojiListProperties(pager) // necessary when floating
        emojiCategory.reloadRecents() // in case recents changed from outside emoji keyboards
        setupSidePadding()
        initDictionaryFacilitator()
    }

    private fun addRecentKey(key: Key) {
        if (Settings.values.incognitoModeEnabled) {
            // We do not want to log recent keys while being in incognito
            return
        }
        if (emojiCategory.isInRecentTab) {
            getRecentsKeyboard().addPendingKey(key)
            return
        }
        getRecentsKeyboard().addKeyFirst(key)
        pager.getAdapter()!!.notifyItemChanged(emojiCategory.recentTabId)
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo?, listener: KeyboardActionListener) {
        val keyboardView: MainKeyboardView = findViewById(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(getContext(), editorInfo)
        val keyboard = kls.getKeyboard(KeyboardElement.EMOJI_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
    }

    private fun setupSidePadding() {
        val sv = Settings.values
        val keyboardWidth = ResourceUtils.getKeyboardWidth(getContext(), sv)
        val keyboardAttr = getContext().obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
        val leftPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.sidePaddingScale
        val rightPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.sidePaddingScale
        keyboardAttr.recycle()
        pager.setPadding(
                leftPadding.toInt(),
                pager.getPaddingTop(),
                rightPadding.toInt(),
                pager.getPaddingBottom()
        )
        val indicatorView = emojiCategoryPageIndicatorView!!
        indicatorView.setPadding(
                leftPadding.toInt(),
                indicatorView.getPaddingTop(),
                rightPadding.toInt(),
                indicatorView.getPaddingBottom()
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
        keyboardActionListener = listener
    }

    private fun updateEmojiCategoryPageIdView() {
        val indicatorView = emojiCategoryPageIndicatorView
        if (indicatorView == null) {
            return
        }
        indicatorView.setCategoryPageId(
                emojiCategory.currentCategoryPageCount,
                emojiCategory.currentCategoryPageId, 0.0f)
    }

    private fun setCurrentCategory(category: EmojiCategory.Category, initial: Boolean) {
        val oldCategory = emojiCategory.currentCategory
        if (initial || oldCategory != category) {
            emojiCategory.currentCategory = category
            findViewById<android.widget.TextView>(R.id.emoji_section_title)?.let {
                it.text = emojiCategory.getAccessibilityDescription(category)
                it.setTextColor(Settings.values.colors.get(ColorType.KEY_HINT_TEXT))
            }

            if (pager.getScrollState() != ViewPager2.SCROLL_STATE_DRAGGING) {
                // Not swiping
                pager.setCurrentItem(emojiCategory.getTabIdFromCategoryId(
                                emojiCategory.currentCategory), !initial && !isAnimationsDisabled())
            }

            if (Settings.values.isSecondaryStripVisible) {
                val strip = tabStrip!!
                val old: View? = strip.findViewWithTag(oldCategory)
                val current: View? = strip.findViewWithTag(category)

                if (old is ImageView) {
                    Settings.values.colors.setColor(old, ColorType.EMOJI_CATEGORY)
                    Settings.values.colors.setBackground(old, ColorType.STRIP_BACKGROUND)
                }
                if (current is ImageView) {
                    // Caret: the category in view sits in a filled circle, as on Gboard; the circle
                    // marks it, so the icon keeps the color of the others and stays readable on it
                    Settings.values.colors.setColor(current, ColorType.EMOJI_CATEGORY)
                    val circle = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Settings.values.colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
                    }
                    // keep the tab of the category in view when the tabs scroll
                    tabScroll?.let { scroll -> scroll.post { scroll.smoothScrollTo((current.left - (scroll.width - current.width) / 2).coerceAtLeast(0), 0) } }
                    val size = (36 * resources.displayMetrics.density).toInt()
                    val insetX = ((current.width - size) / 2).coerceAtLeast(0)
                    val insetY = ((current.height - size) / 2).coerceAtLeast(0)
                    current.background = android.graphics.drawable.InsetDrawable(circle, insetX, insetY, insetX, insetY)
                }
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
        pager.getAdapter()!!.notifyDataSetChanged()
        closeDictionaryFacilitator()
    }

    private fun initDictionaryFacilitator() {
        if (Settings.values.showEmojiDescriptions) {
            val locale = RichInputMethodManager.getInstance().currentSubtype.locale
            val facilitator = dictionaryFacilitator
            if (facilitator == null || !facilitator.isForLocale(locale)) {
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
            dictionaryFacilitator?.let {
                it.closeDictionaries()
                dictionaryFacilitator = null
            }
        }

        private const val TAB_WIDTH_DP = 42

        /** Whether emoji search is reached from the tab row: when there is one, and something to search. */
        fun searchInTabRow(context: Context) =
            Settings.values.isSecondaryStripVisible && (DictionaryInfoUtils.getLocalesWithEmojiDicts(context).isNotEmpty()
                || EmojiDictionaryDownload.nameFor(RichInputMethodManager.getInstance().currentSubtype.locale) != null)
    }
}
