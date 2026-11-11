package com.aivicventures.subtitletranslator.browser

import com.aivicventures.subtitletranslator.audio.Resampler
import com.aivicventures.subtitletranslator.buffering.Coverage
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decoded sound of a web video, mono 16-bit at the stream's own rate, placed by video time.
 * Only what the page's player has loaded is here, and only around the playhead: pieces more
 * than [keepBehind] seconds behind it are dropped, and at most [maxSeconds] are kept.
 * Thread-safe.
 */
class SoundStore(private val keepBehind: Double = 30.0, private val maxSeconds: Double = 240.0) {
    private class Block(val start: Double, val rate: Int, val pcm: ShortArray) {
        val end: Double get() = start + pcm.size.toDouble() / rate
    }

    private val blocks = TreeMap<Double, Block>()
    private var coverage = Coverage()
    private var seconds = 0.0
    /** When sound was last added (elapsed ms, from the caller's clock). */
    @Volatile var lastAddedAt = 0L
        private set

    @Synchronized
    fun put(start: Double, rate: Int, pcm: ShortArray, now: Long) {
        if (pcm.isEmpty() || rate <= 0 || start < 0) return
        val b = Block(start, rate, pcm)
        blocks.put(start, b)?.let { seconds -= it.pcm.size.toDouble() / it.rate }
        seconds += pcm.size.toDouble() / rate
        coverage.add(start, b.end)
        lastAddedAt = now
    }

    /** Drops sound before [position] - keepBehind, then the oldest while over the limit. */
    @Synchronized
    fun trim(position: Double) {
        var changed = false
        val cut = position - keepBehind
        while (blocks.isNotEmpty()) {
            val first = blocks.firstEntry().value
            if (first.end < cut || seconds > maxSeconds) {
                blocks.pollFirstEntry()
                seconds -= first.pcm.size.toDouble() / first.rate
                changed = true
            } else {
                break
            }
        }
        if (changed) rebuildCoverage()
    }

    private fun rebuildCoverage() {
        coverage = Coverage()
        for (b in blocks.values) coverage.add(b.start, b.end)
    }

    @Synchronized
    fun clear() {
        blocks.clear()
        coverage = Coverage()
        seconds = 0.0
    }

    /** End of the sound that runs on without a gap from [t] (t itself when there is none at t). */
    @Synchronized
    fun continuousUntil(t: Double): Double = coverage.firstUncovered(t)

    /** Start of the next stretch of sound after [t], or null. */
    @Synchronized
    fun nextStart(t: Double): Double? = coverage.nextCoveredStart(t)

    @Synchronized
    fun isEmpty(): Boolean = blocks.isEmpty()

    /** Total seconds held (diagnostics). */
    @Synchronized
    fun heldSeconds(): Double = seconds

    /**
     * [a]..[b] as 16 kHz mono float for Whisper; silence where nothing was loaded. Pieces at
     * another rate (the site switched quality) are converted to the rate of the first one.
     */
    fun read16k(a: Double, b: Double): FloatArray {
        val parts = synchronized(this) {
            val from = blocks.floorKey(a) ?: a
            blocks.subMap(from, true, b, false).values.filter { it.end > a }.toList()
        }
        val outLen = ((b - a) * RATE).roundToInt().coerceAtLeast(0)
        if (parts.isEmpty() || outLen == 0) return FloatArray(outLen)
        val rate = parts.first().rate
        val n = ((b - a) * rate).roundToInt()
        val buf = FloatArray(n)
        for (p in parts) {
            val src = if (p.rate == rate) toFloat(p.pcm) else {
                val f = toFloat(p.pcm)
                Resampler.resample(f, p.rate, rate, (f.size.toDouble() * rate / p.rate).roundToInt())
            }
            val at = ((p.start - a) * rate).roundToInt()
            val s0 = max(0, -at)
            val s1 = min(src.size, n - at)
            if (s1 > s0) System.arraycopy(src, s0, buf, at + s0, s1 - s0)
        }
        return Resampler.resample(buf, rate, RATE, outLen)
    }

    private fun toFloat(s: ShortArray) = FloatArray(s.size) { s[it] / 32768f }

    companion object {
        const val RATE = 16000
    }
}
