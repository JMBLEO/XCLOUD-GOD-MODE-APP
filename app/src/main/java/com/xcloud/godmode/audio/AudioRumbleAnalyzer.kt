package com.xcloud.godmode.audio

import kotlin.math.sqrt

/**
 * AudioRumbleAnalyzer: Evaluates RMS energy in the low-frequency PCM spectrum in real-time,
 * generating acoustic haptic feedback for game events (explosions, gunshots, impacts).
 */
class AudioRumbleAnalyzer(
    private val rumbleThresholdRms: Float = 0.25f,
    private val onRumbleTriggered: (intensity: Float) -> Unit
) {
    var isEnabled: Boolean = true

    fun analyzeChunk(pcmData: ByteArray, length: Int) {
        if (!isEnabled || length <= 0) return

        var sumSquares = 0.0
        var sampleCount = 0

        // Parse 16-bit PCM samples
        for (i in 0 until length - 1 step 4) { // stride 4 for stereo
            var sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            if (sample > 32767) sample -= 65536
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            sampleCount++
        }

        if (sampleCount == 0) return

        val rms = sqrt(sumSquares / sampleCount).toFloat()
        if (rms >= rumbleThresholdRms) {
            val intensity = ((rms - rumbleThresholdRms) / (1.0f - rumbleThresholdRms)).coerceIn(0.1f, 1.0f)
            onRumbleTriggered(intensity)
        }
    }
}
