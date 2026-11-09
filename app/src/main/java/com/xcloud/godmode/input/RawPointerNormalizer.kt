package com.xcloud.godmode.input

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Phase 66: Raw Mouse Input & Scroll Wheel Normalizer.
 * Ingests raw mouse deltas via SOURCE_MOUSE_RELATIVE / pointer capture,
 * eliminates Android OS non-linear pointer acceleration curves for true 1:1 aiming,
 * and maps mouse wheel scrolling (AXIS_VSCROLL) to weapon swap actions (Xbox Y or D-Pad).
 */
class RawPointerNormalizer {

    data class NormalizedPointerOutput(
        val deltaX: Float,
        val deltaY: Float,
        val weaponCycleDirection: Int, // -1 = Prev weapon, 0 = None, +1 = Next weapon
        val leftClick: Boolean,
        val rightClick: Boolean,
        val middleClick: Boolean
    )

    data class MouseConfig(
        var sensitivity: Float = 1.0f,
        var invertY: Boolean = false,
        var deadbandThreshold: Float = 0.01f
    )

    private val config = MouseConfig()

    fun updateConfig(sensitivity: Float, invertY: Boolean) {
        config.sensitivity = sensitivity.coerceIn(0.1f, 5.0f)
        config.invertY = invertY
    }

    /**
     * Parses MotionEvent from a captured mouse pointer.
     */
    fun processMotionEvent(event: MotionEvent): NormalizedPointerOutput {
        var dx = 0f
        var dy = 0f

        val isMouse = (event.source and InputDevice.SOURCE_MOUSE) != 0 ||
                (event.source and InputDevice.SOURCE_MOUSE_RELATIVE) != 0

        if (isMouse) {
            dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)

            // Fallback if relative axis not populated directly
            if (dx == 0f && dy == 0f && event.historySize > 0) {
                dx = event.x - event.getHistoricalX(0)
                dy = event.y - event.getHistoricalY(0)
            }
        }

        // Apply linear sensitivity without curve distortion
        var finalDx = dx * config.sensitivity
        var finalDy = dy * config.sensitivity
        if (config.invertY) finalDy = -finalDy

        if (kotlin.math.abs(finalDx) < config.deadbandThreshold) finalDx = 0f
        if (kotlin.math.abs(finalDy) < config.deadbandThreshold) finalDy = 0f

        // Scroll wheel
        val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val weaponCycle = when {
            vScroll > 0.5f -> 1   // Next weapon
            vScroll < -0.5f -> -1 // Previous weapon
            else -> 0
        }

        val buttonState = event.buttonState
        val left = (buttonState and MotionEvent.BUTTON_PRIMARY) != 0
        val right = (buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        val middle = (buttonState and MotionEvent.BUTTON_TERTIARY) != 0

        return NormalizedPointerOutput(
            deltaX = finalDx,
            deltaY = finalDy,
            weaponCycleDirection = weaponCycle,
            leftClick = left,
            rightClick = right,
            middleClick = middle
        )
    }
}
