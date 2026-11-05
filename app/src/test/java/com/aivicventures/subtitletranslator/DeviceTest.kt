package com.aivicventures.subtitletranslator

import com.aivicventures.subtitletranslator.audio.AudioTrackInfo
import com.aivicventures.subtitletranslator.audio.AudioWindowReader
import com.aivicventures.subtitletranslator.audio.FfmpegAudio
import com.aivicventures.subtitletranslator.device.CpuTopology
import com.aivicventures.subtitletranslator.device.PowerMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {
    @Test fun cpuListsParsed() {
        assertEquals(listOf(0, 1, 2, 3, 6), CpuTopology.parseCpuList("0-3,6\n"))
        assertEquals(listOf(7), CpuTopology.parseCpuList("7"))
        assertEquals(emptyList<Int>(), CpuTopology.parseCpuList(""))
    }

    @Test fun fastCoresByCapacityOrFrequency() {
        val cap = CpuTopology.CAPACITY_SHARE
        val freq = CpuTopology.FREQUENCY_SHARE
        // Snapdragon 8 Gen 2 style: 3 efficiency, 4 performance, 1 prime core.
        val sd8g2Freq = (0..2).associateWith { 2_016_000L } + (3..6).associateWith { 2_803_000L } + mapOf(7 to 3_187_000L)
        assertEquals(listOf(3, 4, 5, 6, 7), CpuTopology.fastOf(sd8g2Freq, freq))
        val sd8g2Cap = (0..2).associateWith { 325L } + (3..6).associateWith { 870L } + mapOf(7 to 1024L)
        assertEquals(listOf(3, 4, 5, 6, 7), CpuTopology.fastOf(sd8g2Cap, cap))
        // 2 × A76 at 2.2 GHz + 6 × A55 at 2.0 GHz: frequency cannot tell them apart, capacity can.
        val g99Cap = (0..5).associateWith { 400L } + (6..7).associateWith { 1024L }
        assertEquals(listOf(6, 7), CpuTopology.fastOf(g99Cap, cap))
        // Budget 2+6 phone with clearly lower little-core clocks: frequency is enough.
        val budgetFreq = (0..5).associateWith { 1_800_000L } + (6..7).associateWith { 2_400_000L }
        assertEquals(listOf(6, 7), CpuTopology.fastOf(budgetFreq, freq))
        // All cores the same (emulator, some tablets): all of them.
        assertEquals(listOf(0, 1, 2, 3), CpuTopology.fastOf((0..3).associateWith { 1024L }, cap))
        assertEquals(emptyList<Int>(), CpuTopology.fastOf(emptyMap(), cap))
    }

    @Test fun powerSavingTriggers() {
        assertTrue(PowerMonitor.evaluate(false, false, 80, false, 2).warm)  // less work, all threads
        assertFalse(PowerMonitor.evaluate(false, false, 80, false, 2).saving)
        assertFalse(PowerMonitor.evaluate(false, false, 80, false, 1).warm)
        assertTrue(PowerMonitor.evaluate(false, false, 80, false, 3).warm)
        val normal = PowerMonitor.evaluate(false, false, 80, false, 0)
        assertFalse(normal.saving)
        assertEquals(4, normal.threads(4))

        val setting = PowerMonitor.evaluate(true, false, 80, true, 0)
        assertTrue(setting.saving)
        assertEquals(2, setting.threads(4))
        assertEquals(1, setting.threads(1))

        assertEquals("system power saver", PowerMonitor.evaluate(false, true, 80, false, 0).reason)
        assertEquals("battery 15 %", PowerMonitor.evaluate(false, false, 15, false, 0).reason)
        assertFalse(PowerMonitor.evaluate(false, false, 15, true, 0).saving)  // charging
        assertFalse(PowerMonitor.evaluate(false, false, 16, false, 0).saving)
        assertFalse(PowerMonitor.evaluate(false, false, -1, false, 0).saving)  // level unknown
        assertFalse(PowerMonitor.evaluate(false, false, 80, false, 2).saving)  // moderate heat

        val hot = PowerMonitor.evaluate(false, false, 80, true, PowerMonitor.THERMAL_SEVERE)
        assertTrue(hot.saving && !hot.critical)
        assertEquals("phone is hot", hot.reason)
        val critical = PowerMonitor.evaluate(false, false, 80, true, PowerMonitor.THERMAL_CRITICAL)
        assertTrue(critical.saving && critical.critical)
        assertEquals(1, critical.threads(4))
    }

    private fun track(ordinal: Int, mime: String, lang: String?, decodable: Boolean = true, channels: Int = 2) =
        AudioTrackInfo(index = ordinal + 1, ordinal = ordinal, mime = mime, language = lang, channels = channels, decodable = decodable)

    @Test fun audioTrackDefaults() {
        val tracks = listOf(
            track(0, "audio/vnd.dts", "en", decodable = false, channels = 6),
            track(1, "audio/ac3", "en", channels = 6),
            track(2, "audio/mp4a-latm", "ko"),
        )
        assertEquals(1, AudioTrackInfo.chooseDefault(tracks, null, "auto"))  // first decodable
        assertEquals(2, AudioTrackInfo.chooseDefault(tracks, null, "ko"))    // movie language
        assertEquals(2, AudioTrackInfo.chooseDefault(tracks, null, "ko-KR"))
        assertEquals(1, AudioTrackInfo.chooseDefault(tracks, null, "ja"))    // no Japanese track
        assertEquals(0, AudioTrackInfo.chooseDefault(tracks, 0, "ko"))       // the viewer's choice wins
        assertEquals(1, AudioTrackInfo.chooseDefault(tracks, 9, "auto"))     // stale choice ignored
        assertEquals(0, AudioTrackInfo.chooseDefault(listOf(track(0, "audio/vnd.dts", "en", false)), null, "auto"))
        assertEquals(0, AudioTrackInfo.chooseDefault(emptyList(), null, "auto"))
    }

    @Test fun audioTrackLabels() {
        assertEquals("2. English AC-3 5.1", track(1, "audio/ac3", "en", channels = 6).describe())
        assertEquals("1. Korean AAC stereo", track(0, "audio/mp4a-latm", "ko").describe())
        assertEquals("3. Unknown language DTS", track(2, "audio/vnd.dts", "und", channels = 0).describe())
    }

    @Test fun downmixFavoursDialogue() {
        assertEquals(listOf(0.5f, 0.5f), AudioWindowReader.downmixWeights(2).toList())
        assertEquals(listOf(1f), AudioWindowReader.downmixWeights(1).toList())
        val w51 = AudioWindowReader.downmixWeights(6)
        assertEquals(0.4f, w51[2], 1e-6f)  // centre (dialogue): 0.4 instead of 1/6
        assertEquals(0f, w51[3], 0f)       // LFE
        assertEquals(1f, w51.sum(), 1e-6f)
        assertEquals(1f, AudioWindowReader.downmixWeights(8).sum(), 1e-6f)
        assertEquals(listOf(0.25f, 0.25f, 0.25f, 0.25f), AudioWindowReader.downmixWeights(4).toList())
    }

    @Test fun ffmpegCodecNamesMatchMedia3() {
        assertEquals("ac3", FfmpegAudio.codecName("audio/ac3"))
        assertEquals("eac3", FfmpegAudio.codecName("audio/eac3-joc"))
        assertEquals("dca", FfmpegAudio.codecName("audio/vnd.dts.hd"))
        assertEquals("truehd", FfmpegAudio.codecName("audio/true-hd"))
        assertNull(FfmpegAudio.codecName("audio/mp4a-latm"))  // the phone always decodes AAC
    }
}
