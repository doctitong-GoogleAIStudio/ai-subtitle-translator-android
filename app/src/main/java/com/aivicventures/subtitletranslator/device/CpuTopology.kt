package com.aivicventures.subtitletranslator.device

import android.util.Log
import com.aivicventures.subtitletranslator.asr.WhisperNative
import java.io.File

/**
 * Which CPU cores are fast. Phones mix fast and slow cores (big.LITTLE); whisper.cpp splits
 * each matrix evenly over its threads, so one thread on a slow core holds all the others
 * back. Cores are told apart by the kernel's cpu_capacity (speed times IPC, 1024 for the
 * fastest core; present with the energy-aware scheduler on most phones since 2018), else by
 * top frequency, which misses cores that clock high but do little per cycle (A55 at 2.0 GHz
 * next to A76 at 2.2 GHz). Both are in sysfs, readable without any permission on stock
 * Android; when neither can be read, every core counts as fast.
 */
object CpuTopology {
    private const val TAG = "CpuTopology"

    private val possible: List<Int> by lazy {
        runCatching { parseCpuList(File("/sys/devices/system/cpu/possible").readText()) }
            .getOrElse { (0 until coreCount).toList() }
    }

    /** Top frequency in kHz per core id (empty when sysfs is not readable). */
    val maxFreqKhz: Map<Int, Long> by lazy { readPerCore("cpufreq/cpuinfo_max_freq") }

    /** Scheduler capacity per core id, 1024 for the fastest (empty on older kernels). */
    val capacity: Map<Int, Long> by lazy { readPerCore("cpu_capacity") }

    private val metric: Map<Int, Long> get() = capacity.takeIf { it.size > 1 } ?: maxFreqKhz

    val coreCount: Int get() = Runtime.getRuntime().availableProcessors()

    /** The prime and performance cores: see [fastOf]. */
    val fastCores: List<Int> by lazy {
        val byCapacity = capacity.size > 1
        fastOf(metric, if (byCapacity) CAPACITY_SHARE else FREQUENCY_SHARE).ifEmpty { (0 until coreCount).toList() }.also {
            Log.i(TAG, "cores=$coreCount capacity=$capacity freqs=$maxFreqKhz fast=$it")
        }
    }

    /** True when the phone has slow cores worth avoiding. */
    val heterogeneous: Boolean get() = metric.isNotEmpty() && fastCores.size < metric.size

    /** Default thread count: the fast cores, at most 4 (more threads rarely help Whisper). */
    fun recommendedThreads(): Int = fastCores.size.coerceIn(1, 4)

    /** Little cores have about a third of a big core's capacity; mid cores 60–90 %. */
    const val CAPACITY_SHARE = 0.5
    /** Without capacity, frequency is all there is: big cores clock at least ~1.3× little ones. */
    const val FREQUENCY_SHARE = 0.8

    /** Cores whose [values] (capacity or top frequency) reach [share] of the highest. */
    fun fastOf(values: Map<Int, Long>, share: Double): List<Int> {
        val top = values.values.maxOrNull() ?: return emptyList()
        if (top <= 0) return emptyList()
        return values.filterValues { it >= top * share }.keys.sorted()
    }

    /** Parses a sysfs CPU list such as "0-3,6". */
    fun parseCpuList(text: String): List<Int> = text.trim().split(',').filter { it.isNotBlank() }.flatMap { part ->
        val bounds = part.trim().split('-').map { it.trim().toInt() }
        if (bounds.size == 2) (bounds[0]..bounds[1]).toList() else listOf(bounds[0])
    }

    /**
     * Restricts the calling thread to [cpus]. Threads it starts later inherit the mask, which
     * is how whisper.cpp's and CTranslate2's worker threads end up on the same cores.
     */
    fun pinCurrentThread(cpus: List<Int>): Boolean =
        cpus.isNotEmpty() && runCatching { WhisperNative.setThreadAffinity(cpus.toIntArray()) }.getOrDefault(false)

    /** Runs [block] with the calling thread pinned to [cpus], then restores its previous mask. */
    fun <T> withPinned(cpus: List<Int>?, block: () -> T): T {
        if (cpus.isNullOrEmpty()) return block()
        val previous = runCatching { WhisperNative.getThreadAffinity() }.getOrNull()
        pinCurrentThread(cpus)
        try {
            return block()
        } finally {
            if (previous != null && previous.isNotEmpty()) runCatching { WhisperNative.setThreadAffinity(previous) }
        }
    }

    private fun readPerCore(file: String): Map<Int, Long> = possible.mapNotNull { id ->
        runCatching { File("/sys/devices/system/cpu/cpu$id/$file").readText().trim().toLong() }
            .getOrNull()?.let { id to it }
    }.toMap()
}
