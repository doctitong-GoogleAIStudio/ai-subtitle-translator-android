package com.aivicventures.subtitletranslator

import com.aivicventures.subtitletranslator.asr.Hallucination
import com.aivicventures.subtitletranslator.asr.WhisperAsr
import com.aivicventures.subtitletranslator.audio.Resampler
import com.aivicventures.subtitletranslator.buffering.Coverage
import com.aivicventures.subtitletranslator.subtitles.Layout
import com.aivicventures.subtitletranslator.subtitles.Srt
import com.aivicventures.subtitletranslator.subtitles.Subtitle
import com.aivicventures.subtitletranslator.translate.M2M100Translator
import com.aivicventures.subtitletranslator.translate.SentencePieceBpe
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class CoreTest {
    @Test fun hallucinationsRejectedRealDialogueKept() {
        assertTrue(Hallucination.isHallucination("Thanks for watching!", 2.0))
        assertTrue(Hallucination.isHallucination("다음 영상에서 만나요", 2.0))
        assertTrue(Hallucination.isHallucination("ha ha ha ha ha ha ha ha ha ha", 3.0))
        assertTrue(Hallucination.isHallucination("으아아아아아!", 1.0))
        assertTrue(Hallucination.isHallucination("Subtitles by the Amara.org community", 3.0))
        assertFalse(Hallucination.isHallucination("Where were you last night?", 2.0))
        assertFalse(Hallucination.isHallucination("Mama", 0.5))
        assertFalse(Hallucination.isHallucination("Thanks for watching my kids, I owe you one and I mean it.", 3.0))
    }

    @Test fun signOffStrippedFromRealLine() {
        assertEquals("We have to go now.", Hallucination.stripHallucinations("We have to go now. Thanks for watching!"))
        assertEquals("Run! Get out of here!", Hallucination.stripHallucinations("Run! Get out of here!"))
    }

    @Test fun soundTags() {
        assertTrue(Hallucination.isSoundTag("[Music]"))
        assertTrue(Hallucination.isSoundTag("(upbeat music)"))
        assertTrue(Hallucination.isSoundTag("♪ ♪"))
        assertFalse(Hallucination.isSoundTag("I (really) mean it"))
    }

    @Test fun cleanRestoresMissingSpace() {
        assertEquals("the target point. Have you seen it?", WhisperAsr.clean("  the target point.Have you   seen it? "))
        assertEquals("Amara.org", WhisperAsr.clean("Amara.org"))
    }

    @Test fun sentencePieceNormalisation() {
        assertEquals("▁a▁b", SentencePieceBpe.normalize("  a \t\n b  "))
        assertEquals("▁ABC123", SentencePieceBpe.normalize("ＡＢＣ１２３"))
        assertEquals("", SentencePieceBpe.normalize(" ​ "))
        assertEquals("Hello world", SentencePieceBpe.decode(listOf("▁Hello", "▁wor", "ld")))
    }

    /** Same pieces as the reference sentencepiece library (model from test-media, if downloaded). */
    @Test fun sentencePieceMatchesReference() {
        val model = java.io.File("../test-media/m2m100-ct2/sentencepiece.bpe.model")
        Assume.assumeTrue("M2M100 tokenizer not downloaded", model.exists())
        val spm = SentencePieceBpe(model.readBytes())
        val lines = javaClass.getResourceAsStream("/spm_gold.txt")!!.bufferedReader(Charsets.UTF_8).readLines().filter { !it.startsWith("#") }
        assertTrue(lines.size >= 30)
        for (i in lines.indices step 2) {
            val text = String(java.util.Base64.getDecoder().decode(lines[i]), Charsets.UTF_8)
            val expected = lines[i + 1].split(' ').filter { it.isNotEmpty() }
            assertEquals("pieces for: $text", expected, spm.encode(text))
        }
    }

    @Test fun m2m100LanguageCodes() {
        assertEquals("ko", M2M100Translator.sourceCode("ko"))
        assertEquals("jv", M2M100Translator.sourceCode("jw"))
        assertNull(M2M100Translator.sourceCode("en"))
        assertNull(M2M100Translator.sourceCode("haw"))
    }

    @Test fun speechRegionsGrouped() {
        val g = WhisperAsr.groupRegions(listOf(0.5 to 2.0, 2.5 to 4.0, 6.0 to 8.0, 8.5 to 31.9), 32.0)
        assertEquals(3, g.size)
        assertEquals(0.3, g[0].first, 1e-9)
        assertEquals(4.2, g[0].second, 1e-9)
        assertEquals(5.8, g[1].first, 1e-9)
        assertEquals(8.3, g[2].first, 1e-9)
        assertEquals(32.0, g[2].second, 1e-9)
    }

    @Test fun coverageMergesAndFindsGaps() {
        val c = Coverage()
        c.add(0.0, 10.0)
        c.add(20.0, 30.0)
        assertEquals(10.0, c.firstUncovered(5.0), 1e-9)
        assertEquals(15.0, c.firstUncovered(15.0), 1e-9)
        assertEquals(30.0, c.firstUncovered(20.0), 1e-9)
        assertEquals(20.0, c.nextCoveredStart(12.0)!!, 1e-9)
        assertEquals(5.0, c.reserve(5.0), 1e-9)
        c.add(10.0, 20.0)
        assertEquals(listOf(0.0 to 30.0), c.spans())
        assertNull(c.nextCoveredStart(1.0))
        assertEquals(30.0, c.total(), 1e-9)
    }

    @Test fun layoutKeepsTwoLinesOf42() {
        val long = "This is a rather long sentence that will certainly not fit on a single subtitle line, and it goes on and on for a while longer still."
        val cues = Layout.buildCues(listOf(Subtitle(1, 10.0, 18.0, long, "", "ko")))
        assertTrue(cues.size >= 2)
        for (c in cues) {
            val lines = c.text.split("\n")
            assertTrue(lines.size <= 2)
            lines.forEach { assertTrue("line too long: $it", it.length <= 42) }
            assertTrue(c.end - c.start <= 7.0 + 1e-6)
        }
        for (i in 1 until cues.size) assertTrue(cues[i].start >= cues[i - 1].end - 1e-6)
    }

    @Test fun shortCueExtendedButNoOverlap() {
        val cues = Layout.buildCues(listOf(
            Subtitle(1, 1.0, 1.2, "Hey!", "", "ko"),
            Subtitle(2, 1.6, 3.0, "What?", "", "ko"),
        ))
        assertEquals(2, cues.size)
        assertTrue(cues[0].end <= 1.6 - 0.08 + 1e-6)
        assertTrue(cues[0].end > 1.2)
    }

    @Test fun srtTimestamps() {
        assertEquals("01:02:03,450", Srt.timestamp(3723.45))
        val text = Srt.write(Layout.buildCues(listOf(Subtitle(1, 0.5, 2.0, "Hello there.", "", "es"))))
        assertTrue(text.startsWith("1\n00:00:00,500 --> 00:00:02,000\nHello there.\n"))
    }

    @Test fun resamplerKeepsToneAndLength() {
        val from = 48_000
        val src = FloatArray(from) { sin(2 * PI * 440 * it / from).toFloat() * 0.5f }
        val out = Resampler.resample(src, from, 16_000, 16_000)
        assertEquals(16_000, out.size)
        val rms = sqrt(out.copyOfRange(1000, 15000).map { it * it }.average())
        assertEquals(0.5 / sqrt(2.0), rms, 0.02)
        // Phase preserved: sample n of the output corresponds to sample 3n of the input.
        assertTrue(abs(out[8000] - src[24000]) < 0.02)
        // 44.1 kHz input
        val src2 = FloatArray(44_100) { sin(2 * PI * 1000 * it / 44_100).toFloat() }
        val out2 = Resampler.resample(src2, 44_100, 16_000, 16_000)
        assertTrue(abs(out2[8000] - sin(2 * PI * 1000 * 8000 / 16_000).toFloat()) < 0.03)
    }

    @Test fun resamplerOddRateUsesDirectPath() {
        val from = 47_999
        val src = FloatArray(from) { sin(2 * PI * 440 * it / from).toFloat() }
        val out = Resampler.resample(src, from, 16_000, 16_000)
        assertTrue(abs(out[8000] - sin(2 * PI * 440 * 8000 / 16_000).toFloat()) < 0.03)
    }

    @Test fun resamplerRemovesAliasing() {
        // 12 kHz tone is above the 8 kHz Nyquist limit of 16 kHz audio: must be filtered out.
        val src = FloatArray(48_000) { sin(2 * PI * 12_000 * it / 48_000).toFloat() }
        val out = Resampler.resample(src, 48_000, 16_000, 16_000)
        val rms = sqrt(out.copyOfRange(1000, 15000).map { it * it }.average())
        assertTrue("alias rms $rms", rms < 0.02)
    }
}
