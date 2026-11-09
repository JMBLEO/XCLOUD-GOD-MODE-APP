package com.xcloud.godmode.streaming

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * RtccAbraController: Adaptive Bitrate (ABR) Controller based on RTCP metrics.
 * Monitors RTT and Packet Loss to adjust bitrate in 15 Mbps steps.
 * 
 * Adaptive rate control algorithm:
 * - Increases bitrate if network is stable (Low loss, low RTT).
 * - Decreases bitrate aggressively if congestion is detected.
 */
class RtccAbraController(
    private val minBitrateMbps: Int = 15,
    private val maxBitrateMbps: Int = 150,
    private val stepMbps: Int = 15
) {
    private val TAG = "RtccAbraController"

    private val _currentBitrateMbps = MutableStateFlow(45) // Default start
    val currentBitrateMbps: StateFlow<Int> = _currentBitrateMbps.asStateFlow()

    private var consecutiveLowLoss = 0
    private var lastAdjustmentTime = 0L
    private val adjustmentCooldownMs = 3000L

    /**
     * Evaluates metrics and updates bitrate if necessary.
     * @return true if bitrate was adjusted.
     */
    fun evaluate(packetLossPct: Float, rttMs: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAdjustmentTime < adjustmentCooldownMs) return false

        var adjusted = false
        val current = _currentBitrateMbps.value

        // Congestion Detection: Aggressive Decrease
        if (packetLossPct > 3.0f || rttMs > 150) {
            val next = max(minBitrateMbps, current - stepMbps)
            if (next != current) {
                _currentBitrateMbps.value = next
                lastAdjustmentTime = now
                consecutiveLowLoss = 0
                Log.w(TAG, "Congestion detected! Loss=$packetLossPct%, RTT=$rttMs. Bitrate decreased to $next Mbps")
                adjusted = true
            }
        } 
        // Stability Detection: Gradual Increase
        else if (packetLossPct < 0.5f && rttMs < 60) {
            consecutiveLowLoss++
            // Require 5 consecutive stable ticks (approx 15s if ticks are every 3s)
            if (consecutiveLowLoss >= 5) {
                val next = min(maxBitrateMbps, current + stepMbps)
                if (next != current) {
                    _currentBitrateMbps.value = next
                    lastAdjustmentTime = now
                    consecutiveLowLoss = 0
                    Log.i(TAG, "Network stable. Bitrate increased to $next Mbps")
                    adjusted = true
                }
            }
        } else {
            consecutiveLowLoss = 0
        }

        return adjusted
    }

    fun forceBitrate(mbps: Int) {
        _currentBitrateMbps.value = mbps.coerceIn(minBitrateMbps, maxBitrateMbps)
    }
}
