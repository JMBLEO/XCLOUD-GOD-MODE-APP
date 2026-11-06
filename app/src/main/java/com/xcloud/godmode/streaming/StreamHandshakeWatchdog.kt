package com.xcloud.godmode.streaming

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * StreamHandshakeWatchdog: Monitors the startup phase of the stream.
 * If the first frame is not received within a specific timeout (450ms-1200ms),
 * it triggers a PLI (Picture Loss Indication) or keyframe request.
 */
class StreamHandshakeWatchdog(
    private val onTriggerPli: () -> Unit,
    private val onTriggerKeyframe: () -> Unit
) {
    private val TAG = "StreamWatchdog"
    private val handler = Handler(Looper.getMainLooper())
    private var isFrameReceived = false
    private var watchdogActive = false

    fun start(timeoutMs: Long = 850L) {
        if (watchdogActive) return
        watchdogActive = true
        isFrameReceived = false
        Log.d(TAG, "Starting watchdog (timeout: $timeoutMs ms)")
        
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isFrameReceived && watchdogActive) {
                    Log.w(TAG, "Watchdog triggered! Requesting PLI/Keyframe to wake up stream.")
                    onTriggerPli()
                    onTriggerKeyframe()
                    // Re-schedule for a second attempt if still nothing
                    handler.postDelayed(this, timeoutMs)
                }
            }
        }, timeoutMs)
    }

    fun notifyFirstFrame() {
        if (!isFrameReceived) {
            Log.d(TAG, "First frame received! Watchdog satisfied.")
        }
        isFrameReceived = true
        watchdogActive = false
        handler.removeCallbacksAndMessages(null)
    }

    fun stop() {
        watchdogActive = false
        handler.removeCallbacksAndMessages(null)
    }
}
