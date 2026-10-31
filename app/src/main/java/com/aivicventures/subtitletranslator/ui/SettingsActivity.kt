package com.aivicventures.subtitletranslator.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import androidx.activity.ComponentActivity
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.settings.Languages
import com.aivicventures.subtitletranslator.translate.TranslatorNative
import com.aivicventures.subtitletranslator.settings.Mode

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = app.settings
        val col = column()

        col.addFull(heading(getString(R.string.setting_mode)))
        col.addFull(body(getString(R.string.setting_mode_help)))
        col.addFull(spinner(Mode.entries.map { it.label }, Mode.entries.indexOf(s.mode)) { s.mode = Mode.entries[it] })

        col.addFull(heading(getString(R.string.setting_model)))
        val asr = app.models.catalog.filter { it.kind == "asr" }
        val options = listOf(getString(R.string.setting_model_auto)) + asr.map { it.name + if (app.models.isInstalled(it)) "" else " (not installed)" }
        val selected = asr.indexOfFirst { it.id == s.asrModel } + 1
        col.addFull(spinner(options, selected) { s.asrModel = if (it == 0) "" else asr[it - 1].id })

        col.addFull(heading(getString(R.string.setting_translator)))
        col.addFull(body(getString(
            when {
                !TranslatorNative.available -> R.string.setting_translator_32bit
                app.models.translationModel() == null -> R.string.setting_translator_missing
                else -> R.string.setting_translator_help
            },
        )))
        val engines = listOf("auto", "m2m100", "whisper")
        col.addFull(spinner(listOf(getString(R.string.translator_auto), getString(R.string.translator_m2m100), getString(R.string.translator_whisper)),
            engines.indexOf(s.translator).coerceAtLeast(0)) { s.translator = engines[it] })

        col.addFull(heading(getString(R.string.setting_language)))
        col.addFull(body(getString(R.string.setting_language_help)))
        val langs = Languages.choices
        col.addFull(spinner(langs.map { it.second }, langs.indexOfFirst { it.first == s.sourceLanguage }.coerceAtLeast(0)) { s.sourceLanguage = langs[it].first })

        col.addFull(heading(getString(R.string.setting_threads)))
        val threadsLabel = body()
        fun showThreads() { threadsLabel.text = getString(R.string.setting_threads_value, s.threads, Runtime.getRuntime().availableProcessors()) }
        showThreads()
        col.addFull(threadsLabel)
        col.addFull(SeekBar(this).apply {
            max = 7
            progress = s.threads - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { s.threads = p + 1; showThreads() } }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        })

        col.addFull(heading(getString(R.string.setting_size)))
        val sizeLabel = body(getString(R.string.setting_size_value, s.subtitleSizeSp))
        col.addFull(sizeLabel)
        col.addFull(SeekBar(this).apply {
            max = 28
            progress = s.subtitleSizeSp - 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { s.subtitleSizeSp = p + 12; sizeLabel.text = getString(R.string.setting_size_value, s.subtitleSizeSp) }
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        })

        col.addFull(Switch(this).apply {
            text = getString(R.string.setting_pause_low)
            isChecked = s.pauseOnLowBuffer
            setOnCheckedChangeListener { _, v -> s.pauseOnLowBuffer = v }
        })
        col.addFull(Switch(this).apply {
            text = getString(R.string.setting_continue)
            isChecked = s.continueWholeMovie
            setOnCheckedChangeListener { _, v -> s.continueWholeMovie = v }
        })

        val scroll = ScrollView(this).apply { addView(col) }
        scroll.padForSystemBars()
        setContentView(scroll)
    }

    private fun spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected.coerceIn(0, items.size - 1), false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
}
