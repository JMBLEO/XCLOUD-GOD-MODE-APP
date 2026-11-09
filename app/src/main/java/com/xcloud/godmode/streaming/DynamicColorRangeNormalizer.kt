package com.xcloud.godmode.streaming

import android.media.MediaFormat
import android.os.Build

/**
 * Phase 57: Dynamic Color Range Normalizer.
 * Calibrates Full Range RGB (0-255) vs Limited Range YUV (16-235).
 * Prevents black crush or washed out grays on OLED panels and external HDMI displays.
 */
class DynamicColorRangeNormalizer {

    enum class ColorRangeMode {
        AUTO,
        FULL_RANGE_RGB,      // 0 - 255 (Optimal for high gamut OLED & Native PC displays)
        LIMITED_RANGE_YUV,   // 16 - 235 (Standard broadcast & legacy HDMI displays)
        HDR10_WIDE_BT2020    // Wide gamut HDR 10-bit
    }

    data class ColorMatrixSettings(
        val rangeMode: ColorRangeMode,
        val blackLevelOffset: Float, // Adjusts pedestal
        val contrastFactor: Float,
        val saturationMultiplier: Float,
        val glslMatrix: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ColorMatrixSettings) return false
            return rangeMode == other.rangeMode &&
                    blackLevelOffset == other.blackLevelOffset &&
                    contrastFactor == other.contrastFactor &&
                    saturationMultiplier == other.saturationMultiplier &&
                    glslMatrix.contentEquals(other.glslMatrix)
        }

        override fun hashCode(): Int {
            var result = rangeMode.hashCode()
            result = 31 * result + blackLevelOffset.hashCode()
            result = 31 * result + contrastFactor.hashCode()
            result = 31 * result + saturationMultiplier.hashCode()
            result = 31 * result + glslMatrix.contentHashCode()
            return result
        }
    }

    /**
     * Injects color format and range metadata into MediaFormat prior to codec initialization.
     */
    fun injectColorMetadata(format: MediaFormat, mode: ColorRangeMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            when (mode) {
                ColorRangeMode.FULL_RANGE_RGB -> {
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
                ColorRangeMode.LIMITED_RANGE_YUV -> {
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
                ColorRangeMode.HDR10_WIDE_BT2020 -> {
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                }
                ColorRangeMode.AUTO -> {
                    // Let hardware negotiate standard BT709
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
            }
        }
    }

    /**
     * Builds standard 4x4 matrix representation for post-processing shaders.
     */
    fun computeShaderMatrix(mode: ColorRangeMode, isOledDisplay: Boolean): ColorMatrixSettings {
        return when (mode) {
            ColorRangeMode.FULL_RANGE_RGB -> {
                // Expansion: [16, 235] -> [0, 255]
                // scale = 255.0 / (235.0 - 16.0) = 1.16438
                val scale = 1.16438f
                val offset = -16f / 255f * scale
                val matrix = floatArrayOf(
                    scale, 0f, 0f, 0f,
                    0f, scale, 0f, 0f,
                    0f, 0f, scale, 0f,
                    offset, offset, offset, 1f
                )
                ColorMatrixSettings(
                    rangeMode = mode,
                    blackLevelOffset = offset,
                    contrastFactor = 1.05f,
                    saturationMultiplier = if (isOledDisplay) 1.0f else 1.08f,
                    glslMatrix = matrix
                )
            }
            ColorRangeMode.LIMITED_RANGE_YUV -> {
                // Identity limited pass-through
                val matrix = floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f
                )
                ColorMatrixSettings(
                    rangeMode = mode,
                    blackLevelOffset = 0f,
                    contrastFactor = 1.0f,
                    saturationMultiplier = 1.0f,
                    glslMatrix = matrix
                )
            }
            ColorRangeMode.HDR10_WIDE_BT2020 -> {
                val matrix = floatArrayOf(
                    1.1f, 0f, 0f, 0f,
                    0f, 1.1f, 0f, 0f,
                    0f, 0f, 1.1f, 0f,
                    0f, 0f, 0f, 1f
                )
                ColorMatrixSettings(
                    rangeMode = mode,
                    blackLevelOffset = 0f,
                    contrastFactor = 1.12f,
                    saturationMultiplier = 1.15f,
                    glslMatrix = matrix
                )
            }
            ColorRangeMode.AUTO -> {
                computeShaderMatrix(
                    if (isOledDisplay) ColorRangeMode.FULL_RANGE_RGB else ColorRangeMode.LIMITED_RANGE_YUV,
                    isOledDisplay
                )
            }
        }
    }
}
