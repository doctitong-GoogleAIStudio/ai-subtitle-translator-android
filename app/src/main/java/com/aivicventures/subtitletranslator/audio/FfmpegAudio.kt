package com.aivicventures.subtitletranslator.audio

import android.util.Log
import java.nio.ByteBuffer

/**
 * The optional LGPL FFmpeg decoders (scripts/build_ffmpeg.sh), used for recognition when the
 * phone has no MediaCodec decoder for the movie's audio (AC-3, E-AC-3, DTS, TrueHD are often
 * missing). Absent from builds made without FFmpeg; then [available] is false.
 *
 * The licence is checked at run time too: FFmpeg reports the licence its build configuration
 * implies, and anything other than LGPL disables it.
 */
object FfmpegAudio {
    private const val TAG = "FfmpegAudio"

    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("subtrans_ff")
            val licence = license()
            Log.i(TAG, "FFmpeg ${version()}, $licence")
            licence.startsWith("LGPL")
        }.getOrElse { false }
    }

    /** "7.1.5 (LGPL version 2.1 or later)", or null without FFmpeg. */
    val description: String? by lazy { if (available) "${version()} (${license()})" else null }

    /** FFmpeg decoder name for a Media3 MIME type (same mapping as Media3's FFmpeg extension). */
    fun codecName(mime: String): String? = when (mime) {
        "audio/ac3" -> "ac3"
        "audio/eac3", "audio/eac3-joc" -> "eac3"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> "dca"
        "audio/true-hd" -> "truehd"
        "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2" -> "mp3"
        else -> null
    }

    fun supports(mime: String): Boolean = available && codecName(mime)?.let { hasDecoder(it) } == true

    external fun license(): String
    external fun version(): String
    external fun hasDecoder(name: String): Boolean
    external fun open(name: String, extradata: ByteArray?, sampleRate: Int, channels: Int): Long
    /** Mono samples decoded from one packet (maybe none); null on a hard error. */
    external fun decode(ptr: Long, buffer: ByteBuffer, size: Int, ptsUs: Long): FloatArray?
    external fun sampleRate(ptr: Long): Int
    external fun lastPts(ptr: Long): Long
    external fun flush(ptr: Long)
    external fun close(ptr: Long)
}
