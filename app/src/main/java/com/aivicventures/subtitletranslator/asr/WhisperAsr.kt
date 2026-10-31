package com.aivicventures.subtitletranslator.asr

import android.content.Context
import android.util.Log
import com.aivicventures.subtitletranslator.settings.Languages

/** One cleaned recognised line; times in seconds relative to the audio window start. */
data class AsrSegment(
    val start: Double,
    val end: Double,
    val text: String,
    val language: String,
    val avgLogprob: Float,
    val noSpeechProb: Float,
    /** True when [text] is already English (Whisper translated it, or it was spoken in English). */
    val english: Boolean = true,
)

data class AsrResult(val segments: List<AsrSegment>, val language: String, val dropped: Int, val unsupportedLanguage: String?)

/**
 * whisper.cpp speech recognition. Without a text translator Whisper's translate task turns
 * any language into English; with one, Whisper transcribes and the engine translates.
 * Not thread-safe: the engine calls it from one worker.
 */
class WhisperAsr(context: Context, modelPath: String, private val vadPath: String?, val modelId: String) : AutoCloseable {
    private var ptr: Long

    init {
        WhisperNative.ensureLoaded(context)
        val t = System.nanoTime()
        ptr = WhisperNative.initContext(modelPath)
        require(ptr != 0L) { "Could not load the speech model (file damaged or not enough memory)." }
        Log.i(TAG, "loaded $modelId in ${(System.nanoTime() - t) / 1_000_000} ms; ${WhisperNative.systemInfo()}")
    }

    private val langCounts = HashMap<String, Int>()
    private var autoGroups = 0
    private var mainLanguage: String? = null
    @Volatile private var aborted = false
    private val recent = ArrayDeque<AsrSegment>()

    /** Whisper without temperature fallback can loop on one line; keep only the first. */
    private fun isRepeat(text: String, start: Double): Boolean {
        val n = Hallucination.norm(text)
        return n.isNotEmpty() && recent.any { Hallucination.norm(it.text) == n && start - it.end < REPEAT_WINDOW && start >= it.start - 1.0 }
    }

    /**
     * Runs the VAD over the window, then decodes each speech group on its own: exact
     * timestamps and one language per group (films switch languages between lines).
     * Once one language clearly dominates it is locked, which skips detection (half the
     * encoder cost); a locked group Whisper is unsure about is re-checked in auto mode.
     *
     * @param language Whisper code or "auto"
     * @param thorough temperature fallback (High Accuracy / Full Movie, never in catch-up)
     * @param textTranslation languages a text translator will handle: those are transcribed
     *   (segments with english = false); null = Whisper translates everything
     * @return null when aborted
     */
    fun run(
        samples: FloatArray,
        language: String,
        threads: Int,
        thorough: Boolean,
        textTranslation: ((String) -> Boolean)? = null,
    ): AsrResult? {
        aborted = false
        WhisperNative.clearAbort(ptr)
        val groups = speechGroups(samples) ?: return null
        val out = mutableListOf<AsrSegment>()
        var dropped = 0
        var unsupported: String? = null
        val seen = HashMap<String, Int>()
        for ((g0, g1) in groups) {
            if (aborted) return null
            val a = (g0 * RATE).toInt().coerceIn(0, samples.size)
            val b = (g1 * RATE).toInt().coerceIn(a, samples.size)
            if (b - a < RATE / 4) continue
            val chunk = samples.copyOfRange(a, b)
            val fixed = language != "auto"
            val locked = if (fixed) language else mainLanguage
            var (raw, english) = decode(chunk, locked ?: "auto", threads, thorough, textTranslation) ?: return null
            var lang = raw.language.ifEmpty { locked ?: "auto" }
            if (!fixed && locked != null && meanLogprob(raw) < RECHECK_LP) {
                val (alt, altEnglish) = decode(chunk, "auto", threads, thorough, textTranslation) ?: return null
                val altLang = alt.language
                if (altLang.isNotEmpty() && altLang != locked && meanLogprob(alt) >= RECHECK_LP &&
                    meanLogprob(alt) > meanLogprob(raw)
                ) {
                    Log.d(TAG, "group %.1f-%.1f re-checked: $locked -> $altLang".format(g0, g1))
                    raw = alt
                    english = altEnglish
                    lang = altLang
                }
            }
            seen[lang] = (seen[lang] ?: 0) + 1
            if (lang in Languages.unsupported) {
                unsupported = lang
                dropped += raw.texts.size
                continue
            }
            var keptInGroup = 0
            for (i in raw.texts.indices) {
                val text = clean(String(raw.texts[i], Charsets.UTF_8))
                val start = g0 + raw.t0[i] / 1000.0
                val end = minOf(maxOf(g0 + raw.t1[i] / 1000.0, start + 0.2), g1 + 0.5)
                val ns = raw.noSpeechProb[i]
                val lp = raw.avgLogprob[i]
                val reason = when {
                    text.isEmpty() || Hallucination.isSoundTag(text) -> "sound tag"
                    ns > 0.6f && lp < -1.0f -> "no speech"
                    lp < -1.6f -> "unsure"
                    Hallucination.isHallucination(text, end - start) -> "hallucination"
                    isRepeat(text, start) -> "repeat"
                    else -> null
                }
                val kept = if (reason == null) Hallucination.stripHallucinations(text).trim() else ""
                if (reason != null || kept.isEmpty()) {
                    dropped++
                    Log.d(TAG, "drop [$reason] %.2f-%.2f ns=%.2f lp=%.2f: %s".format(start, end, ns, lp, text))
                    continue
                }
                val seg = AsrSegment(start, end, kept, lang, lp, ns, english)
                out.add(seg)
                recent.addLast(seg)
                if (recent.size > 2) recent.removeFirst()
                keptInGroup++
            }
            // Only confident speech counts towards the movie's main language (music and
            // noise are often "detected" as English).
            if (!fixed && locked == null && keptInGroup > 0 && meanLogprob(raw) >= RECHECK_LP) noteLanguage(lang)
        }
        val windowLang = seen.maxByOrNull { it.value }?.key ?: mainLanguage ?: language
        return AsrResult(out, windowLang, dropped, if (out.isEmpty()) unsupported else null)
    }

    /**
     * One whisper_full call for a speech group. Transcribes when the language (given or
     * detected) is English or one [textTranslation] handles, otherwise uses Whisper's
     * translate task. Returns the result and whether its text is already English.
     */
    private fun decode(
        chunk: FloatArray,
        language: String,
        threads: Int,
        thorough: Boolean,
        textTranslation: ((String) -> Boolean)?,
    ): Pair<NativeResult, Boolean>? {
        if (textTranslation == null) {
            return (WhisperNative.transcribe(ptr, chunk, language, true, threads, thorough, null, null) ?: return null) to true
        }
        val transcribe = language == "auto" || language == "en" || textTranslation(language)
        val raw = WhisperNative.transcribe(ptr, chunk, language, !transcribe, threads, thorough, null, null) ?: return null
        if (!transcribe) return raw to true
        val detected = raw.language.ifEmpty { language }
        if (detected == "en") return raw to true
        if (detected != "auto" && textTranslation(detected)) return raw to false
        if (detected in Languages.unsupported) return raw to true // dropped by the caller
        // The text translator does not know this language: let Whisper translate it.
        val translated = WhisperNative.transcribe(ptr, chunk, detected, true, threads, thorough, null, null) ?: return null
        return translated to true
    }

    /** Speech regions merged into groups (gap <= 1 s, <= 25 s each), padded; whole window if no VAD. */
    private fun speechGroups(samples: FloatArray): List<Pair<Double, Double>>? {
        val len = samples.size.toDouble() / RATE
        val vad = vadPath ?: return listOf(0.0 to len)
        val r = WhisperNative.detectSpeech(ptr, vad, samples) ?: return listOf(0.0 to len)
        if (aborted) return null
        return groupRegions((0 until r.size / 2).map { r[2 * it].toDouble() to r[2 * it + 1].toDouble() }, len)
    }

    private fun noteLanguage(lang: String) {
        if (lang.isEmpty() || lang == "auto") return
        langCounts[lang] = (langCounts[lang] ?: 0) + 1
        autoGroups++
        val (top, n) = langCounts.maxByOrNull { it.value } ?: return
        if (n >= LOCK_MIN && n >= LOCK_SHARE * autoGroups) {
            mainLanguage = top
            Log.i(TAG, "main language locked: $top ($n of $autoGroups groups)")
        }
    }

    fun abort() {
        aborted = true
        if (ptr != 0L) WhisperNative.requestAbort(ptr)
    }

    override fun close() {
        if (ptr != 0L) WhisperNative.freeContext(ptr)
        ptr = 0
    }

    companion object {
        private const val TAG = "WhisperAsr"
        private const val RATE = 16_000
        private const val GROUP_GAP = 1.0
        private const val GROUP_MAX = 25.0
        private const val GROUP_PAD = 0.2
        private const val RECHECK_LP = -0.8f
        private const val LOCK_MIN = 3
        private const val LOCK_SHARE = 0.7
        private const val REPEAT_WINDOW = 10.0

        private fun meanLogprob(r: NativeResult): Float {
            var sum = 0.0
            var n = 0
            for (i in r.avgLogprob.indices) {
                sum += r.avgLogprob[i] * r.nTokens[i]
                n += r.nTokens[i]
            }
            return if (n == 0) -10f else (sum / n).toFloat()
        }

        /** Merges sorted speech regions (seconds) into decode groups within [0, len]. */
        fun groupRegions(regions: List<Pair<Double, Double>>, len: Double): List<Pair<Double, Double>> {
            val out = mutableListOf<Pair<Double, Double>>()
            var cur: Pair<Double, Double>? = null
            for ((t0, t1) in regions.sortedBy { it.first }) {
                val c = cur
                cur = if (c != null && t0 - c.second <= GROUP_GAP && t1 - c.first <= GROUP_MAX) {
                    c.first to maxOf(c.second, t1)
                } else {
                    if (c != null) out.add(c)
                    t0 to t1
                }
            }
            cur?.let { out.add(it) }
            return out.map { (a, b) -> maxOf(0.0, a - GROUP_PAD) to minOf(len, b + GROUP_PAD) }
        }
        private val SPACES = Regex("""\s+""")
        private val MISSING_SPACE = Regex("""([a-z][.!?])([A-Z])""")

        /** Whitespace squashed; "…target point.Have you…" gets its missing space back. */
        fun clean(text: String): String =
            MISSING_SPACE.replace(text.replace(SPACES, " ").trim(), "$1 $2").trim().removePrefix("- ").trim()
    }
}
