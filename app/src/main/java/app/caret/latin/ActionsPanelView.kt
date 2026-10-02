// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.caret.keyboard.KeyboardTypeface
import app.caret.keyboard.internal.KeyboardIconsSet
import app.caret.latin.common.ColorType
import app.caret.latin.settings.Settings
import app.caret.latin.utils.ToolbarKey
import app.caret.latin.utils.getStringResourceOrName

/**
 * Every action in one grid, shown over the keys. The toolbar holds the favorites, this is the
 * overflow: tap an action to run it, or Close to go back to the keys.
 */
@SuppressLint("ViewConstructor")
class ActionsPanelView(
    context: Context,
    private val onAction: (View) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    init {
        orientation = VERTICAL
        isClickable = true // swallow touches so they do not reach the keys underneath
        val colors = Settings.values.colors
        setBackgroundColor(colors.get(ColorType.MAIN_BACKGROUND))
        val textColor = colors.get(ColorType.KEY_TEXT)
        val tileColor = colors.get(ColorType.KEY_BACKGROUND)
        val functionColor = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)

        val grid = GridLayout(context).apply { columnCount = COLUMNS }
        val actions = ToolbarKey.entries.filter {
            it != ToolbarKey.MORE && it != ToolbarKey.CLOSE_HISTORY && it != ToolbarKey.BACKGROUND_GATHERING
        }
        for (action in actions) {
            val tile = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                tag = action
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(tileColor) }
                setPadding(dp(4), dp(8), dp(4), dp(6))
                setOnClickListener { view -> onAction(view) }
            }
            tile.addView(ImageView(context).apply {
                setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(action.name, context))
                setColorFilter(textColor)
            }, LayoutParams(dp(24), dp(24)))
            tile.addView(TextView(context).apply {
                text = action.name.lowercase().getStringResourceOrName("", context)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = KeyboardTypeface.resolve(null)
                gravity = Gravity.CENTER
                maxLines = 2
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            grid.addView(tile, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = dp(76)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        val scroll = ScrollView(context).apply {
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            setPadding(dp(5), dp(4), dp(5), 0)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val close = TextView(context).apply {
            text = context.getString(R.string.actions_panel_close)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = KeyboardTypeface.resolve(null)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(functionColor) }
            setOnClickListener { onClose() }
        }
        addView(close, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { setMargins(dp(8), dp(6), dp(8), dp(8)) })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // take the size of the keyboard, never make it bigger
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY || MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(0, 0)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        const val COLUMNS = 4
    }
}
