package com.aivicventures.subtitletranslator.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app

class MainActivity : ComponentActivity() {
    private lateinit var modelStatus: TextView
    private lateinit var recent: LinearLayout

    private val openVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            play(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addFull(heading(getString(R.string.app_name)))
        col.addFull(body(getString(R.string.tagline)))
        col.addFull(button(getString(R.string.open_video)) { openVideo.launch(arrayOf("video/*")) })
        modelStatus = body()
        col.addFull(modelStatus)
        col.addFull(button(getString(R.string.models_title)) { startActivity(Intent(this, ModelsActivity::class.java)) })
        col.addFull(button(getString(R.string.settings_title)) { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addFull(button(getString(R.string.about_title)) { startActivity(Intent(this, AboutActivity::class.java)) })
        col.addFull(heading(getString(R.string.recent)))
        recent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addFull(recent)
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.padForSystemBars()
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val m = app.models
        val asr = m.chooseAsr(app.settings.asrModel)
        val vad = m.vadModel()
        modelStatus.text = when {
            asr == null && vad == null -> getString(R.string.need_models)
            asr == null -> getString(R.string.need_asr)
            vad == null -> getString(R.string.need_vad)
            else -> getString(R.string.models_ready, asr.name, app.settings.mode.label)
        }
        recent.removeAllViews()
        val movies = app.db.recentMovies()
        if (movies.isEmpty()) recent.addFull(body(getString(R.string.no_recent)))
        for (mv in movies) {
            val canOpen = contentResolver.persistedUriPermissions.any { it.uri.toString() == mv.uri } || mv.uri.startsWith("file:")
            val label = "${mv.name}  (${formatTime(mv.duration)}, at ${formatTime(mv.lastPosition)})"
            recent.addFull(button(label) { if (canOpen) play(Uri.parse(mv.uri)) else openVideo.launch(arrayOf("video/*")) })
        }
    }

    private fun play(uri: Uri) {
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    companion object {
        fun displayName(activity: ComponentActivity, uri: Uri): String {
            if (uri.scheme == "file") return uri.lastPathSegment ?: "video"
            return runCatching {
                activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "video"
        }
    }
}
