package com.aivicventures.subtitletranslator.buffering

/**
 * Union of time ranges (seconds) whose audio has been recognised and translated.
 * Kept merged and sorted; not thread-safe (the engine guards it).
 */
class Coverage(initial: List<Pair<Double, Double>> = emptyList()) {
    private val spans = mutableListOf<DoubleArray>()

    init {
        initial.forEach { add(it.first, it.second) }
    }

    /** Touching or nearly touching ranges (gap < [EPS]) are merged. */
    fun add(start: Double, end: Double) {
        if (end <= start) return
        var s = start
        var e = end
        val out = mutableListOf<DoubleArray>()
        var inserted = false
        for (span in spans) {
            when {
                span[1] < s - EPS -> out.add(span)
                span[0] > e + EPS -> {
                    if (!inserted) { out.add(doubleArrayOf(s, e)); inserted = true }
                    out.add(span)
                }
                else -> { s = minOf(s, span[0]); e = maxOf(e, span[1]) }
            }
        }
        if (!inserted) out.add(doubleArrayOf(s, e))
        spans.clear()
        spans.addAll(out)
    }

    fun contains(t: Double): Boolean = spans.any { t >= it[0] - EPS && t < it[1] }

    /** First time >= t that is not covered (t itself if uncovered). */
    fun firstUncovered(t: Double): Double {
        var x = t
        for (span in spans) {
            if (span[1] <= x) continue
            if (span[0] > x + EPS) return x
            x = span[1]
        }
        return x
    }

    /** Start of the next covered range after t, or null. */
    fun nextCoveredStart(t: Double): Double? = spans.firstOrNull { it[0] > t + EPS }?.get(0)

    /** Length of continuous coverage starting at t. */
    fun reserve(t: Double): Double = (firstUncovered(t) - t).coerceAtLeast(0.0)

    fun total(): Double = spans.sumOf { it[1] - it[0] }

    fun spans(): List<Pair<Double, Double>> = spans.map { it[0] to it[1] }

    companion object {
        const val EPS = 0.05
    }
}
