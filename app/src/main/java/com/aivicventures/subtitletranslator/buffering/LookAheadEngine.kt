package com.aivicventures.subtitletranslator.buffering

import android.util.Log
import com.aivicventures.subtitletranslator.asr.AsrSegment
import com.aivicventures.subtitletranslator.asr.Hallucination
import com.aivicventures.subtitletranslator.asr.WhisperAsr
import com.aivicventures.subtitletranslator.audio.AudioWindowReader
import com.aivicventures.subtitletranslator.cache.CacheDb
import com.aivicventures.subtitletranslator.device.CpuTopology
import com.aivicventures.subtitletranslator.device.PowerMonitor
import com.aivicventures.subtitletranslator.settings.Mode
import com.aivicventures.subtitletranslator.subtitles.Subtitle
import com.aivicventures.subtitletranslator.translate.M2M100Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.math.max
import kotlin.math.min

enum class BufferState(val label: String) {
    LOADING_MODEL("Loading speech model…"),
    PREPARING("Preparing subtitles"),
    READY("Subtitles ready"),
    TRANSLATING_AHEAD("Translating ahead"),
    BUFFER_LOW("Buffer low"),
    DELAYED("Translation delayed"),
    COMPLETE("Translation complete"),
    ERROR("Error"),
}

data class EngineStatus(
    val state: BufferState,
    val reserveSec: Double = 0.0,
    val targetSec: Double = 0.0,
    val coveredSec: Double = 0.0,
    val durationSec: Double = 0.0,
    /** Audio seconds recognised per second of processing (smoothed); 0 = not measured yet. */
    val speed: Double = 0.0,
    val language: String = "",
    val message: String = "",
    /** True once enough subtitles are ready to start playback (the readiness gate). */
    val ready: Boolean = false,
    /** Why the engine is saving power ("" = full speed). */
    val saving: String = "",
)

/**
 * The look-ahead scheduler (port of the Windows app's app/buffering): keeps at least the
 * mode's target of subtitles ready ahead of the playhead, nearest job first.
 *
 * Each job [t, t + chunk) decodes audio [t - LEAD, end + TAIL], runs Whisper (VAD, then
 * translate or transcribe) and keeps the segments [claims] gives it, skipping any that overlap
 * lines already stored (deduplication at chunk joins). With a text translator, only those kept
 * lines are translated by M2M100. Lines are shown after each Whisper run, not at the end of the
 * chunk. Results are cached in SQLite, so a movie is never processed twice. Seeking far away
 * aborts the running job.
 */
class LookAheadEngine(
    private val reader: AudioWindowReader,
    /** Second reader on its own thread: decodes the next window while Whisper is busy. */
    private val prefetchReader: AudioWindowReader?,
    private val asr: WhisperAsr,
    /** M2M100 for lines Whisper transcribed; null = Whisper translates (Phase 1 path). */
    private val translator: M2M100Translator?,
    private val db: CacheDb,
    private val profileId: Long,
    private val durationSec: Double,
    private val mode: Mode,
    private val language: String,
    private val threads: Int,
    private val continueWholeMovie: Boolean,
    /** M2M100 beam size; 0 = 4 in Full Movie mode, otherwise greedy (beam 4 scored no better on FLEURS at 1.6× the cost). */
    private val mtBeam: Int = 0,
    /** Battery and heat; null = always full speed. */
    private val power: PowerMonitor? = null,
    /** CPU cores for the engine thread and the worker threads it starts; null = any core. */
    private val pinCpus: List<Int>? = null,
    /** Test hook: always encode only the length of each line. */
    private val forceShortAudio: Boolean = false,
    /** Largest silence (seconds) inside one Whisper run; each run costs a full encoder pass. */
    private val groupGap: Double = WhisperAsr.GROUP_GAP,
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            if (pinCpus != null) Log.i(TAG, "engine thread pinned to cores $pinCpus: ${CpuTopology.pinCurrentThread(pinCpus)}")
            r.run()
        }, "subtitle-engine").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    @Volatile private var powerState = PowerMonitor.State(saving = false, critical = false, reason = "")
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private val prefetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-prefetch") }
    private var prefetch: Prefetch? = null

    private class Prefetch(val start: Double, val end: Double, val audio: Future<FloatArray>)

    private val lock = Any()
    private val coverage = Coverage(db.coverage(profileId))
    private val subs = db.segments(profileId).toMutableList()

    @Volatile var playhead = 0.0
        private set
    @Volatile var playing = false
    @Volatile private var generation = 0
    @Volatile private var current: Pair<Double, Double>? = null
    private var speed = 0.0
    private var audioAvg = 0.0
    private var workAvg = 0.0
    /** Speed on chunks with speech: silent ones finish at once and say nothing about keeping up. */
    private var pace = 0.0
    /** Seconds until a job's first lines appear: the work of one Whisper run. */
    private var runWork = 0.0
    /** Too slow to keep up: Whisper encodes only as much audio as each line holds, until the speed recovers. */
    private var shortAudio = forceShortAudio
    private var lastLanguage = ""
    @Volatile private var paused = false

    val target: Double = min(mode.targetSec, durationSec)

    private val _status = MutableStateFlow(EngineStatus(BufferState.PREPARING, targetSec = target, durationSec = durationSec))
    val status: StateFlow<EngineStatus> = _status

    /** Increments every time new subtitles arrive; the UI rebuilds its cues then. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun subtitles(): List<Subtitle> = synchronized(lock) { subs.toList() }

    fun start() {
        loop = scope.launch {
            publish()
            while (isActive) {
                if (paused) { waitForChange(); continue }
                updatePower()
                val job = synchronized(lock) { nextJob() }
                if (job == null) {
                    publish()
                    waitForChange()
                    continue
                }
                // Very hot: give the phone time to cool between jobs (the viewer sees "delayed").
                if (powerState.critical) delay(COOL_DOWN_MS)
                runJob(job)
            }
        }
    }

    /** Called by the player about every 200 ms and on seeks. */
    fun updatePlayhead(position: Double, isPlaying: Boolean) {
        val jumped = kotlin.math.abs(position - playhead) > 2.0
        playhead = position.coerceIn(0.0, durationSec)
        playing = isPlaying
        if (jumped) {
            generation++
            val cur = current
            // The running job is useless if the viewer jumped before it or far beyond it.
            if (cur != null && (cur.second < playhead || cur.first > playhead + target + mode.chunkSec)) {
                Log.i(TAG, "seek to %.1f: aborting job %.1f-%.1f".format(playhead, cur.first, cur.second))
                asr.abort()
            }
        }
        wake.trySend(Unit)
        if (!jumped) publish()
    }

    fun setPaused(p: Boolean) {
        paused = p
        wake.trySend(Unit)
    }

    fun markShown(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val set = ids.toSet()
            for (i in subs.indices) if (subs[i].id in set && !subs[i].shown) subs[i] = subs[i].copy(shown = true)
        }
        scope.launch { db.markShown(ids) }
    }

    suspend fun stop() {
        asr.abort()
        loop?.cancelAndJoin()
        worker.shutdown()
        prefetcher.shutdown()
        prefetcher.awaitTermination(10, TimeUnit.SECONDS)
    }

    private suspend fun waitForChange() {
        withTimeoutOrNull(1000) { wake.receive() }
    }

    private data class JobSpec(val start: Double, val end: Double, val hurry: Boolean, val generation: Int)

    private fun nextJob(): JobSpec? {
        val ph = playhead
        var t = coverage.firstUncovered(ph)
        val reserve = t - ph
        val background = mode == Mode.FULL || (continueWholeMovie && !powerState.saving)
        if (t >= durationSec - 0.05) {
            if (!background) return null
            t = coverage.firstUncovered(0.0) // back-fill parts skipped by seeking
            if (t >= durationSec - 0.05) return null
        } else if (reserve >= target && !background) {
            return null
        }
        // Too late for this job's first lines: start where the playhead will be when they appear,
        // so they are still ahead of the viewer (the skipped part is back-filled later).
        var jumped = false
        if (playing && mode != Mode.FULL && runWork > 0.0) {
            val eta = min(runWork, mode.chunkSec)
            if (t - ph < eta - MIN_SKIP) {
                val ahead = coverage.firstUncovered(min(ph + eta, durationSec))
                if (ahead < durationSec - 0.5) {
                    val skipped = min(ahead, coverage.nextCoveredStart(t) ?: ahead) - t
                    Log.i(TAG, "behind: skipping %.1f s of audio at %.1f, pace=%.2fx run=%.1fs".format(skipped, t, pace, runWork))
                    t = ahead
                    jumped = true
                }
            }
        }
        var end = min(t + mode.chunkSec, durationSec)
        coverage.nextCoveredStart(t)?.let { end = min(end, it) }
        if (end - t < 0.5) {
            // A sliver between two covered ranges: nothing worth recognising on its own.
            coverage.add(t, end)
            return nextJob()
        }
        // Catch-up: the viewer is watching with less than the target ready → cheapest decoding.
        val hurry = jumped || (playing && reserve < target && t - ph < target + mode.chunkSec)
        return JobSpec(t, end, hurry, generation)
    }

    private fun runJob(job: JobSpec) {
        current = job.start to job.end
        val winStart = max(0.0, job.start - LEAD)
        val winEnd = min(durationSec, job.end + TAIL)
        val t0 = System.nanoTime()
        val pre = prefetch
        prefetch = null
        val audio = try {
            if (pre != null && pre.start == winStart && pre.end == winEnd) {
                pre.audio.get()
            } else {
                reader.read(winStart, winEnd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio read failed at %.1f".format(job.start), e)
            fail("Could not decode the audio at ${fmt(job.start)}: ${e.message}")
            return
        }
        val t1 = System.nanoTime()
        prefetchNext(job)
        val beam = if (mtBeam > 0) mtBeam else if (mode == Mode.FULL && !job.hurry && !powerState.saving) 4 else 1
        val kept = mutableListOf<AsrSegment>()
        val added = mutableListOf<Subtitle>()
        var mtSec = 0.0
        var translationFailed = false
        val result = try {
            asr.run(audio, language, powerState.threads(threads), mode.thorough && !job.hurry, translator?.let { { l: String -> M2M100Translator.supports(l) } }, shortAudio,
                if (powerState.warm) max(groupGap, WARM_GROUP_GAP) else groupGap, recheck = !powerState.warm) { lines ->
                // Only this worker thread adds subtitles, so filtering outside the lock is safe.
                val claimed = lines.filter { seg ->
                    val s = winStart + seg.start
                    val e = min(winStart + seg.end, winEnd)
                    claims(s, e, job.start, job.end) && !overlapsStored(s, e)
                }
                val tm = System.nanoTime()
                val english = translate(claimed, beam)
                mtSec += (System.nanoTime() - tm) / 1e9
                if (english == null) {
                    translationFailed = true
                    asr.abort()
                } else {
                    kept += claimed
                    added += store(claimed, english, winStart, winEnd)
                    val ph = playhead
                    for (seg in claimed) {
                        val e = min(winStart + seg.end, winEnd)
                        if (e <= ph) Log.i(TAG, "blank-late %.2f %.2f playhead=%.2f".format(winStart + seg.start, e, ph))
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "recognition failed", e)
            fail("Speech recognition failed: ${e.message}")
            return
        } finally {
            current = null
        }
        if (translationFailed) return
        val t2 = System.nanoTime()
        if (result == null) {
            Log.i(TAG, "job %.1f-%.1f aborted".format(job.start, job.end))
            return
        }
        val asrSec = (t2 - t1) / 1e9 - mtSec
        if (result.language.isNotEmpty()) lastLanguage = result.language
        val workSec = ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.01)
        val chunkSpeed = (job.end - job.start) / workSec
        // Audio over work, on speech only: one silent chunk at 200x must not hide a slow phone.
        if (result.segments.isNotEmpty() || result.dropped > 0) {
            audioAvg = if (audioAvg == 0.0) job.end - job.start else audioAvg * 0.7 + (job.end - job.start) * 0.3
            workAvg = if (workAvg == 0.0) workSec else workAvg * 0.7 + workSec * 0.3
            pace = audioAvg / workAvg
            speed = pace
            if (result.groups > 0) {
                val perRun = workSec / result.groups
                runWork = if (runWork == 0.0) perRun else runWork * 0.7 + perRun * 0.3
            }
        }
        val nextShortAudio = when {
            forceShortAudio -> true
            mode == Mode.FULL -> false
            pace == 0.0 -> shortAudio // not measured since the last switch
            else -> shortAudioNext(shortAudio, pace)
        }
        if (nextShortAudio != shortAudio) {
            Log.i(TAG, "short audio: $nextShortAudio at %.1f, pace=%.2fx".format(job.end, pace))
            // The old pace says nothing about the new setting; measure again before skipping.
            audioAvg = 0.0
            workAvg = 0.0
            pace = 0.0
            runWork = 0.0
        }
        shortAudio = nextShortAudio

        synchronized(lock) {
            coverage.add(job.start, job.end)
            db.saveChunk(profileId, emptyList(), emptyList(), coverage.spans())
        }
        Log.i(
            TAG,
            "chunk %.1f-%.1f audio=%.1fs decode=%.2fs asr=%.2fs mt=%.2fs speed=%.2fx lang=%s kept=%d dropped=%d hurry=%b gen=%d/%d threads=%d"
                .format(job.start, job.end, winEnd - winStart, (t1 - t0) / 1e9, asrSec, mtSec, chunkSpeed, result.language,
                    added.size, result.dropped, job.hurry, job.generation, generation, powerState.threads(threads)),
        )
        added.forEach { Log.i(TAG, "  %s → %s  %s".format(fmt(it.start), fmt(it.end), it.english)) }
        logBlankSources(job, winStart, winEnd, result, kept)
        if (result.unsupportedLanguage != null) {
            _status.value = _status.value.copy(message = "Speech in ${result.unsupportedLanguage} is not supported and was left without subtitles.")
        }
        _version.value++
        publish()
    }

    /** Saves one Whisper run's lines and hands them to the player at once. */
    private fun store(lines: List<AsrSegment>, english: List<String>, winStart: Double, winEnd: Double): List<Subtitle> {
        val fresh = mutableListOf<Subtitle>()
        val logprobs = mutableListOf<Float>()
        lines.forEachIndexed { i, seg ->
            val text = english[i]
            if (text.isEmpty()) return@forEachIndexed
            fresh.add(Subtitle(0, winStart + seg.start, min(winStart + seg.end, winEnd), text, if (seg.english) "" else seg.text, seg.language))
            logprobs.add(seg.avgLogprob)
        }
        if (fresh.isEmpty()) return emptyList()
        val added = synchronized(lock) {
            val saved = db.saveChunk(profileId, fresh, logprobs, coverage.spans())
            subs.addAll(saved)
            subs.sortBy { it.start }
            saved
        }
        _version.value++
        return added
    }

    /**
     * English text for each kept segment: Whisper's own text, or M2M100's translation of the
     * transcript (one batch per language). Empty string = drop the line (the translation
     * came out as a known hallucination). Null after a translation failure.
     */
    private fun translate(kept: List<AsrSegment>, beam: Int): List<String>? {
        val out = kept.map { if (it.english) it.text else "" }.toMutableList()
        val mt = translator ?: return out
        for ((lang, idx) in kept.indices.filter { !kept[it].english }.groupBy { kept[it].language }) {
            val translated = try {
                mt.translate(idx.map { kept[it].text }, lang, beam)
            } catch (e: Throwable) {
                Log.e(TAG, "translation failed", e)
                fail("Translation failed: ${e.message}")
                return null
            }
            idx.forEachIndexed { k, i ->
                val text = WhisperAsr.clean(translated[k])
                val seg = kept[i]
                out[i] = if (Hallucination.isHallucination(text, seg.end - seg.start)) "" else Hallucination.stripHallucinations(text).trim()
            }
        }
        return out
    }

    /** Starts decoding the window the next job will most likely need (the one right after
     *  [job]); a seek simply leaves it unused. */
    private fun prefetchNext(job: JobSpec) {
        val pr = prefetchReader ?: return
        val start = job.end
        if (start >= durationSec - 0.5) return
        var end = min(start + mode.chunkSec, durationSec)
        synchronized(lock) { coverage.nextCoveredStart(start) }?.let { end = min(end, it) }
        if (end - start < 0.5) return
        val ws = max(0.0, start - LEAD)
        val we = min(durationSec, end + TAIL)
        prefetch = Prefetch(ws, we, prefetcher.submit(Callable { pr.read(ws, we) }))
    }

    private fun overlapsStored(s: Double, e: Double): Boolean = subs.any { o ->
        val ov = min(e, o.end) - max(s, o.start)
        ov > 0.5 * min(e - s, o.end - o.start)
    }

    /** Where a subtitle could go missing, as times and reasons only (never the dialogue). */
    private fun logBlankSources(job: JobSpec, winStart: Double, winEnd: Double, result: com.aivicventures.subtitletranslator.asr.AsrResult, kept: List<AsrSegment>) {
        for ((a, b) in result.speech) {
            val s = max(winStart + a, job.start)
            val e = min(winStart + b, job.end)
            if (e > s) Log.i(TAG, "blank-speech %.2f %.2f".format(s, e))
        }
        for (d in result.drops) {
            Log.i(TAG, "blank-drop %.2f %.2f %s".format(winStart + d.start, winStart + d.end, d.reason))
        }
        for (seg in result.segments) {
            if (seg in kept) continue
            val s = winStart + seg.start
            val e = min(winStart + seg.end, winEnd)
            val why = when {
                s >= job.end -> "next-chunk"
                overlapsStored(s, e) -> "duplicate"
                else -> "before-chunk"
            }
            Log.i(TAG, "blank-unclaimed %.2f %.2f %s".format(s, e, why))
        }
    }

    private fun updatePower() {
        val next = power?.state() ?: return
        if (next != powerState) {
            Log.i(TAG, "power: warm=${next.warm} saving=${next.saving} critical=${next.critical} reason=${next.reason.ifEmpty { "-" }} threads=${next.threads(threads)}")
            powerState = next
            publish()
        }
    }

    private fun publish() {
        val (reserve, covered) = synchronized(lock) { coverage.reserve(playhead) to coverage.total() }
        val remaining = durationSec - playhead
        val needed = min(target, remaining)
        val complete = covered >= durationSec - 0.5
        val ready = if (mode == Mode.FULL) complete else reserve >= needed - 0.05
        val state = when {
            complete -> BufferState.COMPLETE
            !ready && !playing -> BufferState.PREPARING
            !ready && reserve < 1.0 -> BufferState.DELAYED
            !ready -> BufferState.BUFFER_LOW
            current != null -> BufferState.TRANSLATING_AHEAD
            else -> BufferState.READY
        }
        val old = _status.value
        _status.value = old.copy(
            state = if (old.state == BufferState.ERROR) old.state else state,
            reserveSec = reserve, targetSec = target, coveredSec = covered, durationSec = durationSec,
            speed = speed, language = lastLanguage, ready = ready, saving = powerState.reason,
        )
    }

    private fun fail(message: String) {
        _status.value = _status.value.copy(state = BufferState.ERROR, message = message)
        paused = true
    }

    companion object {
        private const val TAG = "SubEngine"
        /** Audio decoded before / after each chunk, so words at the joins are heard whole. */
        const val LEAD = 1.0
        const val TAIL = 3.0
        private const val COOL_DOWN_MS = 10_000L
        /** Fewer Whisper runs when warm; a film rarely changes language within this gap. */
        private const val WARM_GROUP_GAP = 6.0
        /** A shorter skip is not worth losing the words for. */
        private const val MIN_SKIP = 2.0
        /** Lead-in a line may start in and still count as starting inside the chunk. */
        private const val START_MARGIN = 0.05
        /** A line that began earlier must reach this far into the chunk to be its own. */
        private const val MIN_ORPHAN = 0.3
        private const val SHORT_AUDIO_BELOW = 1.0
        private const val FULL_AUDIO_ABOVE = 2.0

        /**
         * Only when audio would otherwise be skipped: short audio costs quality (FLEURS chrF 42
         * to 32). It makes jobs about 1.5x faster, so the way back needs that much margin.
         */
        fun shortAudioNext(current: Boolean, speed: Double): Boolean = when {
            speed < SHORT_AUDIO_BELOW -> true
            speed > FULL_AUDIO_ABOVE -> false
            else -> current
        }

        /**
         * Whether a line at [s]-[e] belongs to the chunk [start]-[end]. A line that starts later
         * is the next chunk's. A line already under way when the chunk begins is dated to the
         * lead-in by Whisper: it is this chunk's unless an earlier chunk stored it (the caller
         * checks), or a skip or a long sentence would leave it with no subtitle at all.
         */
        fun claims(s: Double, e: Double, start: Double, end: Double): Boolean =
            s < end && (s >= start - START_MARGIN || e >= start + MIN_ORPHAN)

        fun fmt(sec: Double): String {
            val s = sec.toInt()
            return "%d:%02d:%02d.%d".format(s / 3600, s / 60 % 60, s % 60, ((sec - s) * 10).toInt())
        }
    }
}
