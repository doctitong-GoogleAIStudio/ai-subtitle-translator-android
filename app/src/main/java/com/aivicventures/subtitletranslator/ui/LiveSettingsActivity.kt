package com.aivicventures.subtitletranslator.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.live.AudioMode
import com.aivicventures.subtitletranslator.live.LiveEngine
import com.aivicventures.subtitletranslator.live.OutlinedTextView
import com.aivicventures.subtitletranslator.live.Quality
import com.aivicventures.subtitletranslator.settings.Languages
import com.aivicventures.subtitletranslator.settings.LiveSettings
import com.aivicventures.subtitletranslator.translate.TranslatorNative

/** Subtitle, translation, audio and performance settings of Live Floating Subtitles; a running session picks up the look and timing at once. */
class LiveSettingsActivity : ComponentActivity() {
    private val s by lazy { app.liveSettings }
    private lateinit var preview: OutlinedTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = scaffold(getString(R.string.live_settings_title)).content

        col.addFull(sectionTitle(getString(R.string.live_set_appearance)))
        preview = OutlinedTextView(this).apply {
            text = getString(R.string.live_preview)
            gravity = Gravity.CENTER
        }
        col.addFull(FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                colors = intArrayOf(0xFF2B4A6F.toInt(), 0xFF0E1A2B.toInt())
            }
            setPadding(dp(16), dp(36), dp(16), dp(20))
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })
        col.addFull(card(16) {
            addFull(slider(getString(R.string.setting_size), LiveSettings.SIZE_MIN, LiveSettings.SIZE_MAX, s.sizeSp, { getString(R.string.setting_size_value, it) }) {
                s.sizeSp = it
                updatePreview()
            })
            addFull(choice(getString(R.string.live_set_color), LiveSettings.COLORS.map { it.first }, s.colorIndex, null) {
                s.colorIndex = it
                updatePreview()
            })
            addFull(switchRow(getString(R.string.live_set_bold), null, s.bold) { s.bold = it; updatePreview() })
            addFull(switchRow(getString(R.string.live_set_outline), null, s.outline) { s.outline = it; updatePreview() })
            addFull(slider(getString(R.string.live_set_background), 0, 100, s.backgroundPercent, { getString(R.string.live_percent, it) }) {
                s.backgroundPercent = it
                updatePreview()
            })
            addFull(slider(getString(R.string.live_set_position), LiveSettings.POSITION_MIN, LiveSettings.POSITION_MAX, 0.01f, s.position,
                { getString(R.string.live_set_position_value, (it * 100).toInt()) }) { s.position = it })
            addFull(choice(getString(R.string.live_set_lines), listOf(getString(R.string.live_set_lines_1), getString(R.string.live_set_lines_2)), s.maxLines - 1,
                getString(R.string.live_set_lines_help)) { s.maxLines = it + 1 })
        })

        col.addFull(sectionTitle(getString(R.string.live_set_translation)))
        col.addFull(card(16) {
            val langs = Languages.choices
            addFull(choice(getString(R.string.live_source_language), langs.map { it.second },
                langs.indexOfFirst { it.first == s.sourceLanguage }.coerceAtLeast(0), getString(R.string.setting_language_help)) { s.sourceLanguage = langs[it].first })
            addFull(choiceRow(getString(R.string.live_target_language), getString(R.string.live_english)) {}.apply { isClickable = false; background = null })
            val qualities = Quality.entries
            addFull(choice(getString(R.string.live_quality),
                qualities.map { getString(if (it == Quality.FAST) R.string.live_quality_fast else R.string.live_quality_high) },
                qualities.indexOf(s.quality), getString(R.string.live_quality_help)) { s.quality = qualities[it] })
            val asr = app.models.catalog.filter { it.kind == "asr" }
            val auto = LiveEngine.chooseModel(app.models, "")
            addFull(choice(getString(R.string.setting_model), listOf(getString(R.string.setting_model_auto, auto?.name ?: "-")) + asr.map { it.name },
                asr.indexOfFirst { it.id == s.asrModel } + 1, getString(R.string.live_set_model_help)) { s.asrModel = if (it == 0) "" else asr[it - 1].id })
            val engines = listOf("auto", "m2m100", "whisper")
            addFull(choice(getString(R.string.setting_translator),
                listOf(getString(R.string.translator_auto), getString(R.string.translator_m2m100), getString(R.string.translator_whisper)),
                engines.indexOf(s.translator).coerceAtLeast(0),
                getString(if (!TranslatorNative.available) R.string.setting_translator_32bit else R.string.setting_translator_help)) { s.translator = engines[it] })
            addFull(switchRow(getString(R.string.live_set_context), getString(R.string.live_set_context_help), s.contextMemory) { s.contextMemory = it })
        })

        col.addFull(sectionTitle(getString(R.string.live_set_timing)))
        col.addFull(card(16) {
            addFull(slider(getString(R.string.live_set_delay), LiveSettings.DELAY_MIN, LiveSettings.DELAY_MAX, 0.5f, s.delaySec,
                { getString(R.string.live_set_delay_value, it) }) { s.delaySec = it })
            addFull(caption(getString(R.string.live_set_delay_help)), 4)
        })

        col.addFull(sectionTitle(getString(R.string.live_set_audio)))
        col.addFull(card(16) {
            val modes = AudioMode.entries
            addFull(choice(getString(R.string.live_audio_source), modes.map {
                getString(when (it) {
                    AudioMode.INTERNAL -> R.string.live_mode_internal
                    AudioMode.MICROPHONE -> R.string.live_mode_mic
                    AudioMode.SECOND_DEVICE -> R.string.live_mode_second
                })
            }, modes.indexOf(s.audioMode), null) { s.audioMode = modes[it] })
            val ns = NoiseSuppressor.isAvailable()
            addFull(switchRow(getString(R.string.live_set_ns), getString(if (ns) R.string.live_set_ns_help else R.string.live_set_ns_none), s.noiseSuppression && ns, ns) {
                s.noiseSuppression = it
            })
            addFull(slider(getString(R.string.live_set_gain), 0.5f, 4f, 0.25f, s.micGain, { getString(R.string.live_percent, (it * 100).toInt()) }) { s.micGain = it })
            addFull(caption(getString(R.string.live_set_gain_help)), 4)
        })

        col.addFull(sectionTitle(getString(R.string.settings_performance)))
        col.addFull(card(16) {
            val stops = LiveSettings.AUTO_STOP
            addFull(choice(getString(R.string.live_set_auto_stop), stops.map { if (it == 0) getString(R.string.live_set_auto_stop_never) else getString(R.string.live_set_auto_stop_value, it) },
                stops.indexOf(s.autoStopMinutes).coerceAtLeast(0), getString(R.string.live_set_auto_stop_help)) { s.autoStopMinutes = stops[it] })
            addFull(choiceRow(getString(R.string.live_set_shared), getString(R.string.live_set_shared_value)) {
                startActivity(android.content.Intent(this@LiveSettingsActivity, SettingsActivity::class.java))
            })
            addFull(choiceRow(getString(R.string.live_set_network), getString(R.string.live_set_network_value)) {}.apply { isClickable = false; background = null })
        })

        col.addFull(sectionTitle(getString(R.string.live_set_developer)))
        col.addFull(card(16) {
            addFull(switchRow(getString(R.string.live_set_latency), getString(R.string.live_set_latency_help), s.showLatency) { s.showLatency = it })
            addFull(switchRow(getString(R.string.live_set_short_context), getString(R.string.live_set_short_context_help), s.shortContext) { s.shortContext = it })
        })
        updatePreview()
    }

    private fun updatePreview() {
        val a = s.appearance()
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, a.sizeSp.toFloat())
        preview.setTextColor(a.color)
        preview.typeface = Typeface.create(Typeface.SANS_SERIF, if (a.bold) Typeface.BOLD else Typeface.NORMAL)
        preview.outline = a.outline
        preview.setPadding(dp(10), dp(4), dp(10), dp(6))
        preview.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.argb(a.backgroundPercent * 255 / 100, 0, 0, 0))
        }
    }
}
