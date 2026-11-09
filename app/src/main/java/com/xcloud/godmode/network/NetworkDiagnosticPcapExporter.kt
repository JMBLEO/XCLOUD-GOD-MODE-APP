package com.xcloud.godmode.network

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

data class PcapPacketRecord(
    val timestampSec: Long,
    val timestampUsec: Long,
    val headerBytes: ByteArray,
    val originalLength: Int
)

/**
 * NetworkDiagnosticPcapExporter: Records UDP stream packet headers into a circular buffer
 * and exports standard .pcap dump files compatible with Wireshark for deep MTU and QoS analysis.
 */
class NetworkDiagnosticPcapExporter(private val maxPackets: Int = 10_000) {
    companion object {
        private const val TAG = "PcapExporter"
        private const val PCAP_MAGIC_NUMBER = 0xA1B2C3D4.toInt()
        private const val PCAP_VERSION_MAJOR = 2
        private const val PCAP_VERSION_MINOR = 4
        private const val LINKTYPE_RAW_IP = 101 // Raw IPv4/IPv6
    }

    private val packetBuffer = ArrayDeque<PcapPacketRecord>()

    @Synchronized
    fun recordPacket(packetData: ByteArray, packetLength: Int = packetData.size) {
        val nowMs = System.currentTimeMillis()
        val sec = nowMs / 1000
        val usec = (nowMs % 1000) * 1000

        // Extract header only (up to 40 bytes) to protect user payload privacy
        val headerLen = packetLength.coerceAtMost(40)
        val headerBytes = packetData.copyOfRange(0, headerLen)

        if (packetBuffer.size >= maxPackets) {
            packetBuffer.removeFirst()
        }

        packetBuffer.addLast(
            PcapPacketRecord(
                timestampSec = sec,
                timestampUsec = usec,
                headerBytes = headerBytes,
                originalLength = packetLength
            )
        )
    }

    @Synchronized
    fun exportToPcapFile(targetFile: File): Boolean {
        return try {
            FileOutputStream(targetFile).use { out ->
                // 1. Write PCAP Global Header (24 bytes)
                val globalHeader = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(PCAP_MAGIC_NUMBER)
                    putShort(PCAP_VERSION_MAJOR.toShort())
                    putShort(PCAP_VERSION_MINOR.toShort())
                    putInt(0) // thiszone (GMT)
                    putInt(0) // sigfigs
                    putInt(65535) // snaplen
                    putInt(LINKTYPE_RAW_IP) // network linktype
                }
                out.write(globalHeader.array())

                // 2. Write Packet Records (16 bytes header + packet bytes)
                for (pkt in packetBuffer) {
                    val pktHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(pkt.timestampSec.toInt())
                        putInt(pkt.timestampUsec.toInt())
                        putInt(pkt.headerBytes.size) // captured length
                        putInt(pkt.originalLength)   // untruncated length
                    }
                    out.write(pktHeader.array())
                    out.write(pkt.headerBytes)
                }
            }
            Log.i(TAG, "Exported ${packetBuffer.size} packets to ${targetFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export PCAP file", e)
            false
        }
    }

    @Synchronized
    fun getRecordedPacketCount(): Int = packetBuffer.size

    @Synchronized
    fun clear() {
        packetBuffer.clear()
    }
}
