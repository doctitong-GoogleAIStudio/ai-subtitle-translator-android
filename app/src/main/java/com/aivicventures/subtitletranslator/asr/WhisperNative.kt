package com.aivicventures.subtitletranslator.asr

import android.content.Context

/** Raw result of one whisper.cpp call; times in milliseconds relative to the window start. */
class NativeResult(
    @JvmField val language: String,
    @JvmField val texts: Array<ByteArray>,
    @JvmField val t0: LongArray,
    @JvmField val t1: LongArray,
    @JvmField val noSpeechProb: FloatArray,
    @JvmField val avgLogprob: FloatArray,
    @JvmField val nTokens: IntArray,
)

/** JNI entry points (app/src/main/cpp/subtrans_jni.cpp). */
object WhisperNative {
    @Volatile private var loaded = false

    /** Loads the native libraries and the ggml CPU backend best suited to this CPU. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        System.loadLibrary("subtrans_jni")
        loadBackends(context.applicationInfo.nativeLibraryDir)
        loaded = true
    }

    external fun loadBackends(dir: String)
    external fun systemInfo(): String
    /** Loads a model from [length] bytes at [offset] of the open file [fd] (the APK). */
    external fun initContext(fd: Int, offset: Long, length: Long): Long
    external fun freeContext(ptr: Long)
    external fun requestAbort(ptr: Long)
    external fun clearAbort(ptr: Long)
    /** Pins the calling thread to these CPU ids (inherited by threads it starts later). */
    external fun setThreadAffinity(cpus: IntArray): Boolean
    external fun getThreadAffinity(): IntArray
    /** Speech regions as [t0, t1, t0, t1, …] in seconds; null if the VAD model cannot load. */
    external fun detectSpeech(ptr: Long, vadModel: String, samples: FloatArray): FloatArray?
    external fun transcribe(
        ptr: Long,
        samples: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        fallback: Boolean,
        vadModel: String?,
        initialPrompt: String?,
        /** Encoder frames (50 per second of audio); 0 = whisper.cpp's full 30 s window. */
        audioCtx: Int,
    ): NativeResult?
}
