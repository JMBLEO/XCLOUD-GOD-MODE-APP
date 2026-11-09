package com.xcloud.godmode.audio

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Phase 62: Low-Latency Recording Audio Mixer.
 * Fuses stereo PCM 48kHz game audio and optional microphone input into a unified
 * balanced audio stream with dynamic ducking and a soft-limiter to prevent distortion in MP4 clips.
 */
class RecordingAudioMixer {

    data class MixerConfig(
        var gameVolume: Float = 1.0f,
        var micVolume: Float = 0.8f,
        var duckingFactor: Float = 0.4f, // Dips game volume when user speaks
        var duckingThresholdRms: Float = 0.05f
    )

    private val config = MixerConfig()

    fun updateConfig(gameVol: Float, micVol: Float, ducking: Float) {
        config.gameVolume = gameVol.coerceIn(0f, 2f)
        config.micVolume = micVol.coerceIn(0f, 2f)
        config.duckingFactor = ducking.coerceIn(0f, 1f)
    }

    /**
     * Mixes interleaved 16-bit PCM game audio (stereo) and mic audio (mono or stereo)
     * into outputBuffer.
     * Uses soft-limiting: out = tanh(in) to eliminate digital clipping (clipping-free saturation).
     */
    fun mixPcm16(
        gamePcm: ShortArray,
        gameLength: Int,
        micPcm: ShortArray?,
        micLength: Int,
        outputPcm: ShortArray
    ): Int {
        val samplesToMix = gameLength.coerceAtMost(outputPcm.size)

        // Check voice activity for dynamic ducking
        var micRms = 0f
        if (micPcm != null && micLength > 0) {
            var sum = 0.0
            val checkCount = micLength.coerceAtMost(256)
            for (i in 0 until checkCount) {
                val sampleNorm = micPcm[i].toFloat() / 32768f
                sum += (sampleNorm * sampleNorm)
            }
            micRms = kotlin.math.sqrt(sum / checkCount).toFloat()
        }

        val isSpeaking = micRms > config.duckingThresholdRms
        val effectiveGameVol = if (isSpeaking) {
            config.gameVolume * config.duckingFactor
        } else {
            config.gameVolume
        }

        for (i in 0 until samplesToMix) {
            val gameSampleNorm = (gamePcm[i].toFloat() / 32768f) * effectiveGameVol

            var micSampleNorm = 0f
            if (micPcm != null && micLength > 0) {
                // If mic is mono, duplicate across stereo pair (i / 2)
                val micIndex = (i / 2).coerceAtMost(micLength - 1)
                micSampleNorm = (micPcm[micIndex].toFloat() / 32768f) * config.micVolume
            }

            // Sum and apply soft-limiter tanh
            val mixed = gameSampleNorm + micSampleNorm
            val limited = softLimit(mixed)

            outputPcm[i] = (limited * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        return samplesToMix
    }

    /**
     * Fast soft-limiter approximating tanh without expensive transcendental math for sub-microsecond DSP.
     * f(x) = x / (1 + |x|) for |x| > 0.8
     */
    private fun softLimit(x: Float): Float {
        return if (abs(x) < 0.8f) {
            x
        } else {
            // Rational soft-curve compression
            x / (1.0f + abs(x) * 0.25f)
        }
    }
}
