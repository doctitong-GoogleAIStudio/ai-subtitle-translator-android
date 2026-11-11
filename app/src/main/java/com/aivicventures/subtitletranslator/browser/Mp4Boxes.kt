package com.aivicventures.subtitletranslator.browser

/**
 * The few fragmented-MP4 facts the browser needs about the sound a web player appends (Media
 * Source Extensions): where the initialisation segment ends, the track's timescale, a media
 * segment's start time, and whether the sound is encrypted (then it is never decoded).
 * Only box headers are read; the sound itself is decoded by Android's MediaExtractor.
 */
object Mp4Boxes {
    data class Box(val type: String, val start: Int, val size: Int, val header: Int) {
        val end: Int get() = start + size
        val body: Int get() = start + header
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or
            ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    /** The boxes directly inside [from]..[to] of [b]; stops at the first damaged header. */
    fun boxes(b: ByteArray, from: Int = 0, to: Int = b.size): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = u64(b, p + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) {
                // A box cut off at the end of the piece: report what is there.
                if (size >= header && p + header <= to) out += Box(String(b, p + 4, 4, Charsets.ISO_8859_1), p, to - p, header)
                break
            }
            out += Box(String(b, p + 4, 4, Charsets.ISO_8859_1), p, size.toInt(), header)
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, parent: Box, type: String): Box? = boxes(b, parent.body, parent.end).firstOrNull { it.type == type }

    /** End of the initialisation segment (ftyp + moov) at the start of [b], or -1 when there is none. */
    fun initEnd(b: ByteArray): Int = boxes(b).firstOrNull { it.type == "moov" }?.end ?: -1

    /** Whether [b] holds a media segment (moof). */
    fun hasMedia(b: ByteArray): Boolean = boxes(b).any { it.type == "moof" }

    /** Timescale (units per second) of the first track in an initialisation segment; 0 when unknown. */
    fun timescale(init: ByteArray): Long {
        val moov = boxes(init).firstOrNull { it.type == "moov" } ?: return 0
        for (trak in boxes(init, moov.body, moov.end).filter { it.type == "trak" }) {
            val mdia = child(init, trak, "mdia") ?: continue
            val mdhd = child(init, mdia, "mdhd") ?: continue
            val v = init[mdhd.body].toInt()
            val p = mdhd.body + 4 + if (v == 1) 16 else 8
            if (p + 4 <= mdhd.end) return u32(init, p)
        }
        return 0
    }

    /** baseMediaDecodeTime of the first fragment in [seg] (track units), or -1. */
    fun baseDecodeTime(seg: ByteArray): Long {
        val moof = boxes(seg).firstOrNull { it.type == "moof" } ?: return -1
        val traf = child(seg, moof, "traf") ?: return -1
        val tfdt = child(seg, traf, "tfdt") ?: return -1
        val v = seg[tfdt.body].toInt()
        val p = tfdt.body + 4
        return when {
            v == 1 && p + 8 <= tfdt.end -> u64(seg, p)
            p + 4 <= tfdt.end -> u32(seg, p)
            else -> -1
        }
    }

    private val ENCRYPTION = listOf("enca", "encv", "tenc", "pssh", "sinf")

    /** Common Encryption signs in an initialisation segment: protected sound is never decoded. */
    fun encrypted(init: ByteArray): Boolean {
        val end = initEnd(init).takeIf { it > 0 } ?: init.size
        val s = String(init, 0, end, Charsets.ISO_8859_1)
        return ENCRYPTION.any { s.contains(it) }
    }

    /** A WebM (Matroska) piece starts with the EBML header. */
    fun isWebm(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 0x1a.toByte() && b[1] == 0x45.toByte() && b[2] == 0xdf.toByte() && b[3] == 0xa3.toByte()
}
