package com.aivicventures.subtitletranslator.live

/** Where the live audio comes from. */
enum class AudioMode {
    /** Other apps' sound through AudioPlaybackCapture (Android 10+, MediaProjection consent);
     *  only apps that allow capture can be heard. */
    INTERNAL,

    /** The microphone, subtitles in the floating overlay. */
    MICROPHONE,

    /** The microphone listening to a TV or another device; big subtitles in this app. */
    SECOND_DEVICE,

    /** The built-in browser's video sound, tapped in the page; subtitles drawn in the browser.
     *  No microphone, no screen capture, no foreground service. */
    PAGE;

    val usesMicrophone: Boolean get() = this == MICROPHONE || this == SECOND_DEVICE
    val usesOverlay: Boolean get() = this == INTERNAL || this == MICROPHONE

    companion object {
        /** The sources offered on the Live screen (the page source lives in the browser). */
        val EXTERNAL = listOf(INTERNAL, MICROPHONE, SECOND_DEVICE)
    }
}

/**
 * Fast shows provisional subtitles while a line is still being spoken and ends lines on short
 * pauses; High Accuracy waits for longer pauses, decodes with temperature fallback and gives
 * Whisper more context.
 */
enum class Quality(
    /** Silence that ends a line. */
    val endSilenceSec: Double,
    /** Longest line before it is cut at its quietest point. */
    val maxUtteranceSec: Double,
    /** Seconds of new speech between provisional decodes; 0 = none. */
    val partialEverySec: Double,
    /** whisper.cpp temperature fallback (slower, fewer broken lines). */
    val thorough: Boolean,
    /** Recent lines kept as context for Whisper's prompt. */
    val contextLines: Int,
) {
    FAST(0.5, 6.0, 1.6, false, 2),
    HIGH_ACCURACY(0.9, 12.0, 0.0, true, 4),
}

/** Overlay look, read from [com.aivicventures.subtitletranslator.settings.LiveSettings]. */
data class Appearance(
    val sizeSp: Int = 22,
    val color: Int = 0xFFFFFFFF.toInt(),
    val bold: Boolean = true,
    val outline: Boolean = true,
    /** Background opacity 0–100 %. */
    val backgroundPercent: Int = 45,
    /** Bottom edge of the subtitles as a share of the screen height. */
    val position: Float = 0.9f,
    val maxLines: Int = 2,
)
