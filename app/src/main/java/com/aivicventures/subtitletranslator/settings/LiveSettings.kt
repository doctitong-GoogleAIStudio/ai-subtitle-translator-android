package com.aivicventures.subtitletranslator.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.aivicventures.subtitletranslator.live.Appearance
import com.aivicventures.subtitletranslator.live.AudioMode
import com.aivicventures.subtitletranslator.live.Quality

/** Settings of Live Floating Subtitles, kept apart from the movie player's ([Settings]). */
class LiveSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("live", Context.MODE_PRIVATE)

    var sourceLanguage: String
        get() = prefs.getString("source_language", "auto") ?: "auto"
        set(v) = prefs.edit().putString("source_language", v).apply()

    /** Internal audio needs Android 10; older phones start with the microphone. */
    var audioMode: AudioMode
        get() = runCatching { AudioMode.valueOf(prefs.getString("audio_mode", null)!!) }
            .getOrDefault(if (Build.VERSION.SDK_INT >= 29) AudioMode.INTERNAL else AudioMode.MICROPHONE)
        set(v) = prefs.edit().putString("audio_mode", v.name).apply()

    var quality: Quality
        get() = runCatching { Quality.valueOf(prefs.getString("quality", Quality.FAST.name)!!) }.getOrDefault(Quality.FAST)
        set(v) = prefs.edit().putString("quality", v.name).apply()

    /** Catalog id of the Whisper model for live use; empty = automatic (see LiveEngine.chooseModel). */
    var asrModel: String
        get() = prefs.getString("asr_model", "") ?: ""
        set(v) = prefs.edit().putString("asr_model", v).apply()

    /** "auto", "m2m100" or "whisper", as in the movie player's setting. */
    var translator: String
        get() = prefs.getString("translator", "auto") ?: "auto"
        set(v) = prefs.edit().putString("translator", v).apply()

    /** Recent lines as context for the next one (names, wording); never saved. */
    var contextMemory: Boolean
        get() = prefs.getBoolean("context_memory", true)
        set(v) = prefs.edit().putBoolean("context_memory", v).apply()

    /** Seconds after the speech at which a line appears, if it is ready by then. */
    var delaySec: Float
        get() = prefs.getFloat("delay", 0f)
        set(v) = prefs.edit().putFloat("delay", v.coerceIn(DELAY_MIN, DELAY_MAX)).apply()

    var sizeSp: Int
        get() = prefs.getInt("size_sp", 22)
        set(v) = prefs.edit().putInt("size_sp", v.coerceIn(SIZE_MIN, SIZE_MAX)).apply()

    /** Index into [COLORS]. */
    var colorIndex: Int
        get() = prefs.getInt("color", 0).coerceIn(0, COLORS.size - 1)
        set(v) = prefs.edit().putInt("color", v).apply()

    var bold: Boolean
        get() = prefs.getBoolean("bold", true)
        set(v) = prefs.edit().putBoolean("bold", v).apply()

    var outline: Boolean
        get() = prefs.getBoolean("outline", true)
        set(v) = prefs.edit().putBoolean("outline", v).apply()

    var backgroundPercent: Int
        get() = prefs.getInt("background", 45)
        set(v) = prefs.edit().putInt("background", v.coerceIn(0, 100)).apply()

    /** Bottom edge of the subtitles as a share of the screen height. */
    var position: Float
        get() = prefs.getFloat("position", 0.9f)
        set(v) = prefs.edit().putFloat("position", v.coerceIn(POSITION_MIN, POSITION_MAX)).apply()

    var maxLines: Int
        get() = prefs.getInt("max_lines", 2).coerceIn(1, 2)
        set(v) = prefs.edit().putInt("max_lines", v.coerceIn(1, 2)).apply()

    /** Android's noise suppressor on the microphone, where the phone has one. */
    var noiseSuppression: Boolean
        get() = prefs.getBoolean("noise_suppression", true)
        set(v) = prefs.edit().putBoolean("noise_suppression", v).apply()

    /** Software gain on the microphone, 0.5–4×. */
    var micGain: Float
        get() = prefs.getFloat("mic_gain", 1f)
        set(v) = prefs.edit().putFloat("mic_gain", v.coerceIn(0.5f, 4f)).apply()

    /** Stop by itself after this many minutes without speech (0 = never), to save battery. */
    var autoStopMinutes: Int
        get() = prefs.getInt("auto_stop", 30)
        set(v) = prefs.edit().putInt("auto_stop", v).apply()

    /** Developer option: per-stage latency next to the subtitles. */
    var showLatency: Boolean
        get() = prefs.getBoolean("show_latency", false)
        set(v) = prefs.edit().putBoolean("show_latency", v).apply()

    /** Developer option: encode only as much audio as the line holds (much faster for short lines than Whisper's fixed 30 s). */
    var shortContext: Boolean
        get() = prefs.getBoolean("short_context", true)
        set(v) = prefs.edit().putBoolean("short_context", v).apply()

    /** Floating button: vertical place (share of the screen height) and side. */
    var bubbleY: Float
        get() = prefs.getFloat("bubble_y", 0.35f)
        set(v) = prefs.edit().putFloat("bubble_y", v.coerceIn(0f, 1f)).apply()
    var bubbleLeft: Boolean
        get() = prefs.getBoolean("bubble_left", false)
        set(v) = prefs.edit().putBoolean("bubble_left", v).apply()

    /** Timing-only report of the last session ([com.aivicventures.subtitletranslator.live.LiveReport]); empty before the first. */
    var lastReport: String
        get() = prefs.getString("last_report", "") ?: ""
        set(v) = prefs.edit().putString("last_report", v).apply()

    fun appearance() = Appearance(sizeSp, COLORS[colorIndex].second, bold, outline, backgroundPercent, position, maxLines)

    companion object {
        const val DELAY_MIN = -3f
        const val DELAY_MAX = 10f
        const val SIZE_MIN = 14
        const val SIZE_MAX = 40
        const val POSITION_MIN = 0.15f
        const val POSITION_MAX = 0.97f
        val COLORS = listOf(
            "White" to 0xFFFFFFFF.toInt(),
            "Yellow" to 0xFFFFE14D.toInt(),
            "Cyan" to 0xFF7FE9FF.toInt(),
            "Green" to 0xFF9CFF8F.toInt(),
        )
        val AUTO_STOP = listOf(0, 10, 30, 60)
    }
}
