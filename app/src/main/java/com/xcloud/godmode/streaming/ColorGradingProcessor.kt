package com.xcloud.godmode.streaming

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

data class ColorGradingSettings(
    val saturation: Float = 1.0f, // 0.0 to 2.0
    val contrast: Float = 1.0f,   // 0.5 to 2.0
    val brightness: Float = 0.0f, // -0.5 to 0.5
    val gamma: Float = 1.0f       // 0.5 to 2.0
)

/**
 * ColorGradingProcessor: Handles video post-processing adjustments (saturation, contrast,
 * brightness, and gamma) via direct color matrices and GLSL uniforms.
 */
class ColorGradingProcessor {
    var settings: ColorGradingSettings = ColorGradingSettings()

    fun buildColorMatrix(): ColorMatrix {
        val cm = ColorMatrix()

        // 1. Saturation
        cm.setSaturation(settings.saturation)

        // 2. Contrast & Brightness
        val scale = settings.contrast
        val translate = (settings.brightness * 255f) + (1f - scale) * 128f / 2f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)
        return cm
    }

    fun buildColorFilter(): ColorMatrixColorFilter {
        return ColorMatrixColorFilter(buildColorMatrix())
    }
}
