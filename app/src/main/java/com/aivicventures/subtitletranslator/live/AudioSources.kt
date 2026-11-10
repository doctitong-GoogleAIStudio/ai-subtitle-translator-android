package com.aivicventures.subtitletranslator.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.aivicventures.subtitletranslator.audio.AudioWindowReader

/** A live 16 kHz mono stream. [read] blocks; it returns -1 once the source has ended or was closed. */
interface LiveAudioSource : AutoCloseable {
    /** Throws [IllegalStateException] with a message for the user when the source cannot start. */
    fun start()
    fun read(out: FloatArray): Int
    override fun close()
}

/**
 * AudioRecord at 16 kHz mono, or at 48 kHz with streaming conversion where a phone
 * refuses 16 kHz. Raw audio is only held in memory, never written anywhere.
 */
abstract class RecordSource(private val gain: Float) : LiveAudioSource {
    private var record: AudioRecord? = null
    private var rate = RATE
    private var shorts = ShortArray(0)
    @Volatile private var closed = false
    // Streaming 48 kHz → 16 kHz: average of 3 (a simple low-pass) and keep 1.
    private var carry = FloatArray(0)

    protected abstract fun build(rate: Int, bufferBytes: Int): AudioRecord

    override fun start() {
        for (r in intArrayOf(RATE, 48_000)) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = runCatching { build(r, maxOf(min, r / 5 * 2)) }.getOrNull() ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                continue
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                error("The audio input is in use by another app.")
            }
            onStarted(rec)
            record = rec
            rate = r
            Log.i(TAG, "${javaClass.simpleName} recording at $r Hz")
            return
        }
        error("This phone could not open the audio input.")
    }

    protected open fun onStarted(record: AudioRecord) {}

    override fun read(out: FloatArray): Int {
        val rec = record ?: return -1
        if (closed) return release()
        val ratio = rate / RATE
        val want = out.size * ratio
        if (shorts.size < want) shorts = ShortArray(want)
        val n = rec.read(shorts, 0, want)
        if (n < 0 || closed) return release()
        if (ratio == 1) {
            for (i in 0 until n) out[i] = (shorts[i] * gain / 32768f).coerceIn(-1f, 1f)
            return n
        }
        val all = FloatArray(carry.size + n)
        System.arraycopy(carry, 0, all, 0, carry.size)
        for (i in 0 until n) all[carry.size + i] = shorts[i] / 32768f
        val m = all.size / ratio
        for (i in 0 until m) {
            var s = 0f
            for (k in 0 until ratio) s += all[i * ratio + k]
            out[i] = (s / ratio * gain).coerceIn(-1f, 1f)
        }
        carry = all.copyOfRange(m * ratio, all.size)
        return m
    }

    /** Stops recording, which also ends a [read] blocked on another thread; that thread releases it. */
    override fun close() {
        closed = true
        runCatching { record?.stop() }
    }

    /** Called on the reading thread once the source is closed. */
    protected open fun release(): Int {
        record?.release()
        record = null
        return -1
    }

    companion object {
        const val RATE = 16_000
        private const val TAG = "LiveAudio"

        fun format(rate: Int): AudioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
    }
}

/**
 * Other apps' playback through AudioPlaybackCapture. Android only delivers the sound of apps
 * that allow it (media, game and unknown usages; an app can opt out, and protected content
 * is never captured): the rest arrives as silence, which the engine detects and reports.
 */
@RequiresApi(29)
class PlaybackCaptureSource(private val projection: MediaProjection) : RecordSource(1f) {
    // RECORD_AUDIO is checked by the service before a session starts.
    @SuppressLint("MissingPermission")
    override fun build(rate: Int, bufferBytes: Int): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(format(rate))
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }
}

/**
 * The microphone with the VOICE_RECOGNITION source: tuned for speech, without the echo
 * canceller of call audio, which would remove a movie played by this same phone.
 */
class MicrophoneSource(gain: Float, private val noiseSuppression: Boolean) : RecordSource(gain) {
    private var suppressor: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    override fun build(rate: Int, bufferBytes: Int): AudioRecord =
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)

    override fun onStarted(record: AudioRecord) {
        if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            suppressor = runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
    }

    val hasNoiseSuppressor: Boolean get() = suppressor != null

    override fun release(): Int {
        suppressor?.release()
        suppressor = null
        return super.release()
    }
}

/**
 * Test source: the audio of a media file, delivered at real-time speed as if it were being
 * played. Used to check the whole live pipeline on an emulator with a known recording.
 */
class FileTestSource(private val context: Context, private val uri: Uri) : LiveAudioSource {
    private var buffer = FloatArray(0)
    private var bufPos = 0
    private var startedAt = 0L
    @Volatile private var closed = false

    /** Decodes the whole file first (capped), so decoding does not compete with recognition for the CPU. */
    override fun start() {
        AudioWindowReader(context, uri).use { r -> buffer = r.read(0.0, minOf(r.durationSec, MAX_SEC)) }
        bufPos = 0
        startedAt = SystemClock.elapsedRealtime()
    }

    override fun read(out: FloatArray): Int {
        if (closed || bufPos >= buffer.size) return -1
        val n = minOf(out.size, buffer.size - bufPos)
        System.arraycopy(buffer, bufPos, out, 0, n)
        bufPos += n
        // Hold back until the wall clock catches up with the audio.
        val due = startedAt + bufPos.toLong() * 1000 / RecordSource.RATE
        val wait = due - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(wait)
        return if (closed) -1 else n
    }

    override fun close() {
        closed = true
    }

    private companion object {
        /** Ten minutes of 16 kHz float audio is about 38 MB. */
        const val MAX_SEC = 600.0
    }
}
