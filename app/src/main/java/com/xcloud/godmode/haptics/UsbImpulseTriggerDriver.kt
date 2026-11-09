package com.xcloud.godmode.haptics

import android.util.Log

enum class HapticVibrationDestination {
    CONTROLLER_NATIVE,
    DEVICE_VIBRATOR,
    DUAL_SIMULTANEOUS
}

/**
 * UsbImpulseTriggerDriver: Controls dedicated trigger rumble motors on Xbox One / Series controllers,
 * supporting independent LT/RT impulse feedback, inversion, and mirroring.
 */
class UsbImpulseTriggerDriver {
    companion object {
        private const val TAG = "ImpulseTriggerDriver"
    }

    var vibrationDestination: HapticVibrationDestination = HapticVibrationDestination.CONTROLLER_NATIVE
    var isLeftRightInverted: Boolean = false
    var isTriggerMirroringEnabled: Boolean = false

    /**
     * Formats an impulse trigger output report for Xbox controllers.
     * @param leftTriggerRumble 0 to 100% (0.0f to 1.0f)
     * @param rightTriggerRumble 0 to 100% (0.0f to 1.0f)
     */
    fun buildXboxImpulseReport(
        leftTriggerRumble: Float,
        rightTriggerRumble: Float,
        leftGripRumble: Float = 0f,
        rightGripRumble: Float = 0f
    ): ByteArray {
        var lt = (leftTriggerRumble.coerceIn(0f, 1f) * 100f).toInt()
        var rt = (rightTriggerRumble.coerceIn(0f, 1f) * 100f).toInt()

        if (isLeftRightInverted) {
            val temp = lt
            lt = rt
            rt = temp
        }

        if (isTriggerMirroringEnabled) {
            val max = maxOf(lt, rt)
            lt = max
            rt = max
        }

        val lg = (leftGripRumble.coerceIn(0f, 1f) * 100f).toInt()
        val rg = (rightGripRumble.coerceIn(0f, 1f) * 100f).toInt()

        // 9-byte Xbox One rumble packet
        return byteArrayOf(
            0x09.toByte(), // Command ID for rumble
            0x00.toByte(),
            0x0F.toByte(), // Actuator mask (both triggers + both grips)
            lt.toByte(),   // Left Trigger motor
            rt.toByte(),   // Right Trigger motor
            lg.toByte(),   // Left Grip motor
            rg.toByte(),   // Right Grip motor
            0xFF.toByte(), // Duration (infinite / until next report)
            0x00.toByte()
        )
    }
}
