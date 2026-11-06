package com.xcloud.godmode.streaming

import android.util.Log
import com.xcloud.godmode.catalog.CloudLaunchDiagnostics
import com.xcloud.godmode.catalog.PerformanceSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * ControllerLatencyMonitor: Measures the deviation between physical input events and on-screen response.
 * Integrates results into CloudLaunchDiagnostics for analysis.
 */
class ControllerLatencyMonitor(
    private val inputBridge: NativeInputBridge,
    private val diagnostics: CloudLaunchDiagnostics,
    private val scope: CoroutineScope
) {
    private val TAG = "GodModeLatencyMonitor"
    private var monitoringJob: Job? = null
    
    private var lastMeasuredInputNano = 0L
    private var lastMeasuredFrameNano = 0L

    fun start() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch(Dispatchers.Default) {
            TelemetryBridge.telemetryFlow.collectLatest { metrics ->
                // Use the latest controller state (Player 0)
                val latestInput = inputBridge.controllers[0]
                
                if (latestInput.lastUpdateNano > lastMeasuredInputNano && metrics.lastFrameTimestampNano > lastMeasuredFrameNano) {
                    lastMeasuredInputNano = latestInput.lastUpdateNano
                    lastMeasuredFrameNano = metrics.lastFrameTimestampNano
                    
                    // Input-to-Presentation Latency Approximation
                    // Formula: (RenderTimestamp - InputArrivalTimestamp)
                    val latencyNano = metrics.lastFrameTimestampNano - latestInput.lastUpdateNano
                    val latencyMs = latencyNano / 1_000_000L
                    
                    // Filter outlier/invalid data (e.g., negative or impossible values)
                    if (latencyMs in 2..400) {
                        val jitter = calculateJitter(latencyMs)
                        
                        val snapshot = PerformanceSnapshot(
                            inputLatencyMs = latencyMs,
                            displayJitterMs = jitter,
                            epoch = System.currentTimeMillis()
                        )
                        
                        diagnostics.recordPerformanceSnapshot(snapshot)
                        
                        // Report back to TelemetryBridge for UI HUD visibility
                        TelemetryBridge.updateMetricsLocally(
                            rttMs = metrics.rttMs,
                            fps = metrics.fps,
                            packetLossPct = metrics.packetLossPct,
                            bitrateKbps = metrics.bitrateKbps,
                            activeEngineId = metrics.activeEngineId,
                            stunKeepAlives = metrics.stunKeepAlives,
                            hwFencesProcessed = metrics.hwFencesProcessed,
                            aiUpscalingActive = metrics.aiUpscalingActive,
                            thermalStatus = metrics.thermalStatus,
                            clockDriftUs = metrics.clockDriftUs,
                            lastFrameTimestampNano = metrics.lastFrameTimestampNano,
                            inputToDisplayMs = latencyMs.toInt()
                        )
                    }
                }
            }
        }
        Log.i(TAG, "ControllerLatencyMonitor iniciado con éxito.")
    }

    private var lastLatencyMs = 0L
    private var smoothedJitter = 0f
    
    private fun calculateJitter(currentLatencyMs: Long): Float {
        if (lastLatencyMs == 0L) {
            lastLatencyMs = currentLatencyMs
            return 0f
        }
        val delta = Math.abs(currentLatencyMs - lastLatencyMs).toFloat()
        lastLatencyMs = currentLatencyMs
        // RFC 3550-like smoothed jitter calculation
        smoothedJitter = (smoothedJitter * 0.9f) + (delta * 0.1f)
        return smoothedJitter
    }

    fun stop() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.i(TAG, "ControllerLatencyMonitor detenido.")
    }
}
