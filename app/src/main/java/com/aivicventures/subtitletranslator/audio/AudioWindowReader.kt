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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class UnsupportedAudioException(message: String) : Exception(message)

/** One audio track of a movie; [ordinal] counts audio tracks only (0 = the first). */
data class AudioTrackInfo(
    val index: Int,
    val ordinal: Int,
    val mime: String,
    val language: String?,
    val channels: Int,
    val decodable: Boolean,
) {
    fun describe(): String {
        val lang = language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
        val layout = when (channels) { 0 -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$channels ch" }
        return listOfNotNull("${ordinal + 1}.", lang ?: "Unknown language", codecLabel(mime), layout).joinToString(" ")
    }

    companion object {
        fun codecLabel(mime: String): String = when (mime) {
            "audio/mp4a-latm" -> "AAC"
            "audio/ac3" -> "AC-3"
            "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
            "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> "DTS"
            "audio/true-hd" -> "TrueHD"
            "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2" -> "MPEG audio"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            else -> mime.removePrefix("audio/")
        }

        /**
         * The track to use: the one chosen before for this movie; else, when the movie
         * language is set, the first decodable track in that language; else the first
         * decodable track; else the first track.
         */
        fun chooseDefault(tracks: List<AudioTrackInfo>, saved: Int?, movieLanguage: String): Int {
            if (saved != null && saved in tracks.indices) return saved
            val usable = tracks.filter { it.decodable }
            if (movieLanguage != "auto") {
                usable.firstOrNull { it.language?.substringBefore('-').equals(movieLanguage.substringBefore('-'), ignoreCase = true) }?.let { return it.ordinal }
            }
            return usable.firstOrNull()?.ordinal ?: 0
        }
    }
}

/**
 * Decodes 16 kHz mono windows of a movie's audio at arbitrary timestamps, without playing
 * anything: the engine's own demuxer (Media3's extractors, the same ones the player uses,
 * so MKV/MP4/WebM/TS/AVI all work) plus the device's MediaCodec decoder, or the optional
 * LGPL FFmpeg decoders ([FfmpegAudio]) for formats the phone cannot decode (AC-3, DTS, …).
 * Same contract as the Windows app's audio extractor: read(start, end) returns exactly
 * (end - start) * 16000 samples, silence where the stream has no audio.
 */
@OptIn(UnstableApi::class)
class AudioWindowReader(context: Context, uri: Uri, trackIndex: Int? = null) : AutoCloseable {
    private val extractor = MediaExtractorCompat(context)
    private val codec: MediaCodec?
    private var ff = 0L
    private var packetBuffer: ByteBuffer? = null
    val track: Int
    val mime: String
    val durationSec: Double
    /** The decoder in use, for logs: a MediaCodec name or "ffmpeg:<codec>". */
    val decoderName: String

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
        if (name != null) {
            codec = MediaCodec.createByCodecName(name)
            codec.configure(format, null, null, 0)
            codec.start()
            decoderName = name
        } else {
            val ffName = FfmpegAudio.codecName(mime)?.takeIf { FfmpegAudio.supports(mime) }
                ?: throw UnsupportedAudioException("This device has no decoder for the movie's audio format (${AudioTrackInfo.codecLabel(mime)}).")
            val csd = if (format.containsKey("csd-0")) format.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } } else null
            ff = FfmpegAudio.open(ffName, csd, format.intOr(MediaFormat.KEY_SAMPLE_RATE), format.intOr(MediaFormat.KEY_CHANNEL_COUNT))
            if (ff == 0L) throw UnsupportedAudioException("The FFmpeg $ffName decoder could not open this audio track.")
            codec = null
            decoderName = "ffmpeg:$ffName"
        }
    }

    /** Mono 16 kHz float samples for [startSec, endSec). */
    fun read(startSec: Double, endSec: Double): FloatArray {
        val outLen = ((endSec - startSec) * TARGET_RATE).toInt().coerceAtLeast(0)
        if (outLen == 0) return FloatArray(0)
        val startUs = (startSec * 1e6).toLong()
        val endUs = (endSec * 1e6).toLong()

        val tSeek = System.nanoTime()
        extractor.seekTo(startUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
        codec?.flush() ?: FfmpegAudio.flush(ff)
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
        val codec = codec ?: return readFfmpeg(startSec, endSec, outLen, tDecode)
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

    /** The same window with FFmpeg doing the decoding and the downmix to mono. */
    private fun readFfmpeg(startSec: Double, endSec: Double, outLen: Int, tDecode: Long): FloatArray {
        val startUs = (startSec * 1e6).toLong()
        val endUs = (endSec * 1e6).toLong()
        val buf = packetBuffer ?: ByteBuffer.allocateDirect(PACKET_BUFFER).also { packetBuffer = it }
        var rate = 0
        var native: FloatArray? = null
        var packets = 0
        while (true) {
            val t = extractor.sampleTime
            if (t < 0 || t > endUs + 500_000) break
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            val pcm = FfmpegAudio.decode(ff, buf, size, t) ?: throw IOException("FFmpeg could not decode the packet at $t µs")
            extractor.advance()
            packets++
            if (pcm.isEmpty()) continue
            if (rate == 0) rate = FfmpegAudio.sampleRate(ff)
            val dst = native ?: FloatArray(((endSec - startSec) * rate).toInt() + 1).also { native = it }
            val pts = FfmpegAudio.lastPts(ff).takeIf { it != Long.MIN_VALUE } ?: t
            val first = Math.round((pts - startUs) / 1e6 * rate).toInt()
            for (i in maxOf(0, -first) until minOf(pcm.size, dst.size - first)) dst[first + i] = pcm[i]
            if (pts > endUs) break
        }
        val src = native ?: return FloatArray(outLen)
        val tr = System.nanoTime()
        return Resampler.resample(src, rate, TARGET_RATE, outLen).also {
            Log.d(TAG, "ffmpeg decoded %.1f s (%d packets) at %d Hz in %.0f ms".format(endSec - startSec, packets, rate, (tr - tDecode) / 1e6))
        }
    }

    private inline fun downmix(frames: Int, channels: Int, dst: FloatArray, first: Int, sample: (Int) -> Float) {
        val w = downmixWeights(channels)
        val lo = maxOf(0, -first)
        val hi = minOf(frames, dst.size - first)
        for (i in lo until hi) {
            var sum = 0f
            val base = i * channels
            for (c in 0 until channels) sum += w[c] * sample(base + c)
            dst[first + i] = sum
        }
    }

    private fun MediaFormat.intOr(key: String): Int = if (containsKey(key)) getInteger(key) else 0

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        if (ff != 0L) { FfmpegAudio.close(ff); ff = 0L }
        runCatching { extractor.release() }
    }

    companion object {
        const val TARGET_RATE = 16_000
        private const val PREROLL_US = 300_000L
        /** Largest compressed audio packet expected (DTS-HD MA and TrueHD frames are far smaller). */
        private const val PACKET_BUFFER = 1 shl 20
        private const val TAG = "AudioWindowReader"

        /** The movie's audio tracks, with whether this phone (or FFmpeg) can decode each. */
        fun tracks(context: Context, uri: Uri): List<AudioTrackInfo> {
            val ex = MediaExtractorCompat(context)
            try {
                ex.setDataSource(uri, 0)
                val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val audio = (0 until ex.trackCount).map { it to ex.getTrackFormat(it) }
                    .filter { (_, f) -> f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                return audio.mapIndexed { ordinal, (i, f) ->
                    val mime = f.getString(MediaFormat.KEY_MIME)!!
                    AudioTrackInfo(
                        index = i, ordinal = ordinal, mime = mime,
                        language = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null,
                        channels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0,
                        decodable = codecs.findDecoderForFormat(f) != null || FfmpegAudio.supports(mime),
                    )
                }
            } finally {
                runCatching { ex.release() }
            }
        }

        /**
         * Mono weights for MediaCodec's channel order (FL FR FC LFE BL BR SL SR for 5.1 and 7.1).
         * Films put dialogue in the centre channel, so it counts double and the LFE not at all;
         * a plain average would leave dialogue 16 dB down in 5.1. The weights sum to 1.
         * subtrans_ff.cpp applies the same weights by channel name.
         */
        fun downmixWeights(channels: Int): FloatArray {
            val raw = FloatArray(channels) { 1f }
            if (channels == 6 || channels == 8) {
                raw[2] = 2f
                raw[3] = 0f
                for (c in 4 until channels) raw[c] = 0.5f
            }
            val total = raw.sum()
            return FloatArray(channels) { raw[it] / total }
        }

        /** Duration in seconds without opening a decoder. */
        fun probeDuration(context: Context, uri: Uri): Double = try {
            AudioWindowReader(context, uri).use { it.durationSec }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed", e)
            0.0
        }
    }
}
