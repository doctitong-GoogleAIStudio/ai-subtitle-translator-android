package com.aivicventures.subtitletranslator.live

import java.util.Locale

/**
 * A timing-only account of one live session that the user can copy and send when the
 * subtitles did not work as expected: what was heard, how many lines were made, shown or
 * skipped, how late they were, and how busy and hot the phone was. It never holds any
 * recognised or translated text, audio, app names or anything the user watched.
 */
object LiveReport {
    /** Filled while a session runs: capture thread ([listened]) and recognition thread ([worked]). */
    class Counters {
        @Volatile var captureSec = 0.0
            private set
        @Volatile var heardSec = 0.0
            private set
        @Volatile var blockedSec = 0.0
            private set
        @Volatile var jobs = 0
            private set
        @Volatile var warmJobs = 0
            private set
        @Volatile var maxThermal = 0
            private set
        @Volatile var savingReason = ""
            private set
        @Volatile var busyPeak = 0.0
            private set
        @Volatile var provisionalOff = 0
        @Volatile var provisionalSkipped = 0
        private var busySum = 0.0
        private var busyCount = 0

        @Synchronized
        fun listened(sec: Double, hearing: Boolean, blocked: Boolean) {
            captureSec += sec
            if (hearing) heardSec += sec
            if (blocked) blockedSec += sec
        }

        @Synchronized
        fun worked(busy: Double, warm: Boolean, thermal: Int, saving: String) {
            jobs++
            if (warm) warmJobs++
            maxThermal = maxOf(maxThermal, thermal)
            if (saving.isNotEmpty()) savingReason = saving
            busyPeak = maxOf(busyPeak, busy)
            busySum += busy
            busyCount++
        }

        val busyAverage: Double @Synchronized get() = if (busyCount == 0) 0.0 else busySum / busyCount
    }

    /** Everything the report says, gathered by the service when a session ends. */
    data class Facts(
        val app: String,
        val device: String,
        val android: String,
        val cores: Int,
        val ramMb: Long,
        val mode: AudioMode?,
        val quality: Quality,
        val model: String,
        val translator: String,
        val threads: Int,
        val overlayAllowed: Boolean,
        val overlayShown: Boolean,
        val durationSec: Double,
        val usage: Usage?,
        val lagMedianMs: Long?,
        val lagMaxMs: Long?,
        val shownLines: Int,
        val counters: Counters?,
        /** Why it ended: the user's Stop, an error message, or a notice. */
        val ending: String,
    )

    private val THERMAL = listOf("normal", "light", "warm", "hot", "very hot", "emergency", "shutdown")

    fun text(f: Facts): String {
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')
        line("${f.app}: live subtitle report")
        line("Phone: ${f.device}, ${f.android}, ${f.cores} cores, %.1f GB memory".format(Locale.ROOT, f.ramMb / 1024.0))
        line("Source: ${f.mode?.let(::modeName) ?: "-"} · ${if (f.quality == Quality.FAST) "Fast" else "High accuracy"} · " +
            "model ${f.model.ifEmpty { "-" }} · translation ${f.translator.ifEmpty { "-" }} · ${f.threads} threads")
        if (f.mode?.usesOverlay == true) {
            line("Floating subtitles: ${if (f.overlayShown) "shown" else "not shown"} (display over other apps ${if (f.overlayAllowed) "allowed" else "not allowed"})")
        }
        line("Length: ${clock(f.durationSec)}")
        val c = f.counters
        if (c != null && c.captureSec > 0) {
            line("Sound heard: %d %% of %s listened".format(Locale.ROOT, (100 * c.heardSec / c.captureSec).toInt(), clock(c.captureSec)) +
                if (f.mode == AudioMode.INTERNAL) " · silent while media played (capture blocked): %.0f s".format(Locale.ROOT, c.blockedSec) else "")
        } else line("Sound heard: no audio reached the app")
        val u = f.usage
        if (u != null) {
            line("Lines: ${u.lines} made, ${f.shownLines} shown, ${u.droppedLate} too late, ${u.droppedBacklog} skipped while busy")
            line("Speech recognised: %.1f min".format(Locale.ROOT, u.recognisedSec / 60))
        }
        if (f.lagMedianMs != null && f.lagMaxMs != null) {
            line("Delay from end of speech to subtitle: median %.1f s, slowest %.1f s".format(Locale.ROOT, f.lagMedianMs / 1000.0, f.lagMaxMs / 1000.0))
        }
        if (c != null && c.jobs > 0) {
            line("Recognition busy: %d %% on average, %d %% at most · provisional lines paused %d times (%d skipped)".format(Locale.ROOT,
                (100 * c.busyAverage).toInt(), (100 * c.busyPeak).toInt(), c.provisionalOff, c.provisionalSkipped))
            line("Phone heat: hottest ${THERMAL.getOrElse(c.maxThermal) { c.maxThermal.toString() }} · warm for %d %% of lines".format(Locale.ROOT,
                100 * c.warmJobs / c.jobs) + if (c.savingReason.isNotEmpty()) " · power saving: ${c.savingReason}" else "")
        }
        line("Ended: ${f.ending}")
        sb.append("No subtitle text, audio or app names are included.")
        return sb.toString()
    }

    private fun modeName(m: AudioMode) = when (m) {
        AudioMode.INTERNAL -> "Internal audio"
        AudioMode.MICROPHONE -> "Microphone"
        AudioMode.SECOND_DEVICE -> "External device"
        AudioMode.PAGE -> "Browser video sound"
    }

    private fun clock(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }
}
