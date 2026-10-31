package com.aivicventures.subtitletranslator.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.nio.ByteOrder

class UnsupportedAudioException(message: String) : Exception(message)

/**
 * Decodes 16 kHz mono windows of a movie's audio at arbitrary timestamps, without playing
 * anything: the engine's own demuxer (Media3's extractors, the same ones the player uses,
 * so MKV/MP4/WebM/TS/AVI all work) plus the device's MediaCodec decoder.
 * Same contract as the Windows app's audio extractor: read(start, end) returns exactly
 * (end - start) * 16000 samples, silence where the stream has no audio.
 */
@OptIn(UnstableApi::class)
class AudioWindowReader(context: Context, uri: Uri, trackIndex: Int? = null) : AutoCloseable {
    private val extractor = MediaExtractorCompat(context)
    private val codec: MediaCodec
    val track: Int
    val mime: String
    val durationSec: Double

    init {
        extractor.setDataSource(uri, 0)
        val audioTracks = (0 until extractor.trackCount).filter {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (audioTracks.isEmpty()) throw UnsupportedAudioException("This video has no audio track.")
        track = trackIndex?.takeIf { it in audioTracks } ?: audioTracks.first()
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        mime = format.getString(MediaFormat.KEY_MIME)!!
        durationSec = (0 until extractor.trackCount).maxOf {
            val f = extractor.getTrackFormat(it)
            if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            ?: throw UnsupportedAudioException("This device has no decoder for the movie's audio format ($mime).")
        codec = MediaCodec.createByCodecName(name)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** Mono 16 kHz float samples for [startSec, endSec). */
    fun read(startSec: Double, endSec: Double): FloatArray {
        val outLen = ((endSec - startSec) * TARGET_RATE).toInt().coerceAtLeast(0)
        if (outLen == 0) return FloatArray(0)
        val startUs = (startSec * 1e6).toLong()
        val endUs = (endSec * 1e6).toLong()

        val tSeek = System.nanoTime()
        extractor.seekTo(startUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        // A Matroska file often indexes only its video track, so the seek may land far before
        // the window (even at the file start). Skip those packets without decoding them:
        // demuxing is cheap, decoding minutes of audio is not. Audio packets are all sync
        // frames; PREROLL_US of real decoding before the window primes the decoder.
        var skipped = 0
        while (extractor.sampleTime in 0 until startUs - PREROLL_US) {
            if (!extractor.advance()) break
            skipped++
        }
        val tDecode = System.nanoTime()
        if (skipped > 0) Log.d(TAG, "skipped $skipped packets before %.1f s in %.0f ms".format(startSec, (tDecode - tSeek) / 1e6))
        var packets = 0

        var rate = 0
        var channels = 0
        var pcmFloat = false
        var native: FloatArray? = null // mono samples at the decoder's rate, aligned to startUs
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        var idle = 0
        var shorts = ShortArray(0)
        var floats = FloatArray(0)

        // Returns false when the codec had nothing to give.
        fun drainOne(timeoutUs: Long): Boolean {
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                return true
            }
            if (outIdx < 0) return false
            if (rate == 0) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            val dst = native ?: FloatArray(((endSec - startSec) * rate).toInt() + 1).also { native = it }
            if (info.size > 0) {
                val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val first = Math.round((info.presentationTimeUs - startUs) / 1e6 * rate).toInt()
                if (pcmFloat) {
                    val n = info.size / 4
                    if (floats.size < n) floats = FloatArray(n)
                    buf.asFloatBuffer().get(floats, 0, n)
                    downmix(n / channels, channels, dst, first) { floats[it] }
                } else {
                    val n = info.size / 2
                    if (shorts.size < n) shorts = ShortArray(n)
                    buf.asShortBuffer().get(shorts, 0, n)
                    downmix(n / channels, channels, dst, first) { shorts[it] / 32768f }
                }
            }
            codec.releaseOutputBuffer(outIdx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs > endUs) outputDone = true
            return true
        }

        while (!outputDone) {
            var progressed = false
            while (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(0)
                if (inIdx < 0) break
                val buf = codec.getInputBuffer(inIdx)!!
                val size = extractor.readSampleData(buf, 0)
                val t = extractor.sampleTime
                if (size < 0 || t > endUs + 500_000) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inIdx, 0, size, t, 0)
                    extractor.advance()
                    packets++
                }
                progressed = true
            }
            while (!outputDone && drainOne(0)) progressed = true
            if (!progressed && !outputDone) {
                // Nothing to do right now: wait briefly for the decoder.
                if (drainOne(5_000)) idle = 0 else if (inputDone && ++idle > 200) outputDone = true
            }
        }
        val src = native ?: return FloatArray(outLen)
        val tr = System.nanoTime()
        return Resampler.resample(src, rate, TARGET_RATE, outLen).also {
            Log.d(TAG, "decoded %.1f s (%d packets) at %d Hz x%d in %.0f ms; resample %.0f ms".format(endSec - startSec, packets, rate, channels, (tr - tDecode) / 1e6, (System.nanoTime() - tr) / 1e6))
        }
    }

    private inline fun downmix(frames: Int, channels: Int, dst: FloatArray, first: Int, sample: (Int) -> Float) {
        val lo = maxOf(0, -first)
        val hi = minOf(frames, dst.size - first)
        for (i in lo until hi) {
            var sum = 0f
            val base = i * channels
            for (c in 0 until channels) sum += sample(base + c)
            dst[first + i] = sum / channels
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    companion object {
        const val TARGET_RATE = 16_000
        private const val PREROLL_US = 300_000L
        private const val TAG = "AudioWindowReader"

        /** Duration in seconds without opening a decoder. */
        fun probeDuration(context: Context, uri: Uri): Double = try {
            AudioWindowReader(context, uri).use { it.durationSec }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed", e)
            0.0
        }
    }
}
