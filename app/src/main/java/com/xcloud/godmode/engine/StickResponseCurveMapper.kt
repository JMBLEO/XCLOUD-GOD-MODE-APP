package com.xcloud.godmode.engine

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Phase 79: Analog Stick Response Curve Mapper & Elliptical Deadzone.
 * Shapes analog stick inputs (Left / Right sticks) across mathematical response curves:
 * - Linear (1:1 direct)
 * - Exponential (Fine sniper precision near center)
 * - S-Curve Smoothstep (3x^2 - 2x^3 for progressive acceleration)
 * and applies biaxial elliptical deadzones to eliminate stick drift while preserving full outer diagonals.
 */
class StickResponseCurveMapper {

    enum class ResponseCurve {
        LINEAR,
        EXPONENTIAL,
        S_CURVE_SMOOTHSTEP,
        WIDE_DYNAMIC_SIGMOID
    }

    data class StickConfig(
        var curve: ResponseCurve = ResponseCurve.LINEAR,
        var deadzoneX: Float = 0.08f, // 8% deadband
        var deadzoneY: Float = 0.08f,
        var exponentialGamma: Float = 2.2f,
        var outerDeadzone: Float = 0.98f // Ensures 100% saturation at outer ring
    )

    data class StickResult(
        val outputX: Float,
        val outputY: Float,
        val isInDeadzone: Boolean
    )

    private val leftConfig = StickConfig()
    private val rightConfig = StickConfig(curve = ResponseCurve.EXPONENTIAL) // Aim stick defaults to exponential

    fun configureLeftStick(config: StickConfig) {
        leftConfig.curve = config.curve
        leftConfig.deadzoneX = config.deadzoneX
        leftConfig.deadzoneY = config.deadzoneY
    }

    fun configureRightStick(config: StickConfig) {
        rightConfig.curve = config.curve
        rightConfig.deadzoneX = config.deadzoneX
        rightConfig.deadzoneY = config.deadzoneY
    }

    /**
     * Maps raw input coordinates (-1.0 .. 1.0) through elliptical deadzone and curve transform.
     */
    fun mapStick(rawX: Float, rawY: Float, isRightStick: Boolean = false): StickResult {
        val config = if (isRightStick) rightConfig else leftConfig

        // Elliptical deadzone calculation: (x/a)^2 + (y/b)^2
        val normalizedDistSq = (rawX / config.deadzoneX).pow(2) + (rawY / config.deadzoneY).pow(2)
        if (normalizedDistSq <= 1.0f) {
            return StickResult(0f, 0f, isInDeadzone = true)
        }

        // True radial magnitude
        val magnitude = sqrt(rawX * rawX + rawY * rawY)
        if (magnitude == 0f) return StickResult(0f, 0f, isInDeadzone = true)

        val dirX = rawX / magnitude
        val dirY = rawY / magnitude

        // Rescale magnitude from [deadzone, outerDeadzone] -> [0.0, 1.0]
        val avgDeadzone = (config.deadzoneX + config.deadzoneY) / 2f
        val scaledMagnitude = ((magnitude - avgDeadzone) / (config.outerDeadzone - avgDeadzone))
            .coerceIn(0f, 1f)

        // Apply selected curve
        val curvedMagnitude = when (config.curve) {
            ResponseCurve.LINEAR -> scaledMagnitude
            ResponseCurve.EXPONENTIAL -> scaledMagnitude.pow(config.exponentialGamma)
            ResponseCurve.S_CURVE_SMOOTHSTEP -> {
                // Smoothstep: 3x^2 - 2x^3
                3f * scaledMagnitude * scaledMagnitude - 2f * scaledMagnitude * scaledMagnitude * scaledMagnitude
            }
            ResponseCurve.WIDE_DYNAMIC_SIGMOID -> {
                // Centered sigmoid curve
                (1f / (1f + kotlin.math.exp(-10f * (scaledMagnitude - 0.5f))))
            }
        }.coerceIn(0f, 1f)

        return StickResult(
            outputX = (dirX * curvedMagnitude).coerceIn(-1f, 1f),
            outputY = (dirY * curvedMagnitude).coerceIn(-1f, 1f),
            isInDeadzone = false
        )
    }
}
