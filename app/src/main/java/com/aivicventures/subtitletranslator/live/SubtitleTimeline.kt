package com.aivicventures.subtitletranslator.live

import com.aivicventures.subtitletranslator.asr.Hallucination
import com.aivicventures.subtitletranslator.subtitles.Layout

/**
 * The live subtitle queue: decides what is on screen at each moment.
 *
 * Times are session-clock seconds (see [UtteranceSegmenter]). A line can only appear once
 * it has been recognised, so it is shown at `max(arrival, speech start + delay)`: a delay
 * larger than the processing latency gives an even rhythm, while a smaller (or negative)
 * delay cannot move a line before its arrival. Each line stays for a reading time
 * ([MIN_SHOW]–[MAX_SHOW] s at [CPS] characters per second), shorter if newer lines push it
 * off. Lines that arrive more than [maxLagSec] after they were spoken are dropped rather
 * than shown out of place (playback moved on). A provisional line is replaced by later
 * snapshots of the same utterance, at most every [MIN_PARTIAL_UPDATE] s so it does not
 * flicker, and by the final text. The same sentence arriving again within [REPEAT_MERGE] s
 * extends the line on screen instead of repeating it.
 */
class SubtitleTimeline(var delaySec: Double = 0.0, var maxLines: Int = 2, private val maxChars: Int = 42) {
    data class Line(
        /** Utterance id; display chunks of one utterance share it. */
        val id: Long,
        val chunk: Int,
        val speechStart: Double,
        val speechEnd: Double,
        val text: String,
        val provisional: Boolean,
        val arrival: Double,
        val showAt: Double,
        var hideAt: Double,
    )

    private val lines = ArrayList<Line>()
    private val lastPartialUpdate = HashMap<Long, Double>()

    /** Lines dropped because they arrived too late to be useful. */
    var droppedLate = 0
        private set

    val maxLagSec: Double get() = MAX_LAG + maxOf(0.0, delaySec)

    /**
     * Adds recognised [text] of utterance [id] spoken in [speechStart, speechEnd]. Returns false
     * if it was not used (stale, empty, a repeat, or a too-frequent provisional update).
     */
    @Synchronized
    fun offer(id: Long, speechStart: Double, speechEnd: Double, text: String, provisional: Boolean, now: Double): Boolean {
        val clean = text.trim()
        if (provisional) {
            if (lines.any { it.id == id && !it.provisional }) return false
            val last = lastPartialUpdate[id]
            if (last != null && now - last < MIN_PARTIAL_UPDATE) return false
            val current = lines.firstOrNull { it.id == id }
            if (current != null && Hallucination.norm(current.text) == Hallucination.norm(clean)) return false
        }
        lines.removeAll { it.id == id }
        lastPartialUpdate.remove(id)
        if (clean.isEmpty()) return false
        if (now - speechEnd > maxLagSec) {
            if (!provisional) droppedLate++
            return false
        }
        val n = Hallucination.norm(clean)
        lines.lastOrNull { !it.provisional && Hallucination.norm(it.text) == n && now - it.hideAt < REPEAT_MERGE }?.let { same ->
            same.hideAt = maxOf(same.hideAt, now + readingTime(clean))
            return false
        }
        if (provisional) lastPartialUpdate[id] = now
        // Long text is shown in chunks that fit the line limit, sharing its reading time.
        val chunks = Layout.splitForDisplay(clean, Layout.Rules(maxChars = maxChars, maxLines = maxLines.coerceAtLeast(1)))
            .let { if (provisional) listOf(it.last()) else it }
        var showAt = maxOf(now, speechStart + delaySec)
        for ((k, c) in chunks.withIndex()) {
            val hideAt = showAt + readingTime(c)
            lines.add(Line(id, k, speechStart, speechEnd, c, provisional, now, showAt, hideAt))
            showAt = hideAt
        }
        lines.sortWith(compareBy({ it.speechStart }, { it.chunk }))
        return true
    }

    /** The lines on screen at [now], oldest first, within [maxLines] screen lines. */
    @Synchronized
    fun visible(now: Double): List<Line> {
        lines.removeAll { it.hideAt <= now }
        if (lines.isEmpty()) lastPartialUpdate.clear()
        val due = lines.filter { it.showAt <= now }
        if (due.isEmpty()) return emptyList()
        val out = ArrayDeque<Line>()
        var used = 0
        for (l in due.asReversed()) {
            val h = Layout.wrapLines(l.text, maxChars).size.coerceAtLeast(1)
            if (out.isNotEmpty() && used + h > maxLines) break
            out.addFirst(l)
            used += h
        }
        // Older lines that no longer fit are gone for good (they would come back out of order).
        val oldest = out.first()
        lines.removeAll { it.showAt <= now && (it.speechStart < oldest.speechStart || (it.id == oldest.id && it.chunk < oldest.chunk)) }
        return out.toList()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        lastPartialUpdate.clear()
    }

    companion object {
        const val CPS = 15.0
        const val MIN_SHOW = 1.5
        const val MAX_SHOW = 6.0
        const val MAX_LAG = 10.0
        const val MIN_PARTIAL_UPDATE = 0.7
        const val REPEAT_MERGE = 4.0

        fun readingTime(text: String): Double = (Layout.displayWidth(text) / CPS).coerceIn(MIN_SHOW, MAX_SHOW)
    }
}
