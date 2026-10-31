package com.aivicventures.subtitletranslator.subtitles

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Turn subtitle lines into readable cues (port of the Windows app's app/subtitles/layout.py).
 *
 * Rules (Netflix-style defaults):
 * - at most [Rules.maxLines] lines of [Rules.maxChars] characters,
 * - reading speed at most [Rules.maxCps] characters per second (long text is split into
 *   several cues sharing the sentence's time span proportionally),
 * - minimum cue duration, no overlaps, small gap between consecutive cues,
 * - at most [Rules.maxDuration] seconds on screen: a longer two-line cue is shown line by line,
 *   and a short line spoken slowly (or across a pause) clears after that time.
 */
object Layout {
    data class Rules(
        val maxChars: Int = 42,
        val maxLines: Int = 2,
        val maxCps: Double = 20.0,
        val minDuration: Double = 0.9,
        val maxDuration: Double = 7.0,
        val minGap: Double = 0.08,
    )

    /** Approximate rendered width: East-Asian wide characters count double. */
    fun displayWidth(text: String): Int {
        var w = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += if (isWide(cp)) 2 else 1
            i += Character.charCount(cp)
        }
        return w
    }

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x20000..0x3FFFD

    private val BREAK_AFTER = Regex("""(?<=[.!?;:,…。！？；，、])\s*""")
    private val SPACES = Regex("""\s+""")

    private fun squash(text: String) = text.trim().replace(SPACES, " ")

    /** Greedy word wrap that also handles text without spaces (CJK, Thai). */
    fun wrapLines(text0: String, maxChars: Int): List<String> {
        val text = squash(text0)
        if (text.isEmpty()) return emptyList()
        if (displayWidth(text) <= maxChars) return listOf(text)
        val hasSpace = ' ' in text
        val words = if (hasSpace) text.split(' ') else text.map { it.toString() }
        val joiner = if (hasSpace) " " else ""
        val lines = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            val cand = if (cur.isNotEmpty()) "$cur$joiner$w" else w
            if (displayWidth(cand) <= maxChars || cur.isEmpty()) {
                cur = cand
            } else {
                lines.add(cur)
                cur = w
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    /** Split text into two lines of similar length (bottom-heavy pyramid preferred). */
    fun balanceTwoLines(text: String, maxChars: Int): List<String> {
        val lines = wrapLines(text, maxChars)
        if (lines.size != 2 || ' ' !in text) return lines
        val words = squash(text).split(' ')
        var best: Pair<Int, List<String>>? = null
        for (i in 1 until words.size) {
            val a = words.subList(0, i).joinToString(" ")
            val b = words.subList(i, words.size).joinToString(" ")
            if (displayWidth(a) > maxChars || displayWidth(b) > maxChars) continue
            var score = abs(displayWidth(a) - displayWidth(b))
            if (a.endsWith(",") || a.endsWith(".") || a.endsWith("?") || a.endsWith("!") || a.endsWith(";") || a.endsWith(":")) {
                score -= 6 // prefer breaking after punctuation
            }
            if (best == null || score < best.first) best = score to listOf(a, b)
        }
        return best?.second ?: lines
    }

    /** Split text into chunks that each fit in maxLines lines. */
    fun splitForDisplay(text0: String, rules: Rules): List<String> {
        val text = squash(text0)
        if (text.isEmpty()) return emptyList()
        val capacity = rules.maxChars * rules.maxLines
        if (wrapLines(text, rules.maxChars).size <= rules.maxLines) return listOf(text)
        // Prefer sentence/clause boundaries, then fall back to line wrapping.
        val pieces = BREAK_AFTER.split(text).filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        var cur = ""
        val joiner = if (' ' in text) " " else ""
        for (piece in pieces) {
            val cand = if (cur.isNotEmpty()) "$cur$joiner$piece".trim() else piece
            if (wrapLines(cand, rules.maxChars).size <= rules.maxLines) {
                cur = cand
                continue
            }
            if (cur.isNotEmpty()) chunks.add(cur)
            if (wrapLines(piece, rules.maxChars).size <= rules.maxLines) {
                cur = piece
            } else {
                val lines = wrapLines(piece, rules.maxChars)
                var i = 0
                while (i < lines.size) {
                    chunks.add(lines.subList(i, min(i + rules.maxLines, lines.size)).joinToString(joiner))
                    i += rules.maxLines
                }
                cur = ""
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        return chunks.filter { it.isNotEmpty() }.ifEmpty { listOf(text.take(capacity)) }
    }

    fun formatLines(text: String, rules: Rules): String {
        val lines = if (rules.maxLines >= 2) balanceTwoLines(text, rules.maxChars) else wrapLines(text, rules.maxChars)
        return lines.joinToString("\n")
    }

    private fun round3(x: Double) = (x * 1000).roundToLong() / 1000.0

    /** Create display cues for rendering or export. */
    fun buildCues(subs: List<Subtitle>, rules: Rules = Rules()): List<Cue> {
        val cues = mutableListOf<Cue>()
        val ordered = subs.sortedBy { it.start }
        for ((idx, sub) in ordered.withIndex()) {
            val main = sub.english.trim()
            if (main.isEmpty()) continue
            val start = sub.start
            var end = max(sub.end, sub.start + 0.01)
            val nextStart = if (idx + 1 < ordered.size) ordered[idx + 1].start else null
            // Extend very short cues for readability, without running into the next one.
            val wanted = max(rules.minDuration, main.length / rules.maxCps)
            if (end - start < wanted) {
                val limit = if (nextStart != null) nextStart - rules.minGap else start + wanted
                end = max(end, min(start + wanted, limit))
            }
            if (nextStart != null && end > nextStart - rules.minGap) {
                end = max(start + 0.2, nextStart - rules.minGap)
            }
            val parts = lineByLine(splitForDisplay(main, rules), end - start, rules)
            val total = parts.sumOf { it.length }.coerceAtLeast(1)
            var t = start
            for ((i, part) in parts.withIndex()) {
                val share = (end - start) * part.length / total
                val nextT = if (i == parts.size - 1) end else t + share
                val cueEnd = min(nextT, t + max(rules.maxDuration, part.length / rules.maxCps))
                cues.add(Cue(round3(t), round3(cueEnd), formatLines(part, rules), listOf(sub.id)))
                t = nextT
            }
        }
        return cues
    }

    /** [parts], with each one that would stay on screen longer than maxDuration split into
     *  its lines (a long sentence spoken slowly: one line at a time). */
    private fun lineByLine(parts: List<String>, duration: Double, rules: Rules): List<String> {
        val total = parts.sumOf { it.length }.coerceAtLeast(1)
        val out = mutableListOf<String>()
        for (part in parts) {
            val lines = formatLines(part, rules).split("\n")
            if (duration * part.length / total > rules.maxDuration && lines.size > 1) out.addAll(lines) else out.add(part)
        }
        return out
    }
}
