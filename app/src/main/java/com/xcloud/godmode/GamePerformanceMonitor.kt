package com.xcloud.godmode

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PerformanceMetrics(
    val fps: Int = 0,
    val latency: Int = 0,
    val jitter: Int = 0,
    val packetLoss: Float = 0f,
    val bitrateKbps: Int = 0,
    val decodeTimeMs: Float = 0f
)

class GamePerformanceMonitor : ViewModel() {

    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    fun updateMetrics(
        fps: Int? = null,
        latency: Int? = null,
        jitter: Int? = null,
        packetLoss: Float? = null,
        bitrate: Int? = null,
        decodeTime: Float? = null
    ) {
        val current = _metrics.value
        _metrics.value = current.copy(
            fps = fps ?: current.fps,
            latency = latency ?: current.latency,
            jitter = jitter ?: current.jitter,
            packetLoss = packetLoss ?: current.packetLoss,
            bitrateKbps = bitrate ?: current.bitrateKbps,
            decodeTimeMs = decodeTime ?: current.decodeTimeMs
        )
    }

    fun getActionableSummary(): String {
        val m = _metrics.value
        return "FPS: ${m.fps}, Latency: ${m.latency}ms, Jitter: ${m.jitter}ms, Loss: ${m.packetLoss}%, Bitrate: ${m.bitrateKbps}kbps, Decode: ${m.decodeTimeMs}ms"
    }
}
