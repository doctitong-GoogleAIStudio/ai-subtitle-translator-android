package com.aivicventures.subtitletranslator.live

import kotlin.math.log10
import kotlin.math.max

/**
 * Cuts a live 16 kHz mono stream into utterances for Whisper.
 *
 * Voice activity: 20 ms frames compared with an adaptive noise floor (falls quickly, rises
 * slowly), so steady background noise does not count as speech. An utterance starts after
 * 40 ms of sound above the floor (with [Params.preRollSec] of audio before it, so the first
 * syllable is not cut) and ends after [Params.endSilenceSec] below it. One that reaches
 * [Params.maxUtteranceSec] (rapid dialogue, or music that never falls silent) is cut at
 * its quietest frame in the last 1.5 s and the rest starts the next one, so words are not
 * split. Whisper's own Silero VAD then separates speech from music inside each utterance.
 *
 * While an utterance is in progress, [Params.partialEverySec] > 0 produces snapshots for
 * provisional subtitles. Times are seconds of audio since the session started (the
 * session clock). Not thread-safe.
 */
class UtteranceSegmenter(private var p: Params, private val rate: Int = 16_000) {
    data class Params(
        val endSilenceSec: Double,
        val maxUtteranceSec: Double,
        val partialEverySec: Double = 0.0,
        val preRollSec: Double = 0.3,
        /** Shorter bursts (clicks, a door) are dropped. */
        val minSpeechSec: Double = 0.25,
        /** Speech must be this much louder than the noise floor… */
        val thresholdDb: Double = 9.0,
        /** …and louder than this in absolute terms (full scale = 0 dB). */
        val minDb: Double = -50.0,
    ) {
        companion object {
            fun of(q: Quality) = Params(q.endSilenceSec, q.maxUtteranceSec, q.partialEverySec)
        }
    }

    sealed class Event {
        abstract val id: Long
        abstract val startSec: Double
        abstract val endSec: Double
        abstract val samples: FloatArray
    }

    /** The utterance so far; a later Partial or the Final with the same [id] replaces it. */
    class Partial(override val id: Long, override val startSec: Double, override val endSec: Double, override val samples: FloatArray) : Event()

    class Final(override val id: Long, override val startSec: Double, override val endSec: Double, override val samples: FloatArray) : Event()

    private val frameLen = rate / 50
    private val frameSec = frameLen.toDouble() / rate
    private val pending = FloatArray(frameLen)
    private var pendingLen = 0

    /** Samples consumed since the start (or the last [reset]). */
    var totalSamples = 0L
        private set

    /** Session time of sample 0, so event times match the session clock (set when a source starts). */
    var originSec = 0.0

    private var noiseDb = -60.0
    /** Level of the last frame in dBFS, for a level meter. */
    var levelDb = -100.0
        private set

    /** How long the input has been exact digital zeros: what a blocked playback capture delivers. */
    val digitalSilenceSec: Double get() = zeroFrames * frameSec
    private var zeroFrames = 0

    private val preRoll = ArrayDeque<FloatArray>()
    private val preRollDb = ArrayDeque<Double>()
    private var onsetFrames = 0

    private var inSpeech = false
    private val frames = ArrayList<FloatArray>()
    private val frameDb = ArrayList<Double>()
    private var startSample = 0L
    private var speechFrames = 0
    private var trailingSilence = 0
    private var lastPartialFrames = 0
    private var nextId = 1L
    private var id = 0L

    fun setParams(params: Params) {
        p = params
    }

    /** Feeds samples; returns the utterances (and snapshots) completed by them. */
    fun push(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): List<Event> {
        var out: MutableList<Event>? = null
        var i = offset
        val end = offset + length
        while (i < end) {
            val n = minOf(frameLen - pendingLen, end - i)
            System.arraycopy(samples, i, pending, pendingLen, n)
            pendingLen += n
            i += n
            if (pendingLen == frameLen) {
                pendingLen = 0
                frame(pending.copyOf())?.let { (out ?: mutableListOf<Event>().also { l -> out = l }).addAll(it) }
            }
        }
        return out ?: emptyList()
    }

    /** Ends the current utterance now (stop, pause or a change of source). */
    fun flush(): List<Event> {
        if (!inSpeech) return emptyList()
        val out = finish(frames.size)
        clearUtterance()
        return listOfNotNull(out)
    }

    /** Drops everything buffered (after a pause: old speech must not come back). */
    fun reset() {
        clearUtterance()
        preRoll.clear()
        preRollDb.clear()
        onsetFrames = 0
        pendingLen = 0
        zeroFrames = 0
    }

    private fun frame(x: FloatArray): List<Event>? {
        var sum = 0.0
        var zero = true
        for (v in x) {
            sum += v * v
            if (v != 0f) zero = false
        }
        zeroFrames = if (zero) zeroFrames + 1 else 0
        val db = max(-100.0, 10 * log10(sum / x.size + 1e-12))
        levelDb = db
        noiseDb = if (db < noiseDb) noiseDb + (db - noiseDb) * 0.3 else minOf(noiseDb + 0.015, -20.0)
        val speech = db > max(noiseDb + p.thresholdDb, p.minDb)
        totalSamples += x.size

        if (!inSpeech) {
            preRoll.addLast(x)
            preRollDb.addLast(db)
            val keep = (p.preRollSec / frameSec).toInt() + 2
            while (preRoll.size > keep) {
                preRoll.removeFirst()
                preRollDb.removeFirst()
            }
            onsetFrames = if (speech) onsetFrames + 1 else 0
            if (onsetFrames < ONSET_FRAMES) return null
            inSpeech = true
            id = nextId++
            frames.addAll(preRoll)
            frameDb.addAll(preRollDb)
            preRoll.clear()
            preRollDb.clear()
            startSample = totalSamples - frames.size.toLong() * frameLen
            speechFrames = onsetFrames
            trailingSilence = 0
            lastPartialFrames = 0
            onsetFrames = 0
            return null
        }

        frames.add(x)
        frameDb.add(db)
        if (speech) {
            speechFrames++
            trailingSilence = 0
        } else {
            trailingSilence++
        }
        if (trailingSilence * frameSec >= p.endSilenceSec) {
            // Keep 0.2 s of the trailing silence: Whisper places the last word better.
            val keep = frames.size - trailingSilence + (0.2 / frameSec).toInt()
            val out = finish(keep.coerceAtMost(frames.size))
            clearUtterance()
            return listOfNotNull(out)
        }
        if (frames.size * frameSec >= p.maxUtteranceSec) return listOfNotNull(cut())
        if (p.partialEverySec > 0 && (frames.size - lastPartialFrames) * frameSec >= p.partialEverySec &&
            speechFrames * frameSec >= MIN_PARTIAL_SPEECH_SEC
        ) {
            lastPartialFrames = frames.size
            val s = originSec + startSample.toDouble() / rate
            return listOf(Partial(id, s, s + frames.size * frameSec, concat(frames.size)))
        }
        return null
    }

    /** Max length reached: end the utterance at its quietest recent frame and carry the rest over. */
    private fun cut(): Event? {
        val search = (CUT_SEARCH_SEC / frameSec).toInt().coerceAtMost(frames.size - 1)
        var at = frames.size
        var best = Double.MAX_VALUE
        for (k in frames.size - search until frames.size) {
            if (frameDb[k] < best) {
                best = frameDb[k]
                at = k + 1
            }
        }
        val out = finish(at)
        val restFrames = ArrayList(frames.subList(at, frames.size))
        val restDb = ArrayList(frameDb.subList(at, frameDb.size))
        val restStart = startSample + at.toLong() * frameLen
        clearUtterance()
        inSpeech = true
        id = nextId++
        frames.addAll(restFrames)
        frameDb.addAll(restDb)
        startSample = restStart
        speechFrames = restDb.count { it > max(noiseDb + p.thresholdDb, p.minDb) }
        return out
    }

    private fun finish(count: Int): Final? {
        if (speechFrames * frameSec < p.minSpeechSec || count <= 0) return null
        val s = originSec + startSample.toDouble() / rate
        return Final(id, s, s + count * frameSec, concat(count))
    }

    private fun concat(count: Int): FloatArray {
        val out = FloatArray(count * frameLen)
        for (k in 0 until count) System.arraycopy(frames[k], 0, out, k * frameLen, frameLen)
        return out
    }

    private fun clearUtterance() {
        inSpeech = false
        frames.clear()
        frameDb.clear()
        speechFrames = 0
        trailingSilence = 0
        lastPartialFrames = 0
    }

    companion object {
        private const val ONSET_FRAMES = 2
        private const val CUT_SEARCH_SEC = 1.5
        private const val MIN_PARTIAL_SPEECH_SEC = 1.0
    }
}
