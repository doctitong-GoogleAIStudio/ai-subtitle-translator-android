package com.aivicventures.subtitletranslator.translate

import android.content.res.AssetFileDescriptor
import android.util.Log
import java.io.File

/** JNI entry points (app/src/main/cpp/subtrans_mt.cpp); 64-bit ABIs only. */
object TranslatorNative {
    /** False on 32-bit phones, where CTranslate2 is not built. */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("subtrans_mt") }
            .onFailure { Log.i("M2M100", "text translation unavailable on this device: ${it.message}") }
            .isSuccess
    }

    /** [modelDir] holds config and vocabulary; model.bin is [length] bytes at [offset] of [fd]. */
    external fun init(modelDir: String, threads: Int, fd: Int, offset: Long, length: Long): Long
    external fun free(ptr: Long)
    external fun translate(ptr: Long, sources: Array<ByteArray>, targetPrefix: String, beam: Int, maxLength: Int): Array<ByteArray>?
}

/**
 * M2M100 418M (int8, CTranslate2) translating recognised lines into English, with the
 * Windows app's decoding settings (repetition penalty 1.1, no repeated 4-grams). Not
 * thread-safe: the engine calls it from its single worker.
 */
class M2M100Translator(modelDir: File, weights: AssetFileDescriptor, threads: Int) : AutoCloseable {
    private val tokenizer = SentencePieceBpe(File(modelDir, "sentencepiece.bpe.model").readBytes())
    private var ptr = TranslatorNative.init(modelDir.path, threads, weights.parcelFileDescriptor.fd, weights.startOffset, weights.length)

    /** Translates [texts] spoken in [language] (Whisper code) into English, in order.
     *  [beam] 4 = the Windows app's quality setting, 1 = greedy (fast). */
    fun translate(texts: List<String>, language: String, beam: Int): List<String> {
        if (texts.isEmpty()) return emptyList()
        val src = sourceCode(language) ?: return texts
        val sources = texts.map { t ->
            val pieces = tokenizer.encode(t).take(MAX_INPUT_TOKENS)
            (listOf("__${src}__") + pieces + "</s>").joinToString(" ").toByteArray(Charsets.UTF_8)
        }.toTypedArray()
        val out = TranslatorNative.translate(ptr, sources, "__en__", beam, MAX_OUTPUT_TOKENS) ?: return texts
        return out.map { bytes ->
            val tokens = String(bytes, Charsets.UTF_8).split(' ').filter { it.isNotEmpty() }
            SentencePieceBpe.decode(tokens)
        }
    }

    override fun close() {
        if (ptr != 0L) TranslatorNative.free(ptr)
        ptr = 0
    }

    companion object {
        private const val MAX_INPUT_TOKENS = 200
        private const val MAX_OUTPUT_TOKENS = 200

        /** M2M100's 100 languages (fairseq order is irrelevant here: tokens are "__xx__"). */
        private val LANGUAGES = (
            "af am ar ast az ba be bg bn br bs ca ceb cs cy da de el en es et fa ff fi fr fy ga gd gl gu " +
                "ha he hi hr ht hu hy id ig ilo is it ja jv ka kk km kn ko lb lg ln lo lt lv mg mk ml mn mr " +
                "ms my ne nl no ns oc or pa pl ps pt ro ru sd si sk sl so sq sr ss su sv sw ta th tl tn tr " +
                "uk ur uz vi wo xh yi yo zh zu"
            ).split(' ').toSet()

        /** Whisper language code → M2M100 code, or null when M2M100 does not know the language. */
        fun sourceCode(whisper: String): String? {
            val code = when (whisper) {
                "jw" -> "jv"
                "nn" -> "no"
                "yue" -> "zh"
                else -> whisper
            }
            return code.takeIf { it in LANGUAGES && it != "en" }
        }

        fun supports(whisper: String): Boolean = sourceCode(whisper) != null
    }
}
