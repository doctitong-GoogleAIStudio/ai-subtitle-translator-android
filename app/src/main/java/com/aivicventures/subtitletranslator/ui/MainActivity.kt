package com.aivicventures.subtitletranslator.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.cache.MovieRecord
import com.google.android.material.R as M
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : ComponentActivity() {
    private lateinit var chips: ChipGroup
    private lateinit var recent: LinearLayout

    private val openVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            play(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = scaffold(getString(R.string.app_name), back = false)
        s.toolbar.menu.apply {
            add(Menu.NONE, 1, 1, R.string.settings_title).setIcon(R.drawable.ic_settings).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            add(Menu.NONE, 2, 2, R.string.models_title)
            add(Menu.NONE, 3, 3, R.string.about_title)
        }
        s.toolbar.setOnMenuItemClickListener {
            val target = when (it.itemId) {
                1 -> SettingsActivity::class.java
                2 -> ModelsActivity::class.java
                else -> AboutActivity::class.java
            }
            startActivity(Intent(this, target))
            true
        }
        val col = s.content

        // Hero: what the app does, the main action and what it will use.
        col.addFull(card(20, M.attr.colorPrimaryContainer) {
            addView(iconBadge(R.drawable.ic_subtitles, androidx.appcompat.R.attr.colorPrimary, M.attr.colorOnPrimary, 52))
            addFull(headline(getString(R.string.hero_title)).apply { setTextColor(themeColor(M.attr.colorOnPrimaryContainer)) }, 16)
            addFull(body(getString(R.string.tagline)).apply { setTextColor(themeColor(M.attr.colorOnPrimaryContainer)) }, 6)
            addFull(button(getString(R.string.open_video), R.drawable.ic_video_library) { openVideo.launch(arrayOf("video/*")) }.apply {
                minHeight = dp(56)
                textSize = 16f
            }, 18)
            chips = ChipGroup(context)
            addFull(chips, 10)
        }, 8)

        col.addFull(sectionTitle(getString(R.string.recent)))
        recent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addFull(recent)
    }

    override fun onResume() {
        super.onResume()
        showStatus()
        showRecent()
    }

    private fun showStatus() {
        val m = app.models
        val asr = m.chooseAsr(app.settings.asrModel)
        chips.removeAllViews()
        fun chip(text: String, icon: Int) = chips.addView(Chip(this).apply {
            this.text = text
            setChipIconResource(icon)
            isChipIconVisible = true
            isClickable = false
        })
        if (asr != null && m.vadModel() != null) chip(asr.name.substringBefore(" ("), R.drawable.ic_check)
        else chip(getString(R.string.models_missing), R.drawable.ic_info)
        chip(app.settings.mode.label, R.drawable.ic_speed)
        chip(getString(R.string.offline), R.drawable.ic_shield)
    }

    private fun showRecent() {
        recent.removeAllViews()
        val movies = app.db.recentMovies()
        if (movies.isEmpty()) {
            recent.addFull(card {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(iconBadge(R.drawable.ic_movie, M.attr.colorSurfaceContainerHighest, M.attr.colorOnSurfaceVariant, 56))
                addView(body(getString(R.string.no_recent)).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
            })
            return
        }
        for (mv in movies) recent.addFull(movieCard(mv), 8)
    }

    private fun movieCard(mv: MovieRecord) = card(12) {
        val covered = app.db.coveredSeconds(mv.fingerprint)
        val subs = when {
            covered <= 0 -> getString(R.string.recent_no_subtitles)
            covered >= mv.duration - 1 -> getString(R.string.recent_subtitles_done)
            else -> getString(R.string.recent_subtitles_part, 100 * covered / mv.duration.coerceAtLeast(1.0))
        }
        val where = if (mv.lastPosition > 5) getString(R.string.recent_at, formatTime(mv.lastPosition), formatTime(mv.duration)) else formatTime(mv.duration)
        val progress = LinearProgressIndicator(context).apply {
            max = 1000
            progress = (1000 * mv.lastPosition / mv.duration.coerceAtLeast(1.0)).toInt()
            trackCornerRadius = dp(2)
            trackStopIndicatorSize = 0
            setPadding(0, dp(8), 0, 0)
            // An empty bar reads as nearly full, so show it only once there is a place to resume.
            visibility = if (mv.lastPosition > 5) View.VISIBLE else View.GONE
        }
        val middle = stack(heading(mv.name).singleLine(), caption("$where · $subs"), progress)
        val more = iconButton(R.drawable.ic_more, getString(R.string.recent_more, mv.name)) { anchor -> movieMenu(mv, anchor) }
        addView(row(iconBadge(R.drawable.ic_movie), middle, more))
        isClickable = true
        background = ripple()
        setOnClickListener { open(mv, fromStart = false) }
    }

    private fun movieMenu(mv: MovieRecord, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, 1, 1, R.string.recent_play_start)
            menu.add(Menu.NONE, 2, 2, R.string.recent_remove)
            menu.add(Menu.NONE, 3, 3, R.string.recent_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> open(mv, fromStart = true)
                    2 -> remove(mv, deleteSubtitles = false)
                    else -> MaterialAlertDialogBuilder(this@MainActivity)
                        .setIcon(R.drawable.ic_delete)
                        .setTitle(R.string.recent_delete_title)
                        .setMessage(getString(R.string.recent_delete_message, mv.name))
                        .setPositiveButton(R.string.delete) { _, _ -> remove(mv, deleteSubtitles = true) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                true
            }
        }.show()
    }

    /** Takes the movie off the list (and optionally its cached subtitles); the video file is untouched. */
    private fun remove(mv: MovieRecord, deleteSubtitles: Boolean) {
        app.db.removeMovie(mv.fingerprint, deleteSubtitles)
        if (deleteSubtitles) app.settings.forgetMovie(mv.fingerprint)
        runCatching {
            if (mv.uri.startsWith("content:")) contentResolver.releasePersistableUriPermission(Uri.parse(mv.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Toast.makeText(this, if (deleteSubtitles) R.string.recent_deleted else R.string.recent_removed, Toast.LENGTH_SHORT).show()
        showRecent()
    }

    private fun open(mv: MovieRecord, fromStart: Boolean) {
        val canOpen = contentResolver.persistedUriPermissions.any { it.uri.toString() == mv.uri } || mv.uri.startsWith("file:")
        if (!canOpen) {
            Toast.makeText(this, R.string.recent_pick_again, Toast.LENGTH_LONG).show()
            openVideo.launch(arrayOf("video/*"))
            return
        }
        play(Uri.parse(mv.uri), fromStart)
    }

    private fun play(uri: Uri, fromStart: Boolean = false) {
        startActivity(
            Intent(this, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(PlayerActivity.EXTRA_FROM_START, fromStart),
        )
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
