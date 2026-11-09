package com.xcloud.godmode.services

import android.util.Log

/**
 * Phase 80: Smart Standby & Session Pause Retention.
 * Throttle stream down to 80 kbps keepalive upon app backgrounding or incoming telephone call,
 * preventing cloud session disconnect, and instantly resumes video playback in <250ms on return.
 */
class SmartStandbyController(
    private val onThrottledModeChanged: (isThrottled: Boolean, targetBitrateBps: Int) -> Unit,
    private val onFastResumeRequested: () -> Unit
) {

    companion object {
        private const val TAG = "SmartStandby"
        const val STANDBY_BITRATE_BPS = 80_000 // 80 kbps heartbeat
        const val RESUME_TARGET_BITRATE_BPS = 15_000_000
    }

    enum class StandbyState {
        ACTIVE,
        STANDBY_PAUSED,
        RESUMING
    }

    private var state = StandbyState.ACTIVE
    private var standbyEnteredTimestamp = 0L

    fun onEnterBackground() {
        if (state == StandbyState.STANDBY_PAUSED) return
        Log.i(TAG, "Entering background. Engaging Smart Standby (80 kbps heartbeat mode).")
        state = StandbyState.STANDBY_PAUSED
        standbyEnteredTimestamp = System.currentTimeMillis()
        onThrottledModeChanged(true, STANDBY_BITRATE_BPS)
    }

    fun onReturnToForeground() {
        if (state != StandbyState.STANDBY_PAUSED) return
        val pausedDurationMs = System.currentTimeMillis() - standbyEnteredTimestamp
        Log.i(TAG, "Returning to foreground after ${pausedDurationMs}ms. Initiating fast resume (<250ms).")
        state = StandbyState.RESUMING
        onThrottledModeChanged(false, RESUME_TARGET_BITRATE_BPS)
        onFastResumeRequested()
        state = StandbyState.ACTIVE
    }

    fun isPaused(): Boolean = state == StandbyState.STANDBY_PAUSED
}
