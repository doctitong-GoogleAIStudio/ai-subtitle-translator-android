package com.aivicventures.subtitletranslator.browser

/** One caption cue of the page's video, times in video seconds. */
data class Cue(val start: Double, val end: Double, val text: String)

/** A subtitle or caption track the page's video offers (`video.textTracks`). */
data class CaptionTrack(val index: Int, val kind: String, val label: String, val language: String)

object Captions {
    private val TAG = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")

    /** WebVTT markup (`<i>`, `<c.x>`, `<v Name>`) and HTML entities removed, lines joined. */
    fun clean(raw: String): String {
        val noTags = raw.replace(TAG, "")
        val decoded = noTags
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&lrm;", "").replace("&rlm;", "")
            .replace("&amp;", "&")
        return decoded.replace(SPACE, " ").trim()
    }

    /** A page language tag (BCP 47, e.g. "ko-KR", "zh-Hans", "iw") as the app's language code; null when unknown. */
    fun languageOf(tag: String?): String? {
        val t = tag?.trim()?.lowercase()?.replace('_', '-') ?: return null
        val base = t.substringBefore('-')
        if (base.isEmpty() || base == "und" || base == "mul" || base == "zxx") return null
        return when (base) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "fil" -> "tl"
            "nb", "nn" -> "no"
            "cmn", "yue", "zho", "chi" -> "zh"
            "jpn" -> "ja"
            "kor" -> "ko"
            "eng" -> "en"
            "spa" -> "es"
            else -> base.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
        }
    }

    /**
     * The language when the page names none, from the writing system — only scripts that
     * belong to one language (Latin, Cyrillic or Arabic script could be many languages).
     */
    fun guessLanguage(texts: List<String>): String? {
        var hangul = 0
        var kana = 0
        var han = 0
        var thai = 0
        var hebrew = 0
        var greek = 0
        var georgian = 0
        var armenian = 0
        var letters = 0
        for (s in texts) for (c in s) {
            if (!c.isLetter()) continue
            letters++
            when (c.code) {
                in 0xAC00..0xD7A3, in 0x1100..0x11FF, in 0x3130..0x318F -> hangul++
                in 0x3040..0x30FF -> kana++
                in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> han++
                in 0x0E00..0x0E7F -> thai++
                in 0x0590..0x05FF -> hebrew++
                in 0x0370..0x03FF -> greek++
                in 0x10A0..0x10FF -> georgian++
                in 0x0530..0x058F -> armenian++
            }
        }
        if (letters == 0) return null
        fun share(n: Int) = n.toDouble() / letters
        return when {
            share(hangul) > 0.3 -> "ko"
            share(kana) > 0.05 && share(kana + han) > 0.3 -> "ja"
            share(han) > 0.3 -> "zh"
            share(thai) > 0.3 -> "th"
            share(hebrew) > 0.3 -> "he"
            share(greek) > 0.3 -> "el"
            share(georgian) > 0.3 -> "ka"
            share(armenian) > 0.3 -> "hy"
            else -> null
        }
    }

    /** The track to translate: an English one needs no translation, otherwise the first subtitle track. */
    fun pick(tracks: List<CaptionTrack>): CaptionTrack? {
        val usable = tracks.filter { it.kind == "subtitles" || it.kind == "captions" || it.kind.isEmpty() }
        return usable.firstOrNull { languageOf(it.language) == "en" } ?: usable.firstOrNull()
    }

    /** The cues showing at video time [t]. [cues] is sorted by start. */
    fun activeAt(cues: List<Cue>, t: Double): List<Cue> {
        val out = ArrayList<Cue>(2)
        for (c in cues) {
            if (c.start > t) break
            if (t < c.end) out += c
        }
        return out
    }

    /**
     * Smart Delay: seconds after [now] that are translated without a gap, at most [horizon].
     * A cue that has not been translated yet ends the buffer at its start time. Past the
     * last known cue there is nothing left to wait for, so the buffer counts as full.
     */
    fun readyAhead(cues: List<Cue>, translated: (Cue) -> Boolean, now: Double, horizon: Double): Double {
        for (c in cues) {
            if (c.end <= now) continue
            if (c.start >= now + horizon) break
            if (!translated(c)) return (c.start - now).coerceIn(0.0, horizon)
        }
        return horizon
    }

    /** Smart Delay targets offered in the browser, in seconds (0 = off). */
    val SMART_DELAY_CHOICES = listOf(0, 5, 10, 15, 20, 30, 45, 60)

    private val SENTENCE_END = ".!?…。！？♪"
    private val CLOSERS = "\"'”’»)]」』"
    private val NO_SPACE_LANGUAGES = setOf("ja", "zh")

    /** [text] ends a sentence (closing quotes and brackets aside). */
    fun endsSentence(text: String): Boolean {
        val t = text.trimEnd().trimEnd { it in CLOSERS }
        return t.isEmpty() || t.last() in SENTENCE_END
    }

    /** A dialogue dash: a new speaker starts, so the cue begins its own sentence. */
    fun startsSpeaker(text: String): Boolean = text.trimStart().let { it.startsWith("-") || it.startsWith("–") || it.startsWith("—") }

    /**
     * Sentences split over several cues ("I told him that" / "we would leave tomorrow.") are
     * translated together, because a half sentence translates badly on its own. From index
     * [from] of [cues] (sorted, all untranslated as far as [usable] says), the cues that belong
     * to the same sentence: consecutive, at most [maxGap] s apart, no new speaker, at most
     * [maxCues] cues and [maxChars] characters.
     */
    fun sentenceAt(cues: List<Cue>, from: Int, usable: (Cue) -> Boolean, maxCues: Int = 3, maxChars: Int = 220, maxGap: Double = 1.5): List<Cue> {
        val out = arrayListOf(cues[from])
        var chars = cues[from].text.length
        var i = from + 1
        while (out.size < maxCues && i < cues.size) {
            val last = out.last()
            val next = cues[i]
            if (endsSentence(last.text) || startsSpeaker(next.text) || !usable(next)) break
            // Overlapping cues are two lines on screen at once (often two speakers), not one sentence.
            if (next.start < last.end - 0.05 || next.start - last.end > maxGap) break
            if (chars + next.text.length > maxChars) break
            out += next
            chars += next.text.length + 1
            i++
        }
        return out
    }

    /** The cue texts of one sentence as one line for translation. */
    fun joinSentence(texts: List<String>, language: String?): String =
        if (language in NO_SPACE_LANGUAGES) texts.joinToString("") { it.trim() } else texts.joinToString(" ") { it.trim() }

    /**
     * The English of a sentence shared out over its [weights] (source lengths of its cues):
     * cut at word boundaries near the same share of the text, preferring a cut after
     * punctuation close by. Each part gets at least one word while there are words left.
     */
    fun splitSentence(english: String, weights: List<Int>): List<String> {
        val n = weights.size
        if (n <= 1) return listOf(english.trim())
        val words = english.trim().split(SPACE).filter { it.isNotEmpty() }
        // Too few words to share: each cue shows the whole (short) sentence.
        if (words.size < n) return List(n) { english.trim() }
        val total = weights.sumOf { it.coerceAtLeast(1) }.toDouble()
        val charsBefore = IntArray(words.size + 1)
        for (k in words.indices) charsBefore[k + 1] = charsBefore[k] + words[k].length + 1
        val len = charsBefore[words.size].toDouble()
        val cuts = ArrayList<Int>()
        var acc = 0.0
        var prev = 0
        for (k in 0 until n - 1) {
            acc += weights[k].coerceAtLeast(1)
            val target = acc / total * len
            // Candidate cut positions: after word j (1..), leaving room for the remaining parts.
            val lo = prev + 1
            val hi = words.size - (n - 1 - k)
            var best = lo
            var bestCost = Double.MAX_VALUE
            for (j in lo..hi) {
                var cost = kotlin.math.abs(charsBefore[j] - target) / len
                val w = words[j - 1]
                if (w.last() in ",;:" || w.trimEnd { it in CLOSERS }.lastOrNull()?.let { it in SENTENCE_END } == true) cost -= 0.08
                if (cost < bestCost) {
                    bestCost = cost
                    best = j
                }
            }
            cuts += best
            prev = best
        }
        cuts += words.size
        var start = 0
        return cuts.map { end -> words.subList(start, end).joinToString(" ").also { start = end } }
    }
}
