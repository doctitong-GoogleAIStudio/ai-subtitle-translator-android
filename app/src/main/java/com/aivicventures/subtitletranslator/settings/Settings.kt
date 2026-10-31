package com.aivicventures.subtitletranslator.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Look-ahead modes, as on Windows (docs/ARCHITECTURE.md §2 of the Windows app): how many
 * seconds of subtitles must be ready ahead of the playhead, and the chunk length per job.
 */
enum class Mode(val label: String, val targetSec: Double, val chunkSec: Double) {
    FAST("Fast (5 s ahead)", 5.0, 8.0),
    BALANCED("Balanced (10 s ahead)", 10.0, 15.0),
    HIGH_30("High Accuracy (30 s ahead)", 30.0, 30.0),
    HIGH_45("High Accuracy (45 s ahead)", 45.0, 30.0),
    HIGH_60("High Accuracy (60 s ahead)", 60.0, 30.0),
    FULL("Full Movie (process everything first)", Double.POSITIVE_INFINITY, 30.0);

    /** High Accuracy and Full Movie decode with temperature fallback (slower, fewer broken lines). */
    val thorough: Boolean get() = this != FAST && this != BALANCED
}

/** Source languages offered for manual selection (Whisper codes). Telugu and Tamil were
 *  removed from the product by the publisher and are reported as not supported. */
object Languages {
    val choices: List<Pair<String, String>> = listOf(
        "auto" to "Detect automatically",
        "ar" to "Arabic", "bn" to "Bengali", "zh" to "Chinese", "nl" to "Dutch", "en" to "English",
        "tl" to "Filipino / Tagalog", "fr" to "French", "de" to "German", "el" to "Greek",
        "he" to "Hebrew", "hi" to "Hindi", "id" to "Indonesian", "it" to "Italian",
        "ja" to "Japanese", "ko" to "Korean", "ms" to "Malay", "fa" to "Persian / Farsi",
        "pl" to "Polish", "pt" to "Portuguese", "ru" to "Russian", "es" to "Spanish",
        "sw" to "Swahili", "th" to "Thai", "tr" to "Turkish", "uk" to "Ukrainian",
        "ur" to "Urdu", "vi" to "Vietnamese",
    )
    val unsupported = setOf("te", "ta")
    fun name(code: String): String = choices.firstOrNull { it.first == code }?.second ?: code
}

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString("mode", Mode.BALANCED.name)!!) }.getOrDefault(Mode.BALANCED)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    /** Catalog id of the Whisper model; empty = best installed. */
    var asrModel: String
        get() = prefs.getString("asr_model", "") ?: ""
        set(v) = prefs.edit().putString("asr_model", v).apply()

    var sourceLanguage: String
        get() = prefs.getString("source_language", "auto") ?: "auto"
        set(v) = prefs.edit().putString("source_language", v).apply()

    var threads: Int
        get() = prefs.getInt("threads", defaultThreads())
        set(v) = prefs.edit().putInt("threads", v.coerceIn(1, 8)).apply()

    /** Pause playback while fewer subtitles than the target are ready (off by default, as on Windows). */
    var pauseOnLowBuffer: Boolean
        get() = prefs.getBoolean("pause_on_low_buffer", false)
        set(v) = prefs.edit().putBoolean("pause_on_low_buffer", v).apply()

    /** Keep translating beyond the look-ahead target until the whole movie is done. */
    var continueWholeMovie: Boolean
        get() = prefs.getBoolean("continue_whole_movie", false)
        set(v) = prefs.edit().putBoolean("continue_whole_movie", v).apply()

    /** "auto" = M2M100 with speech models where it measured better (tiny, base), "m2m100" = always when
     *  installed, "whisper" = Whisper's own translation. M2M100 also needs a 64-bit phone. */
    var translator: String
        get() = prefs.getString("translator", "auto") ?: "auto"
        set(v) = prefs.edit().putString("translator", v).apply()

    var subtitleSizeSp: Int
        get() = prefs.getInt("subtitle_size_sp", 20)
        set(v) = prefs.edit().putInt("subtitle_size_sp", v.coerceIn(12, 40)).apply()

    /** Subtitle delay in seconds (positive = later). */
    var subtitleDelay: Float
        get() = prefs.getFloat("subtitle_delay", 0f)
        set(v) = prefs.edit().putFloat("subtitle_delay", v).apply()

    companion object {
        /** Four threads suit big.LITTLE phones (the big cores); fewer on small CPUs. */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}
