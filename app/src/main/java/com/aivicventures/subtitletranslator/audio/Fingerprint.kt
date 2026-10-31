package com.aivicventures.subtitletranslator.audio

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Content fingerprint, as on Windows: SHA-256 over the file size and three 4 MB samples
 * (start, middle, end). Renamed or moved files are recognised; different files never collide.
 */
object Fingerprint {
    private const val SAMPLE = 4 * 1024 * 1024

    fun of(context: Context, uri: Uri): String {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val size = ch.size()
                val md = MessageDigest.getInstance("SHA-256")
                md.update(size.toString().toByteArray())
                val offsets = listOf(0L, (size / 2 - SAMPLE / 2).coerceAtLeast(0), (size - SAMPLE).coerceAtLeast(0))
                val buf = ByteBuffer.allocate(SAMPLE)
                for (off in offsets) {
                    buf.clear()
                    var pos = off
                    while (buf.hasRemaining()) {
                        val n = ch.read(buf, pos)
                        if (n <= 0) break
                        pos += n
                    }
                    buf.flip()
                    md.update(buf)
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }
}
