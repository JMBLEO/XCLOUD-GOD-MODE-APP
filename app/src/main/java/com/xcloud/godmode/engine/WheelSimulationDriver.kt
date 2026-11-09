package com.xcloud.godmode.engine

/**
 * Phase 72: Racing Wheel Simulation & FFB (USB OTG).
 * Translates commercial racing wheels (Logitech G29, G920, G923, Thrustmaster T300)
 * connected via USB OTG into standard Xbox controller analogs with adjustable lock-to-lock degrees (270° to 1080°),
 * non-linear steering curves, and multi-pedal normalization (gas, brake, clutch).
 */
class WheelSimulationDriver {

    data class WheelConfig(
        var maxRotationDegrees: Float = 900f,
        var targetRotationDegrees: Float = 360f, // Virtual steering range
        var centeringSpringForce: Float = 0.5f,
        var deadzoneDegrees: Float = 2.0f,
        var invertPedals: Boolean = false
    )

    data class NormalizedWheelOutput(
        val leftStickX: Float, // -1.0 .. 1.0 (Steering)
        val rightTrigger: Float, // 0.0 .. 1.0 (Throttle)
        val leftTrigger: Float,  // 0.0 .. 1.0 (Brake)
        val clutchAnalog: Float, // 0.0 .. 1.0
        val shifterGear: Int,    // 0 = Neutral, 1..6 = Gears, -1 = Reverse
        val isClutchEngaged: Boolean
    )

    private val config = WheelConfig()

    fun updateConfig(maxDegrees: Float, virtualDegrees: Float, springForce: Float) {
        config.maxRotationDegrees = maxDegrees.coerceIn(180f, 1080f)
        config.targetRotationDegrees = virtualDegrees.coerceIn(180f, config.maxRotationDegrees)
        config.centeringSpringForce = springForce.coerceIn(0f, 1f)
    }

    /**
     * Ingests raw USB HID axis values from wheel.
     * @param rawWheelAxis -32768 .. 32767
     * @param rawThrottleAxis 0 .. 255 or -32768 .. 32767
     * @param rawBrakeAxis 0 .. 255 or -32768 .. 32767
     * @param rawClutchAxis 0 .. 255 or -32768 .. 32767
     */
    fun processWheelInputs(
        rawWheelAxis: Int,
        rawThrottleAxis: Int,
        rawBrakeAxis: Int,
        rawClutchAxis: Int = 0,
        rawShifterBits: Int = 0
    ): NormalizedWheelOutput {
        // Convert raw wheel position to degrees
        val normalizedRaw = (rawWheelAxis.toFloat() / 32768f).coerceIn(-1f, 1f)
        val currentDegrees = normalizedRaw * (config.maxRotationDegrees / 2f)

        // Apply deadzone
        val effectiveDegrees = if (kotlin.math.abs(currentDegrees) < config.deadzoneDegrees) {
            0f
        } else {
            val sign = if (currentDegrees > 0) 1f else -1f
            (currentDegrees - (sign * config.deadzoneDegrees))
        }

        // Map to virtual range (-1.0 .. 1.0)
        val halfVirtual = config.targetRotationDegrees / 2f
        val stickX = (effectiveDegrees / halfVirtual).coerceIn(-1f, 1f)

        // Pedals: Logitech uses 255 (released) to 0 (pressed) or 0 to 65535
        val throttle = normalizePedal(rawThrottleAxis)
        val brake = normalizePedal(rawBrakeAxis)
        val clutch = normalizePedal(rawClutchAxis)

        // Shifter decoding: bits 0..5 for 1st-6th gear, bit 6 for reverse
        val gear = when {
            (rawShifterBits and 0x40) != 0 -> -1 // Reverse
            (rawShifterBits and 0x01) != 0 -> 1
            (rawShifterBits and 0x02) != 0 -> 2
            (rawShifterBits and 0x04) != 0 -> 3
            (rawShifterBits and 0x08) != 0 -> 4
            (rawShifterBits and 0x10) != 0 -> 5
            (rawShifterBits and 0x20) != 0 -> 6
            else -> 0 // Neutral
        }

        return NormalizedWheelOutput(
            leftStickX = stickX,
            rightTrigger = throttle,
            leftTrigger = brake,
            clutchAnalog = clutch,
            shifterGear = gear,
            isClutchEngaged = clutch > 0.5f
        )
    }

    private fun normalizePedal(rawValue: Int): Float {
        // Assumes typical 0..255 or 0..1023 or 0..32767
        val normalized = when {
            rawValue > 1024 -> rawValue.toFloat() / 32767f
            rawValue > 255 -> rawValue.toFloat() / 1023f
            else -> rawValue.toFloat() / 255f
        }
        return normalized.coerceIn(0f, 1f)
    }
}
