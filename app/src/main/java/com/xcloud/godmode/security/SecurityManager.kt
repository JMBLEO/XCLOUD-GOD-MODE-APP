package com.xcloud.godmode.security

import android.util.Log

object SecurityManager {
    private const val TAG = "GodModeSecurity"

    init {
        try {
            System.loadLibrary("godmode_native")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library for security", e)
        }
    }

    fun startSecurityWatchdog() {
        try {
            nativeStartWatchdog()
            Log.i(TAG, "Security watchdog started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start security watchdog", e)
        }
    }

    private external fun nativeStartWatchdog()
}
