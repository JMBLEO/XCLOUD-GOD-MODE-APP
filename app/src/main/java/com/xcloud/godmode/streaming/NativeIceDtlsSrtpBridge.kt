package com.xcloud.godmode.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * StunRttMetrics: Holds real-time network latency metrics measured by delta
 * between outgoing STUN Binding Requests (0x0001) and incoming STUN Success Responses (0x0101).
 */
data class StunRttMetrics(
    val currentRttMs: Float = 0.0f,
    val minRttMs: Float = 0.0f,
    val maxRttMs: Float = 0.0f,
    val smoothedRttMs: Float = 0.0f,
    val jitterMs: Float = 0.0f,
    val totalSuccessReceived: Long = 0L,
    val isPinging: Boolean = false,
    val lastMeasurementTimestampEpochMs: Long = System.currentTimeMillis()
)

/**
 * NativeIceDtlsSrtpBridge:
 * Kotlin-side bridge that monitors round-trip time (RTT) calculated at microsecond
 * precision within the C++ NativeIceDtlsSrtpBridge by tracking outgoing STUN Binding Requests
 * (0x0001) and their corresponding STUN Success Responses (0x0101).
 */
object NativeIceDtlsSrtpBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _metrics = MutableStateFlow(StunRttMetrics())
    val metrics: StateFlow<StunRttMetrics> = _metrics.asStateFlow()

    private var isMonitoringActive = false

    init {
        startLatencyMonitoringLoop()
    }

    fun startLatencyMonitoringLoop() {
        if (isMonitoringActive) return
        isMonitoringActive = true

        scope.launch {
            while (isActive) {
                try {
                    val raw = NativeStreamBridge.getStunLatencyMetrics()
                    if (raw != null && raw.size >= 6) {
                        val currentRtt = raw[0]
                        val minRtt = raw[1]
                        val maxRtt = raw[2]
                        val smoothedRtt = raw[3]
                        val jitter = raw[4]
                        val successCount = raw[5].toLong()

                        if (currentRtt > 0.0f) {
                            _metrics.value = StunRttMetrics(
                                currentRttMs = currentRtt,
                                minRttMs = minRtt,
                                maxRttMs = maxRtt,
                                smoothedRttMs = smoothedRtt,
                                jitterMs = jitter,
                                totalSuccessReceived = successCount,
                                isPinging = true,
                                lastMeasurementTimestampEpochMs = System.currentTimeMillis()
                            )
                        }
                    }
                } catch (_: Throwable) {}
                delay(200) // 5Hz polling for smooth Sparkline update in HUD
            }
        }
    }

    /**
     * Resets or triggers an immediate STUN measurement probe.
     */
    fun triggerProbe() {
        // Triggers immediate STUN polling update
        val raw = NativeStreamBridge.getStunLatencyMetrics()
        if (raw != null && raw.size >= 6) {
            _metrics.value = _metrics.value.copy(
                currentRttMs = raw[0],
                minRttMs = raw[1],
                maxRttMs = raw[2],
                smoothedRttMs = raw[3],
                jitterMs = raw[4],
                totalSuccessReceived = raw[5].toLong()
            )
        }
    }
}
