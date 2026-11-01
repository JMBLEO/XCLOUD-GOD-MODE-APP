package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * WakeOnLanManager: Sends "Magic Packets" to wake up physical Xbox consoles on the local network.
 */
object WakeOnLanManager {

    private const val TAG = "WakeOnLanManager"

    suspend fun wake(macAddress: String) = withContext(Dispatchers.IO) {
        try {
            val macBytes = getMacBytes(macAddress)
            val bytes = ByteArray(6 + 16 * macBytes.size)
            
            for (i in 0..5) {
                bytes[i] = 0xff.toByte()
            }
            
            var i = 6
            while (i < bytes.size) {
                System.arraycopy(macBytes, 0, bytes, i, macBytes.size)
                i += macBytes.size
            }

            val address = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(bytes, bytes.size, address, 9)
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.send(packet)
            socket.close()
            
            Log.d(TAG, "Magic Packet sent to $macAddress")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WOL packet: ${e.message}")
        }
    }

    private fun getMacBytes(macStr: String): ByteArray {
        val bytes = ByteArray(6)
        val hex = macStr.split(":", "-")
        if (hex.size != 6) throw IllegalArgumentException("Invalid MAC address")
        try {
            for (i in 0..5) {
                bytes[i] = hex[i].toInt(16).toByte()
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid hex in MAC address")
        }
        return bytes
    }

    data class DiscoveredConsole(
        val ipAddress: String,
        val port: Int,
        val serverName: String,
        val usn: String
    )

    /**
     * Descubrimiento SSDP en la red local (Puertos 1900 y 10243) para consolas Xbox One y Series X|S
     */
    suspend fun discoverLocalConsoles(timeoutMs: Long = 2500L): List<DiscoveredConsole> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredConsole>()
        try {
            val ssdpSearch = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

            val ssdpBytes = ssdpSearch.toByteArray(Charsets.UTF_8)
            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs.toInt()
            val group = InetAddress.getByName("239.255.255.250")
            val packet = DatagramPacket(ssdpBytes, ssdpBytes.size, group, 1900)
            socket.send(packet)

            // Sondeo directo por puerto Xbox 10243
            val probe10243 = DatagramPacket(ssdpBytes, ssdpBytes.size, InetAddress.getByName("255.255.255.255"), 10243)
            socket.send(probe10243)

            val receiveBuf = ByteArray(2048)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val recvPacket = DatagramPacket(receiveBuf, receiveBuf.size)
                    socket.receive(recvPacket)
                    val response = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                    if (response.contains("Xbox", ignoreCase = true) || response.contains("MSFT", ignoreCase = true) || recvPacket.port == 10243) {
                        val hostIp = recvPacket.address.hostAddress.orEmpty()
                        if (discovered.none { it.ipAddress == hostIp }) {
                            discovered.add(
                                DiscoveredConsole(
                                    ipAddress = hostIp,
                                    port = recvPacket.port,
                                    serverName = if (response.contains("Xbox Series", ignoreCase = true)) "Xbox Series X|S" else "Xbox Console (LAN)",
                                    usn = response.substringAfter("USN: ", "").substringBefore("\r\n")
                                )
                            )
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Console SSDP discovery failed: ${e.message}")
        }
        discovered
    }
}
