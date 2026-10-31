package com.aivicventures.subtitletranslator.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Small helpers for the code-built screens (no AppCompat / Material dependency). */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
}

fun Context.heading(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 18f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(12), 0, dp(4))
}

fun Context.body(text: String = ""): TextView = TextView(this).apply {
    this.text = text
    textSize = 15f
    setPadding(0, dp(2), 0, dp(2))
}

fun Context.button(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun LinearLayout.addFull(v: View): LinearLayout {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return this
}

/** Edge-to-edge is enforced from API 35: keep content clear of the status and navigation bars. */
fun View.padForSystemBars() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
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
