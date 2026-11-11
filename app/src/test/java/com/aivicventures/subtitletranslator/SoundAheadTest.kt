package com.aivicventures.subtitletranslator

import com.aivicventures.subtitletranslator.browser.Captions
import com.aivicventures.subtitletranslator.browser.Mp4Boxes
import com.aivicventures.subtitletranslator.browser.SoundStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Smart Delay for a web video's sound: the MP4 facts read from the player's pieces, and the sound store. */
class SoundAheadTest {
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = ByteArrayOutputStream().apply { body.forEach { write(it) } }.toByteArray()
        val size = content.size + 8
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.ISO_8859_1) + content
    }

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xffffffffL)

    private fun init(timescale: Long, extra: ByteArray = ByteArray(0)): ByteArray {
        // mdhd version 0: version/flags, creation, modification, timescale, duration, language.
        val mdhd = box("mdhd", u32(0), u32(0), u32(0), u32(timescale), u32(0), u32(0))
        val trak = box("trak", box("tkhd", ByteArray(84)), box("mdia", mdhd, box("hdlr", ByteArray(25))), extra)
        return box("ftyp", "iso6".toByteArray(), u32(0)) + box("moov", box("mvhd", ByteArray(100)), trak)
    }

    private fun fragment(decodeTime: Long, version1: Boolean): ByteArray {
        val tfdt = if (version1) box("tfdt", u32(0x01000000), u64(decodeTime)) else box("tfdt", u32(0), u32(decodeTime))
        return box("moof", box("mfhd", u32(0), u32(7)), box("traf", box("tfhd", u32(0), u32(1)), tfdt, box("trun", u32(0), u32(0)))) +
            box("mdat", ByteArray(32))
    }

    @Test
    fun mp4PiecesGiveTheirVideoTime() {
        val i = init(48000)
        assertEquals(48000L, Mp4Boxes.timescale(i))
        assertEquals(i.size, Mp4Boxes.initEnd(i))
        assertFalse(Mp4Boxes.encrypted(i))
        val seg = fragment(96000, version1 = true)
        assertEquals(-1, Mp4Boxes.initEnd(seg))
        assertTrue(Mp4Boxes.hasMedia(seg))
        assertEquals(96000L, Mp4Boxes.baseDecodeTime(seg))
        assertEquals(44100L * 5, Mp4Boxes.baseDecodeTime(fragment(44100L * 5, version1 = false)))
        // Init and first fragment in one append: the init ends where the fragment starts.
        val both = i + seg
        assertEquals(i.size, Mp4Boxes.initEnd(both))
        assertEquals(96000L, Mp4Boxes.baseDecodeTime(both.copyOfRange(i.size, both.size)))
    }

    @Test
    fun encryptedSoundIsRecognisedAsProtected() {
        val protectedInit = init(48000, box("enca", ByteArray(28), box("sinf", box("frma", "mp4a".toByteArray()))))
        assertTrue(Mp4Boxes.encrypted(protectedInit))
        assertFalse(Mp4Boxes.isWebm(protectedInit))
        assertTrue(Mp4Boxes.isWebm(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0)))
    }

    @Test
    fun soundIsPlacedByVideoTime() {
        val store = SoundStore(keepBehind = 5.0)
        val rate = 48000
        // One second of a steady level at 10 s, the next second right after it.
        store.put(10.0, rate, ShortArray(rate) { 16384 }, now = 1)
        store.put(11.0, rate, ShortArray(rate) { 16384 }, now = 2)
        assertEquals(12.0, store.continuousUntil(10.0), 0.01)
        assertEquals(10.5, store.continuousUntil(10.5) - 1.5, 0.01)
        assertEquals(9.0, store.continuousUntil(9.0), 0.0)
        val pcm = store.read16k(9.5, 12.5)
        assertEquals(48000, pcm.size)
        assertEquals(0f, pcm[(0.2 * 16000).toInt()], 1e-3f) // 9.7 s: nothing loaded there
        assertEquals(0.5f, pcm[(1.0 * 16000).toInt()], 0.02f) // 10.5 s
        assertEquals(0.5f, pcm[(2.0 * 16000).toInt()], 0.02f) // 11.5 s
        assertEquals(0f, pcm[(2.8 * 16000).toInt()], 1e-3f) // 12.3 s: past the loaded sound
        // A piece at another rate (the site changed quality) is converted.
        store.put(12.0, 44100, ShortArray(44100) { 16384 }, now = 3)
        assertEquals(0.5f, store.read16k(9.5, 12.5)[(2.8 * 16000).toInt()], 0.05f)
        // Sound long behind the playhead is let go.
        store.trim(position = 20.0)
        assertTrue(store.isEmpty())
    }

    @Test
    fun smartDelayOffersUpToAMinute() {
        assertEquals(listOf(0, 5, 10, 15, 20, 30, 45, 60), Captions.SMART_DELAY_CHOICES)
    }
}
