package com.aivicventures.subtitletranslator.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.aivicventures.subtitletranslator.BuildConfig
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.asr.WhisperNative
import com.aivicventures.subtitletranslator.audio.FfmpegAudio
import com.google.android.material.R as M
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Version, privacy note, engine details and every third-party licence (assets/licenses). */
class AboutActivity : ComponentActivity() {
    /** FFmpeg's LGPL source (assets/source/ffmpeg-<version>-source.zip), absent in builds without FFmpeg. */
    private val ffmpegSource by lazy { assets.list("source").orEmpty().firstOrNull { it.startsWith("ffmpeg-") && it.endsWith(".zip") } }

    // The system file picker chooses where the copy goes, so no storage permission is needed.
    private val saveSource = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = ffmpegSource
        if (uri == null || file == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)!!.use { out -> assets.open("source/$file").use { it.copyTo(out, 1 shl 20) } }
                }.isSuccess
            }
            Toast.makeText(this@AboutActivity, if (ok) R.string.about_ffmpeg_saved else R.string.about_ffmpeg_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = scaffold(getString(R.string.about_title)).content

        col.addFull(card(20) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(headline(getString(R.string.app_name)).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            addView(body(getString(R.string.about_version, BuildConfig.VERSION_NAME)).apply { gravity = Gravity.CENTER })
        }, 8)

        col.addFull(sectionTitle(getString(R.string.about_privacy)))
        col.addFull(card {
            addView(row(iconBadge(R.drawable.ic_shield), stack(body(getString(R.string.about_text)))))
        })

        col.addFull(sectionTitle(getString(R.string.about_engine)))
        // whisper.cpp reports "... CPU : NEON = 1 | ARM_FMA = 1 | ...": list the instruction sets in use.
        val info = runCatching { WhisperNative.ensureLoaded(this); WhisperNative.systemInfo() }.getOrDefault("")
        val features = info.substringAfter("CPU :", "").split('|')
            .mapNotNull { it.split('=').takeIf { kv -> kv.size == 2 && kv[1].trim() == "1" }?.get(0)?.trim() }
            .filter { it != "REPACK" }
        val cpu = if (features.isEmpty()) getString(R.string.about_cpu_plain) else getString(R.string.about_cpu, features.joinToString(", "))
        col.addFull(card {
            addView(row(iconBadge(R.drawable.ic_memory), stack(heading(getString(R.string.about_cpu_title)), caption(cpu))))
            addFull(divider(), 12)
            addFull(row(iconBadge(R.drawable.ic_audio), stack(
                heading(getString(R.string.about_ffmpeg_title)),
                caption(FfmpegAudio.description?.let { getString(R.string.about_ffmpeg, it) } ?: getString(R.string.about_ffmpeg_none)),
            )), 12)
            ffmpegSource?.let { file ->
                val size = runCatching { assets.openFd("source/$file").use { it.length } }.getOrNull()
                addFull(tonalButton(getString(R.string.about_ffmpeg_source, size?.let(::formatSize) ?: "zip"), R.drawable.ic_download) {
                    saveSource.launch(file)
                }, 12)
            }
        })

        col.addFull(sectionTitle(getString(R.string.licences)))
        col.addFull(card(4) {
            val files = assets.list("licenses").orEmpty().sorted().sortedBy { if (it.startsWith("THIRD_PARTY")) 0 else 1 }
            files.forEachIndexed { i, f ->
                if (i > 0) addFull(divider())
                val name = if (f.startsWith("THIRD_PARTY")) getString(R.string.licence_notices) else f.substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
                addFull(choiceRow(name, getString(R.string.licence_open)) { showLicence(f) }.apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                })
            }
        })
    }

    private fun showLicence(file: String) {
        val text = TextView(this).apply {
            text = assets.open("licenses/$file").bufferedReader().use { it.readText() }
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(themeColor(M.attr.colorOnSurface))
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(file)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
