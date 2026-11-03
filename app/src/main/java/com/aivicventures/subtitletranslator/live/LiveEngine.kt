package com.aivicventures.subtitletranslator.live

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.asr.AsrSegment
import com.aivicventures.subtitletranslator.asr.WhisperAsr
import com.aivicventures.subtitletranslator.device.CpuTopology
import com.aivicventures.subtitletranslator.device.PowerMonitor
import com.aivicventures.subtitletranslator.models.ModelManager
import com.aivicventures.subtitletranslator.models.ModelSpec
import com.aivicventures.subtitletranslator.settings.Languages
import com.aivicventures.subtitletranslator.translate.M2M100Translator
import com.aivicventures.subtitletranslator.translate.TranslatorNative
import java.util.Locale

/** Turns recognised lines into English. On-device M2M100 today; a cloud provider would be another implementation. */
fun interface LineTranslator {
    fun translate(texts: List<String>, language: String): List<String>
}

/**
 * The live pipeline: audio source → [UtteranceSegmenter] → Whisper ([WhisperAsr], with its
 * Silero VAD, language lock and hallucination filters) → M2M100 where it translates better
 * (same rule as the movie player) → [SubtitleTimeline].
 *
 * Two threads: capture (reads the source, segments, and checks for blocked capture) and
 * recognition (loads the models, then decodes one utterance at a time; finished lines first,
 * provisional snapshots only when nothing else waits). If recognition falls behind, the
 * oldest waiting lines are dropped rather than shown late. Everything stays in memory and
 * is gone when the session stops.
 */
class LiveEngine(private val context: Context, @Volatile var mode: AudioMode) {
    private val live = context.app.liveSettings
    private val player = context.app.settings
    val quality = live.quality
    private val language = live.sourceLanguage
    val timeline = SubtitleTimeline(live.delaySec.toDouble(), live.maxLines)
    val stats = LiveStats()
    private val memory = ContextMemory(quality.contextLines)
    private val useContext = live.contextMemory
    private val shortContext = live.shortContext
    private val segmenter = UtteranceSegmenter(UtteranceSegmenter.Params.of(quality))
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private class Job(val event: UtteranceSegmenter.Event, val endpointMs: Long, val queuedAt: Long)

    private val lock = Object()
    private val finals = ArrayDeque<Job>()
    private var partial: Job? = null
    private val recentLanguages = ArrayDeque<String>()

    @Volatile private var running = false
    @Volatile var paused = false
        private set
    @Volatile private var source: LiveAudioSource? = null
    @Volatile private var nextSource: LiveAudioSource? = null
    private var worker: Thread? = null
    private var capture: Thread? = null
    @Volatile private var asr: WhisperAsr? = null
    private var startedAt = 0L
    @Volatile var lastSpeechAt = 0L
        private set

    /** Session clock in seconds (the timeline's time base). */
    fun now(): Double = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

    fun start(first: LiveAudioSource) {
        running = true
        startedAt = SystemClock.elapsedRealtime()
        lastSpeechAt = startedAt
        stats.start(startedAt)
        nextSource = first
        worker = Thread(::recognitionMain, "live-recognition").apply { start() }
    }

    /** Continues with another source (e.g. the microphone after capture was blocked); models stay loaded. */
    fun switchSource(next: LiveAudioSource, newMode: AudioMode) {
        val old = source
        mode = newMode
        nextSource = next
        old?.close()
        LiveSession.update { it.copy(mode = newMode, captureBlocked = false) }
    }

    /** While paused, audio is read (the clock keeps running) but nothing is recognised; resuming drops what was said meanwhile. */
    fun setPaused(p: Boolean) {
        paused = p
        synchronized(lock) {
            finals.clear()
            partial = null
        }
        segmenter.reset()
        timeline.clear()
        LiveSession.update { it.copy(phase = if (p) LiveSession.Phase.PAUSED else LiveSession.Phase.LISTENING, lines = emptyList()) }
    }

    fun stop() {
        running = false
        nextSource?.close()
        source?.close()
        asr?.abort()
        synchronized(lock) { lock.notifyAll() }
        capture?.join(2000)
        worker?.join(3000)
        memory.clear()
        timeline.clear()
    }

    private fun recognitionMain() {
        val models = context.app.models
        var mt: M2M100Translator? = null
        try {
            val pins = CpuTopology.fastCores.takeIf { player.fastCoresOnly && CpuTopology.heterogeneous }
            // whisper.cpp's and CTranslate2's worker threads inherit this thread's cores.
            if (pins != null) CpuTopology.pinCurrentThread(pins)
            val spec = chooseModel(models, live.asrModel)
            val vad = models.vadModel()
            if (spec == null || vad == null) error("The speech models are missing from this build.")
            LiveSession.update { it.copy(phase = LiveSession.Phase.LOADING, model = spec.name) }
            val threads = player.threads
            val a = models.weights(spec).use { WhisperAsr(context, it, models.localFile(vad.files.first()).path, spec.id) }
            asr = a
            val useMt = when (live.translator) {
                "whisper" -> false
                "m2m100" -> true
                else -> spec.autoMt
            }
            val mtSpec = models.translationModel()
            mt = if (useMt && mtSpec != null && TranslatorNative.available) {
                runCatching { models.weights(mtSpec).use { M2M100Translator(models.localFolder(mtSpec), it, threads) } }
                    .onFailure { Log.w(TAG, "M2M100 unavailable, Whisper translates", it) }.getOrNull()
            } else null
            val translator = mt?.let { m -> LineTranslator { texts, lang -> m.translate(texts, lang, 1) } }
            Log.i(TAG, "live start mode=$mode quality=$quality model=${spec.id} mt=${mt != null} lang=$language threads=$threads pin=${pins ?: "-"} context=$useContext")
            LiveSession.update {
                it.copy(phase = if (paused) LiveSession.Phase.PAUSED else LiveSession.Phase.LISTENING, translator = if (mt != null) "M2M100" else "Whisper")
            }
            if (!running) return
            capture = Thread(::captureMain, "live-capture").apply { priority = Thread.MAX_PRIORITY; start() }
            val power = PowerMonitor(context, player.batterySaver)
            while (running) {
                val job = take() ?: break
                process(a, translator, job, power, threads)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "live engine failed", e)
            if (running) LiveSession.update { it.copy(phase = LiveSession.Phase.ERROR, error = e.message ?: e.javaClass.simpleName) }
        } finally {
            asr?.close()
            asr = null
            mt?.close()
        }
    }

    private fun take(): Job? = synchronized(lock) {
        while (running) {
            finals.removeFirstOrNull()?.let { return it }
            partial?.let {
                partial = null
                return it
            }
            lock.wait(500)
        }
        null
    }

    private fun process(a: WhisperAsr, translator: LineTranslator?, job: Job, power: PowerMonitor, baseThreads: Int) {
        val e = job.event
        val provisional = e is UtteranceSegmenter.Partial
        if (paused) return
        if (!provisional && now() - e.endSec > timeline.maxLagSec) {
            stats.backlogDropped()
            return
        }
        val queueMs = SystemClock.elapsedRealtime() - job.queuedAt
        val ps = power.state()
        LiveSession.update { it.copy(recognising = true) }
        val t0 = SystemClock.elapsedRealtime()
        val prompt: ((String, Boolean) -> String?)? = if (useContext) { lang, english -> memory.prompt(lang, english, e.startSec) } else null
        val res = a.run(
            e.samples, language, ps.threads(baseThreads), quality.thorough && !provisional,
            translator?.let { { lang: String -> M2M100Translator.supports(lang) } }, prompt, e.startSec, provisional, shortContext,
        )
        val t1 = SystemClock.elapsedRealtime()
        LiveSession.update { it.copy(recognising = false) }
        if (res == null || !running || paused) return
        stats.recognised(e.samples.size / RecordSource.RATE.toDouble())
        val segs = res.segments
        if (segs.isEmpty()) {
            if (!provisional) timeline.offer(e.id, e.startSec, e.endSec, "", false, now())
            if (res.unsupportedLanguage != null) LiveSession.update { it.copy(notice = "${Languages.name(res.unsupportedLanguage)} is not supported.") }
            return
        }
        val english = translate(segs, translator)
        val t2 = SystemClock.elapsedRealtime()
        val text = memory.harmonize(english.joinToString(" ").trim())
        val lang = segs.groupingBy { it.language }.eachCount().maxByOrNull { it.value }!!.key
        if (!provisional) {
            lastSpeechAt = t2
            if (useContext) memory.add(e.endSec, segs.filter { !it.english }.joinToString(" ") { it.text }, text, lang)
            noteLanguage(lang)
        }
        val shown = timeline.offer(e.id, e.startSec, e.endSec, text, provisional, now())
        Log.i(TAG, "line ${e.id}%s %.1f-%.1f s (%.1f s audio): asr %d ms, mt %d ms, lag %.1f s, %s".format(Locale.ROOT,
            if (provisional) "~" else "", e.startSec, e.endSec, e.samples.size / RecordSource.RATE.toDouble(), t1 - t0, t2 - t1,
            now() - e.endSec, if (shown) "shown" else "not shown"))
        // The text itself only with `adb shell setprop log.tag.LiveEngine DEBUG` (it may be private).
        // Audio time (from the start of the source), in the format scripts/score_e2e.py reads.
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val t = (e.startSec - segmenter.originSec).coerceAtLeast(0.0)
            Log.d(TAG, "text%s ${e.id} %d:%02d:%04.1f → %s %s".format(Locale.ROOT, if (provisional) "~" else "", (t / 3600).toInt(), (t / 60 % 60).toInt(), t % 60, lang, text))
        }
        if (provisional) {
            if (shown) stats.provisionalUpdate()
        } else {
            stats.ready(e.id, job.endpointMs, queueMs, t1 - t0, t2 - t1, segs.any { !it.english })
        }
        // Very hot: give the phone time to cool between lines.
        if (ps.critical) Thread.sleep(COOL_DOWN_MS)
    }

    /** English for each segment: as recognised when Whisper already gave English, else via [translator]. */
    private fun translate(segs: List<AsrSegment>, translator: LineTranslator?): List<String> {
        if (translator == null || segs.all { it.english }) return segs.map { it.text }
        val out = segs.map { it.text }.toMutableList()
        segs.indices.filter { !segs[it].english }.groupBy { segs[it].language }.forEach { (lang, idx) ->
            val done = runCatching { translator.translate(idx.map { segs[it].text }, lang) }
                .onFailure { Log.w(TAG, "translation failed", it) }.getOrNull()
            if (done != null && done.size == idx.size) idx.forEachIndexed { k, i -> out[i] = done[k] }
        }
        return out
    }

    private fun noteLanguage(lang: String) {
        recentLanguages.addLast(lang)
        while (recentLanguages.size > 10) recentLanguages.removeFirst()
        val (top, n) = recentLanguages.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return
        LiveSession.update { it.copy(language = top, languageCount = n, languageOf = recentLanguages.size) }
    }

    private fun captureMain() {
        val buf = FloatArray(RecordSource.RATE / 10)
        var lastCheck = 0L
        while (running) {
            val src = nextSource?.also {
                nextSource = null
                source = it
            } ?: break
            try {
                src.start()
            } catch (e: Exception) {
                Log.w(TAG, "audio source failed", e)
                LiveSession.update { it.copy(phase = LiveSession.Phase.ERROR, captureActive = false, error = e.message ?: "The audio input could not start.") }
                src.close()
                return
            }
            segmenter.reset()
            // Audio time starts now, after model loading (or a source switch); line times must be session times.
            segmenter.originSec = now() - segmenter.totalSamples.toDouble() / RecordSource.RATE
            LiveSession.update { it.copy(captureActive = true, noiseSuppressor = (src as? MicrophoneSource)?.hasNoiseSuppressor == true) }
            while (running) {
                val n = src.read(buf)
                if (n < 0) break
                val events = segmenter.push(buf, 0, n)
                if (!paused) events.forEach(::enqueue)
                val t = SystemClock.elapsedRealtime()
                if (t - lastCheck >= 500) {
                    lastCheck = t
                    val hearing = segmenter.levelDb > HEARING_DB
                    // Media is playing, yet only exact zeros arrive: the playing app does not allow capture.
                    val blocked = mode == AudioMode.INTERNAL && segmenter.digitalSilenceSec >= BLOCKED_SEC && audio.isMusicActive
                    LiveSession.update { s -> if (s.hearing == hearing && s.captureBlocked == blocked) s else s.copy(hearing = hearing, captureBlocked = blocked) }
                }
            }
            if (nextSource != null) continue
            // The source ended by itself (test file) or was closed by stop().
            if (running) segmenter.flush().forEach(::enqueue)
            LiveSession.update { it.copy(captureActive = false, hearing = false, notice = if (running) "The audio source ended." else it.notice) }
            break
        }
    }

    private fun enqueue(e: UtteranceSegmenter.Event) {
        val job = Job(e, ((now() - e.endSec) * 1000).toLong().coerceAtLeast(0), SystemClock.elapsedRealtime())
        synchronized(lock) {
            when (e) {
                is UtteranceSegmenter.Final -> {
                    finals.addLast(job)
                    if (partial?.event?.id == e.id) partial = null
                    var dropped = 0
                    while (finals.size > MAX_BACKLOG) {
                        finals.removeFirst()
                        dropped++
                    }
                    if (dropped > 0) {
                        stats.backlogDropped(dropped)
                        LiveSession.update { it.copy(fallingBehind = true) }
                    }
                }
                is UtteranceSegmenter.Partial -> partial = job
            }
            lock.notifyAll()
        }
    }

    companion object {
        private const val TAG = "LiveEngine"
        private const val MAX_BACKLOG = 3
        private const val HEARING_DB = -60.0
        private const val BLOCKED_SEC = 4.0
        private const val COOL_DOWN_MS = 1500L
        /** Automatic choice for live use: the largest model up to this size, for speed. */
        private const val AUTO_MAX_RAM_MB = 300

        /** The model chosen in Live settings, else the most accurate fast one that fits in memory (base, or tiny). */
        fun chooseModel(models: ModelManager, preferredId: String): ModelSpec? {
            val installed = models.installedAsr()
            installed.firstOrNull { it.id == preferredId }?.let { return it }
            val budgetMb = models.deviceMemoryMb() / 4
            return installed.filter { it.ramMb <= budgetMb && it.ramMb <= AUTO_MAX_RAM_MB }.maxByOrNull { it.size }
                ?: installed.minByOrNull { it.size }
        }
    }
}
