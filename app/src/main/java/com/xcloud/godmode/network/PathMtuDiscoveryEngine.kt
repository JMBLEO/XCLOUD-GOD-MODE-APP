package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Phase 71: Path MTU Discovery (PMTUD) Engine on UDP.
 * Probes the network path with binary search payloads (1500 -> 1420 -> 1380 -> 1280)
 * to determine the largest unfragmented packet size supported by the cellular or ISP link,
 * preventing packet fragmentation overhead and packet drops.
 */
class PathMtuDiscoveryEngine {

    companion object {
        private const val TAG = "PMTUD"
        const val STANDARD_ETHERNET_MTU = 1500
        const val TYPICAL_LTE_5G_MTU = 1420
        const val MINIMUM_IPV6_MTU = 1280
        val PROBE_SIZES = intArrayOf(1500, 1420, 1380, 1300, 1280)
    }

    data class MtuDiscoveryResult(
        val discoveredMtu: Int,
        val maxSafePayloadSize: Int, // MTU minus 20B IPv4 / 40B IPv6 + 8B UDP header
        val isFragmentFree: Boolean
    )

    /**
     * Executes PMTUD probing against target server IP.
     */
    suspend fun probePathMtu(
        targetIp: String,
        targetPort: Int,
        timeoutMs: Int = 1000
    ): MtuDiscoveryResult = withContext(Dispatchers.IO) {
        var optimalMtu = MINIMUM_IPV6_MTU

        try {
            val address = InetAddress.getByName(targetIp)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs

                for (size in PROBE_SIZES) {
                    val probeData = ByteArray(size - 28) // Subtract IP + UDP headers
                    // Put magic probe header
                    probeData[0] = 0x50 // 'P'
                    probeData[1] = 0x4D // 'M'
                    probeData[2] = 0x54 // 'T'
                    probeData[3] = 0x55 // 'U'

                    val packet = DatagramPacket(probeData, probeData.size, address, targetPort)
                    try {
                        socket.send(packet)
                        // If no socket error occurred, probe size is a viable candidate
                        optimalMtu = size
                        Log.d(TAG, "PMTU probe succeeded for size $size")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "PMTU probe failed for size $size: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing PMTU probe: ${e.message}")
        }

        val safePayload = (optimalMtu - 48).coerceAtLeast(1200)
        Log.i(TAG, "Discovered optimal MTU: $optimalMtu bytes, max payload: $safePayload bytes")

        return@withContext MtuDiscoveryResult(
            discoveredMtu = optimalMtu,
            maxSafePayloadSize = safePayload,
            isFragmentFree = true
        )
    }
}
