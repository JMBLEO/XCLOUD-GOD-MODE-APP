package com.xcloud.godmode.services

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log

/**
 * Phase 70: Low Memory Resilience Service.
 * Implements ComponentCallbacks2 to dynamically shrink buffers, evict non-essential caches,
 * and shield the foreground stream pipeline when the device experiences severe RAM pressure.
 */
class LowMemoryResilienceService(private val context: Context) : ComponentCallbacks2 {

    companion object {
        private const val TAG = "LowMemoryResilience"
    }

    interface MemoryTrimListener {
        fun onTrimCaches()
        fun onCompactBuffers()
    }

    private val listeners = mutableListOf<MemoryTrimListener>()

    fun registerListener(listener: MemoryTrimListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: MemoryTrimListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun start() {
        context.registerComponentCallbacks(this)
        Log.d(TAG, "LowMemoryResilienceService registered to ComponentCallbacks2")
    }

    fun stop() {
        context.unregisterComponentCallbacks(this)
    }

    override fun onTrimMemory(level: Int) {
        Log.w(TAG, "onTrimMemory triggered with level: $level")
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.e(TAG, "Critical memory pressure! Triggering aggressive cache eviction and buffer compaction.")
                notifyTrim(compactBuffers = true)
                System.gc()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "Moderate memory pressure. Purging soft image/catalog caches.")
                notifyTrim(compactBuffers = false)
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI went to background; free unneeded visual elements
                notifyTrim(compactBuffers = false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No-op
    }

    override fun onLowMemory() {
        Log.e(TAG, "System onLowMemory! Emergency compaction.")
        notifyTrim(compactBuffers = true)
        System.gc()
    }

    private fun notifyTrim(compactBuffers: Boolean) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                listener.onTrimCaches()
                if (compactBuffers) {
                    listener.onCompactBuffers()
                }
            }
        }
    }
}
