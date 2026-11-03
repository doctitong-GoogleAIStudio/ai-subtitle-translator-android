package com.aivicventures.subtitletranslator.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The running live session as the screens see it; written by the service and the engine. */
object LiveSession {
    enum class Phase { IDLE, STARTING, LOADING, LISTENING, PAUSED, ENDED, ERROR }

    data class Line(val text: String, val provisional: Boolean)

    data class State(
        val phase: Phase = Phase.IDLE,
        val mode: AudioMode? = null,
        val quality: Quality = Quality.FAST,
        val model: String = "",
        /** "M2M100" or "Whisper": what turns the speech into English. */
        val translator: String = "",
        val captureActive: Boolean = false,
        /** Sound is arriving (above silence). */
        val hearing: Boolean = false,
        /** Media is playing but the capture gets only digital silence: the app does not allow capture. */
        val captureBlocked: Boolean = false,
        val recognising: Boolean = false,
        val overlayActive: Boolean = false,
        /** Most frequent language of the recent lines, its share ("8 of 10"). */
        val language: String = "",
        val languageCount: Int = 0,
        val languageOf: Int = 0,
        /** Lines were skipped because recognition is slower than the speech. */
        val fallingBehind: Boolean = false,
        val noiseSuppressor: Boolean = false,
        val error: String? = null,
        /** A non-error message, e.g. why the session ended. */
        val notice: String? = null,
        val lines: List<Line> = emptyList(),
        val latency: Latency? = null,
        val usage: Usage? = null,
    ) {
        val running: Boolean get() = phase == Phase.STARTING || phase == Phase.LOADING || phase == Phase.LISTENING || phase == Phase.PAUSED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(f: (State) -> State) = _state.update(f)
}
