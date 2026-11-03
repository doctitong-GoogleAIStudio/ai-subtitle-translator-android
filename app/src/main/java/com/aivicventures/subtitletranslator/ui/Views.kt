package com.aivicventures.subtitletranslator.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.aivicventures.subtitletranslator.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import com.google.android.material.R as M

/** Helpers for the code-built screens: Material 3 components, brand colours from the theme. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, 0)

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(8), dp(16), dp(24))
}

/**
 * A screen: top app bar plus a scrolling column, drawn edge to edge with the bars' insets
 * applied. Returns the column to fill; [toolbar] gets menu items if needed.
 */
class Scaffold(val toolbar: MaterialToolbar, val content: LinearLayout)

fun ComponentActivity.scaffold(title: String, back: Boolean = true): Scaffold {
    enableEdgeToEdge()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(themeColor(M.attr.colorSurface))
    }
    val bar = MaterialToolbar(this).apply {
        this.title = title
        if (back) {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { finish() }
        }
    }
    root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val col = column()
    val scroll = NestedScrollView(this).apply {
        clipToPadding = false
        addView(col)
    }
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        bar.setPadding(b.left, b.top, b.right, 0)
        scroll.setPadding(b.left, 0, b.right, b.bottom)
        insets
    }
    return Scaffold(bar, col)
}

private fun Context.text(text: CharSequence, @AttrRes appearance: Int, @AttrRes color: Int): TextView = TextView(this).apply {
    this.text = text
    val style = themeAttrRes(appearance)
    if (style != 0) setTextAppearance(style)
    setTextColor(themeColor(color))
}

private fun Context.themeAttrRes(@AttrRes attr: Int): Int {
    val tv = android.util.TypedValue()
    return if (theme.resolveAttribute(attr, tv, true)) tv.resourceId else 0
}

fun Context.headline(text: CharSequence): TextView = text(text, M.attr.textAppearanceHeadlineSmall, M.attr.colorOnSurface)
fun Context.heading(text: CharSequence): TextView = text(text, M.attr.textAppearanceTitleMedium, M.attr.colorOnSurface)
/** The title of a settings row. */
fun Context.rowTitle(text: CharSequence): TextView = text(text, M.attr.textAppearanceBodyLarge, M.attr.colorOnSurface)
fun Context.body(text: CharSequence = ""): TextView = text(text, M.attr.textAppearanceBodyMedium, M.attr.colorOnSurfaceVariant)
fun Context.caption(text: CharSequence = ""): TextView = text(text, M.attr.textAppearanceBodySmall, M.attr.colorOnSurfaceVariant)

/** A section label above a group of cards ("Recent movies", "Subtitles", …). */
fun Context.sectionTitle(text: CharSequence): TextView = text(text, M.attr.textAppearanceTitleSmall, androidx.appcompat.R.attr.colorPrimary).apply {
    setPadding(dp(4), dp(20), dp(4), dp(8))
}

fun TextView.singleLine(): TextView = apply {
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

/** Filled card (surface container colour, no shadow) with a padded column inside. */
fun Context.card(padding: Int = 16, @AttrRes background: Int = M.attr.colorSurfaceContainerLow, block: LinearLayout.() -> Unit = {}): MaterialCardView =
    MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(themeColor(background))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            block()
        })
    }

val MaterialCardView.inner: LinearLayout get() = getChildAt(0) as LinearLayout

fun Context.button(text: String, @DrawableRes icon: Int = 0, @AttrRes style: Int = M.attr.materialButtonStyle, onClick: () -> Unit): MaterialButton =
    MaterialButton(this, null, style).apply {
        this.text = text
        isAllCaps = false
        if (icon != 0) setIconResource(icon)
        setOnClickListener { onClick() }
    }

fun Context.tonalButton(text: String, @DrawableRes icon: Int = 0, onClick: () -> Unit) = button(text, icon, R.attr.appTonalButtonStyle, onClick)
fun Context.textButton(text: String, @DrawableRes icon: Int = 0, onClick: () -> Unit) = button(text, icon, R.attr.appTextButtonStyle, onClick)

fun Context.iconButton(@DrawableRes icon: Int, description: String, onClick: (View) -> Unit): MaterialButton =
    MaterialButton(this, null, R.attr.appIconButtonStyle).apply {
        setIconResource(icon)
        contentDescription = description
        setOnClickListener(onClick)
    }

/** An icon in a rounded tinted square, for list rows. */
fun Context.iconBadge(@DrawableRes icon: Int, @AttrRes fill: Int = M.attr.colorSecondaryContainer, @AttrRes tint: Int = M.attr.colorOnSecondaryContainer, size: Int = 44): ImageView =
    ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(themeColor(tint))
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(themeColor(fill))
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

/** A horizontal row: [leading] view, a growing middle column, optional [trailing] view. */
fun Context.row(leading: View?, middle: LinearLayout, trailing: View? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (leading != null) addView(leading)
    addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        if (leading != null) leftMargin = dp(14)
    })
    if (trailing != null) addView(trailing)
}

fun Context.stack(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    views.forEach { addView(it) }
}

/** A settings row: title and current value; tapping it calls [onClick]. */
fun Context.choiceRow(title: String, value: String, help: String? = null, onClick: (TextView) -> Unit): LinearLayout {
    val valueView = body(value).apply { setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary)) }
    val col = stack(rowTitle(title), valueView)
    if (help != null) col.addView(caption(help).apply { setPadding(0, dp(2), 0, 0) })
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        isFocusable = true
        background = ripple()
        addView(col)
        setOnClickListener { onClick(valueView) }
    }
}

/** A settings row with a switch at the end. */
fun Context.switchRow(title: String, help: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): LinearLayout {
    val sw = MaterialSwitch(this).apply {
        isChecked = checked
        isEnabled = enabled
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    val col = stack(rowTitle(title).apply { alpha = if (enabled) 1f else 0.5f })
    if (help != null) col.addView(caption(help).apply { setPadding(0, dp(2), dp(8), 0) })
    return row(null, col, sw).apply {
        setPadding(0, dp(10), 0, dp(10))
        if (enabled) setOnClickListener { sw.toggle() }
    }
}

fun Context.ripple(): android.graphics.drawable.Drawable? {
    val tv = android.util.TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId)
}

fun LinearLayout.addFull(v: View, topMargin: Int = 0): LinearLayout {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        this.topMargin = context.dp(topMargin)
    })
    return this
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(themeColor(M.attr.colorOutlineVariant))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
}

fun formatTime(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A row showing the current choice; tapping opens a single-choice dialog. */
fun Context.choice(title: String, items: List<String>, selected: Int, help: String?, onSelect: (Int) -> Unit): LinearLayout {
    var current = selected.coerceIn(0, items.size - 1)
    return choiceRow(title, items[current], help) { valueView: TextView ->
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), current) { dialog, which ->
                current = which
                valueView.text = items[which]
                onSelect(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

fun Context.slider(title: String, from: Int, to: Int, value: Int, label: (Int) -> String, onChange: (Int) -> Unit): LinearLayout =
    slider(title, from.toFloat(), to.toFloat(), 1f, value.toFloat(), { label(it.toInt()) }) { onChange(it.toInt()) }

/** A titled slider whose current value is shown at the end of the title row. */
fun Context.slider(title: String, from: Float, to: Float, step: Float, value: Float, label: (Float) -> String, onChange: (Float) -> Unit): LinearLayout {
    val valueView = body(label(value))
    return stack(
        row(null, stack(rowTitle(title)), valueView),
        Slider(this).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
            // The slider rejects values off its step grid.
            this.value = (from + Math.round((value.coerceIn(from, to) - from) / step) * step).coerceIn(from, to)
            setLabelFormatter { label(it) }
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    onChange(v)
                    valueView.text = label(v)
                }
            }
        },
    ).apply { setPadding(0, dp(10), 0, 0) }
}
