package com.xcloud.godmode.network

/**
 * Result of the pre-flight queue evaluation.
 */
sealed class PreFlightQueueStatus {
    object NoQueue : PreFlightQueueStatus()
    data class QueueAcceptable(val estimatedWaitSeconds: Int) : PreFlightQueueStatus()
    data class QueueCrowded(
        val estimatedWaitSeconds: Int,
        val recommendedAlternativeRegion: DatacenterInfo?
    ) : PreFlightQueueStatus()
}

/**
 * Intercepts session provisioning telemetry to detect server queues (>120 seconds)
 * and proactively route to fast nearby nodes.
 */
object PreFlightQueueInterceptor {
    private const val CROWDED_THRESHOLD_SECONDS = 120

    fun evaluateQueue(
        estimatedWaitTimeSeconds: Int,
        currentRegionId: String,
        telemetryProvider: DatacenterTelemetryProvider
    ): PreFlightQueueStatus {
        if (estimatedWaitTimeSeconds <= 0) {
            return PreFlightQueueStatus.NoQueue
        }

        if (estimatedWaitTimeSeconds <= CROWDED_THRESHOLD_SECONDS) {
            return PreFlightQueueStatus.QueueAcceptable(estimatedWaitTimeSeconds)
        }

        // Find fastest alternative region with low RTT
        val allDatacenters = telemetryProvider.datacenters.value
        val alternative = allDatacenters.firstOrNull { dc ->
            dc.id != currentRegionId &&
            dc.healthTier != DatacenterHealthTier.UNREACHABLE &&
            (dc.rttMs ?: Long.MAX_VALUE) < 120L
        }

        return PreFlightQueueStatus.QueueCrowded(
            estimatedWaitSeconds = estimatedWaitTimeSeconds,
            recommendedAlternativeRegion = alternative
        )
    }
}
