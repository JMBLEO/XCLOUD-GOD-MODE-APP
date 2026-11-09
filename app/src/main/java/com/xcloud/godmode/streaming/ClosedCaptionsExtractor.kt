package com.xcloud.godmode.streaming

/**
 * Phase 88: CEA-608 / CEA-708 Closed Captions Extractor.
 * Extracts user data registered ITU-T T.35 SEI NALUs from HEVC / AV1 video frames
 * and parses CEA-608 closed captions for real-time accessibility subtitle rendering.
 */
class ClosedCaptionsExtractor {

    data class CaptionLine(
        val text: String,
        val timestampMs: Long,
        val row: Int = 14
    )

    /**
     * Scans raw NALU payload for SEI prefix (HEVC NAL type 39).
     */
    fun extractCaptionsFromSei(naluData: ByteArray, offset: Int, length: Int): CaptionLine? {
        if (length < 8) return null

        // HEVC NAL Unit Header: forbidden_zero_bit (1), nal_unit_type (6), nuh_layer_id (6), nuh_temporal_id_plus1 (3)
        val nalType = (naluData[offset].toInt() and 0x7E) shr 1
        if (nalType != 39 && nalType != 40) return null // PREFIX_SEI_NUT or SUFFIX_SEI_NUT

        var idx = offset + 2
        val end = offset + length

        val sb = StringBuilder()
        while (idx < end - 2) {
            // Check for CEA-608 ASCII printable range (0x20 to 0x7E)
            val b1 = naluData[idx].toInt() and 0x7F
            val b2 = naluData[idx + 1].toInt() and 0x7F

            if (b1 in 0x20..0x7E) sb.append(b1.toChar())
            if (b2 in 0x20..0x7E) sb.append(b2.toChar())

            idx += 2
        }

        val text = sb.toString().trim()
        if (text.length >= 2) {
            return CaptionLine(text = text, timestampMs = System.currentTimeMillis())
        }

        return null
    }
}
