package com.xcloud.godmode.network

import com.xcloud.godmode.models.MetricEvidence
import com.xcloud.godmode.models.StreamConnectionMetrics
import com.xcloud.godmode.models.StreamQualityTier
import com.xcloud.godmode.models.StreamServerConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface StreamMetricsProvider {
    val metrics: StateFlow<StreamConnectionMetrics>

    fun startMonitoring(server: StreamServerConfiguration)
    fun stopMonitoring()
    suspend fun refresh()
}

class DefaultStreamMetricsProvider : StreamMetricsProvider {
    private val _metrics = MutableStateFlow(
        StreamConnectionMetrics(
            latencyMs = null,
            latencyEvidence = MetricEvidence.UNAVAILABLE,
            downstreamMbps = null,
            upstreamMbps = null,
            bandwidthEvidence = MetricEvidence.UNAVAILABLE,
            estimatedQuality = StreamQualityTier.UNAVAILABLE,
            qualityEvidence = MetricEvidence.UNAVAILABLE,
            selectedServerReachable = false,
            measuredAtEpochMs = null,
            measurementNote = "Sin mediciones activas."
        )
    )
    override val metrics: StateFlow<StreamConnectionMetrics> = _metrics.asStateFlow()

    override fun startMonitoring(server: StreamServerConfiguration) {
        _metrics.value = _metrics.value.copy(
            measurementNote = "Monitoreando servidor aplicado: ${server.displayName}"
        )
    }

    override fun stopMonitoring() {
        _metrics.value = _metrics.value.copy(
            measurementNote = "Monitoreo detenido."
        )
    }

    override suspend fun refresh() {
        // Will evaluate actual socket TCP handshake latency when host is available
    }
}
