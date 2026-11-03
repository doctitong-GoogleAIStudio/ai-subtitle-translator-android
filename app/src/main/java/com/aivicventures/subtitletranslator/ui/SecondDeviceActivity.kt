package com.aivicventures.subtitletranslator.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.live.LiveSession
import com.aivicventures.subtitletranslator.live.LiveSubtitleService
import com.aivicventures.subtitletranslator.live.OutlinedTextView
import com.aivicventures.subtitletranslator.settings.Languages
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Second-device mode: the phone listens to a TV, laptop or another phone and shows the
 * English subtitles here, large, on black, with the screen kept on.
 */
class SecondDeviceActivity : ComponentActivity() {
    private lateinit var subtitle: OutlinedTextView
    private lateinit var status: TextView
    private lateinit var pause: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val look = app.liveSettings.appearance()
        subtitle = OutlinedTextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (look.sizeSp * 1.6f).coerceIn(28f, 64f))
            setTextColor(look.color)
            typeface = Typeface.create(Typeface.SANS_SERIF, if (look.bold) Typeface.BOLD else Typeface.NORMAL)
            outline = look.outline
            setPadding(dp(24), 0, dp(24), 0)
        }
        status = TextView(this).apply {
            setTextColor(0xFFB0B4BC.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        pause = tonalButton(getString(R.string.live_pause), R.drawable.ic_pause) {
            LiveSubtitleService.command(this, LiveSubtitleService.ACTION_PAUSE)
        }
        val stop = button(getString(R.string.live_stop), R.drawable.ic_stop) {
            LiveSubtitleService.command(this, LiveSubtitleService.ACTION_STOP)
            finish()
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(pause)
            addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(16) })
            addView(subtitle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(16) })
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { LiveSession.state.collect(::render) }
        }
    }

    private fun render(s: LiveSession.State) {
        val sb = SpannableStringBuilder()
        for ((i, l) in s.lines.withIndex()) {
            if (i > 0) sb.append('\n')
            val from = sb.length
            sb.append(l.text)
            if (l.provisional) sb.setSpan(StyleSpan(Typeface.ITALIC), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        subtitle.text = sb
        val parts = mutableListOf(
            when (s.phase) {
                LiveSession.Phase.LOADING -> getString(R.string.live_phase_loading)
                LiveSession.Phase.PAUSED -> getString(R.string.live_phase_paused)
                LiveSession.Phase.ERROR -> s.error ?: getString(R.string.live_phase_error)
                LiveSession.Phase.ENDED, LiveSession.Phase.IDLE -> s.notice ?: getString(R.string.live_phase_ended)
                else -> getString(if (s.hearing) R.string.live_phase_hearing else R.string.live_phase_listening)
            },
        )
        if (s.language.isNotEmpty()) parts += Languages.name(s.language)
        s.latency?.let { parts += getString(R.string.live_latency_short, it.totalMs / 1000.0) }
        status.text = parts.joinToString(" · ")
        val paused = s.phase == LiveSession.Phase.PAUSED
        pause.text = getString(if (paused) R.string.live_resume else R.string.live_pause)
        pause.setIconResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        pause.isEnabled = s.running
    }
}
