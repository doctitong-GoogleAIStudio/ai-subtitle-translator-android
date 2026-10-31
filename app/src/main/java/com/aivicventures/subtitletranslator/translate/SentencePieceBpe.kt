package com.aivicventures.subtitletranslator.translate

import java.text.Normalizer

/**
 * SentencePiece BPE encoder for M2M100's `sentencepiece.bpe.model` (nmt_nfkc normaliser,
 * dummy prefix, whitespace escaped as U+2581). Reads the model protobuf directly, so no
 * native sentencepiece library is needed; the unit tests compare it with the reference
 * implementation's output.
 */
class SentencePieceBpe(model: ByteArray) {
    /** Score of every piece that BPE may produce (NORMAL and USER_DEFINED pieces). */
    private val scores = HashMap<String, Float>(160_000)

    init {
        Proto(model).forEachField { field, value ->
            if (field == 1 && value is ByteArray) {
                var piece = ""
                var score = 0f
                var type = 1
                Proto(value).forEachField { f, v ->
                    when (f) {
                        1 -> piece = String(v as ByteArray, Charsets.UTF_8)
                        2 -> score = java.lang.Float.intBitsToFloat((v as Long).toInt())
                        3 -> type = (v as Long).toInt()
                    }
                }
                if (type == TYPE_NORMAL || type == TYPE_USER_DEFINED) scores[piece] = score
            }
        }
        require(scores.isNotEmpty()) { "not a SentencePiece model" }
    }

    val size: Int get() = scores.size

    fun encode(text: String): List<String> {
        val norm = normalize(text)
        if (norm.isEmpty()) return emptyList()
        // One symbol per code point, then repeatedly merge the adjacent pair whose union
        // has the highest score (leftmost first on ties), exactly like sentencepiece.
        val symbols = ArrayList<String>(norm.length)
        var i = 0
        while (i < norm.length) {
            val n = Character.charCount(norm.codePointAt(i))
            symbols.add(norm.substring(i, i + n))
            i += n
        }
        while (symbols.size > 1) {
            var best = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (k in 0 until symbols.size - 1) {
                val s = scores[symbols[k] + symbols[k + 1]] ?: continue
                if (s > bestScore) {
                    bestScore = s
                    best = k
                }
            }
            if (best < 0) break
            symbols[best] = symbols[best] + symbols[best + 1]
            symbols.removeAt(best + 1)
        }
        return symbols
    }

    /** Minimal protobuf reader: varint (Long), 64-bit (Long), length-delimited (ByteArray), 32-bit (Long). */
    private class Proto(private val b: ByteArray) {
        private var i = 0

        private fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val c = b[i++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return v
                shift += 7
            }
        }

        fun forEachField(block: (Int, Any) -> Unit) {
            while (i < b.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> { var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; v }
                    2 -> { val n = varint().toInt(); val v = b.copyOfRange(i, i + n); i += n; v }
                    5 -> { var v = 0L; for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 4; v }
                    else -> error("unsupported protobuf wire type")
                }
                block(field, value)
            }
        }
    }

    companion object {
        const val SPACE = "▁"
        private const val TYPE_NORMAL = 1
        private const val TYPE_USER_DEFINED = 4

        /** nmt_nfkc: NFKC, control characters dropped, other whitespace and zero-width
         *  characters turned into spaces, runs of spaces collapsed, dummy prefix added. */
        fun normalize(text: String): String {
            val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val sb = StringBuilder(nfkc.length + 1)
            var lastSpace = true
            var i = 0
            while (i < nfkc.length) {
                val cp = nfkc.codePointAt(i)
                i += Character.charCount(cp)
                val space = when {
                    cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0C || cp == 0x0D -> true
                    cp == 0x1680 || cp in 0x2000..0x200F || cp == 0x2028 || cp == 0x2029 -> true
                    cp == 0x202F || cp == 0x205F || cp == 0x3000 || cp == 0xFEFF || cp == 0x2581 -> true
                    else -> false
                }
                if (space) {
                    if (!lastSpace) sb.append(' ')
                    lastSpace = true
                    continue
                }
                if (cp < 0x20 || cp == 0x7F || cp in 0x80..0x9F) continue
                sb.appendCodePoint(cp)
                lastSpace = false
            }
            val trimmed = sb.toString().trimEnd(' ')
            if (trimmed.isEmpty()) return ""
            return SPACE + trimmed.replace(" ", SPACE)
        }

        fun decode(pieces: List<String>): String = pieces.joinToString("").replace(SPACE, " ").trim()
    }
}
