package com.aivicventures.subtitletranslator.buffering

import android.util.Log
import com.aivicventures.subtitletranslator.asr.AsrSegment
import com.aivicventures.subtitletranslator.asr.Hallucination
import com.aivicventures.subtitletranslator.asr.WhisperAsr
import com.aivicventures.subtitletranslator.audio.AudioWindowReader
import com.aivicventures.subtitletranslator.cache.CacheDb
import com.aivicventures.subtitletranslator.settings.Mode
import com.aivicventures.subtitletranslator.subtitles.Subtitle
import com.aivicventures.subtitletranslator.translate.M2M100Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
)

/**
 * The look-ahead scheduler (port of the Windows app's app/buffering): keeps at least the
 * mode's target of subtitles ready ahead of the playhead, nearest job first.
 *
 * Each job [t, t + chunk) decodes audio [t - LEAD, end + TAIL], runs Whisper (VAD, then
 * translate or transcribe) and keeps the segments that *start* inside [t, end)
 * (owner-by-start), skipping any that overlap lines already stored (deduplication at chunk
 * joins). With a text translator, only those kept lines are translated by M2M100. Results
 * are cached in SQLite, so a movie is never processed twice. Seeking far away aborts the
 * running job.
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
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "subtitle-engine").apply { priority = Thread.NORM_PRIORITY - 1 } }
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
                val job = synchronized(lock) { nextJob() }
                if (job == null) {
                    publish()
                    waitForChange()
                    continue
                }
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
        val background = mode == Mode.FULL || continueWholeMovie
        if (t >= durationSec - 0.05) {
            if (!background) return null
            t = coverage.firstUncovered(0.0) // back-fill parts skipped by seeking
            if (t >= durationSec - 0.05) return null
        } else if (reserve >= target && !background) {
            return null
        }
        // Slower than real time: start where the playhead will be when this job finishes,
        // so its lines are still ahead of the viewer (the skipped part is back-filled later).
        var jumped = false
        if (playing && mode != Mode.FULL && speed in 0.05..0.95) {
            val eta = mode.chunkSec / speed + 1.0
            if (t - ph < eta) {
                val ahead = coverage.firstUncovered(min(ph + eta, durationSec))
                if (ahead < durationSec - 0.5) {
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
        val result = try {
            asr.run(audio, language, threads, mode.thorough && !job.hurry, translator?.let { { l: String -> M2M100Translator.supports(l) } })
        } catch (e: Throwable) {
            Log.e(TAG, "recognition failed", e)
            fail("Speech recognition failed: ${e.message}")
            return
        } finally {
            current = null
        }
        val t2 = System.nanoTime()
        if (result == null) {
            Log.i(TAG, "job %.1f-%.1f aborted".format(job.start, job.end))
            return
        }
        val asrSec = (t2 - t1) / 1e9
        if (result.language.isNotEmpty()) lastLanguage = result.language

        // Only this worker thread adds subtitles, so filtering outside the lock is safe.
        val kept = result.segments.filter { seg ->
            val s = winStart + seg.start
            s >= job.start - 0.05 && s < job.end && !overlapsStored(s, min(winStart + seg.end, winEnd)) // owner-by-start
        }
        val t3 = System.nanoTime()
        val english = translate(kept, if (mtBeam > 0) mtBeam else if (mode == Mode.FULL && !job.hurry) 4 else 1) ?: return
        val mtSec = (System.nanoTime() - t3) / 1e9
        val chunkSpeed = (job.end - job.start) / ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.01)
        speed = if (speed == 0.0) chunkSpeed else speed * 0.7 + chunkSpeed * 0.3

        val added: List<Subtitle>
        synchronized(lock) {
            val fresh = mutableListOf<Subtitle>()
            val logprobs = mutableListOf<Float>()
            kept.forEachIndexed { i, seg ->
                val text = english[i]
                if (text.isEmpty()) return@forEachIndexed
                val s = winStart + seg.start
                val e = min(winStart + seg.end, winEnd)
                fresh.add(Subtitle(0, s, e, text, if (seg.english) "" else seg.text, seg.language))
                logprobs.add(seg.avgLogprob)
            }
            coverage.add(job.start, job.end)
            added = db.saveChunk(profileId, fresh, logprobs, coverage.spans())
            subs.addAll(added)
            subs.sortBy { it.start }
        }
        Log.i(
            TAG,
            "chunk %.1f-%.1f audio=%.1fs decode=%.2fs asr=%.2fs mt=%.2fs speed=%.2fx lang=%s kept=%d dropped=%d hurry=%b gen=%d/%d"
                .format(job.start, job.end, winEnd - winStart, (t1 - t0) / 1e9, asrSec, mtSec, chunkSpeed, result.language,
                    added.size, result.dropped, job.hurry, job.generation, generation),
        )
        added.forEach { Log.i(TAG, "  %s → %s  %s".format(fmt(it.start), fmt(it.end), it.english)) }
        if (result.unsupportedLanguage != null) {
            _status.value = _status.value.copy(message = "Speech in ${result.unsupportedLanguage} is not supported and was left without subtitles.")
        }
        _version.value++
        publish()
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
            speed = speed, language = lastLanguage, ready = ready,
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

        fun fmt(sec: Double): String {
            val s = sec.toInt()
            return "%d:%02d:%02d.%d".format(s / 3600, s / 60 % 60, s % 60, ((sec - s) * 10).toInt())
        }
    }
}
