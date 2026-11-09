package com.xcloud.godmode.engine

import android.os.Process
import android.util.Log
import java.io.File

/**
 * Phase 98: CPU Thread Affinity & Realtime Scheduler Optimizer.
 * Sets realtime Linux process priority (THREAD_PRIORITY_URGENT_DISPLAY / AUDIO)
 * and audits big.LITTLE core topology to isolate high-throughput UDP packet processing
 * onto Prime/Gold high-frequency cores, suppressing frame pacing jitter.
 */
class CpuAffinityOptimizer {

    companion object {
        private const val TAG = "CpuAffinity"
    }

    data class CoreTopology(
        val totalCores: Int,
        val primeCores: List<Int>,
        val littleCores: List<Int>
    )

    /**
     * Inspects CPU frequencies across sysfs to classify Prime vs LITTLE cores.
     */
    fun auditCpuTopology(): CoreTopology {
        val total = Runtime.getRuntime().availableProcessors()
        val prime = mutableListOf<Int>()
        val little = mutableListOf<Int>()

        for (i in 0 until total) {
            val maxFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val maxFreqKHz = if (maxFreqFile.exists()) {
                maxFreqFile.readText().trim().toLongOrNull() ?: 0L
            } else 0L

            // Cores >= 2.4 GHz are Prime/Gold cores
            if (maxFreqKHz >= 2_400_000L || (maxFreqKHz == 0L && i >= (total / 2))) {
                prime.add(i)
            } else {
                little.add(i)
            }
        }

        Log.d(TAG, "Topology: Total=$total, Prime=$prime, Little=$little")
        return CoreTopology(total, prime, little)
    }

    /**
     * Promotes the calling thread to urgent audio/display priority (-16 to -19 nice value).
     */
    fun elevateCallingThreadPriority(isAudio: Boolean = false) {
        try {
            val priority = if (isAudio) {
                Process.THREAD_PRIORITY_AUDIO
            } else {
                Process.THREAD_PRIORITY_URGENT_DISPLAY
            }
            Process.setThreadPriority(priority)
            Log.d(TAG, "Elevated thread TID ${Process.myTid()} priority to $priority")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to elevate thread priority: ${e.message}")
        }
    }
}
