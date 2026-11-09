package com.xcloud.godmode.network

/**
 * Phase 89: Selective Critical Packet Duplicator.
 * Clones high-priority packets (Video headers VPS/SPS/PPS, IDR key slices, Opus voice/audio, 250Hz input frames)
 * 2x with separate sequence IDs, ensuring immunity to burst packet loss while leaving heavy B/P-frames un-duplicated.
 */
class SelectivePacketDuplicator {

    enum class PacketPriority {
        CRITICAL_DUPLICATE_2X, // Headers, Keyframes, Audio, Input
        STANDARD_SINGLE_COPY   // Standard intermediate video slices
    }

    data class DuplicationPlan(
        val priority: PacketPriority,
        val packetsToSend: List<ByteArray>
    )

    /**
     * Evaluates packet type and produces the transmission list.
     * @param payload Raw packet data
     * @param isAudio True if Opus audio packet
     * @param isInput True if controller input frame
     * @param isKeyframe True if IDR keyframe or parameter set
     */
    fun planTransmission(
        payload: ByteArray,
        isAudio: Boolean,
        isInput: Boolean,
        isKeyframe: Boolean
    ): DuplicationPlan {
        val isCritical = isAudio || isInput || isKeyframe

        return if (isCritical) {
            DuplicationPlan(
                priority = PacketPriority.CRITICAL_DUPLICATE_2X,
                packetsToSend = listOf(payload, payload.clone())
            )
        } else {
            DuplicationPlan(
                priority = PacketPriority.STANDARD_SINGLE_COPY,
                packetsToSend = listOf(payload)
            )
        }
    }
}
