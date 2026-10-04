// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.annotation.SuppressLint
import android.content.ClipData
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
 * rest: tap an action to run it, hold and drag it onto the toolbar to put it there, or tap the
 * back arrow to go back to the keys.
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

        // Caret: as on Gboard, a back arrow and a hint on top instead of a close button below
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_left)
            setColorFilter(textColor)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.actions_panel_back)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tileColor) }
            setOnClickListener { onClose() }
        }, LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
        header.addView(TextView(context).apply {
            text = context.getString(R.string.actions_panel_hint)
            setTextColor(textColor)
            alpha = 0.75f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = KeyboardTypeface.resolve(null)
            gravity = Gravity.CENTER
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(44) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        val grid = GridLayout(context).apply { columnCount = COLUMNS }
        val actions = ToolbarKey.entries.filter {
            it != ToolbarKey.MORE && it != ToolbarKey.CLOSE_HISTORY && it != ToolbarKey.BACKGROUND_GATHERING
        }
        for (action in actions) {
            val tile = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                tag = action
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(tileColor) }
                setPadding(dp(4), dp(6), dp(4), dp(4))
                setOnClickListener { view -> onAction(view) }
                // hold and drag onto the toolbar to put the action there
                setOnLongClickListener { view ->
                    view.startDragAndDrop(ClipData.newPlainText("action", action.name), DragShadowBuilder(view), action, 0)
                }
            }
            tile.addView(ImageView(context).apply {
                setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(action.name, context))
                setColorFilter(textColor)
            }, LayoutParams(dp(22), dp(22)))
            tile.addView(TextView(context).apply {
                text = action.name.lowercase().getStringResourceOrName("", context)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = KeyboardTypeface.resolve(null)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            grid.addView(tile, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = dp(56)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        val scroll = ScrollView(context).apply {
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            setPadding(dp(8), 0, dp(8), dp(4))
            clipToPadding = false
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
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
