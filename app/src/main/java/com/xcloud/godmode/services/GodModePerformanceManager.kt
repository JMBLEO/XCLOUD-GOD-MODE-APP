package com.xcloud.godmode.services

import com.xcloud.godmode.streaming.NativeStreamBridge

/**
 * GodModePerformanceManager: Manages system-level performance tuning for the streaming engine.
 * Handles CPU affinity, thread priority, and thermal-aware load balancing.
 */
object GodModePerformanceManager {

    private const val TAG = "PerformanceManager"

    /**
     * Core Masks for Affinity (assuming 8-core Big.LITTLE architecture)
     * 0xF0 (cores 4, 5, 6, 7) are usually the "Performance" (Big) cores.
     */
    private const val PERFORMANCE_CORE_MASK = 0xF0
    private const val EFFICIENCY_CORE_MASK = 0x0F

    /**
     * Optimizes the current thread (e.g. the UDP network thread or Audio thread)
     * for ultra-low latency execution.
     */
    fun optimizeCurrentThreadForStreaming() {
        try {
            // Pin to Big cores and elevate to SCHED_FIFO (Real-Time) via JNI
            NativeStreamBridge.pinCurrentThreadToPerformanceTier(PERFORMANCE_CORE_MASK)
            android.util.Log.i(TAG, "Current thread successfully optimized with CPU Affinity and RT Priority")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to apply thread optimization: ${e.message}")
        }
    }

    /**
     * Adjusts the rendering pipeline to balance performance and thermals.
     * @param isThermalThrottling True if the device is overheating.
     */
    fun adaptStreamingPerformance(isThermalThrottling: Boolean) {
        if (isThermalThrottling) {
            android.util.Log.w(TAG, "Thermal stress detected! Forcing quality degradation for hardware safety.")
            NativeStreamBridge.forceThermalCritical()
            NativeStreamBridge.setFrameRateCap(45)
        } else {
            android.util.Log.i(TAG, "Normal thermal state restored. Restoring 60/120FPS limits.")
            NativeStreamBridge.setFrameRateCap(120)
        }
    }
}
