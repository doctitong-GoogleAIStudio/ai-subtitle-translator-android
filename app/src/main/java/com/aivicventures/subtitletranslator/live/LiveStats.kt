package com.aivicventures.subtitletranslator.live

/** Average latency per stage over the last [LiveStats.WINDOW] lines, in milliseconds. */
data class Latency(
    /** End of speech until the segmenter closed the line (the pause it waits for). */
    val endpointMs: Long,
    /** Waiting for the recognition worker. */
    val queueMs: Long,
    val recognitionMs: Long,
    val translationMs: Long,
    /** Ready until drawn on screen. */
    val renderMs: Long,
    /** End of speech until the subtitle appeared. */
    val totalMs: Long,
    val samples: Int,
)

/** What a session used. Everything runs on the phone, so nothing is billed. */
data class Usage(
    val durationSec: Double,
    /** Audio given to Whisper (provisional decodes included). */
    val recognisedSec: Double,
    val lines: Int,
    val translatedLines: Int,
    val provisionalUpdates: Int,
    val droppedLate: Int,
    val droppedBacklog: Int,
    val averageLatencyMs: Long,
) {
    /** On-device processing has no per-use cost. A cloud provider would fill this in. */
    val estimatedCost: Double get() = 0.0
}

/** Latency measurement and usage metering for one session; thread-safe. */
class LiveStats {
    private class Sample(val endpoint: Long, val queue: Long, val asr: Long, val mt: Long, var render: Long = -1, var total: Long = -1)

    private val pending = HashMap<Long, Sample>()
    private val done = ArrayDeque<Sample>()
    private var startedMs = 0L
    private var recognisedSec = 0.0
    private var lines = 0
    private var translated = 0
    private var provisional = 0
    private var droppedBacklog = 0
    private var latencySum = 0L
    private var latencyCount = 0

    @Synchronized
    fun start(nowMs: Long) {
        startedMs = nowMs
        pending.clear()
        done.clear()
        recognisedSec = 0.0
        lines = 0
        translated = 0
        provisional = 0
        droppedBacklog = 0
        latencySum = 0
        latencyCount = 0
    }

    @Synchronized fun recognised(audioSec: Double) { recognisedSec += audioSec }
    @Synchronized fun provisionalUpdate() { provisional++ }
    @Synchronized fun backlogDropped(n: Int = 1) { droppedBacklog += n }

    /** A final line of utterance [id] is ready; its render time is added by [shown]. */
    @Synchronized
    fun ready(id: Long, endpointMs: Long, queueMs: Long, asrMs: Long, mtMs: Long, translatedLine: Boolean) {
        pending[id] = Sample(endpointMs, queueMs, asrMs, mtMs)
        lines++
        if (translatedLine) translated++
        if (pending.size > 50) pending.remove(pending.keys.min())
    }

    /** Utterance [id] appeared on screen [renderMs] after it was due, [totalMs] after its speech ended. */
    @Synchronized
    fun shown(id: Long, renderMs: Long, totalMs: Long) {
        val s = pending.remove(id) ?: return
        s.render = renderMs
        s.total = totalMs
        done.addLast(s)
        while (done.size > WINDOW) done.removeFirst()
        latencySum += totalMs
        latencyCount++
    }

    @Synchronized
    fun latency(): Latency? {
        if (done.isEmpty()) return null
        fun avg(f: (Sample) -> Long) = done.sumOf(f) / done.size
        return Latency(avg { it.endpoint }, avg { it.queue }, avg { it.asr }, avg { it.mt }, avg { it.render }, avg { it.total }, done.size)
    }

    @Synchronized
    fun usage(nowMs: Long, droppedLate: Int): Usage = Usage(
        durationSec = (nowMs - startedMs).coerceAtLeast(0) / 1000.0,
        recognisedSec = recognisedSec,
        lines = lines,
        translatedLines = translated,
        provisionalUpdates = provisional,
        droppedLate = droppedLate,
        droppedBacklog = droppedBacklog,
        averageLatencyMs = if (latencyCount == 0) 0 else latencySum / latencyCount,
    )

    companion object {
        const val WINDOW = 20
    }
}
