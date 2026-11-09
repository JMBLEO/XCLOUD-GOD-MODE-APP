package com.xcloud.godmode.network

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Teredo NAT Traversal Tunnel Service (RFC 4380).
 * Handles encapsulation of IPv6 packets over UDP (ports 3544 / 3074) for CGNAT bypass.
 */
object TeredoTunnelService {
    const val TEREDO_DEFAULT_PORT = 3544
    const val XBOX_TEREDO_PORT = 3074

    /**
     * Resolves obfuscated IPv4 and port from a 128-bit Teredo IPv6 address (2001:0000::/32).
     */
    fun deobfuscateClientAddress(teredoIpv6: ByteArray): Pair<InetAddress, Int>? {
        if (teredoIpv6.size != 16) return null

        // Check 2001:0000 prefix
        if (teredoIpv6[0] != 0x20.toByte() || teredoIpv6[1] != 0x01.toByte() ||
            teredoIpv6[2] != 0x00.toByte() || teredoIpv6[3] != 0x00.toByte()) {
            return null
        }

        // Obfuscated port is at offset 10..11, XOR with 0xFFFF
        val rawPort = ((teredoIpv6[10].toInt() and 0xFF) shl 8) or (teredoIpv6[11].toInt() and 0xFF)
        val port = rawPort xor 0xFFFF

        // Obfuscated IPv4 is at offset 12..15, XOR with 0xFFFFFFFF
        val ipBytes = ByteArray(4) { i ->
            (teredoIpv6[12 + i].toInt() xor 0xFF).toByte()
        }

        val address = InetAddress.getByAddress(ipBytes)
        return Pair(address, port)
    }
}
