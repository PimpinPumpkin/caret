package app.caret.latin.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import app.caret.keyboard.KeyboardSwitcher
import app.caret.latin.R
import app.caret.latin.define.DebugFlags
import app.caret.latin.settings.Settings
import app.caret.latin.settings.setFloatingSize

// todo: add a frame around the keyboard (because other people care more about optics than I do)
object FloatingKeyboardUtils {
    private val TAG = this::class.java.simpleName
    private val windowFrame = Rect()
    private var extraHeight = 0f
    private const val CORNER_DP = 20
    private const val ELEVATION_DP = 6

    @JvmStatic
    fun setFloating(view: View?) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        view.getWindowVisibleDisplayFrame(windowFrame)
        // On Android 15+ (edge-to-edge) getWindowVisibleDisplayFrame includes the area behind the navbar, so we remove it here
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val navBarBottom = ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            windowFrame.bottom -= navBarBottom
        }
        extraHeight = getSuggestionStripHeight(view.resources) + getFloatingHandleHeight(view.resources)
        val (x, y) = readPosition(
            view.context,
            windowFrame.right - windowFrame.left - Settings.values.floatingWidth,
            windowFrame.bottom - windowFrame.top - extraHeight.toInt() - Settings.values.floatingHeight
        )
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "place floating view at $x, $y, width ${Settings.values.floatingWidth}, height ${Settings.values.floatingHeight}")
        ViewLayoutUtils.placeViewAt(view, x, y, Settings.values.floatingWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Caret: a rounded card that casts a shadow, so it reads as lying on top of the app
        val density = view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, CORNER_DP * density)
        }
        view.clipToOutline = true
        view.elevation = ELEVATION_DP * density
        // Caret: the handles in the color of the key labels, they were white on a light keyboard
        for (id in intArrayOf(R.id.drag_handle, R.id.resize_handle))
            view.findViewById<ImageView>(id)?.let { Settings.values.colors.setColor(it, app.caret.latin.common.ColorType.TOOL_BAR_KEY) }
        if (view.findViewById<View>(R.id.float_handle_container)?.isVisible == true)
            return
        view.findViewById<View>(R.id.float_handle_container)?.isVisible = true
        view.findViewById<ImageView>(R.id.drag_handle)?.setDragListener(view)
        view.findViewById<ImageView>(R.id.resize_handle)?.setResizeListener(lp)
    }

    @JvmStatic
    fun disableFloating(view: View?) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) return // not floating
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "disable floating view")
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.leftMargin = 0
        lp.topMargin = 0
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = false
        view.elevation = 0f
        view.findViewById<View>(R.id.float_handle_container)?.isGone = true
    }

    @JvmStatic
    fun getFloatingHandleHeight(resources: Resources) = resources.getDimension(R.dimen.config_floating_handle_height)

    @JvmStatic
    fun readPosition(context: Context, maxX: Int, maxY: Int): Pair<Int, Int> {
        // got a crash report where max was < 0 -> probably some crap like visibleDisplayFrame size 0
        val maxX = maxX.coerceAtLeast(0)
        val maxY = maxY.coerceAtLeast(0)
        val width = context.resources.displayMetrics.widthPixels
        // Caret: starts centered. Not from maxX and maxY: the touchable region asks without limits
        // and has to get the same place.
        val x = context.prefs().getInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, (width - Settings.values.floatingWidth) / 2)
        val y = context.prefs().getInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, context.resources.displayMetrics.heightPixels / 2)
        if (x > maxX || y > maxY)
            savePosition(context, maxX, maxY)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        val width = context.resources.displayMetrics.widthPixels
        context.prefs().edit {
            putInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, x)
            putInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, y)
        }
    }

    private fun getSuggestionStripHeight(resources: Resources) =
        if (Settings.values.toolbarMode == ToolbarMode.HIDDEN) 0
        else resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setDragListener(view: View) {
        var startX = 0f
        var startY = 0f
        val lp = view.layoutParams as ViewGroup.MarginLayoutParams
        var positionX = lp.leftMargin.toFloat()
        var positionY = lp.topMargin.toFloat()
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    startX = event.rawX
                    startY = event.rawY
                    val sv = Settings.values
                    val availableWidth = windowFrame.right - windowFrame.left
                    val availableHeight = windowFrame.bottom - windowFrame.top
                    positionX = (positionX + dx).coerceIn(0f, (availableWidth - sv.floatingWidth).toFloat())
                    positionY = (positionY + dy).coerceIn(0f, availableHeight - extraHeight - sv.floatingHeight)
                    lp.leftMargin = positionX.toInt()
                    lp.topMargin = positionY.toInt()
                    savePosition(context, lp.leftMargin, lp.topMargin)
                    view.layoutParams = lp // to update immediately
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setResizeListener(lp: ViewGroup.MarginLayoutParams) {
        var startX = 0f
        var startY = 0f
        val scale = 3 / context.resources.displayMetrics.density
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    startX = event.rawX
                    startY = event.rawY
                    val availableWidth = windowFrame.right - windowFrame.left
                    val availableHeight = windowFrame.bottom - windowFrame.top
                    val maxWidth = (availableWidth * 0.9f).toInt()
                    val maxHeight = (availableHeight * 0.9f).toInt()
                    // min size should be independent of orientation
                    val minWidth = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.2)
                        .toInt().coerceAtLeast(200)
                    val minHeight = (minWidth * 0.75).toInt()
                    // avoid setting window outside windowFrame, view behaves strange otherwise
                    val newWidth = (Settings.values.floatingWidth + dx / scale).toInt()
                        .coerceIn(minWidth, maxWidth)
                        .coerceAtMost(availableWidth - lp.leftMargin)
                    val newHeight = (Settings.values.floatingHeight + dy / scale).toInt()
                        .coerceIn(minHeight, maxHeight)
                        .coerceAtMost(availableHeight - extraHeight.toInt() - lp.topMargin)
                    setFloatingSize(context, newWidth, newHeight)
                    KeyboardSwitcher.getInstance().reloadKeyboard()
                    // updating window is done in updateFloating, called by reloadKeyboard
                    true
                }
                else -> false
            }
        }
    }
}
