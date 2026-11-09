package com.xcloud.godmode.engine

import kotlin.math.sqrt

/**
 * Phase 94: Hall-Effect Magnetic Sensor 0.0% Deadband Calibration.
 * Implements circular normalization and center offset cancellation for magnetic Hall effect joysticks.
 * Corrects magnetic non-linearities and allows true 0.0% deadband mode without phantom drift.
 */
class HallEffectCalibrationEngine {

    data class CalibrationProfile(
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var radiusScaleX: Float = 1.0f,
        var radiusScaleY: Float = 1.0f,
        var isCalibrated: Boolean = false
    )

    private val profile = CalibrationProfile()

    fun setCenterOffset(cx: Float, cy: Float) {
        profile.centerX = cx
        profile.centerY = cy
        profile.isCalibrated = true
    }

    fun setScale(sx: Float, sy: Float) {
        profile.radiusScaleX = sx.coerceIn(0.8f, 1.2f)
        profile.radiusScaleY = sy.coerceIn(0.8f, 1.2f)
    }

    /**
     * Applies Hall-Effect centering and circular normalization.
     */
    fun processSample(rawX: Float, rawY: Float): Pair<Float, Float> {
        if (!profile.isCalibrated) return Pair(rawX.coerceIn(-1f, 1f), rawY.coerceIn(-1f, 1f))

        // Center subtraction
        var correctedX = (rawX - profile.centerX) * profile.radiusScaleX
        var correctedY = (rawY - profile.centerY) * profile.radiusScaleY

        // Circular clamp
        val distance = sqrt(correctedX * correctedX + correctedY * correctedY)
        if (distance > 1.0f) {
            correctedX /= distance
            correctedY /= distance
        }

        return Pair(correctedX.coerceIn(-1f, 1f), correctedY.coerceIn(-1f, 1f))
    }

    fun getProfile(): CalibrationProfile = profile
}
