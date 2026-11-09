package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Phase 59: TURN over TLS/TCP Tunnel (Bypass Port 443).
 * Automatic fallback to encrypted TLS tunnel on standard HTTPS port 443
 * when direct UDP connectivity fails after 3 retries or under strict DPI network filters.
 */
class TurnTlsTunnel {

    companion object {
        private const val TAG = "TurnTlsTunnel"
        const val TURN_TLS_PORT = 443
        const val STUN_MAGIC_COOKIE = 0x2112A442
        const val TURN_ALLOCATE_REQUEST = 0x0003
        const val TURN_SEND_INDICATION = 0x0016
        const val TURN_DATA_INDICATION = 0x0017
    }

    data class TunnelState(
        val isConnected: Boolean,
        val serverHost: String,
        val relayedIp: String? = null,
        val relayedPort: Int = 0,
        val bytesTransferred: Long = 0,
        val latencyMs: Long = 0
    )

    private var sslSocket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isTunnelActive = false

    /**
     * Connects to TURN server over TLS on port 443 and negotiates allocation.
     */
    suspend fun connect(
        turnHost: String,
        turnPort: Int = TURN_TLS_PORT,
        timeoutMs: Int = 5000
    ): TunnelState = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            Log.d(TAG, "Initiating TURN over TLS tunnel to $turnHost:$turnPort")
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = factory.createSocket() as SSLSocket
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(turnHost, turnPort), timeoutMs)
            socket.startHandshake()

            sslSocket = socket
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            isTunnelActive = true

            // Send STUN/TURN Allocate Request (RFC 5766)
            val allocateReq = buildTurnAllocatePacket()
            outputStream?.write(allocateReq)
            outputStream?.flush()

            val header = ByteArray(20)
            val read = inputStream?.read(header) ?: 0
            val latency = System.currentTimeMillis() - startTime

            if (read >= 20) {
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val msgType = buffer.short.toInt() and 0xFFFF
                val msgLength = buffer.short.toInt() and 0xFFFF
                val magicCookie = buffer.int

                val isSuccess = magicCookie == STUN_MAGIC_COOKIE && (msgType == 0x0103 || msgType == 0x0113)
                Log.i(TAG, "TURN TLS Handshake complete in ${latency}ms. Success: $isSuccess")
                return@withContext TunnelState(
                    isConnected = isSuccess,
                    serverHost = turnHost,
                    relayedPort = TURN_TLS_PORT,
                    latencyMs = latency
                )
            }

            TunnelState(isConnected = false, serverHost = turnHost, latencyMs = latency)
        } catch (e: Exception) {
            Log.e(TAG, "TURN over TLS connection failed: ${e.message}")
            disconnect()
            TunnelState(isConnected = false, serverHost = turnHost, latencyMs = System.currentTimeMillis() - startTime)
        }
    }

    /**
     * Encapsulates raw UDP game/audio packet inside TURN ChannelData or SendIndication frame.
     */
    fun sendRelayedData(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean {
        if (!isTunnelActive) return false
        return try {
            val os = outputStream ?: return false
            // RFC 5766 ChannelData message: 2 bytes Channel Number, 2 bytes Length, followed by payload and padding
            val frame = ByteBuffer.allocate(4 + length + 3)
            frame.order(ByteOrder.BIG_ENDIAN)
            frame.putShort(0x4000.toShort()) // Channel number 0x4000
            frame.putShort(length.toShort())
            frame.put(data, offset, length)
            // 4-byte boundary padding
            val padding = (4 - (length % 4)) % 4
            for (i in 0 until padding) {
                frame.put(0.toByte())
            }

            os.write(frame.array(), 0, 4 + length + padding)
            os.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error sending relayed packet: ${e.message}")
            false
        }
    }

    fun disconnect() {
        isTunnelActive = false
        try {
            inputStream?.close()
            outputStream?.close()
            sslSocket?.close()
        } catch (_: Exception) {}
        sslSocket = null
        inputStream = null
        outputStream = null
    }

    private fun buildTurnAllocatePacket(): ByteArray {
        val buffer = ByteBuffer.allocate(24)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(TURN_ALLOCATE_REQUEST.toShort())
        buffer.putShort(4.toShort()) // Attributes length: 4 bytes (REQUESTED-TRANSPORT attribute)
        buffer.putInt(STUN_MAGIC_COOKIE)
        // 12-byte Transaction ID
        for (i in 0 until 12) {
            buffer.put((i + 1).toByte())
        }
        // REQUESTED-TRANSPORT attribute (Type 0x0019, Length 4, Protocol 17=UDP)
        buffer.putShort(0x0019.toShort())
        buffer.putShort(4.toShort())
        buffer.put(17.toByte()) // UDP
        buffer.put(0.toByte())
        buffer.put(0.toByte())
        buffer.put(0.toByte())
        return buffer.array()
    }
}
