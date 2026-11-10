package com.aivicventures.subtitletranslator.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.device.CpuTopology
import com.aivicventures.subtitletranslator.settings.Languages
import com.aivicventures.subtitletranslator.settings.Mode
import com.aivicventures.subtitletranslator.translate.TranslatorNative

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = app.settings
        val col = scaffold(getString(R.string.settings_title)).content

        col.addFull(sectionTitle(getString(R.string.settings_subtitles)))
        col.addFull(card(16) {
            addFull(choice(getString(R.string.setting_mode), Mode.entries.map { it.label }, Mode.entries.indexOf(s.mode),
                getString(R.string.setting_mode_help)) { s.mode = Mode.entries[it] })
            val langs = Languages.choices
            addFull(choice(getString(R.string.setting_language), langs.map { it.second },
                langs.indexOfFirst { it.first == s.sourceLanguage }.coerceAtLeast(0), getString(R.string.setting_language_help)) { s.sourceLanguage = langs[it].first })
            addFull(slider(getString(R.string.setting_size), 12, 40, s.subtitleSizeSp, { getString(R.string.setting_size_value, it) }) { s.subtitleSizeSp = it })
            addFull(switchRow(getString(R.string.setting_pause_low), null, s.pauseOnLowBuffer) { s.pauseOnLowBuffer = it })
            addFull(switchRow(getString(R.string.setting_continue), null, s.continueWholeMovie) { s.continueWholeMovie = it })
        })

        col.addFull(sectionTitle(getString(R.string.settings_engine)))
        col.addFull(card(16) {
            val asr = app.models.catalog.filter { it.kind == "asr" }
            val auto = app.models.chooseAsr("")
            val options = listOf(getString(R.string.setting_model_auto, auto?.name ?: "-")) + asr.map { it.name }
            addFull(choice(getString(R.string.setting_model), options, asr.indexOfFirst { it.id == s.asrModel } + 1,
                getString(R.string.setting_model_help)) { s.asrModel = if (it == 0) "" else asr[it - 1].id })
            val engines = listOf("auto", "m2m100", "whisper")
            val help = getString(if (!TranslatorNative.available) R.string.setting_translator_32bit else R.string.setting_translator_help)
            addFull(choice(getString(R.string.setting_translator),
                listOf(getString(R.string.translator_auto), getString(R.string.translator_m2m100), getString(R.string.translator_whisper)),
                engines.indexOf(s.translator).coerceAtLeast(0), help) { s.translator = engines[it] })
        })

        col.addFull(sectionTitle(getString(R.string.settings_performance)))
        col.addFull(card(16) {
            val cores = Runtime.getRuntime().availableProcessors()
            addFull(slider(getString(R.string.setting_threads), 1, 8, s.threads, { getString(R.string.setting_threads_value, it, cores) }) { s.threads = it })
            addFull(switchRow(
                getString(R.string.setting_fast_cores, CpuTopology.fastCores.size, CpuTopology.coreCount),
                getString(if (CpuTopology.heterogeneous) R.string.setting_fast_cores_help else R.string.setting_fast_cores_uniform),
                s.fastCoresOnly, CpuTopology.heterogeneous,
            ) { s.fastCoresOnly = it })
            addFull(switchRow(getString(R.string.setting_battery_saver), getString(R.string.setting_battery_saver_help), s.batterySaver) { s.batterySaver = it })
        })

        col.addFull(sectionTitle(getString(R.string.settings_player)))
        col.addFull(card(16) {
            addFull(switchRow(getString(R.string.setting_landscape), getString(R.string.setting_landscape_help), s.landscapeFullScreen) { s.landscapeFullScreen = it })
        })

        col.addFull(sectionTitle(getString(R.string.live_title)))
        col.addFull(card(16) {
            addFull(choiceRow(getString(R.string.live_settings_title), getString(R.string.live_settings_link)) {
                startActivity(android.content.Intent(this@SettingsActivity, LiveSettingsActivity::class.java))
            })
        })
    }
}
