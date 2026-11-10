package com.aivicventures.subtitletranslator

import com.aivicventures.subtitletranslator.browser.BrowserDb
import com.aivicventures.subtitletranslator.browser.CaptionTrack
import com.aivicventures.subtitletranslator.browser.Captions
import com.aivicventures.subtitletranslator.browser.Cue
import com.aivicventures.subtitletranslator.live.PageAudioBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserTest {
    private val ddg = "https://duckduckgo.com/?q=%s"

    @Test
    fun addressBarKeepsAddresses() {
        assertEquals("https://example.org/a?b=1", BrowserDb.toUrl("https://example.org/a?b=1", ddg))
        assertEquals("http://example.org", BrowserDb.toUrl("  http://example.org ", ddg))
        assertEquals("https://archive.org", BrowserDb.toUrl("archive.org", ddg))
        assertEquals("about:blank", BrowserDb.toUrl("about:blank", ddg))
        assertEquals("", BrowserDb.toUrl("   ", ddg))
    }

    @Test
    fun addressBarSearchesWords() {
        assertEquals("https://duckduckgo.com/?q=public+domain+films", BrowserDb.toUrl("public domain films", ddg))
        assertEquals("https://duckduckgo.com/?q=movie", BrowserDb.toUrl("movie", ddg))
        // A dot alone does not make an address.
        assertEquals("https://duckduckgo.com/?q=.mkv", BrowserDb.toUrl(".mkv", ddg))
        assertEquals("https://duckduckgo.com/?q=a%26b%3Dc", BrowserDb.toUrl("a&b=c", ddg))
    }

    @Test
    fun captionTextIsCleaned() {
        assertEquals("Hello there, friend", Captions.clean("<v Anna><i>Hello</i>\nthere,  <c.yellow>friend</c>"))
        assertEquals("Tom & Jerry <3", Captions.clean("Tom &amp; Jerry &lt;3"))
        assertEquals("", Captions.clean("  <b></b> \n "))
    }

    @Test
    fun captionLanguages() {
        assertEquals("ko", Captions.languageOf("ko-KR"))
        assertEquals("zh", Captions.languageOf("zh-Hans"))
        assertEquals("he", Captions.languageOf("iw"))
        assertEquals("no", Captions.languageOf("nb_NO"))
        assertEquals("en", Captions.languageOf("EN-us"))
        assertNull(Captions.languageOf("und"))
        assertNull(Captions.languageOf(""))
        assertNull(Captions.languageOf(null))
        assertEquals("ko", Captions.guessLanguage(listOf("안녕하세요, 반갑습니다")))
        assertEquals("ja", Captions.guessLanguage(listOf("今日はいい天気ですね")))
        assertEquals("zh", Captions.guessLanguage(listOf("今天天气很好")))
        assertEquals("th", Captions.guessLanguage(listOf("สวัสดีครับ")))
        // Latin script could be many languages: no guess.
        assertNull(Captions.guessLanguage(listOf("Hola, ¿cómo estás?")))
        assertNull(Captions.guessLanguage(listOf("123 !!")))
    }

    @Test
    fun captionTrackChoice() {
        val es = CaptionTrack(0, "subtitles", "Español", "es")
        val en = CaptionTrack(1, "captions", "English", "en-US")
        val chapters = CaptionTrack(2, "chapters", "", "en")
        assertEquals(en, Captions.pick(listOf(es, en, chapters)))
        assertEquals(es, Captions.pick(listOf(chapters, es)))
        assertNull(Captions.pick(listOf(chapters)))
    }

    @Test
    fun smartDelayBuffer() {
        val cues = listOf(Cue(1.0, 3.0, "a"), Cue(4.0, 6.0, "b"), Cue(8.0, 9.0, "c"), Cue(20.0, 22.0, "d"))
        val done = mutableSetOf(cues[0], cues[1])
        // Next untranslated cue starts at 8 s: 8 - 2 = 6 s ready.
        assertEquals(6.0, Captions.readyAhead(cues, { it in done }, 2.0, 10.0), 1e-9)
        done += cues[2]
        // Cue d starts beyond the 10 s horizon: full.
        assertEquals(10.0, Captions.readyAhead(cues, { it in done }, 2.0, 10.0), 1e-9)
        // Inside an untranslated cue: nothing ready.
        assertEquals(0.0, Captions.readyAhead(cues, { it in done }, 21.0, 10.0), 1e-9)
        // After the last cue nothing is left to wait for.
        assertEquals(5.0, Captions.readyAhead(cues, { false }, 30.0, 5.0), 1e-9)
        assertEquals(listOf(cues[1]), Captions.activeAt(cues, 5.0))
        assertEquals(emptyList<Cue>(), Captions.activeAt(cues, 7.0))
    }

    @Test
    fun pcm16IsLittleEndianSigned() {
        val bytes = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val f = PageAudioBus.pcm16ToFloat(bytes)
        assertEquals(4, f.size)
        assertEquals(0f, f[0], 0f)
        assertEquals(32767 / 32768f, f[1], 1e-6f)
        assertEquals(-1f, f[2], 0f)
        assertEquals(-1 / 32768f, f[3], 1e-6f)
        // An odd trailing byte is ignored.
        assertEquals(1, PageAudioBus.pcm16ToFloat(byteArrayOf(1, 0, 5)).size)
    }

    @Test
    fun pageAudioBusDropsOldestWhenFull() {
        PageAudioBus.open = true
        try {
            PageAudioBus.clear()
            val before = PageAudioBus.droppedSamples
            repeat(400) { i -> PageAudioBus.offer(FloatArray(10) { i.toFloat() }) }
            assertEquals(40L * 10, PageAudioBus.droppedSamples - before)
            // The oldest went first: the first piece left is number 40.
            assertEquals(40f, PageAudioBus.poll(0)!![0], 0f)
            PageAudioBus.clear()
            assertNull(PageAudioBus.poll(0))
        } finally {
            PageAudioBus.open = false
        }
        // Closed: nothing is queued.
        PageAudioBus.offer(FloatArray(10))
        assertNull(PageAudioBus.poll(0))
        assertNotNull(PageAudioBus)
    }
}
