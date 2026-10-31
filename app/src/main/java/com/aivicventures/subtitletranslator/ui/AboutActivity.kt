package com.aivicventures.subtitletranslator.ui

import android.os.Bundle
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import com.aivicventures.subtitletranslator.BuildConfig
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.asr.WhisperNative
import com.aivicventures.subtitletranslator.audio.FfmpegAudio

/** Version, privacy note and every third-party licence (assets/licenses). */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addFull(heading(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME))
        col.addFull(body(getString(R.string.about_text)))
        val cpu = runCatching { WhisperNative.ensureLoaded(this); WhisperNative.systemInfo() }.getOrElse { it.message ?: "" }
        col.addFull(body(getString(R.string.about_cpu, cpu)))
        col.addFull(body(FfmpegAudio.description?.let { getString(R.string.about_ffmpeg, it) } ?: getString(R.string.about_ffmpeg_none)))
        col.addFull(heading(getString(R.string.licences)))
        val files = assets.list("licenses").orEmpty().sorted().sortedBy { if (it.startsWith("THIRD_PARTY")) 0 else 1 }
        for (f in files) {
            col.addFull(heading(f))
            col.addFull(body(assets.open("licenses/$f").bufferedReader().use { it.readText() }).apply { textSize = 12f; setTextIsSelectable(true) })
        }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.padForSystemBars()
        setContentView(scroll)
    }
}
