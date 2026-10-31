package com.aivicventures.subtitletranslator.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited resampling (windowed sinc, Blackman window). Common rates (48, 44.1, 32,
 * 22.05 kHz → 16 kHz) use a precomputed polyphase filter bank, so 30 s of 48 kHz audio
 * converts in a few tens of milliseconds on a phone.
 */
object Resampler {
    private const val ZERO_CROSSINGS = 12
    private const val MAX_PHASES = 1000

    private class Bank(val up: Int, val down: Int, val taps: Int, val coeffs: Array<FloatArray>, val offset: Int)

    private val banks = HashMap<Long, Bank>()

    private fun window(x: Double): Double {
        val w = x / ZERO_CROSSINGS
        return if (kotlin.math.abs(w) >= 1.0) 0.0 else 0.42 + 0.5 * cos(PI * w) + 0.08 * cos(2 * PI * w)
    }

    private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Filter bank for from → to: output n reads input around n * down / up. */
    @Synchronized
    private fun bank(from: Int, to: Int): Bank? {
        val g = gcd(from, to)
        val up = to / g
        val down = from / g
        if (up > MAX_PHASES) return null
        return banks.getOrPut(from.toLong() * 1_000_000 + to) {
            val cutoff = min(1.0, to.toDouble() / from) * 0.95 // below the new Nyquist frequency
            val half = kotlin.math.ceil(ZERO_CROSSINGS / cutoff).toInt()
            val taps = 2 * half
            val coeffs = Array(up) { phase ->
                // Output position within the input, fractional part = phase / up.
                val frac = phase.toDouble() / up
                FloatArray(taps) { k ->
                    val x = (k - half + 1) - frac // input index relative to floor(t)
                    (sinc(x * cutoff) * window(x * cutoff) * cutoff).toFloat()
                }
            }
            Bank(up, down, taps, coeffs, half - 1)
        }
    }

    /** Resamples [src] (rate [from]) to [outLen] samples at rate [to]. */
    fun resample(src: FloatArray, from: Int, to: Int, outLen: Int): FloatArray {
        val out = FloatArray(outLen)
        if (from == to) {
            System.arraycopy(src, 0, out, 0, min(src.size, outLen))
            return out
        }
        val b = bank(from, to) ?: return resampleDirect(src, from, to, outLen)
        for (n in 0 until outLen) {
            val pos = n.toLong() * b.down
            val base = (pos / b.up).toInt()
            val c = b.coeffs[(pos % b.up).toInt()]
            val first = base - b.offset
            var acc = 0f
            if (first >= 0 && first + b.taps <= src.size) {
                for (k in 0 until b.taps) acc += src[first + k] * c[k]
            } else {
                for (k in 0 until b.taps) {
                    val i = first + k
                    if (i >= 0 && i < src.size) acc += src[i] * c[k]
                }
            }
            out[n] = acc
        }
        return out
    }

    /** Slow path for unusual rate pairs (gcd too small for a filter bank). */
    private fun resampleDirect(src: FloatArray, from: Int, to: Int, outLen: Int): FloatArray {
        val out = FloatArray(outLen)
        val ratio = to.toDouble() / from
        val cutoff = min(1.0, ratio) * 0.95
        val halfWidth = ZERO_CROSSINGS / cutoff
        for (n in 0 until outLen) {
            val t = n / ratio
            val lo = maxOf(0, floor(t - halfWidth).toInt() + 1)
            val hi = min(src.size - 1, floor(t + halfWidth).toInt())
            var acc = 0.0
            for (k in lo..hi) {
                val x = (t - k) * cutoff
                acc += src[k] * sinc(x) * window(x)
            }
            out[n] = (acc * cutoff).toFloat()
        }
        return out
    }
}
