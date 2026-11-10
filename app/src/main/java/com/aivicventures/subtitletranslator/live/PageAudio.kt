package com.aivicventures.subtitletranslator.live

import android.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hand-over of the built-in browser's video sound (tapped in the page, 16 kHz mono PCM) to the
 * live engine. Bounded: when recognition cannot keep up, the oldest audio is dropped and
 * counted, never piled up in memory. Only filled while a [PageAudioSource] is open.
 */
object PageAudioBus {
    /** About 30 s of audio in the page's usual 85 ms pieces. */
    private const val CAPACITY = 360
    private val queue = ArrayBlockingQueue<FloatArray>(CAPACITY)

    @Volatile var open = false
        internal set
    /** The video is paused or seeking: the source sends silence so the session clock stays true. */
    @Volatile var paused = false
    /** Samples thrown away because the queue was full (reported in the diagnostics). */
    @Volatile var droppedSamples = 0L
        private set
    @Volatile var receivedSamples = 0L
        private set

    /** Base64 of little-endian 16-bit PCM, as the page script sends it. */
    fun offerBase64(b64: String) {
        if (!open) return
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
        offer(pcm16ToFloat(bytes))
    }

    fun offer(samples: FloatArray) {
        if (!open || samples.isEmpty()) return
        receivedSamples += samples.size
        while (!queue.offer(samples)) {
            queue.poll()?.let { droppedSamples += it.size }
        }
    }

    internal fun poll(timeoutMs: Long): FloatArray? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Throws away what is waiting (after a seek the old audio is obsolete). */
    fun clear() {
        queue.clear()
    }

    internal fun reset() {
        clear()
        droppedSamples = 0
        receivedSamples = 0
        paused = false
    }

    fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val n = bytes.size / 2
        return FloatArray(n) { i ->
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }
}

/**
 * The live source for the built-in browser: audio from [PageAudioBus]. While the video is
 * paused, or nothing arrives for a moment, it delivers silence at real-time pace, so line
 * times stay on the session clock and nothing is judged late after a pause.
 */
class PageAudioSource : LiveAudioSource {
    @Volatile private var closed = false
    private var pending: FloatArray? = null
    private var pendingPos = 0
    private var lastAudioAt = 0L

    override fun start() {
        PageAudioBus.reset()
        PageAudioBus.open = true
        closed = false
        lastAudioAt = System.nanoTime()
    }

    override fun read(out: FloatArray): Int {
        while (!closed) {
            val p = pending ?: PageAudioBus.poll(POLL_MS)?.also {
                pending = it
                pendingPos = 0
            }
            if (p != null) {
                val n = minOf(out.size, p.size - pendingPos)
                System.arraycopy(p, pendingPos, out, 0, n)
                pendingPos += n
                if (pendingPos >= p.size) pending = null
                lastAudioAt = System.nanoTime()
                return n
            }
            val quietMs = (System.nanoTime() - lastAudioAt) / 1_000_000
            if (PageAudioBus.paused || quietMs >= GAP_MS) {
                // Silence for the time that passed, one tenth of a second at a time.
                val n = minOf(out.size, RecordSource.RATE / 10)
                java.util.Arrays.fill(out, 0, n, 0f)
                lastAudioAt = System.nanoTime() - (GAP_MS - 100) * 1_000_000
                return n
            }
        }
        return -1
    }

    override fun close() {
        closed = true
        PageAudioBus.open = false
        PageAudioBus.clear()
    }

    private companion object {
        const val POLL_MS = 100L
        /** No page audio for this long counts as a pause (the page sends about every 85 ms). */
        const val GAP_MS = 400L
    }
}
