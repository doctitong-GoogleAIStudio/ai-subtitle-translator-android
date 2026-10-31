package com.aivicventures.subtitletranslator.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.asr.WhisperAsr
import com.aivicventures.subtitletranslator.audio.AudioWindowReader
import com.aivicventures.subtitletranslator.audio.Fingerprint
import com.aivicventures.subtitletranslator.audio.UnsupportedAudioException
import com.aivicventures.subtitletranslator.buffering.BufferState
import com.aivicventures.subtitletranslator.buffering.EngineStatus
import com.aivicventures.subtitletranslator.buffering.LookAheadEngine
import com.aivicventures.subtitletranslator.settings.Languages
import com.aivicventures.subtitletranslator.settings.Mode
import com.aivicventures.subtitletranslator.subtitles.Cue
import com.aivicventures.subtitletranslator.subtitles.Layout
import com.aivicventures.subtitletranslator.subtitles.Srt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays the movie with English subtitles generated on the device. The player's position is
 * the timing authority; the engine only recognises audio ahead of it. Playback starts by
 * itself once the look-ahead target is ready (the readiness gate), never on a timer.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var subtitleView: TextView
    private lateinit var statusView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var gate: LinearLayout
    private lateinit var gateText: TextView

    private var engine: LookAheadEngine? = null
    private var reader: AudioWindowReader? = null
    private var prefetchReader: AudioWindowReader? = null
    private var asr: WhisperAsr? = null
    private var fingerprint: String? = null
    private var movieName = "video"
    private var cues: List<Cue> = emptyList()
    private var shownCue: Cue? = null
    private var autoStartPending = true
    private var pausedForBuffer = false
    private var lastStatus: EngineStatus? = null
    private var controllerVisible = false
    private val settings by lazy { app.settings }
    private lateinit var mode: Mode

    private val exportSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) writeSrt(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: settings.mode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        buildViews()

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(uri))
        player.playWhenReady = false
        player.prepare()
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                engine?.updatePlayhead(newPosition.positionMs / 1000.0, player.isPlaying)
            }
        })

        movieName = MainActivity.displayName(this, uri)
        lifecycleScope.launch { openEngine(uri) }
        lifecycleScope.launch { tick() }
    }

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            setShowSubtitleButton(false)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                controllerVisible = v == View.VISIBLE
                topBar.visibility = if (controllerVisible || lastStatus?.ready != true) View.VISIBLE else View.GONE
                placeSubtitles()
            })
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        subtitleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.subtitleSizeSp.toFloat())
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
        }
        root.addView(subtitleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        statusView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        topBar.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(smallButton("−0.5 s") { nudgeDelay(-0.5f) })
        topBar.addView(smallButton("+0.5 s") { nudgeDelay(0.5f) })
        topBar.addView(smallButton(getString(R.string.export_srt)) { askExport() })
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        gate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        gateText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }
        gate.addView(gateText)
        gate.addView(smallButton(getString(R.string.play_now)) {
            autoStartPending = false
            gate.visibility = View.GONE
            player.play()
        })
        root.addView(gate, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        placeSubtitles()
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    /** Shrinks the text (never enlarges it) so each layout line stays on one screen line. */
    private fun fitSubtitle(text: String) {
        val wanted = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.subtitleSizeSp.toFloat(), resources.displayMetrics)
        val avail = (subtitleView.parent as View).width - dp(48) - subtitleView.paddingLeft - subtitleView.paddingRight
        val paint = android.text.TextPaint(subtitleView.paint).apply { textSize = wanted }
        val widest = text.split('\n').maxOfOrNull { paint.measureText(it) } ?: 0f
        val size = if (avail > 0 && widest > avail) wanted * avail / widest * 0.98f else wanted
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    private fun placeSubtitles() {
        (subtitleView.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = dp(if (controllerVisible) 96 else 24)
            leftMargin = dp(24)
            rightMargin = dp(24)
        }
        subtitleView.requestLayout()
    }

    private suspend fun openEngine(uri: Uri) {
        val models = app.models
        val spec = models.chooseAsr(intent.getStringExtra(EXTRA_MODEL) ?: settings.asrModel)
        val vad = models.vadModel()
        if (spec == null || vad == null) {
            showGate(getString(R.string.need_models))
            AlertDialog.Builder(this)
                .setMessage(R.string.need_models)
                .setPositiveButton(R.string.models_title) { _, _ -> startActivity(Intent(this, ModelsActivity::class.java)); finish() }
                .setNegativeButton(R.string.play_without) { _, _ -> gate.visibility = View.GONE; player.play() }
                .show()
            return
        }
        showGate(getString(R.string.loading_model, spec.name))
        try {
            val language = settings.sourceLanguage
            val (eng, r, a) = withContext(Dispatchers.IO) {
                val fp = Fingerprint.of(this@PlayerActivity, uri)
                fingerprint = fp
                if (intent.getBooleanExtra(EXTRA_CLEAR_CACHE, false)) app.db.clearMovie(fp)
                val r = AudioWindowReader(this@PlayerActivity, uri)
                val duration = r.durationSec.takeIf { it > 0 } ?: error("Could not read the movie's duration.")
                app.db.upsertMovie(fp, uri.toString(), movieName, duration)
                val profile = app.db.profileId(fp, spec.id, "translate", language)
                val a = WhisperAsr(this@PlayerActivity, models.file(spec).path, models.file(vad).path, spec.id)
                val pre = if (!intent.getBooleanExtra(EXTRA_PREFETCH, true)) null
                else runCatching { AudioWindowReader(this@PlayerActivity, uri, r.track) }.getOrNull()
                prefetchReader = pre
                val eng = LookAheadEngine(r, pre, a, app.db, profile, duration, mode, language, settings.threads, settings.continueWholeMovie)
                Log.i(TAG, "open $movieName fp=${fp.take(12)} duration=%.1f mode=$mode model=${spec.id} lang=$language threads=${settings.threads} prefetch=${pre != null} audio=${r.mime} cached=${eng.subtitles().size}".format(duration))
                Triple(eng, r, a)
            }
            reader = r
            asr = a
            engine = eng
            val resume = app.db.movie(fingerprint!!)?.lastPosition ?: 0.0
            if (resume > 5 && intent.getBooleanExtra(EXTRA_FROM_START, false).not()) {
                player.seekTo((resume * 1000).toLong())
                eng.updatePlayhead(resume, false)
            }
            eng.start()
            lifecycleScope.launch { eng.status.collect { onStatus(it) } }
            lifecycleScope.launch { eng.version.collect { rebuildCues() } }
        } catch (e: UnsupportedAudioException) {
            showError(e.message ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "could not start subtitles", e)
            showError(getString(R.string.engine_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun showGate(text: String) {
        gateText.text = text
        gate.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        statusView.text = message
        topBar.visibility = View.VISIBLE
        showGate(message)
    }

    private fun onStatus(s: EngineStatus) {
        val prev = lastStatus
        lastStatus = s
        if (prev?.state != s.state) Log.i(TAG, "status ${s.state} reserve=%.1f/%.0f speed=%.2fx at %.1f".format(s.reserveSec, s.targetSec, s.speed, player.currentPosition / 1000.0))
        val lang = if (s.language.isNotEmpty()) " · ${Languages.name(s.language)}" else ""
        val speed = if (s.speed > 0) " · %.1f× real time".format(s.speed) else ""
        val ahead = if (mode == Mode.FULL) "%.0f%% done".format(100 * s.coveredSec / s.durationSec.coerceAtLeast(1.0)) else "%.0f s ahead".format(s.reserveSec)
        statusView.text = "${s.state.label} · $ahead$speed$lang" + if (s.message.isNotEmpty()) "\n${s.message}" else ""
        if (s.state == BufferState.ERROR) { showError(s.message); return }
        if (!controllerVisible) topBar.visibility = if (s.ready) View.GONE else View.VISIBLE

        if (autoStartPending) {
            if (s.ready) {
                autoStartPending = false
                gate.visibility = View.GONE
                Log.i(TAG, "readiness gate passed: playback starts at %.1f".format(player.currentPosition / 1000.0))
                player.play()
            } else {
                showGate(
                    if (mode == Mode.FULL) getString(R.string.gate_full, 100 * s.coveredSec / s.durationSec.coerceAtLeast(1.0))
                    else getString(R.string.gate_lookahead, s.reserveSec, s.targetSec),
                )
            }
        } else if (settings.pauseOnLowBuffer && mode != Mode.FULL) {
            if (!s.ready && player.isPlaying) { pausedForBuffer = true; player.pause() }
            else if (s.ready && pausedForBuffer) { pausedForBuffer = false; player.play() }
        }
    }

    private suspend fun rebuildCues() {
        val subs = engine?.subtitles() ?: return
        cues = withContext(Dispatchers.Default) { Layout.buildCues(subs) }
    }

    /** Every 100 ms: report the playhead to the engine and show the cue under it. */
    private suspend fun tick() {
        var n = 0
        while (lifecycleScope.isActive) {
            delay(100)
            if (!::player.isInitialized) continue
            val pos = player.currentPosition / 1000.0
            if (++n % 2 == 0) engine?.updatePlayhead(pos, player.isPlaying)
            val t = pos - settings.subtitleDelay
            val cue = findCue(t)
            if (cue != shownCue) {
                shownCue = cue
                if (cue == null) {
                    subtitleView.visibility = View.GONE
                } else {
                    fitSubtitle(cue.text)
                    subtitleView.text = cue.text
                    subtitleView.visibility = View.VISIBLE
                    engine?.markShown(cue.subtitleIds)
                    Log.i(TAG, "show %.2f [%.2f-%.2f] %s".format(pos, cue.start, cue.end, cue.text.replace('\n', ' ')))
                }
            }
        }
    }

    private fun findCue(t: Double): Cue? {
        val list = cues
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = list[mid]
            when {
                t < c.start -> hi = mid - 1
                t >= c.end -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    private fun nudgeDelay(d: Float) {
        settings.subtitleDelay = settings.subtitleDelay + d
        Toast.makeText(this, getString(R.string.delay_value, settings.subtitleDelay), Toast.LENGTH_SHORT).show()
    }

    private fun askExport() {
        val s = lastStatus ?: return
        val name = movieName.substringBeforeLast('.') + ".en.srt"
        if (s.coveredSec < s.durationSec - 0.5) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.export_partial, 100 * s.coveredSec / s.durationSec.coerceAtLeast(1.0)))
                .setPositiveButton(R.string.export_srt) { _, _ -> exportSrt.launch(name) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            exportSrt.launch(name)
        }
    }

    private fun writeSrt(uri: Uri) {
        val subs = engine?.subtitles() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val text = Srt.write(Layout.buildCues(subs))
            val ok = runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlayerActivity, if (ok) getString(R.string.exported, subs.size) else getString(R.string.export_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) player.pause()
        fingerprint?.let { fp -> app.db.savePosition(fp, player.currentPosition / 1000.0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        val eng = engine
        val r = reader
        val pre = prefetchReader
        val a = asr
        if (::player.isInitialized) player.release()
        // Stop the worker first, then free the model: whisper.cpp must not be freed mid-call.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            eng?.stop()
            a?.close()
            r?.close()
            pre?.close()
        }
    }

    companion object {
        private const val TAG = "SubPlayer"
        /** Optional overrides for automated tests: a Mode name, a model id, ignoring the
         *  resume position, and discarding this movie's cached subtitles. */
        const val EXTRA_MODE = "mode"
        const val EXTRA_FROM_START = "from_start"
        const val EXTRA_MODEL = "model"
        const val EXTRA_CLEAR_CACHE = "clear_cache"
        const val EXTRA_PREFETCH = "prefetch"
    }
}
