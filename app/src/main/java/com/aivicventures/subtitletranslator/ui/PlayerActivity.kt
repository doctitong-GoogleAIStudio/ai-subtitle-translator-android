package com.aivicventures.subtitletranslator.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.asr.WhisperAsr
import com.aivicventures.subtitletranslator.translate.M2M100Translator
import com.aivicventures.subtitletranslator.translate.TranslatorNative
import com.aivicventures.subtitletranslator.audio.AudioTrackInfo
import com.aivicventures.subtitletranslator.audio.AudioWindowReader
import com.aivicventures.subtitletranslator.audio.FfmpegAudio
import com.aivicventures.subtitletranslator.device.CpuTopology
import com.aivicventures.subtitletranslator.device.PowerMonitor
import com.aivicventures.subtitletranslator.device.TestHooks
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var translator: M2M100Translator? = null
    private var fingerprint: String? = null
    private var movieName = "video"
    private var cues: List<Cue> = emptyList()
    private var shownCue: Cue? = null
    private var autoStartPending = true
    private var pausedForBuffer = false
    private var lastStatus: EngineStatus? = null
    private var controllerVisible = false
    private lateinit var audioButton: MaterialButton
    private lateinit var fitButton: MaterialButton
    private var orientationChosen = false
    private var audioTracks: List<AudioTrackInfo> = emptyList()
    private var audioOrdinal = 0
    private var audioApplied = false
    private val settings by lazy { app.settings }
    private lateinit var mode: Mode
    /** Test overrides; empty unless [TestHooks] are on (this screen is exported). */
    private val testExtras by lazy { TestHooks.extras(intent) }

    private val exportSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) writeSrt(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: settings.mode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        buildViews()

        // Media3's FFmpeg extension plays what the phone cannot (AC-3, DTS, …); platform
        // decoders still come first (EXTENSION_RENDERER_MODE_ON, not PREFER).
        val renderers = DefaultRenderersFactory(this).setExtensionRendererMode(
            if (FfmpegAudio.available) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
        )
        player = ExoPlayer.Builder(this, renderers).build()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i(TAG, "playback audio decoder $decoderName")
            }
        })
        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(uri))
        player.playWhenReady = false
        player.prepare()
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                engine?.updatePlayhead(newPosition.positionMs / 1000.0, player.isPlaying)
            }

            override fun onTracksChanged(tracks: Tracks) = applyAudioTrack()

            // Landscape movies open rotated to landscape, full screen (setting, on by default).
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (orientationChosen || videoSize.width == 0 || videoSize.height == 0) return
                orientationChosen = true
                val wide = videoSize.width * videoSize.pixelWidthHeightRatio >= videoSize.height
                if (wide && settings.landscapeFullScreen) setFullScreen(true)
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
            // The controller's full-screen button rotates to landscape and back.
            setFullscreenButtonClickListener { full -> setFullScreen(full) }
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
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000),
            )
            setPadding(dp(12), dp(6), dp(8), dp(14))
        }
        statusView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        topBar.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        audioButton = smallButton(getString(R.string.audio_track), R.drawable.ic_audio) { chooseAudioTrack() }.apply { visibility = View.GONE }
        topBar.addView(audioButton)
        fitButton = smallButton(getString(R.string.fill_screen), R.drawable.ic_aspect) { toggleFill() }
        topBar.addView(fitButton)
        topBar.addView(smallButton("−0.5 s") { nudgeDelay(-0.5f) })
        topBar.addView(smallButton("+0.5 s") { nudgeDelay(0.5f) })
        topBar.addView(smallButton(getString(R.string.export_srt), R.drawable.ic_download) { askExport() })
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // Keep the bar's buttons clear of a camera cutout or rounded corners.
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(12) + c.left, dp(6) + c.top, dp(8) + c.right, dp(14))
            insets
        }

        gate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xE61D2024.toInt())
            }
            setPadding(dp(28), dp(20), dp(28), dp(16))
        }
        gateText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }
        gate.addView(gateText)
        gate.addView(button(getString(R.string.play_now), R.drawable.ic_play) {
            autoStartPending = false
            gate.visibility = View.GONE
            player.play()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(gate, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        placeSubtitles()
    }

    private fun smallButton(text: String, icon: Int = 0, onClick: () -> Unit) = textButton(text, icon, onClick).apply {
        setTextColor(Color.WHITE)
        iconTint = android.content.res.ColorStateList.valueOf(Color.WHITE)
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Full screen = rotated to landscape (either way round, by the sensor); off = portrait. */
    private fun setFullScreen(full: Boolean) {
        requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        playerView.setFullscreenButtonState(full)
        hideSystemBars()
    }

    /** Fit shows the whole picture; Fill crops it to cover the screen. */
    private fun toggleFill() {
        val fill = playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        playerView.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        fitButton.text = getString(if (fill) R.string.fit_screen else R.string.fill_screen)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        playerView.setFullscreenButtonState(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        hideSystemBars()
        shownCue = null  // re-fit the current line to the new width
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()  // dialogs and swipes bring the bars back
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
        val spec = models.chooseAsr(testExtras.getStringExtra(EXTRA_MODEL) ?: settings.asrModel)
        val threads = testExtras.getIntExtra(EXTRA_THREADS, 0).takeIf { it in 1..8 } ?: settings.threads
        val vad = models.vadModel()
        if (spec == null || vad == null) {
            showError(getString(R.string.models_missing))
            return
        }
        showGate(getString(R.string.loading_model, spec.name))
        try {
            val language = settings.sourceLanguage
            val (eng, r, a) = withContext(Dispatchers.IO) {
                val fp = Fingerprint.of(this@PlayerActivity, uri)
                fingerprint = fp
                if (testExtras.getBooleanExtra(EXTRA_CLEAR_CACHE, false)) app.db.clearMovie(fp)
                val tracks = AudioWindowReader.tracks(this@PlayerActivity, uri)
                val ordinal = intent.getIntExtra(EXTRA_AUDIO_TRACK, -1).takeIf { it in tracks.indices }
                    ?: AudioTrackInfo.chooseDefault(tracks, settings.audioTrack(fp), language)
                audioTracks = tracks
                audioOrdinal = ordinal
                val r = AudioWindowReader(this@PlayerActivity, uri, tracks.getOrNull(ordinal)?.index)
                val duration = r.durationSec.takeIf { it > 0 } ?: error("Could not read the movie's duration.")
                app.db.upsertMovie(fp, uri.toString(), movieName, duration)
                val a = models.weights(spec).use { WhisperAsr(this@PlayerActivity, it, models.localFile(vad.files.first()).path, spec.id) }
                val mtSpec = models.translationModel()
                val useMt = when (testExtras.getStringExtra(EXTRA_TRANSLATOR) ?: settings.translator) {
                    "whisper" -> false
                    "m2m100" -> true
                    else -> spec.autoMt
                }
                // Fast cores only: the engine thread is pinned (whisper.cpp's workers inherit it);
                // CTranslate2 starts its workers while the translator is built, so pin that too.
                val pins = testExtras.getStringExtra(EXTRA_PIN_CPUS)?.let { CpuTopology.parseCpuList(it) }
                    ?: CpuTopology.fastCores.takeIf { settings.fastCoresOnly && CpuTopology.heterogeneous }
                val mt = if (useMt && mtSpec != null && TranslatorNative.available) {
                    runCatching { CpuTopology.withPinned(pins) { models.weights(mtSpec).use { M2M100Translator(models.localFolder(mtSpec), it, threads) } } }
                        .onFailure { Log.w(TAG, "M2M100 unavailable, Whisper translates", it) }
                        .getOrNull()
                } else null
                translator = mt
                // Separate cache per pipeline (Whisper-translated lines are never mixed with M2M100
                // ones) and per audio track (a dub is a different text); the first track keeps
                // the Phase 1 key so existing caches stay valid.
                val task = (if (mt != null) mtSpec!!.id else "translate") + if (ordinal > 0) "@a$ordinal" else ""
                val profile = app.db.profileId(fp, spec.id, task, language)
                val pre = if (!testExtras.getBooleanExtra(EXTRA_PREFETCH, true)) null
                else runCatching { AudioWindowReader(this@PlayerActivity, uri, r.track) }.getOrNull()
                prefetchReader = pre
                val power = PowerMonitor(this@PlayerActivity, testExtras.getBooleanExtra(EXTRA_BATTERY_SAVER, settings.batterySaver))
                val eng = LookAheadEngine(r, pre, a, mt, app.db, profile, duration, mode, language, threads, settings.continueWholeMovie,
                    testExtras.getIntExtra(EXTRA_MT_BEAM, 0), power, pins, testExtras.getBooleanExtra(EXTRA_SHORT_AUDIO, false),
                    testExtras.getFloatExtra(EXTRA_GROUP_GAP, WhisperAsr.GROUP_GAP.toFloat()).toDouble())
                Log.i(TAG, "open $movieName fp=${fp.take(12)} duration=%.1f mode=$mode model=${spec.id} mt=$task lang=$language threads=$threads prefetch=${pre != null} audio=${r.mime} decoder=${r.decoderName} track=${ordinal + 1}/${tracks.size} pin=${pins ?: "-"} cached=${eng.subtitles().size}".format(duration))
                Triple(eng, r, a)
            }
            reader = r
            asr = a
            engine = eng
            audioButton.visibility = if (audioTracks.size > 1) View.VISIBLE else View.GONE
            applyAudioTrack()
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
        val saving = if (s.saving.isNotEmpty()) " · " + getString(R.string.status_saving, s.saving) else ""
        val ahead = if (mode == Mode.FULL) "%.0f%% done".format(100 * s.coveredSec / s.durationSec.coerceAtLeast(1.0)) else "%.0f s ahead".format(s.reserveSec)
        statusView.text = "${s.state.label} · $ahead$speed$lang$saving" + if (s.message.isNotEmpty()) "\n${s.message}" else ""
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

    /** Plays the audio track the subtitles are made from (Media3 lists audio groups in file order). */
    private fun applyAudioTrack() {
        if (audioApplied || audioTracks.size < 2) return
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val group = groups.getOrNull(audioOrdinal) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        audioApplied = true
        Log.i(TAG, "audio track ${audioOrdinal + 1} of ${groups.size} selected for playback (supported=${group.isTrackSupported(0)})")
    }

    /** Switching track restarts the screen: subtitles come from the new track (cached per track). */
    private fun chooseAudioTrack() {
        val fp = fingerprint ?: return
        val labels = audioTracks.map { it.describe() + if (it.decodable) "" else " " + getString(R.string.audio_track_unsupported) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.audio_track_title)
            .setSingleChoiceItems(labels.toTypedArray(), audioOrdinal) { dialog, which ->
                dialog.dismiss()
                if (which == audioOrdinal) return@setSingleChoiceItems
                settings.setAudioTrack(fp, which)
                app.db.savePosition(fp, player.currentPosition / 1000.0)
                // Test overrides must not undo the viewer's choice after the restart.
                listOf(EXTRA_AUDIO_TRACK, EXTRA_FROM_START, EXTRA_CLEAR_CACHE).forEach { intent.removeExtra(it) }
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun nudgeDelay(d: Float) {
        settings.subtitleDelay = settings.subtitleDelay + d
        Toast.makeText(this, getString(R.string.delay_value, settings.subtitleDelay), Toast.LENGTH_SHORT).show()
    }

    private fun askExport() {
        val s = lastStatus ?: return
        val name = movieName.substringBeforeLast('.') + ".en.srt"
        if (s.coveredSec < s.durationSec - 0.5) {
            MaterialAlertDialogBuilder(this)
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
        val mt = translator
        if (::player.isInitialized) player.release()
        // Stop the worker first, then free the model: whisper.cpp must not be freed mid-call.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            eng?.stop()
            a?.close()
            mt?.close()
            r?.close()
            pre?.close()
        }
    }

    companion object {
        private const val TAG = "SubPlayer"
        /** Mode name, ignoring the resume position and the audio track are open to any caller.
         *  Every other extra below is a test override, read only when [TestHooks] are on. */
        const val EXTRA_MODE = "mode"
        const val EXTRA_FROM_START = "from_start"
        const val EXTRA_MODEL = "model"
        const val EXTRA_CLEAR_CACHE = "clear_cache"
        const val EXTRA_PREFETCH = "prefetch"
        /** Test hook: "whisper" or "m2m100" overrides the translator setting. */
        const val EXTRA_TRANSLATOR = "translator"
        /** Test hook: M2M100 beam size (0 = chosen by mode). */
        const val EXTRA_MT_BEAM = "mt_beam"
        /** Test hooks: audio track (0-based among audio tracks), CPU list to pin to ("0-1"),
         *  and forcing the battery saver on. */
        const val EXTRA_AUDIO_TRACK = "audio_track"
        const val EXTRA_PIN_CPUS = "pin_cpus"
        const val EXTRA_BATTERY_SAVER = "battery_saver"
        /** Test hook: always encode only the length of each line (normally only when behind). */
        const val EXTRA_SHORT_AUDIO = "short_audio"
        /** Test hook: largest silence in seconds inside one Whisper run. */
        const val EXTRA_GROUP_GAP = "group_gap"
        /** Test hook: thread count (1-8) instead of the setting, for benchmarks. */
        const val EXTRA_THREADS = "threads"
    }
}
