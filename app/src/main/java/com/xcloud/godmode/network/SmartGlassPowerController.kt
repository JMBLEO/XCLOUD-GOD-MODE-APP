package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 90: Remote SmartGlass Power Controller (Power Off / Sleep / Standby).
 * Dispatches SmartGlass power management datagrams to Xbox consoles on UDP 5050,
 * allowing the user to gracefully power down or put the console to sleep upon ending remote play.
 */
class SmartGlassPowerController {

    companion object {
        private const val TAG = "SmartGlassPower"
        const val SMARTGLASS_PORT = 5050
    }

    enum class PowerAction {
        POWER_OFF,
        STANDBY_SLEEP,
        REBOOT
    }

    /**
     * Sends power action packet to console IP.
     */
    suspend fun sendPowerCommand(
        consoleIp: String,
        liveId: String,
        action: PowerAction,
        port: Int = SMARTGLASS_PORT
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val packetData = buildPowerPacket(liveId, action)
            DatagramSocket().use { socket ->
                socket.soTimeout = 1500
                val packet = DatagramPacket(
                    packetData,
                    packetData.size,
                    InetAddress.getByName(consoleIp),
                    port
                )
                socket.send(packet)
                Log.i(TAG, "Sent power command $action to $consoleIp:$port")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send power command: ${e.message}")
            false
        }
    }

    private fun buildPowerPacket(liveId: String, action: PowerAction): ByteArray {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        // SmartGlass Message Type 0x0002: Power Control
        buffer.putShort(0x0002.toShort())
        // Sub-command: 0x01 = Standby, 0x02 = Off, 0x03 = Reboot
        val op = when (action) {
            PowerAction.STANDBY_SLEEP -> 0x01.toByte()
            PowerAction.POWER_OFF -> 0x02.toByte()
            PowerAction.REBOOT -> 0x03.toByte()
        }
        buffer.put(op)
        buffer.put(0x00.toByte()) // Padding
        // Live ID hash / padding
        val liveIdBytes = liveId.toByteArray()
        val copyLen = liveIdBytes.size.coerceAtMost(16)
        buffer.put(liveIdBytes, 0, copyLen)
        return buffer.array()
    }
}
