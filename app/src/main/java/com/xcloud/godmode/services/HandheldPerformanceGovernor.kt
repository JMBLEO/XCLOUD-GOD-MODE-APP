package com.xcloud.godmode.services

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 75: Handheld Performance Governor & DVFS Locking.
 * Sets sustained high performance mode via PowerManager and attempts
 * direct CPU governor tuning ('performance' or 'schedutil') on dedicated handhelds
 * (Ayn Odin, Razer Edge, Logitech G Cloud) to prevent frame rate drops during 120Hz streams.
 */
class HandheldPerformanceGovernor(private val context: Context) {

    companion object {
        private const val TAG = "HandheldGov"
        private const val GOVERNOR_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
    }

    enum class PerformanceProfile {
        BALANCED,
        HIGH_PERFORMANCE_120HZ,
        BATTERY_SAVER
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /**
     * Activates sustained performance mode on supported devices (Android 7.0+).
     */
    fun applySustainedPerformance(activity: Activity, enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val supported = powerManager?.isSustainedPerformanceModeSupported == true
            if (supported) {
                activity.window.setSustainedPerformanceMode(enable)
                Log.i(TAG, "Sustained performance mode set to: $enable")
            } else {
                Log.d(TAG, "Sustained performance mode not supported on this device.")
            }
        }
    }

    /**
     * Checks if device is a dedicated handheld console (by model or SoC properties).
     */
    fun isDedicatedHandheld(): Boolean {
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return model.contains("odin") ||
                model.contains("g cloud") ||
                model.contains("edge") ||
                manufacturer.contains("ayn") ||
                manufacturer.contains("ayaneo") ||
                manufacturer.contains("retroid")
    }

    /**
     * Attempts sysfs governor tuning if root / privileged permissions are available.
     */
    fun setKernelGovernor(profile: PerformanceProfile): Boolean {
        val govString = when (profile) {
            PerformanceProfile.HIGH_PERFORMANCE_120HZ -> "performance"
            PerformanceProfile.BALANCED -> "schedutil"
            PerformanceProfile.BATTERY_SAVER -> "powersave"
        }

        return try {
            val govFile = File(GOVERNOR_PATH)
            if (govFile.exists() && govFile.canWrite()) {
                FileOutputStream(govFile).use { it.write(govString.toByteArray()) }
                Log.i(TAG, "Successfully wrote '$govString' to $GOVERNOR_PATH")
                true
            } else {
                Log.d(TAG, "Governor file not writable without root: $GOVERNOR_PATH")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set kernel governor: ${e.message}")
            false
        }
    }
}
