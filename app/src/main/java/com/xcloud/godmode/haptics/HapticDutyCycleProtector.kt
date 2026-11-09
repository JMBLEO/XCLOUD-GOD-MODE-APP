package com.xcloud.godmode.haptics

import android.os.SystemClock
import java.util.ArrayDeque

/**
 * HapticDutyCycleProtector: Protects controller trigger coils and haptic actuators
 * from electromagnetic thermal stress during sustained heavy vibration (e.g. racing/shooters).
 * Evaluates energy integral over a 10-second sliding window: E = ∫ (amplitude)^2 dt.
 * Attenuates peak force by 20% when cumulative thermal load exceeds the 75% threshold.
 */
class HapticDutyCycleProtector(
    private val windowDurationMs: Long = 10_000L,
    private val maxSafeEnergyThreshold: Float = 7_500f // 75% threshold for 10s window
) {
    private data class VibrationSample(val timestampMs: Long, val energy: Float)

    private val samples = ArrayDeque<VibrationSample>()
    private var currentAccumulatedEnergy = 0f

    var isThermalThrottleActive: Boolean = false
        private set

    /**
     * Modulates requested vibration strength [0.0f, 1.0f] according to thermal budget.
     */
    @Synchronized
    fun processVibrationRequest(requestedAmplitude: Float, durationMs: Long): Float {
        val now = SystemClock.elapsedRealtime()
        pruneExpiredSamples(now)

        val clampedAmp = requestedAmplitude.coerceIn(0f, 1f)
        val sampleEnergy = (clampedAmp * clampedAmp) * durationMs
        samples.addLast(VibrationSample(now + durationMs, sampleEnergy))
        currentAccumulatedEnergy += sampleEnergy

        isThermalThrottleActive = currentAccumulatedEnergy > maxSafeEnergyThreshold

        return if (isThermalThrottleActive) {
            // Soft 20% attenuation preserving profile feel without abrupt cuts
            clampedAmp * 0.80f
        } else {
            clampedAmp
        }
    }

    private fun pruneExpiredSamples(now: Long) {
        val cutoff = now - windowDurationMs
        while (samples.isNotEmpty() && samples.first.timestampMs < cutoff) {
            val removed = samples.removeFirst()
            currentAccumulatedEnergy = (currentAccumulatedEnergy - removed.energy).coerceAtLeast(0f)
        }
    }

    @Synchronized
    fun reset() {
        samples.clear()
        currentAccumulatedEnergy = 0f
        isThermalThrottleActive = false
    }
}
