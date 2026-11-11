package com.aivicventures.subtitletranslator.browser

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.asr.AsrSegment
import com.aivicventures.subtitletranslator.asr.Hallucination
import com.aivicventures.subtitletranslator.asr.WhisperAsr
import com.aivicventures.subtitletranslator.buffering.Coverage
import com.aivicventures.subtitletranslator.buffering.LookAheadEngine
import com.aivicventures.subtitletranslator.device.PowerMonitor
import com.aivicventures.subtitletranslator.live.ContextMemory
import com.aivicventures.subtitletranslator.live.LiveEngine
import com.aivicventures.subtitletranslator.live.Quality
import com.aivicventures.subtitletranslator.translate.M2M100Translator
import com.aivicventures.subtitletranslator.translate.TranslatorNative
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * English subtitles from a web video's sound, made before the picture gets there.
 *
 * Web players (Media Source Extensions) load the sound some seconds ahead of the picture. The
 * page script passes on the sound-only part of what the player has loaded ([addPiece]); this
 * class decodes it on the phone (MediaExtractor and MediaCodec, one decoder kept running so
 * the joins between pieces stay clean), and recognises and translates it in chunks from the
 * playhead forward, as the movie player does for a file. Smart Delay can then hold the video
 * until enough English is ready ahead.
 *
 * Nothing is fetched and nothing is written to storage: the sound lives in memory around the
 * playhead ([SoundStore]) and is gone when this object is closed. Encrypted (DRM) sound is
 * never decoded. How far ahead it can work is set by how far the site's player loads.
 */
class SoundAhead(
    context: Context,
    private val quality: Quality,
    /** Called on a worker thread whenever lines arrive or the state changes. */
    private val onChange: () -> Unit,
) : AheadSubtitles {
    enum class State {
        /** Loading the speech models. */
        LOADING,
        /** Models ready, no sound from the page yet. */
        WAITING,
        /** Recognising ahead of the video. */
        WORKING,
        /** Everything loaded so far is done. */
        READY,
        /** It cannot work for this video ([problem] says why). */
        FAILED,
    }

    private class Piece(val sb: Int, val init: Boolean, val base64: String, val offset: Double, val sequence: Boolean, val t0: Double, val t1: Double)

    private val appContext = context.applicationContext
    private val live = appContext.app.liveSettings
    private val player = appContext.app.settings
    private val store = SoundStore()
    private val pieces = LinkedBlockingQueue<Piece>()
    private val lock = Object()
    /** Video time already recognised (guarded by [lock]). */
    private var done = Coverage()
    /** Lines before this time were let go; going back there means recognising it again. */
    private var forgotBefore = 0.0
    private val cues = ArrayList<Cue>()
    private val memory = ContextMemory(maxLines = 4)
    @Volatile private var closed = false
    @Volatile private var asr: WhisperAsr? = null
    @Volatile private var job: Pair<Double, Double>? = null

    @Volatile var state = State.LOADING
        private set
    /** "drm", "codec", "model" or "decode" once [state] is FAILED. */
    @Volatile var problem: String? = null
        private set
    @Volatile var language = ""
        private set
    /** Audio seconds recognised per second of work (smoothed); 0 = not measured yet. */
    @Volatile var speed = 0.0
        private set
    @Volatile var piecesReceived = 0
        private set
    /** Video length in seconds, or 0 when unknown (live streams). */
    @Volatile var duration = 0.0

    override var position = 0.0
        set(v) {
            val jumped = abs(v - field) > 2.0
            if (abs(v - field) > 0.2) movedAt = SystemClock.elapsedRealtime()
            field = v
            if (v < forgotBefore - 0.5) synchronized(lock) {
                done = Coverage()
                forgotBefore = 0.0
            }
            if (jumped) {
                val j = job
                // The chunk being recognised is no use if the viewer jumped before it or far past it.
                if (j != null && (j.second < v || j.first > v + MAX_AHEAD)) {
                    Log.i(TAG, "seek to %.1f: aborting %.1f-%.1f".format(v, j.first, j.second))
                    asr?.abort()
                }
            }
            synchronized(lock) { lock.notifyAll() }
        }

    override val holdable: Boolean
        get() = state != State.FAILED && piecesReceived > 0

    /** One piece of sound from the page (base64), as the player appended it. */
    fun addPiece(sb: Int, init: Boolean, base64: String, offset: Double, sequence: Boolean, t0: Double, t1: Double) {
        if (closed || state == State.FAILED) return
        pieces.offer(Piece(sb, init, base64, offset, sequence, t0, t1))
    }

    /** Seconds of sound the page's player has loaded ahead of [now] (continuous). */
    fun loadedAhead(now: Double): Double = (store.continuousUntil(now) - now).coerceAtLeast(0.0)

    /** When [position] last moved: the video plays while it keeps moving. */
    @Volatile private var movedAt = 0L

    /**
     * The page is moving the waiting video forward so its player loads more (Smart Delay's scan
     * ahead): pauses in the loading are then not the end of what the player will load.
     */
    @Volatile var scanning = false

    /** Milliseconds since the last sound arrived, or 0 before any. */
    fun idleMs(): Long = store.lastAddedAt.let { if (it > 0) SystemClock.elapsedRealtime() - it else 0L }

    /**
     * The player has filled its buffer: nothing new for [STALL_MS] while the video stands
     * still (held or paused). While it plays, the player loads on piece by piece.
     */
    private fun stalled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return !scanning && store.lastAddedAt > 0 && now - store.lastAddedAt > STALL_MS && now - movedAt > STILL_MS
    }

    override fun textAt(t: Double): Pair<String, Boolean> {
        val active = synchronized(lock) { Captions.activeAt(cues, t) }
        return active.joinToString("\n") { it.text } to false
    }

    override fun readyAhead(now: Double, horizon: Double): Double {
        val doneEnd = synchronized(lock) { done.firstUncovered(now) }
        val ahead = doneEnd - now
        if (ahead >= horizon) return horizon
        if (duration > 0 && doneEnd >= duration - 0.5) return horizon
        // All the sound the player has loaded is done and it loads no more for now (it fills
        // its buffer only so far): there is nothing left to wait for.
        if (state == State.READY && store.continuousUntil(now) <= doneEnd + 0.3 && stalled()) return horizon
        return ahead.coerceIn(0.0, horizon)
    }

    /** Seconds after [now] actually recognised without a gap (no allowance for the site's limit). */
    fun doneAhead(now: Double): Double = (synchronized(lock) { done.firstUncovered(now) } - now).coerceAtLeast(0.0)

    /** Lines ready from [now] on (diagnostics). */
    fun lineCount(): Int = synchronized(lock) { cues.size }

    // ---- Decoding ----

    private class Bytes(private val b: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= b.size) return -1
            val n = min(size.toLong(), b.size - position).toInt()
            System.arraycopy(b, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = b.size.toLong()
        override fun close() {}
    }

    private var sb = -1
    private var init: ByteArray? = null
    private var webm = false
    private var timescale = 0L
    private var codec: MediaCodec? = null
    private var codecFormat: MediaFormat? = null
    private var outRate = 0
    private var outChannels = 0
    private var outFloat = false
    /** Video time the next piece should start at when the sound runs on without a gap. */
    private var expected = -1.0
    private val info = MediaCodec.BufferInfo()

    private fun decodeMain() {
        try {
            while (!closed) {
                val p = pieces.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (closed) break
                try {
                    handle(p)
                } catch (e: Exception) {
                    Log.w(TAG, "piece failed (sb ${p.sb}, ${p.base64.length} chars)", e)
                    // One bad piece: the decoder starts fresh with the next.
                    releaseCodec()
                }
                store.trim(position)
            }
        } catch (_: InterruptedException) {
        } finally {
            releaseCodec()
        }
    }

    private fun handle(p: Piece) {
        val bytes = Base64.decode(p.base64, Base64.DEFAULT)
        if (p.sb != sb) {
            // Another player (the next video, or after an advert): start over.
            Log.i(TAG, "sound buffer ${p.sb}")
            sb = p.sb
            init = null
            releaseCodec()
            store.clear()
            synchronized(lock) {
                cues.clear()
                done = Coverage()
                forgotBefore = 0.0
                lock.notifyAll()
            }
        }
        var media = bytes
        if (Mp4Boxes.isWebm(bytes)) {
            webm = true
            init = bytes
            if (!p.init || p.t0 < 0) return
        } else {
            val end = Mp4Boxes.initEnd(bytes)
            if (end > 0) {
                val i = bytes.copyOf(end)
                if (Mp4Boxes.encrypted(i)) {
                    fail("drm")
                    return
                }
                init = i
                webm = false
                timescale = Mp4Boxes.timescale(i)
                Log.i(TAG, "init ${i.size} bytes, timescale $timescale")
                if (end >= bytes.size) return
                media = bytes.copyOfRange(end, bytes.size)
            }
        }
        val head = init ?: return
        piecesReceived++
        val start = startOf(p, media) ?: return
        decode(if (webm && Mp4Boxes.isWebm(media)) media else head + media, start)
    }

    /** Video time of a piece's first sound: from the MP4 fragment, else from what the buffer gained. */
    private fun startOf(p: Piece, media: ByteArray): Double? {
        if (!webm && !p.sequence && timescale > 0) {
            val base = Mp4Boxes.baseDecodeTime(media)
            if (base >= 0) {
                val s = base.toDouble() / timescale + p.offset
                if (p.t0 >= 0 && abs(s - p.t0) > 1.0) Log.i(TAG, "piece time %.2f, buffer gained %.2f-%.2f".format(s, p.t0, p.t1))
                return s
            }
        }
        return p.t0.takeIf { it >= 0 }
    }

    private fun decode(data: ByteArray, start: Double) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(Bytes(data))
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val c = codecFor(format) ?: return
            if (expected >= 0 && abs(start - expected) > GAP_SEC) {
                // A jump (seek, or a piece replaced): the decoder's history belongs elsewhere.
                c.flush()
            }
            val tmp = ByteBuffer.allocate(format.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(8192) ?: 65536)
            var first = -1L
            var last = -1L
            var step = 0L
            while (!closed) {
                tmp.clear()
                val size = ex.readSampleData(tmp, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (first < 0) first = t
                if (last >= 0 && t > last) step = t - last
                last = t
                val pts = (start * 1e6).toLong() + (t - first)
                var idx: Int
                while (true) {
                    idx = c.dequeueInputBuffer(5_000)
                    if (idx >= 0 || closed) break
                    drain(c, 0)
                }
                if (idx < 0) return
                val ib = c.getInputBuffer(idx) ?: return
                ib.clear()
                tmp.limit(size)
                tmp.position(0)
                ib.put(tmp)
                c.queueInputBuffer(idx, 0, size, pts, 0)
                ex.advance()
                drain(c, 0)
            }
            // The decoder keeps a frame or two; they come out with the next piece.
            drain(c, 10_000)
            if (first >= 0) expected = start + (last - first + step) / 1e6
        } finally {
            ex.release()
        }
    }

    private fun codecFor(format: MediaFormat): MediaCodec? {
        val old = codecFormat
        val c = codec
        if (c != null && old != null && same(old, format)) return c
        releaseCodec()
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        return try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
                codec = it
                codecFormat = format
                outRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                outFloat = false
                expected = -1.0
                Log.i(TAG, "decoder $mime ${outRate} Hz ${outChannels} ch")
            }
        } catch (e: Exception) {
            Log.w(TAG, "no decoder for $mime", e)
            fail("codec")
            null
        }
    }

    private fun same(a: MediaFormat, b: MediaFormat): Boolean {
        fun s(f: MediaFormat, k: String) = if (f.containsKey(k)) f.getString(k) else null
        fun i(f: MediaFormat, k: String) = if (f.containsKey(k)) f.getInteger(k) else -1
        fun csd(f: MediaFormat) = f.getByteBuffer("csd-0")?.let { bb -> ByteArray(bb.remaining()).also { bb.duplicate().get(it) } }
        return s(a, MediaFormat.KEY_MIME) == s(b, MediaFormat.KEY_MIME) &&
            i(a, MediaFormat.KEY_SAMPLE_RATE) == i(b, MediaFormat.KEY_SAMPLE_RATE) &&
            i(a, MediaFormat.KEY_CHANNEL_COUNT) == i(b, MediaFormat.KEY_CHANNEL_COUNT) &&
            csd(a).contentEquals(csd(b))
    }

    private fun drain(c: MediaCodec, timeoutUs: Long) {
        while (true) {
            val o = c.dequeueOutputBuffer(info, timeoutUs)
            when {
                o >= 0 -> {
                    val ob = c.getOutputBuffer(o)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val mono = mono(ob.slice().order(ByteOrder.nativeOrder()))
                        store.put(info.presentationTimeUs / 1e6, outRate, mono, SystemClock.elapsedRealtime())
                    }
                    c.releaseOutputBuffer(o, false)
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    outFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                else -> return
            }
        }
    }

    private fun mono(b: ByteBuffer): ShortArray {
        val ch = outChannels.coerceAtLeast(1)
        return if (outFloat) {
            val f = b.asFloatBuffer()
            val frames = f.remaining() / ch
            ShortArray(frames) { i ->
                var s = 0f
                for (k in 0 until ch) s += f.get(i * ch + k)
                (s / ch * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        } else {
            val s = b.asShortBuffer()
            val frames = s.remaining() / ch
            ShortArray(frames) { i ->
                var sum = 0
                for (k in 0 until ch) sum += s.get(i * ch + k)
                (sum / ch).toShort()
            }
        }
    }

    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        codecFormat = null
        expected = -1.0
    }

    // ---- Recognition ----

    private fun recognitionMain() {
        val models = appContext.app.models
        var mt: M2M100Translator? = null
        try {
            val spec = LiveEngine.chooseModel(models, live.asrModel)
            val vad = models.vadModel()
            if (spec == null || vad == null) {
                fail("model")
                return
            }
            val threads = player.threads
            val a = models.weights(spec).use { WhisperAsr(appContext, it, models.localFile(vad.files.first()).path, spec.id) }
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
            Log.i(TAG, "sound ahead: model=${spec.id} mt=${mt != null} quality=$quality threads=$threads lang=${live.sourceLanguage}")
            if (state == State.LOADING) setState(State.WAITING)
            val power = PowerMonitor(appContext, player.batterySaver)
            while (!closed) {
                val next = nextJob() ?: continue
                val ps = power.state()
                run(a, mt, next, ps)
                if (ps.critical && !closed) Thread.sleep(COOL_DOWN_MS)
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.e(TAG, "sound ahead failed", e)
            fail("model")
        } finally {
            asr?.close()
            asr = null
            mt?.close()
        }
    }

    private class Job(val start: Double, val end: Double, val winStart: Double, val winEnd: Double)

    /** The next chunk to recognise, waiting (up to a second) until there is one. */
    private fun nextJob(): Job? {
        synchronized(lock) {
            val job = pick()
            if (job != null) return job
            if (state == State.WORKING && !store.isEmpty()) setState(State.READY)
            lock.wait(WAIT_MS)
            return null
        }
    }

    private fun pick(): Job? {
        val pos = position
        var t = done.firstUncovered(max(0.0, pos - 0.5))
        if (t - pos >= MAX_AHEAD) return null
        if (duration > 0 && t >= duration - 0.3) return null
        var audioEnd = store.continuousUntil(t)
        if (audioEnd - t < 0.3) {
            // No sound at t yet: the next loaded stretch close by, if any.
            val s = store.nextStart(t) ?: return null
            if (s - pos > MAX_AHEAD) return null
            t = s
            audioEnd = store.continuousUntil(t)
        }
        val avail = audioEnd - t
        val end = when {
            avail >= CHUNK_SEC + TAIL_SEC -> t + CHUNK_SEC
            // The player loads no more for now, or the playhead is close: what there is.
            avail >= MIN_PARTIAL_SEC && (stalled() || t - pos < URGENT_SEC) -> audioEnd
            duration > 0 && audioEnd >= duration - 0.3 -> audioEnd
            else -> return null
        }
        val winStart = max(0.0, t - LEAD_SEC)
        val winEnd = min(audioEnd, end + TAIL_SEC)
        return Job(t, end, winStart, winEnd)
    }

    private fun run(a: WhisperAsr, mt: M2M100Translator?, j: Job, ps: PowerMonitor.State) {
        job = j.start to j.end
        setState(State.WORKING)
        val t0 = SystemClock.elapsedRealtime()
        val audio = store.read16k(j.winStart, j.winEnd)
        val beam = if (quality == Quality.HIGH_ACCURACY && !ps.saving) 4 else 1
        var kept = 0
        val result = try {
            a.run(
                audio, live.sourceLanguage, ps.threads(player.threads), quality.thorough && !ps.saving,
                mt?.let { { l: String -> M2M100Translator.supports(l) } },
                prompt = if (live.contextMemory) { lang, english -> memory.prompt(lang, english, j.start) } else null,
            ) { lines ->
                val claimed = synchronized(lock) {
                    lines.filter { seg ->
                        val s = j.winStart + seg.start
                        val e = min(j.winStart + seg.end, j.winEnd)
                        LookAheadEngine.claims(s, e, j.start, j.end) && !overlapsStored(s, e)
                    }
                }
                val english = translate(mt, claimed, beam)
                val fresh = claimed.indices.mapNotNull { i ->
                    val text = english[i].takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val seg = claimed[i]
                    val s = j.winStart + seg.start
                    val h = memory.harmonize(text)
                    memory.add(s, if (seg.english) "" else seg.text, h, seg.language)
                    Cue(s, min(j.winStart + seg.end, j.winEnd), h)
                }
                kept += fresh.size
                // Dialogue text only with `adb shell setprop log.tag.SoundAhead DEBUG` (it may be private).
                if (Log.isLoggable(TAG, Log.DEBUG)) fresh.forEach { Log.d(TAG, "line %s %s → %s".format(LookAheadEngine.fmt(it.start), LookAheadEngine.fmt(it.end), it.text)) }
                if (fresh.isNotEmpty()) {
                    synchronized(lock) {
                        cues += fresh
                        cues.sortBy { it.start }
                        spread()
                    }
                    onChange()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "recognition failed", e)
            null
        } finally {
            job = null
        }
        val work = (SystemClock.elapsedRealtime() - t0) / 1000.0
        if (result == null) {
            Log.i(TAG, "chunk %.1f-%.1f aborted".format(j.start, j.end))
            return
        }
        if (result.language.isNotEmpty()) language = result.language
        val chunk = j.end - j.start
        if (result.segments.isNotEmpty()) {
            val v = chunk / work.coerceAtLeast(0.01)
            speed = if (speed == 0.0) v else speed * 0.7 + v * 0.3
        }
        synchronized(lock) {
            done.add(j.start, j.end)
            // Lines long gone are not needed any more.
            val cut = position - KEEP_LINES_BEHIND
            if (cues.removeAll { it.end < cut }) forgotBefore = max(forgotBefore, cut)
        }
        Log.i(TAG, "chunk %.1f-%.1f audio=%.1fs work=%.2fs lang=%s kept=%d dropped=%d ahead=%.1f loaded=%.1f"
            .format(j.start, j.end, j.winEnd - j.winStart, work, result.language, kept, result.dropped, j.end - position, loadedAhead(position)))
        onChange()
    }

    /** English for each line: Whisper's own, or M2M100's translation; "" drops a line. */
    private fun translate(mt: M2M100Translator?, kept: List<AsrSegment>, beam: Int): List<String> {
        val out = kept.map { if (it.english) it.text else "" }.toMutableList()
        if (mt == null) return out
        for ((lang, idx) in kept.indices.filter { !kept[it].english }.groupBy { kept[it].language }) {
            val translated = runCatching { mt.translate(idx.map { kept[it].text }, lang, beam) }
                .onFailure { Log.w(TAG, "translation failed", it) }.getOrNull() ?: continue
            idx.forEachIndexed { k, i ->
                val text = WhisperAsr.clean(translated[k])
                val seg = kept[i]
                out[i] = if (Hallucination.isHallucination(text, seg.end - seg.start)) "" else Hallucination.stripHallucinations(text).trim()
            }
        }
        return out
    }

    private fun overlapsStored(s: Double, e: Double): Boolean = cues.any { o ->
        val ov = min(e, o.end) - max(s, o.start)
        ov > 0.5 * min(e - s, o.end - o.start)
    }

    /** Short lines stay up long enough to read, without running into the next line. */
    private fun spread() {
        for (i in cues.indices) {
            val c = cues[i]
            val next = cues.getOrNull(i + 1)?.start ?: Double.MAX_VALUE
            val want = max(c.end, c.start + MIN_SHOW_SEC)
            val end = if (want > next && next > c.start) max(c.end, next) else want
            if (end != c.end) cues[i] = c.copy(end = end)
        }
    }

    private fun setState(s: State) {
        if (state == s || state == State.FAILED) return
        state = s
        onChange()
    }

    private fun fail(why: String) {
        if (state == State.FAILED) return
        Log.i(TAG, "sound ahead unavailable: $why")
        problem = why
        state = State.FAILED
        onChange()
    }

    override fun close() {
        closed = true
        asr?.abort()
        pieces.clear()
        synchronized(lock) { lock.notifyAll() }
        decoder.interrupt()
        // The recognition thread frees the models when its current chunk is done.
        store.clear()
    }

    // Started last, once every field above exists.
    private val decoder = Thread(::decodeMain, "sound-decode")
    private val worker = Thread(::recognitionMain, "sound-ahead")

    init {
        decoder.start()
        worker.start()
    }

    companion object {
        private const val TAG = "SoundAhead"
        /** Seconds recognised per chunk (the movie player's normal chunk). */
        const val CHUNK_SEC = 12.0
        private const val LEAD_SEC = 1.0
        private const val TAIL_SEC = 2.0
        /** Shortest chunk worth a Whisper run when the player loads no more for now. */
        private const val MIN_PARTIAL_SEC = 2.0
        /** The playhead is this close: recognise what there is rather than wait for more. */
        private const val URGENT_SEC = 8.0
        /** No new sound for this long: the player has filled its buffer. */
        const val STALL_MS = 3000L
        /** The video counts as standing still when its position has not moved for this long. */
        private const val STILL_MS = 1500L
        /** No need to work further ahead than the largest Smart Delay plus a chunk. */
        const val MAX_AHEAD = 75.0
        private const val GAP_SEC = 0.15
        private const val WAIT_MS = 500L
        private const val COOL_DOWN_MS = 1500L
        private const val MIN_SHOW_SEC = 1.5
        private const val KEEP_LINES_BEHIND = 30.0
    }
}
