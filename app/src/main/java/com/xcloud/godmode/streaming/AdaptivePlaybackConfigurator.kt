package com.xcloud.godmode.streaming

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/**
 * Phase 56: Adaptive Playback Configurator.
 * Configures MediaCodec for seamless dynamic resolution switching without Surface reallocation.
 * Reserves maximum canvas capabilities (up to 1920x1080 / 2560x1440) using FEATURE_AdaptivePlayback.
 */
class AdaptivePlaybackConfigurator {

    companion object {
        private const val TAG = "AdaptivePlayback"
        const val DEFAULT_MAX_WIDTH = 1920
        const val DEFAULT_MAX_HEIGHT = 1080
        const val QHD_MAX_WIDTH = 2560
        const val QHD_MAX_HEIGHT = 1440
    }

    data class AdaptiveCapabilities(
        val isAdaptivePlaybackSupported: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val preferredColorFormat: Int
    )

    /**
     * Inspects codec capabilities and applies adaptive playback format keys.
     */
    fun configureAdaptivePlayback(
        format: MediaFormat,
        codecCapabilities: MediaCodecInfo.CodecCapabilities?,
        targetMaxWidth: Int = DEFAULT_MAX_WIDTH,
        targetMaxHeight: Int = DEFAULT_MAX_HEIGHT
    ): AdaptiveCapabilities {
        val supportsAdaptive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            codecCapabilities?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback) == true
        } else {
            false
        }

        if (supportsAdaptive) {
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, targetMaxWidth)
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, targetMaxHeight)
            Log.d(TAG, "Adaptive Playback enabled: max Canvas $targetMaxWidth x $targetMaxHeight")
        } else {
            Log.w(TAG, "FEATURE_AdaptivePlayback not supported by hardware decoder. Operating in fixed resolution mode.")
        }

        // Low latency priority flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
        }

        return AdaptiveCapabilities(
            isAdaptivePlaybackSupported = supportsAdaptive,
            maxWidth = if (supportsAdaptive) targetMaxWidth else format.getInteger(MediaFormat.KEY_WIDTH, targetMaxWidth),
            maxHeight = if (supportsAdaptive) targetMaxHeight else format.getInteger(MediaFormat.KEY_HEIGHT, targetMaxHeight),
            preferredColorFormat = codecCapabilities?.colorFormats?.firstOrNull() ?: 0
        )
    }

    /**
     * Determines whether an incoming resolution change can be handled seamlessly
     * without decoder reconfiguration.
     */
    fun canSeamlesslyScale(
        currentCapabilities: AdaptiveCapabilities,
        newWidth: Int,
        newHeight: Int
    ): Boolean {
        if (!currentCapabilities.isAdaptivePlaybackSupported) return false
        return newWidth <= currentCapabilities.maxWidth && newHeight <= currentCapabilities.maxHeight
    }
}
