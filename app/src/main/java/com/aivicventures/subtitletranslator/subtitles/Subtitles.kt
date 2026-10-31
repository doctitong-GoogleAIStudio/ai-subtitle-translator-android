package com.aivicventures.subtitletranslator.subtitles

/** One recognised line on the movie timeline (seconds from movie start). */
data class Subtitle(
    val id: Long,
    val start: Double,
    val end: Double,
    val english: String,
    /** Original-language text; empty when Whisper translated directly (Phase 1). */
    val source: String,
    val language: String,
    val shown: Boolean = false,
)

/** A display cue produced by [Layout.buildCues]. */
data class Cue(
    val start: Double,
    val end: Double,
    val text: String,
    val subtitleIds: List<Long>,
)

/** SubRip export. */
object Srt {
    fun timestamp(sec: Double): String {
        val ms = Math.round(sec.coerceAtLeast(0.0) * 1000)
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }

    fun write(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(timestamp(c.start)).append(" --> ").append(timestamp(c.end)).append('\n')
            append(c.text.trim()).append("\n\n")
        }
    }
}
