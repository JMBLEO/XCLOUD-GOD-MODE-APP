package com.xcloud.godmode.thermal

import android.app.Activity
import android.os.PowerManager
import android.os.PowerManager.OnThermalStatusChangedListener
import android.util.Log

/**
 * GodModeThermalGuard: Monitors SoC temperature and degrades stream quality preventively
 * when reaching severe thermal states to avoid kernel throttling.
 */
class GodModeThermalGuard(
    private val activity: Activity, 
    private val onThermalDegradeAction: () -> Unit
) {
    private val TAG = "GodModeThermal"
    private val powerManager = activity.getSystemService(Activity.POWER_SERVICE) as PowerManager
    
    private val thermalListener = OnThermalStatusChangedListener { status ->
        Log.w(TAG, "Thermal Status Changed: $status")
        when (status) {
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE -> {
                Log.w(TAG, "Moderate/Severe thermal status detected. Degrading stream quality...")
                // Action: Switch from 1440p to 1080p HQ or disable NPU Upscale
                onThermalDegradeAction()
            }
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                Log.e(TAG, "Critical/Emergency thermal status! Terminating stream for safety.")
                // Immediate shutdown to prevent device damage or reboot
                activity.finishAffinity()
            }
            else -> {
                // Normal status (NONE / LIGHT)
            }
        }
    }

    fun registerListener() {
        try {
            powerManager.addThermalStatusListener(activity.mainExecutor, thermalListener)
            Log.i(TAG, "Thermal listener registered on MainExecutor")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register thermal listener: ${e.message}")
        }
    }

    fun unregisterListener() {
        try {
            powerManager.removeThermalStatusListener(thermalListener)
            Log.i(TAG, "Thermal listener unregistered")
        } catch (_: Exception) {}
    }
}
