package com.aivicventures.subtitletranslator.live

/**
 * Short-term memory of one live session, used as context for recognition and to keep
 * names consistent. Nothing is saved: [clear] runs when the session ends.
 *
 * - Recent lines (a sliding window of [maxLines] lines from the last [WINDOW_SEC] s) become
 *   Whisper's prompt, which carries the conversation's vocabulary and spelling into the
 *   next line. The prompt is capped at [maxPromptChars] so it stays cheap and cannot drown
 *   the audio.
 * - Names: capitalised words inside English sentences (not at their start) seen at least
 *   twice. [harmonize] rewrites a near miss of a known name ("Xiaomin" for "Xiaoming")
 *   to the spelling seen most.
 */
class ContextMemory(var maxLines: Int = 2, private val maxPromptChars: Int = 160) {
    private class Entry(val time: Double, val source: String, val english: String, val language: String)

    private val recent = ArrayDeque<Entry>()
    private val nameCounts = HashMap<String, Int>()

    @Synchronized
    fun add(time: Double, source: String, english: String, language: String) {
        recent.addLast(Entry(time, source.trim(), english.trim(), language))
        while (recent.size > maxOf(1, maxLines)) recent.removeFirst()
        for (n in extractNames(english)) nameCounts[n] = (nameCounts[n] ?: 0) + 1
        if (nameCounts.size > MAX_NAMES * 4) {
            nameCounts.entries.sortedBy { it.value }.take(nameCounts.size - MAX_NAMES * 2).forEach { nameCounts.remove(it.key) }
        }
    }

    /**
     * Prompt for the next line at [now]: recent source-language lines when Whisper
     * transcribes [language], recent English lines (with the known names) when it translates.
     * Null when there is no recent context.
     */
    @Synchronized
    fun prompt(language: String, englishOutput: Boolean, now: Double): String? {
        val fresh = recent.filter { now - it.time <= WINDOW_SEC }
        val text = if (englishOutput) {
            val lines = fresh.map { it.english }.filter { it.isNotEmpty() }
            val joined = lines.joinToString(" ")
            val missing = names().filter { it !in joined }
            (if (missing.isNotEmpty()) missing.joinToString(", ") + ". " else "") + joined
        } else {
            fresh.filter { it.language == language && it.source.isNotEmpty() }.joinToString(" ") { it.source }
        }.trim()
        if (text.isEmpty()) return null
        return if (text.length <= maxPromptChars) text else text.takeLast(maxPromptChars).let { t ->
            // Start at a word boundary where there is one.
            val sp = t.indexOf(' ')
            if (sp in 1..20) t.substring(sp + 1) else t
        }
    }

    /** Names seen at least twice, most frequent first. */
    @Synchronized
    fun names(): List<String> = nameCounts.filter { it.value >= 2 }.entries
        .sortedByDescending { it.value }.take(MAX_NAMES).map { it.key }

    /** [text] with near misses of known names spelled the usual way. */
    fun harmonize(text: String): String {
        val known = names().flatMap { it.split(' ') }.filter { it.length >= 5 }.distinct()
        if (known.isEmpty()) return text
        return WORD.replace(text) { m ->
            val w = m.value
            if (w.length < 5 || !w[0].isUpperCase() || w in known) return@replace w
            known.firstOrNull { k -> k[0] == w[0] && kotlin.math.abs(k.length - w.length) <= 1 && editDistance(k, w) <= 1 } ?: w
        }
    }

    @Synchronized
    fun clear() {
        recent.clear()
        nameCounts.clear()
    }

    companion object {
        const val WINDOW_SEC = 30.0
        private const val MAX_NAMES = 8
        private val WORD = Regex("""\p{L}[\p{L}'’-]*""")
        private val SENTENCES = Regex("""(?<=[.!?…])\s+""")
        private val NOT_NAMES = setOf(
            "I", "I'm", "I'll", "I've", "I'd", "OK", "Okay", "Mr", "Mrs", "Ms", "Dr", "Sir", "Madam",
            "Mom", "Dad", "God", "Oh", "Hey", "Yes", "No", "Please", "Sorry", "Thank", "Thanks",
        )

        /** Runs of capitalised words after the first word of each sentence ("… told Xiao Ming"). */
        fun extractNames(english: String): List<String> {
            val out = mutableListOf<String>()
            for (sentence in SENTENCES.split(english.trim())) {
                val words = WORD.findAll(sentence).map { it.value }.toList()
                var run = mutableListOf<String>()
                fun close() {
                    if (run.isNotEmpty()) out.add(run.joinToString(" "))
                    run = mutableListOf()
                }
                for ((k, w) in words.withIndex()) {
                    val cap = k > 0 && w.length >= 2 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() } && w !in NOT_NAMES
                    if (cap) run.add(w) else close()
                }
                close()
            }
            return out
        }

        fun editDistance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]
                d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}
