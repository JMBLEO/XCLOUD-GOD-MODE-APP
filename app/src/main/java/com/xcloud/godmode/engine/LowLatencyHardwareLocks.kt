package com.xcloud.godmode.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * LowLatencyHardwareLocks: Ensures the device stays awake and in low-latency mode
 * during active streaming sessions.
 */
class LowLatencyHardwareLocks(context: Context) {
    private val TAG = "HardwareLocks"
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "GodMode:StreamingWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 60 * 1000L)
            }

            if (wifiLock == null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GodMode:StreamingWifiLock")
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
            Log.d(TAG, "Hardware locks acquired (WakeLock + LowLatencyWifi)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire hardware locks", e)
        }
    }

    fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            Log.d(TAG, "Hardware locks released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing hardware locks", e)
        }
    }
}
