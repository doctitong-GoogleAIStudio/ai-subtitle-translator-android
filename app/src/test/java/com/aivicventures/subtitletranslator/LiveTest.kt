package com.aivicventures.subtitletranslator

import com.aivicventures.subtitletranslator.live.ContextMemory
import com.aivicventures.subtitletranslator.live.LiveStats
import com.aivicventures.subtitletranslator.live.Quality
import com.aivicventures.subtitletranslator.live.SubtitleTimeline
import com.aivicventures.subtitletranslator.live.UtteranceSegmenter
import com.aivicventures.subtitletranslator.live.UtteranceSegmenter.Final
import com.aivicventures.subtitletranslator.live.UtteranceSegmenter.Partial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LiveTest {
    private val rate = 16_000

    private fun silence(sec: Double) = FloatArray((sec * rate).toInt())

    /** A loud 220 Hz tone standing in for speech. */
    private fun tone(sec: Double, amp: Float = 0.3f) = FloatArray((sec * rate).toInt()) { (amp * sin(2 * PI * 220 * it / rate)).toFloat() }

    /** Feeds [audio] in 100 ms blocks, as the capture thread does. */
    private fun feed(seg: UtteranceSegmenter, vararg audio: FloatArray): List<UtteranceSegmenter.Event> {
        val all = audio.fold(FloatArray(0)) { acc, a -> acc + a }
        val out = mutableListOf<UtteranceSegmenter.Event>()
        var i = 0
        while (i < all.size) {
            val n = minOf(rate / 10, all.size - i)
            out += seg.push(all, i, n)
            i += n
        }
        return out
    }

    @Test fun segmenterFindsOneUtteranceWithPreRoll() {
        val seg = UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.FAST))
        val events = feed(seg, silence(1.0), tone(2.0), silence(1.0))
        val finals = events.filterIsInstance<Final>()
        assertEquals(1, finals.size)
        val f = finals[0]
        // Starts up to 0.3 s before the onset (pre-roll), ends shortly after it.
        assertTrue("start ${f.startSec}", f.startSec in 0.65..1.0)
        assertTrue("end ${f.endSec}", f.endSec in 3.0..3.3)
        assertEquals(((f.endSec - f.startSec) * rate).toInt().toDouble(), f.samples.size.toDouble(), rate * 0.02)
    }

    @Test fun segmenterTimesFollowTheSessionClock() {
        // Capture began 30 s into the session (after model loading): times must be session times.
        val seg = UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.FAST))
        seg.originSec = 30.0
        val f = feed(seg, silence(1.0), tone(2.0), silence(1.0)).filterIsInstance<Final>().single()
        assertTrue("start ${f.startSec}", f.startSec in 30.65..31.0)
    }

    @Test fun segmenterWaitsLongerInHighAccuracy() {
        val fast = feed(UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.FAST)), tone(1.0), silence(0.7), tone(1.0), silence(1.5))
        val high = feed(UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.HIGH_ACCURACY)), tone(1.0), silence(0.7), tone(1.0), silence(1.5))
        assertEquals(2, fast.filterIsInstance<Final>().size) // a 0.7 s pause ends a line in Fast…
        assertEquals(1, high.filterIsInstance<Final>().size) // …but not in High Accuracy (0.9 s)
    }

    @Test fun segmenterCutsLongSpeechWithoutLosingAudio() {
        val seg = UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.FAST))
        val finals = (feed(seg, tone(14.0)) + seg.flush()).filterIsInstance<Final>()
        assertTrue("${finals.size} parts", finals.size >= 3)
        for (f in finals) assertTrue("part of ${f.endSec - f.startSec} s", f.endSec - f.startSec <= Quality.FAST.maxUtteranceSec + 0.05)
        for (k in 1 until finals.size) assertEquals(finals[k - 1].endSec, finals[k].startSec, 1e-9) // contiguous: nothing dropped at a cut
        assertEquals(14.0, finals.last().endSec - finals.first().startSec, 0.05)
    }

    @Test fun segmenterDropsClicksAndReportsDigitalSilence() {
        val seg = UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.FAST))
        assertTrue(feed(seg, silence(0.5), tone(0.1), silence(1.0)).isEmpty())
        feed(seg, silence(5.0))
        assertTrue(seg.digitalSilenceSec >= 4.9)
        feed(seg, tone(0.1, 0.001f))
        assertEquals(0.0, seg.digitalSilenceSec, 0.0)
    }

    @Test fun segmenterPartialsShareTheFinalId() {
        val seg = UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.FAST))
        val events = feed(seg, tone(5.0), silence(1.0))
        val partials = events.filterIsInstance<Partial>()
        val final = events.filterIsInstance<Final>().single()
        assertTrue(partials.size >= 2)
        assertTrue(partials.all { it.id == final.id })
        assertTrue(partials.first().endSec - partials.first().startSec >= 1.5)
        // High Accuracy shows no provisional lines.
        val high = UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.HIGH_ACCURACY))
        assertTrue(feed(high, tone(5.0), silence(1.0)).none { it is Partial })
    }

    @Test fun segmenterResetDropsBufferedSpeech() {
        val seg = UtteranceSegmenter(UtteranceSegmenter.Params.of(Quality.FAST))
        feed(seg, tone(1.0))
        seg.reset()
        assertTrue(feed(seg, silence(1.0)).isEmpty())
    }

    @Test fun timelineDelayAndArrival() {
        val t = SubtitleTimeline(delaySec = 0.0)
        assertTrue(t.offer(1, 10.0, 12.0, "Where are you going?", false, now = 13.0))
        assertEquals("Where are you going?", t.visible(13.0).single().text)
        // With a delay longer than the latency, the line waits until speech start + delay.
        val d = SubtitleTimeline(delaySec = 5.0)
        d.offer(1, 10.0, 12.0, "Where are you going?", false, now = 13.0)
        assertTrue(d.visible(14.0).isEmpty())
        assertEquals(1, d.visible(15.1).size)
        // A negative delay cannot show a line before it arrived.
        val n = SubtitleTimeline(delaySec = -3.0)
        n.offer(1, 10.0, 12.0, "Hello.", false, now = 13.0)
        assertEquals(13.0, n.visible(13.0).single().showAt, 1e-9)
    }

    @Test fun timelineDropsStaleLinesAndExpiresShownOnes() {
        val t = SubtitleTimeline()
        assertFalse(t.offer(1, 0.0, 2.0, "Too late.", false, now = 2.0 + SubtitleTimeline.MAX_LAG + 1))
        assertEquals(1, t.droppedLate)
        t.offer(2, 20.0, 21.0, "Run!", false, now = 22.0)
        assertEquals(1, t.visible(22.5).size)
        assertTrue(t.visible(22.0 + SubtitleTimeline.MIN_SHOW + 0.01).isEmpty())
    }

    @Test fun timelineProvisionalIsReplacedWithoutFlicker() {
        val t = SubtitleTimeline()
        assertTrue(t.offer(1, 0.0, 1.5, "Where", true, now = 1.6))
        assertFalse(t.offer(1, 0.0, 1.8, "Where are", true, now = 1.9)) // too soon after the last update
        assertTrue(t.offer(1, 0.0, 2.4, "Where are", true, now = 2.4))
        assertTrue(t.visible(2.5).single().provisional)
        assertTrue(t.offer(1, 0.0, 3.0, "Where are you going?", false, now = 3.2))
        val v = t.visible(3.3).single()
        assertFalse(v.provisional)
        assertEquals("Where are you going?", v.text)
        assertFalse(t.offer(1, 0.0, 3.0, "Where are you", true, now = 3.5)) // a late snapshot never replaces the final
        // A final with no speech removes the provisional line.
        t.offer(2, 5.0, 6.0, "Hmm", true, now = 6.1)
        t.offer(2, 5.0, 6.0, "", false, now = 6.5)
        assertTrue(t.visible(6.6).none { it.id == 2L })
    }

    @Test fun timelineMergesRepeatsAndKeepsTheLineLimit() {
        val t = SubtitleTimeline(maxLines = 2)
        t.offer(1, 0.0, 1.0, "No!", false, now = 1.2)
        assertFalse(t.offer(2, 1.5, 2.0, "No!", false, now = 2.2))
        assertEquals(1, t.visible(2.3).size)
        t.offer(3, 2.5, 3.0, "Stop.", false, now = 3.1)
        t.offer(4, 3.2, 3.5, "Please.", false, now = 3.6)
        assertEquals(listOf("Stop.", "Please."), t.visible(3.7).map { it.text })
        t.maxLines = 1
        t.offer(5, 3.8, 4.0, "Now.", false, now = 4.1)
        assertEquals(listOf("Now."), t.visible(4.2).map { it.text })
    }

    @Test fun timelineSplitsLongLinesIntoTimedChunks() {
        val t = SubtitleTimeline(maxLines = 2)
        val long = "I told you a hundred times that we cannot stay here tonight, because the storm is coming and the bridge is already closed by the police."
        t.offer(1, 0.0, 6.0, long, false, now = 7.0)
        val first = t.visible(7.0)
        assertEquals(1, first.size)
        assertTrue(first[0].text.length < long.length)
        val second = t.visible(first[0].hideAt + 0.01)
        assertEquals(1, second.single().chunk)
    }

    @Test fun contextMemoryPromptsAndNames() {
        val m = ContextMemory(maxLines = 2, maxPromptChars = 40)
        assertNull(m.prompt("ko", englishOutput = false, now = 0.0))
        m.add(1.0, "어디 가?", "Where are you going, Seojun?", "ko")
        m.add(3.0, "집에.", "Home. Tell Seojun I said hi.", "ko")
        assertEquals("어디 가? 집에.", m.prompt("ko", englishOutput = false, now = 4.0))
        assertNull(m.prompt("ja", englishOutput = false, now = 4.0)) // other language: no context
        assertTrue(m.prompt("ko", englishOutput = true, now = 4.0)!!.length <= 40)
        assertNull(m.prompt("ko", englishOutput = false, now = 3.0 + ContextMemory.WINDOW_SEC + 1)) // too old
        assertEquals(listOf("Seojun"), m.names())
        assertEquals("Seojun waved at me.", m.harmonize("Seojin waved at me."))
        assertEquals("Where is the station?", m.harmonize("Where is the station?"))
        m.add(5.0, "", "Fine.", "ko")
        assertTrue("Seojun" in m.prompt("ko", englishOutput = true, now = 6.0)!!) // English context keeps the name
        m.clear()
        assertTrue(m.names().isEmpty())
        assertNull(m.prompt("ko", englishOutput = false, now = 6.0))
    }

    @Test fun nameExtraction() {
        assertEquals(listOf("Xiao Ming", "Xiao Ming"), ContextMemory.extractNames("I told Xiao Ming to wait. Where is Xiao Ming?"))
        assertTrue(ContextMemory.extractNames("Where are you going? I'm home.").isEmpty())
        assertEquals(1, ContextMemory.editDistance("Seojun", "Seojin"))
        assertEquals(3, ContextMemory.editDistance("kitten", "sitting"))
    }

    @Test fun statsAverageStagesAndMeterUsage() {
        val s = LiveStats()
        s.start(0)
        s.ready(1, endpointMs = 500, queueMs = 0, asrMs = 900, mtMs = 200, translatedLine = true)
        s.ready(2, endpointMs = 500, queueMs = 400, asrMs = 1100, mtMs = 0, translatedLine = false)
        s.recognised(2.5)
        s.recognised(1.5)
        s.shown(1, renderMs = 100, totalMs = 1700)
        s.shown(2, renderMs = 100, totalMs = 2100)
        s.shown(3, renderMs = 100, totalMs = 9999) // never reported ready: ignored
        val l = s.latency()!!
        assertEquals(2, l.samples)
        assertEquals(1900, l.totalMs)
        assertEquals(200, l.queueMs)
        assertEquals(1000, l.recognitionMs)
        val u = s.usage(nowMs = 60_000, droppedLate = 1)
        assertEquals(60.0, u.durationSec, 1e-9)
        assertEquals(4.0, u.recognisedSec, 1e-9)
        assertEquals(2, u.lines)
        assertEquals(1, u.translatedLines)
        assertEquals(1900, u.averageLatencyMs)
        assertEquals(0.0, u.estimatedCost, 0.0)
        assertTrue(abs(u.droppedLate - 1) == 0)
    }

    @Test
    fun sessionReportHasTimingsOnly() {
        val stats = LiveStats().apply { start(0) }
        for (id in 1L..5L) {
            stats.ready(id, 500, 0, 800, 400, true)
            stats.shown(id, 0, id * 1000)
        }
        assertEquals(3000L to 5000L, stats.lagSpread())
        assertEquals(5, stats.shownCount)
        val c = com.aivicventures.subtitletranslator.live.LiveReport.Counters().apply {
            listened(10.0, true, false)
            listened(10.0, false, true)
            worked(0.5, false, 0, "")
            worked(0.9, true, 3, "phone is hot")
        }
        val text = com.aivicventures.subtitletranslator.live.LiveReport.text(
            com.aivicventures.subtitletranslator.live.LiveReport.Facts(
                app = "App 1.2.0 (8)", device = "Maker Phone", android = "Android 14 (API 34)", cores = 8, ramMb = 6144,
                mode = com.aivicventures.subtitletranslator.live.AudioMode.INTERNAL, quality = Quality.FAST, model = "base",
                translator = "M2M100", threads = 4, overlayAllowed = true, overlayShown = true, durationSec = 754.0,
                usage = stats.usage(754_000, 2), lagMedianMs = 3000, lagMaxMs = 5000, shownLines = 5, counters = c, ending = "stopped by the user",
            ),
        )
        assertTrue(text, "12:34" in text)
        assertTrue(text, "Sound heard: 50 %" in text)
        assertTrue(text, "capture blocked): 10 s" in text)
        assertTrue(text, "5 made, 5 shown, 2 too late" in text)
        assertTrue(text, "median 3.0 s, slowest 5.0 s" in text)
        assertTrue(text, "70 % on average, 90 % at most" in text)
        assertTrue(text, "hottest hot" in text && "phone is hot" in text)
        assertTrue(text, "Floating subtitles: shown" in text)
    }
}
