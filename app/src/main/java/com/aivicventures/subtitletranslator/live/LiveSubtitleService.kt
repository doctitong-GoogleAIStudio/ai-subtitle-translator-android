package com.aivicventures.subtitletranslator.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aivicventures.subtitletranslator.BuildConfig
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.ui.BrowserActivity
import com.aivicventures.subtitletranslator.ui.LiveActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs a live subtitle session while other apps are in front: a foreground service of type
 * mediaProjection (internal audio) and/or microphone, with an ongoing notification that
 * shows the session and has Pause and Stop. It owns the [LiveEngine], the [SubtitleOverlay]
 * and the MediaProjection. Each session needs fresh consent: the projection token is used
 * once and the session ends when the user stops it, revokes it or Android stops it.
 */
class LiveSubtitleService : Service(), SubtitleOverlay.Actions {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: LiveEngine? = null
    private var overlay: SubtitleOverlay? = null
    private var projection: MediaProjection? = null
    private var mode = AudioMode.INTERNAL
    /** Started by the built-in browser, which shows the subtitles itself. */
    private var inApp = false
    private var lastStartId = 0
    private var testThreads = 0
    private var testAdaptive = true
    private var startedAtMs = 0L
    /** The floating window was seen on screen at least once in this session (for the report). */
    private var overlaySeen = false
    private var shownIds = HashSet<Long>()
    private var lastLines: List<LiveSession.Line> = emptyList()
    private var lastStatsAt = 0L
    private var collector: Job? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> main.post { applySettings() } }
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSession(null)
            ACTION_PAUSE -> togglePause()
            ACTION_USE_MIC -> useMicrophone()
        }
        // A command without a session (or a restart by the system) has nothing to run.
        if (engine == null && intent?.action != ACTION_START) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (engine != null) return
        mode = runCatching { AudioMode.valueOf(intent.getStringExtra(EXTRA_MODE)!!) }.getOrDefault(AudioMode.MICROPHONE)
        inApp = intent.getBooleanExtra(EXTRA_IN_APP, false)
        val testFile = intent.getStringExtra(EXTRA_TEST_FILE)
        testThreads = intent.getIntExtra(EXTRA_TEST_THREADS, 0)
        testAdaptive = intent.getBooleanExtra(EXTRA_TEST_ADAPTIVE, true)
        startedAtMs = SystemClock.elapsedRealtime()
        overlaySeen = false
        active = this
        LiveSession.update { LiveSession.State(phase = LiveSession.Phase.STARTING, mode = mode, quality = app.liveSettings.quality) }
        if (mode == AudioMode.PAGE) {
            // The browser's own video sound: nothing outside the app is heard or captured, so
            // no foreground service, notification or microphone permission is involved, and
            // the session ends with the browser screen.
            return run(PageAudioSource())
        }
        // Android 14+: the service must be in the foreground with the right type before the
        // projection token is used, and the microphone type needs RECORD_AUDIO already granted.
        val type = when {
            mode == AudioMode.INTERNAL && testFile == null && Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or micType()
            else -> micType()
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            fail(getString(R.string.live_error_foreground))
            return
        }
        if (!hasRecordAudio()) return fail(getString(R.string.live_error_mic_denied))
        val source: LiveAudioSource = try {
            when {
                testFile != null -> FileTestSource(this, Uri.fromFile(java.io.File(testFile)))
                mode == AudioMode.INTERNAL -> {
                    if (Build.VERSION.SDK_INT < 29) return fail(getString(R.string.live_error_internal_old))
                    val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    val p = data?.let { mpm.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), it) }
                        ?: return fail(getString(R.string.live_error_projection))
                    p.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            main.post { if (mode == AudioMode.INTERNAL && engine != null) stopSession(getString(R.string.live_notice_projection_stopped)) }
                        }
                    }, main)
                    projection = p
                    PlaybackCaptureSource(p)
                }
                else -> micSource()
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio source", e)
            return fail(e.message ?: getString(R.string.live_error_generic))
        }
        run(source)
    }

    private fun run(source: LiveAudioSource) {
        // In the built-in browser the subtitles are drawn over the video there, not floating.
        if (mode.usesOverlay && !inApp) {
            if (!Settings.canDrawOverlays(this)) return fail(getString(R.string.live_error_overlay_denied))
            overlay = try {
                SubtitleOverlay(this, this).also { it.show() }
            } catch (e: Exception) {
                // Some phone skins refuse the window even with the permission granted.
                Log.e(TAG, "overlay refused", e)
                return fail(getString(R.string.live_error_overlay_refused))
            }
        }
        getSharedPreferences("live", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        shownIds.clear()
        engine = LiveEngine(this, mode, testThreads, testAdaptive).also { it.start(source) }
        LiveSession.update { it.copy(overlayActive = overlay != null) }
        collector = scope.launch {
            LiveSession.state.collect { s ->
                overlay?.setState(s)
                if (s.phase == LiveSession.Phase.ERROR && engine != null) updateNotification()
            }
        }
        main.post(tick)
    }

    private fun micSource() = MicrophoneSource(app.liveSettings.micGain, app.liveSettings.noiseSuppression)

    private fun micType() = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    private fun hasRecordAudio() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun fail(message: String) {
        LiveSession.update { it.copy(phase = LiveSession.Phase.ERROR, error = message) }
        saveReport(engine, getString(R.string.live_report_error, message))
        teardown()
    }

    /** Keeps the timing-only [LiveReport] of the session that is ending, for the Live screen. */
    private fun saveReport(e: LiveEngine?, ending: String) {
        runCatching {
            val s = LiveSession.state.value
            val nowMs = SystemClock.elapsedRealtime()
            val lag = e?.stats?.lagSpread()
            val facts = LiveReport.Facts(
                app = "AI Subtitle Translator ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                cores = Runtime.getRuntime().availableProcessors(),
                ramMb = app.models.deviceMemoryMb(),
                mode = mode,
                quality = app.liveSettings.quality,
                model = s.model,
                translator = s.translator,
                threads = e?.threads ?: app.settings.threads,
                overlayAllowed = Settings.canDrawOverlays(this),
                overlayShown = overlaySeen,
                durationSec = (nowMs - startedAtMs) / 1000.0,
                usage = e?.stats?.usage(nowMs, e.timeline.droppedLate),
                lagMedianMs = lag?.first,
                lagMaxMs = lag?.second,
                shownLines = e?.stats?.shownCount ?: 0,
                counters = e?.counters,
                ending = ending,
            )
            app.liveSettings.lastReport = LiveReport.text(facts)
        }.onFailure { Log.w(TAG, "report", it) }
    }

    /** Ends the session; [notice] says why when it was not the user's Stop. */
    private fun stopSession(notice: String?) {
        val e = engine
        val usage = e?.let { it.stats.usage(SystemClock.elapsedRealtime(), it.timeline.droppedLate) }
        val error = LiveSession.state.value.error.takeIf { LiveSession.state.value.phase == LiveSession.Phase.ERROR }
        if (e != null) saveReport(e, when {
            error != null -> getString(R.string.live_report_error, error)
            notice != null -> notice
            else -> getString(R.string.live_report_stopped)
        })
        teardown()
        LiveSession.update {
            if (it.phase == LiveSession.Phase.ERROR) it.copy(lines = emptyList(), captureActive = false, overlayActive = false)
            else it.copy(phase = LiveSession.Phase.ENDED, notice = notice, lines = emptyList(), captureActive = false, hearing = false,
                recognising = false, overlayActive = false, usage = usage ?: it.usage)
        }
    }

    private fun teardown() {
        main.removeCallbacks(tick)
        collector?.cancel()
        collector = null
        getSharedPreferences("live", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        overlay?.remove()
        overlay = null
        val e = engine
        engine = null
        // Stopping joins the engine's threads (whisper.cpp aborts within a fraction of a second).
        e?.stop()
        projection?.stop()
        projection = null
        if (active === this) active = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only if no newer request is queued: Stop followed at once by Start (a source change)
        // must not end the service before the Start arrives.
        stopSelf(lastStartId)
    }

    private fun onTick() {
        val e = engine ?: return
        if (!overlaySeen && overlay?.onScreen == true) overlaySeen = true
        val now = e.now()
        val visible = e.timeline.visible(now)
        val nowMs = SystemClock.elapsedRealtime()
        for (l in visible) {
            if (!l.provisional && l.chunk == 0 && shownIds.add(l.id)) {
                e.stats.shown(l.id, ((now - maxOf(l.arrival, l.showAt)) * 1000).toLong(), ((now - l.speechEnd) * 1000).toLong())
            }
        }
        if (shownIds.size > 500) shownIds = HashSet(shownIds.sortedDescending().take(100))
        val lines = visible.map { LiveSession.Line(it.text, it.provisional) }
        val statsDue = nowMs - lastStatsAt >= 1000
        if (lines != lastLines || statsDue) {
            val latency = if (statsDue) e.stats.latency() else LiveSession.state.value.latency
            overlay?.setLines(lines, if (app.liveSettings.showLatency) latencyText(latency) else null)
            lastLines = lines
            LiveSession.update { s ->
                if (statsDue) s.copy(lines = lines, latency = latency, usage = e.stats.usage(nowMs, e.timeline.droppedLate)) else s.copy(lines = lines)
            }
            if (statsDue) lastStatsAt = nowMs
        }
        // Battery: end a session that has heard no speech for a long time.
        val minutes = app.liveSettings.autoStopMinutes
        if (minutes > 0 && nowMs - e.lastSpeechAt > minutes * 60_000L && LiveSession.state.value.phase == LiveSession.Phase.LISTENING) {
            stopSession(getString(R.string.live_notice_auto_stop, minutes))
        }
    }

    private fun latencyText(l: Latency?): String? = l?.let {
        getString(R.string.live_latency_detail, it.totalMs / 1000.0, it.endpointMs, it.queueMs, it.recognitionMs, it.translationMs, it.renderMs)
    }

    private fun applySettings() {
        val e = engine ?: return
        val s = app.liveSettings
        e.timeline.delaySec = s.delaySec.toDouble()
        e.timeline.maxLines = s.maxLines
        overlay?.applyAppearance()
    }

    override fun togglePause() {
        val e = engine ?: return
        e.setPaused(!e.paused)
        updateNotification()
    }

    override fun stop() = stopSession(null)

    /** After the user agreed in the overlay (or the app): continue with the microphone, models kept. */
    override fun useMicrophone() {
        val e = engine ?: return
        if (!hasRecordAudio()) return
        try {
            // The service already holds the microphone type (started in the foreground), so this narrows it.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(AudioMode.MICROPHONE), micType())
        } catch (ex: Exception) {
            Log.w(TAG, "could not switch the service type", ex)
        }
        e.switchSource(micSource(), AudioMode.MICROPHONE)
        mode = AudioMode.MICROPHONE
        projection?.stop()
        projection = null
        updateNotification()
    }

    override fun openApp() {
        startActivity(Intent(this, screen()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Where the notification leads: the browser for its own sessions. */
    private fun screen(): Class<*> = if (inApp) BrowserActivity::class.java else LiveActivity::class.java

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        if (engine != null) stopSession(null)
        if (active === this) active = null
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        if (engine == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(m: AudioMode = mode): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.live_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.live_channel_help)
                setShowBadge(false)
            })
        }
        val paused = engine?.paused == true
        val open = PendingIntent.getActivity(this, 0, Intent(this, screen()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, LiveSubtitleService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val source = getString(
            when (m) {
                AudioMode.INTERNAL -> R.string.live_notif_internal
                AudioMode.MICROPHONE -> R.string.live_notif_mic
                AudioMode.SECOND_DEVICE -> R.string.live_notif_second
                AudioMode.PAGE -> R.string.live_notif_page
            },
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (paused) R.string.live_notif_paused else R.string.live_notif_title))
            .setContentText(source)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(if (paused) R.drawable.ic_play else R.drawable.ic_pause, getString(if (paused) R.string.live_resume else R.string.live_pause), action(ACTION_PAUSE, 1))
            .addAction(R.drawable.ic_stop, getString(R.string.live_stop), action(ACTION_STOP, 2))
            .build()
    }

    companion object {
        private const val TAG = "LiveService"
        private const val CHANNEL = "live_subtitles"
        private const val NOTIFICATION_ID = 7
        private const val TICK_MS = 150L
        const val ACTION_START = "com.aivicventures.subtitletranslator.live.START"
        const val ACTION_STOP = "com.aivicventures.subtitletranslator.live.STOP"
        const val ACTION_PAUSE = "com.aivicventures.subtitletranslator.live.PAUSE"
        const val ACTION_USE_MIC = "com.aivicventures.subtitletranslator.live.USE_MIC"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TEST_FILE = "test_file"
        const val EXTRA_IN_APP = "in_app"
        const val EXTRA_TEST_THREADS = "test_threads"
        const val EXTRA_TEST_ADAPTIVE = "test_adaptive"

        /** The running service (main thread only). */
        private var active: LiveSubtitleService? = null

        /** The browser's video jumped: drop what was heard before the jump. */
        fun resync() {
            active?.engine?.resync()
        }

        /**
         * Starts a session; for [AudioMode.INTERNAL] pass the screen-capture consent result.
         * [inApp]: the built-in browser draws the subtitles, so no floating overlay is shown.
         */
        fun start(
            context: Context, mode: AudioMode, resultCode: Int = 0, resultData: Intent? = null,
            testFile: String? = null, inApp: Boolean = false, testThreads: Int = 0, testAdaptive: Boolean = true,
        ) {
            val i = Intent(context, LiveSubtitleService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_IN_APP, inApp)
            if (resultData != null) i.putExtra(EXTRA_RESULT_DATA, resultData)
            if (testFile != null) i.putExtra(EXTRA_TEST_FILE, testFile).putExtra(EXTRA_TEST_THREADS, testThreads).putExtra(EXTRA_TEST_ADAPTIVE, testAdaptive)
            // The page source runs only while the browser is on screen: a plain service is enough.
            if (mode == AudioMode.PAGE && testFile == null) context.startService(i)
            else ContextCompat.startForegroundService(context, i)
        }

        fun command(context: Context, action: String) {
            context.startService(Intent(context, LiveSubtitleService::class.java).setAction(action))
        }
    }
}
