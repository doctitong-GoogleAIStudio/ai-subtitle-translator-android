package com.aivicventures.subtitletranslator.browser

/**
 * English subtitles made ahead of a web video and shown by video time: from the video's own
 * captions ([CaptionTranslator]) or from the sound its player has already loaded ([SoundAhead]).
 * Smart Delay holds the video until enough of them is ready.
 */
interface AheadSubtitles : AutoCloseable {
    /** Video position the work goes forward from. */
    var position: Double

    /** The English for video time [t], and whether a line due then is still missing. */
    fun textAt(t: Double): Pair<String, Boolean>

    /** Seconds after [now] that are ready without a gap, at most [horizon]. */
    fun readyAhead(now: Double, horizon: Double): Double

    /** Still working ahead, so Smart Delay may hold the video for it. */
    val holdable: Boolean
}
