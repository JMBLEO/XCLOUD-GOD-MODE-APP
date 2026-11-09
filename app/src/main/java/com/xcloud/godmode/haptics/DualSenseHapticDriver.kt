package com.xcloud.godmode.haptics

import android.util.Log

enum class TriggerEffectType {
    OFF,
    CONTINUOUS_RESISTANCE,
    SECTION_SNAP,
    MACHINE_GUN_RECOIL
}

data class AdaptiveTriggerEffect(
    val type: TriggerEffectType = TriggerEffectType.OFF,
    val startPosition: Int = 0, // 0 to 255
    val endPosition: Int = 255, // 0 to 255
    val forceStrength: Int = 128, // 0 to 255
    val frequencyHz: Int = 10 // For recoil
)

/**
 * DualSenseHapticDriver: Assembles Sony PlayStation DualSense HID reports
 * (48 bytes for USB, 64 bytes for Bluetooth) to control hardware adaptive triggers.
 */
object DualSenseHapticDriver {
    private const val TAG = "DualSenseHaptics"

    /**
     * Builds a 48-byte HID report for USB connected DualSense controller.
     */
    fun buildUsbReport(
        leftTrigger: AdaptiveTriggerEffect,
        rightTrigger: AdaptiveTriggerEffect,
        rumbleLeft: Int = 0,
        rumbleRight: Int = 0
    ): ByteArray {
        val report = ByteArray(48)
        report[0] = 0x02.toByte() // USB Output Report ID
        report[1] = 0xFF.toByte() // Valid flags (enable rumble and triggers)
        report[2] = 0x15.toByte()

        // Rumble motors
        report[3] = (rumbleRight and 0xFF).toByte() // Light motor
        report[4] = (rumbleLeft and 0xFF).toByte()  // Heavy motor

        // Right trigger adaptive effect (offsets 11 to 21)
        encodeTriggerEffect(report, 11, rightTrigger)

        // Left trigger adaptive effect (offsets 22 to 32)
        encodeTriggerEffect(report, 22, leftTrigger)

        return report
    }

    /**
     * Builds a 64-byte HID report for Bluetooth connected DualSense controller.
     */
    fun buildBluetoothReport(
        leftTrigger: AdaptiveTriggerEffect,
        rightTrigger: AdaptiveTriggerEffect,
        rumbleLeft: Int = 0,
        rumbleRight: Int = 0
    ): ByteArray {
        val report = ByteArray(64)
        report[0] = 0x31.toByte() // Bluetooth Output Report ID
        report[1] = 0x02.toByte()
        report[2] = 0xFF.toByte() // Valid flags
        report[3] = 0x15.toByte()

        report[4] = (rumbleRight and 0xFF).toByte()
        report[5] = (rumbleLeft and 0xFF).toByte()

        // Right trigger
        encodeTriggerEffect(report, 12, rightTrigger)

        // Left trigger
        encodeTriggerEffect(report, 23, leftTrigger)

        return report
    }

    private fun encodeTriggerEffect(report: ByteArray, offset: Int, effect: AdaptiveTriggerEffect) {
        when (effect.type) {
            TriggerEffectType.OFF -> {
                report[offset] = 0x00.toByte()
            }
            TriggerEffectType.CONTINUOUS_RESISTANCE -> {
                report[offset] = 0x01.toByte() // Resistance mode
                report[offset + 1] = (effect.startPosition and 0xFF).toByte()
                report[offset + 2] = (effect.forceStrength and 0xFF).toByte()
            }
            TriggerEffectType.SECTION_SNAP -> {
                report[offset] = 0x02.toByte() // Bow / Snap mode
                report[offset + 1] = (effect.startPosition and 0xFF).toByte()
                report[offset + 2] = (effect.endPosition and 0xFF).toByte()
                report[offset + 3] = (effect.forceStrength and 0xFF).toByte()
            }
            TriggerEffectType.MACHINE_GUN_RECOIL -> {
                report[offset] = 0x06.toByte() // Vibration / Recoil mode
                report[offset + 1] = (effect.startPosition and 0xFF).toByte()
                report[offset + 2] = (effect.forceStrength and 0xFF).toByte()
                report[offset + 3] = (effect.frequencyHz and 0xFF).toByte()
            }
        }
    }
}
