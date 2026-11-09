package com.xcloud.godmode.network

import android.net.Network
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Phase 73: Multipath UDP Dual-Stream Binder (Wi-Fi + 5G).
 * Concurrently manages dual sockets bound directly to Wi-Fi and Cellular interfaces.
 * Replicates critical input control frames across both channels (zero-loss redundancy)
 * and dynamically directs video reception to the path with the lowest RTT and packet jitter.
 */
class MultipathStreamBinder {

    companion object {
        private const val TAG = "MultipathStream"
    }

    enum class ActiveVideoPath {
        WIFI_PRIMARY,
        CELLULAR_BACKUP,
        AUTO_BEST_RTT
    }

    data class PathHealth(
        val isBound: Boolean,
        val rttMs: Long = 0,
        val lossRatePct: Float = 0f,
        val packetsSent: Long = 0,
        val packetsReceived: Long = 0
    )

    private var wifiSocket: DatagramSocket? = null
    private var cellularSocket: DatagramSocket? = null
    private var wifiHealth = PathHealth(isBound = false)
    private var cellularHealth = PathHealth(isBound = false)
    private var activePath = ActiveVideoPath.AUTO_BEST_RTT

    /**
     * Binds a socket explicitly to an Android Network interface (Wi-Fi or Cellular).
     */
    fun bindWifiNetwork(network: Network) {
        try {
            val socket = DatagramSocket()
            network.bindSocket(socket)
            wifiSocket = socket
            wifiHealth = wifiHealth.copy(isBound = true)
            Log.i(TAG, "Wi-Fi socket successfully bound to network interface")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Wi-Fi socket: ${e.message}")
        }
    }

    fun bindCellularNetwork(network: Network) {
        try {
            val socket = DatagramSocket()
            network.bindSocket(socket)
            cellularSocket = socket
            cellularHealth = cellularHealth.copy(isBound = true)
            Log.i(TAG, "Cellular socket successfully bound to network interface")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Cellular socket: ${e.message}")
        }
    }

    /**
     * Transmits high-priority input packets redundantly over both networks.
     */
    fun sendRedundantInput(
        packetBytes: ByteArray,
        length: Int,
        destAddress: InetAddress,
        destPort: Int
    ) {
        val packet = DatagramPacket(packetBytes, length, destAddress, destPort)

        wifiSocket?.let { sock ->
            try {
                sock.send(packet)
                wifiHealth = wifiHealth.copy(packetsSent = wifiHealth.packetsSent + 1)
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi input send error: ${e.message}")
            }
        }

        cellularSocket?.let { sock ->
            try {
                sock.send(packet)
                cellularHealth = cellularHealth.copy(packetsSent = cellularHealth.packetsSent + 1)
            } catch (e: Exception) {
                Log.w(TAG, "Cellular input send error: ${e.message}")
            }
        }
    }

    /**
     * Selects the optimal video transport socket based on RTT.
     */
    fun getActiveVideoSocket(): DatagramSocket? {
        return when (activePath) {
            ActiveVideoPath.WIFI_PRIMARY -> wifiSocket ?: cellularSocket
            ActiveVideoPath.CELLULAR_BACKUP -> cellularSocket ?: wifiSocket
            ActiveVideoPath.AUTO_BEST_RTT -> {
                if (wifiHealth.isBound && cellularHealth.isBound) {
                    if (wifiHealth.rttMs <= cellularHealth.rttMs + 10) wifiSocket else cellularSocket
                } else {
                    wifiSocket ?: cellularSocket
                }
            }
        }
    }

    fun updatePathRtt(isWifi: Boolean, rttMs: Long) {
        if (isWifi) {
            wifiHealth = wifiHealth.copy(rttMs = rttMs)
        } else {
            cellularHealth = cellularHealth.copy(rttMs = rttMs)
        }
    }

    fun release() {
        try {
            wifiSocket?.close()
            cellularSocket?.close()
        } catch (_: Exception) {}
        wifiSocket = null
        cellularSocket = null
        wifiHealth = PathHealth(isBound = false)
        cellularHealth = PathHealth(isBound = false)
    }
}
