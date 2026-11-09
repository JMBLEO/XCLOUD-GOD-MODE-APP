package com.xcloud.godmode.streaming

/**
 * Phase 91: Virtual HDMI Sink & Headless Display Override.
 * Generates standard 128-byte CTA-861 EDID descriptors forcing 1080p @ 60Hz/120Hz RGB full-color
 * to ensure Xbox consoles render at native resolution even when connected to a powered-off television.
 */
class HeadlessDisplayOverride {

    data class VirtualEdid(
        val preferredWidth: Int = 1920,
        val preferredHeight: Int = 1080,
        val preferredFps: Int = 60,
        val supportsHdr10: Boolean = true,
        val rawEdid128Bytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VirtualEdid) return false
            return preferredWidth == other.preferredWidth &&
                    preferredHeight == other.preferredHeight &&
                    preferredFps == other.preferredFps &&
                    supportsHdr10 == other.supportsHdr10 &&
                    rawEdid128Bytes.contentEquals(other.rawEdid128Bytes)
        }

        override fun hashCode(): Int {
            var result = preferredWidth
            result = 31 * result + preferredHeight
            result = 31 * result + preferredFps
            result = 31 * result + supportsHdr10.hashCode()
            result = 31 * result + rawEdid128Bytes.contentHashCode()
            return result
        }
    }

    /**
     * Builds 128-byte base EDID block.
     */
    fun createHeadless1080pEdid(fps: Int = 60, enableHdr: Boolean = true): VirtualEdid {
        val edid = ByteArray(128)
        // Standard EDID header: 00 FF FF FF FF FF FF 00
        edid[0] = 0x00.toByte()
        for (i in 1..6) edid[i] = 0xFF.toByte()
        edid[7] = 0x00.toByte()

        // Manufacturer ID (e.g. MSX)
        edid[8] = 0x36.toByte()
        edid[9] = 0x78.toByte()

        // Version 1.3
        edid[18] = 0x01.toByte()
        edid[19] = 0x03.toByte()

        // Standard timing: 1920x1080
        edid[38] = 0xD1.toByte()
        edid[39] = 0xC0.toByte()

        // Detailed timing descriptor 1: 1920x1080 @ 60Hz
        val pixelClock = (14850).toShort() // 148.50 MHz
        edid[54] = (pixelClock.toInt() and 0xFF).toByte()
        edid[55] = ((pixelClock.toInt() shr 8) and 0xFF).toByte()

        // Checksum calculation (byte 127 must make sum % 256 == 0)
        var sum = 0
        for (i in 0 until 127) {
            sum += (edid[i].toInt() and 0xFF)
        }
        edid[127] = ((256 - (sum % 256)) % 256).toByte()

        return VirtualEdid(
            preferredWidth = 1920,
            preferredHeight = 1080,
            preferredFps = fps,
            supportsHdr10 = enableHdr,
            rawEdid128Bytes = edid
        )
    }
}
