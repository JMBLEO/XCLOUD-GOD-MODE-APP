package com.xcloud.godmode.haptics

/**
 * Phase 60: DualSense Touchpad, RGB Lightbar & Player LEDs.
 * Decodes Sony DualSense HID bytes 33-42 for dual-point capacitive touchpad gestures
 * and formats output reports for RGB color (defaulting to Xbox Emerald #107C10) and Player indicator LEDs.
 */
class DualSenseTouchpadHandler {

    data class TouchPoint(
        val isActive: Boolean,
        val id: Int,
        val x: Int, // 0..1919
        val y: Int  // 0..1079
    )

    data class TouchpadState(
        val touch1: TouchPoint,
        val touch2: TouchPoint,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Parses Sony HID report bytes 33-42 (Touchpad packet slice).
     * Format:
     * Byte 33: Touch 1 Active flag (bit 7 == 0) + ID (bits 0-6)
     * Byte 34: Touch 1 X low 8 bits
     * Byte 35: Touch 1 X high 4 bits (bits 0-3) | Touch 1 Y low 4 bits (bits 4-7)
     * Byte 36: Touch 1 Y high 8 bits
     * Bytes 37-40: Touch 2 in identical format
     * Byte 41: Touchpad packet counter
     */
    fun parseTouchpadSlice(report: ByteArray, offset: Int = 33): TouchpadState? {
        if (report.size < offset + 8) return null

        // Touch 1
        val b0 = report[offset].toInt() and 0xFF
        val t1Active = (b0 and 0x80) == 0
        val t1Id = b0 and 0x7F
        val b1 = report[offset + 1].toInt() and 0xFF
        val b2 = report[offset + 2].toInt() and 0xFF
        val b3 = report[offset + 3].toInt() and 0xFF
        val t1X = b1 or ((b2 and 0x0F) shl 8)
        val t1Y = ((b2 and 0xF0) shr 4) or (b3 shl 4)

        // Touch 2
        val b4 = report[offset + 4].toInt() and 0xFF
        val t2Active = (b4 and 0x80) == 0
        val t2Id = b4 and 0x7F
        val b5 = report[offset + 5].toInt() and 0xFF
        val b6 = report[offset + 6].toInt() and 0xFF
        val b7 = report[offset + 7].toInt() and 0xFF
        val t2X = b5 or ((b6 and 0x0F) shl 8)
        val t2Y = ((b6 and 0xF0) shr 4) or (b7 shl 4)

        return TouchpadState(
            touch1 = TouchPoint(t1Active, t1Id, t1X.coerceIn(0, 1920), t1Y.coerceIn(0, 1080)),
            touch2 = TouchPoint(t2Active, t2Id, t2X.coerceIn(0, 1920), t2Y.coerceIn(0, 1080))
        )
    }

    /**
     * Converts touch coordinates to mouse delta movements.
     */
    fun computeMouseDelta(
        previous: TouchPoint?,
        current: TouchPoint,
        sensitivity: Float = 1.0f
    ): Pair<Float, Float> {
        if (previous == null || !previous.isActive || !current.isActive) {
            return Pair(0f, 0f)
        }
        val dx = (current.x - previous.x) * sensitivity
        val dy = (current.y - previous.y) * sensitivity
        return Pair(dx, dy)
    }

    /**
     * Builds Sony HID Output Report payload for RGB Lightbar and Player LEDs.
     * @param r Red (0..255)
     * @param g Green (0..255) Default Xbox Emerald is 0x10, 0x7C, 0x10
     * @param b Blue (0..255)
     * @param playerIndex 1..4 (controls bottom white LED array)
     */
    fun buildLedOutputReport(
        r: Int = 0x10,
        g: Int = 0x7C,
        b: Int = 0x10,
        playerIndex: Int = 1,
        isUsb: Boolean = true
    ): ByteArray {
        val size = if (isUsb) 48 else 78
        val report = ByteArray(size)

        val offset = if (isUsb) 0 else 1 // Bluetooth adds report ID byte 0x31

        if (!isUsb) {
            report[0] = 0x31.toByte()
            report[1] = 0x02.toByte() // Enable flags
        } else {
            report[0] = 0x02.toByte() // USB Report ID
        }

        // Feature flags byte (enable lightbar & player LEDs)
        val flagOffset = offset + 2
        report[flagOffset] = 0x04.toByte() // Enable lightbar
        report[flagOffset + 1] = 0x10.toByte() // Enable player LEDs

        // RGB Lightbar bytes (typically offset 45, 46, 47 in USB)
        val rgbOffset = offset + 43
        if (rgbOffset + 4 < size) {
            report[rgbOffset] = r.toByte()
            report[rgbOffset + 1] = g.toByte()
            report[rgbOffset + 2] = b.toByte()

            // Player LED mask: 1=Center, 2=Inner two, 3=Three, 4=All
            val ledMask = when (playerIndex) {
                1 -> 0x04 // Center LED
                2 -> 0x0A // Inner two LEDs
                3 -> 0x15 // Three LEDs
                4 -> 0x1B // Four/All LEDs
                else -> 0x00
            }
            report[rgbOffset + 3] = ledMask.toByte()
        }

        return report
    }
}
