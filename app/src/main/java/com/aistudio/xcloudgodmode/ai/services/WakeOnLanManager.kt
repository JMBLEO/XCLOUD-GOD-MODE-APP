package com.aistudio.xcloudgodmode.ai.services

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
}
