package com.xcloud.godmode.network

/**
 * Phase 87: Precision Time Protocol (PTP/NTP) Delay Breakdown Engine.
 * Implements standard IEEE 1588 / NTP 4-timestamp exchange (t1, t2, t3, t4)
 * to measure asymmetric one-way latency: uplink delay (controller input to cloud)
 * vs downlink delay (video decode & delivery from cloud).
 */
class PrecisionTimeProtocolEngine {

    data class DelayMetrics(
        val roundTripTimeUs: Long,
        val uplinkDelayUs: Long,   // Client -> Server
        val downlinkDelayUs: Long, // Server -> Client
        val clockOffsetUs: Long    // Client vs Server clock differential
    )

    /**
     * Calculates precise asymmetric delays from 4 timestamps:
     * @param t1 Client send timestamp (us)
     * @param t2 Server receive timestamp (us)
     * @param t3 Server send timestamp (us)
     * @param t4 Client receive timestamp (us)
     */
    fun computeDelays(t1: Long, t2: Long, t3: Long, t4: Long): DelayMetrics {
        // Round-trip delay = (t4 - t1) - (t3 - t2)
        val rtt = (t4 - t1) - (t3 - t2)
        // Clock offset theta = ((t2 - t1) + (t3 - t4)) / 2
        val offset = ((t2 - t1) + (t3 - t4)) / 2

        // Uplink: t2 - t1 - offset
        val uplink = ((t2 - t1) - offset).coerceAtLeast(0)
        // Downlink: t4 - t3 + offset
        val downlink = ((t4 - t3) + offset).coerceAtLeast(0)

        return DelayMetrics(
            roundTripTimeUs = rtt.coerceAtLeast(0),
            uplinkDelayUs = uplink,
            downlinkDelayUs = downlink,
            clockOffsetUs = offset
        )
    }

    /**
     * Creates PTP query packet payload (8 bytes: client t1 timestamp in microseconds).
     */
    fun createPtpQueryPacket(): ByteArray {
        val nowUs = System.nanoTime() / 1000L
        val data = ByteArray(8)
        for (i in 0 until 8) {
            data[i] = ((nowUs shr (56 - i * 8)) and 0xFF).toByte()
        }
        return data
    }
}
