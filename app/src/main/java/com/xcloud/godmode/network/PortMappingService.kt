package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.HttpURLConnection

/**
 * Phase 58: Automatic Port Mapping Service (UPnP / NAT-PMP).
 * Discovers local router gateway via SSDP M-SEARCH (239.255.255.250:1900)
 * and requests port mapping for UDP ports (3074 Xbox, 5050 SmartGlass, 9002 Stream)
 * to bypass double-NAT in xHome remote play sessions.
 */
class PortMappingService {

    companion object {
        private const val TAG = "PortMappingService"
        private const val SSDP_MULTICAST_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        val TARGET_UDP_PORTS = listOf(3074, 5050, 9002)
    }

    data class PortMappingResult(
        val port: Int,
        val isMapped: Boolean,
        val protocol: String,
        val externalIp: String? = null,
        val details: String
    )

    /**
     * Executes SSDP M-SEARCH to locate IGD (Internet Gateway Device).
     */
    suspend fun discoverUpnpGateway(timeoutMs: Int = 2000): String? = withContext(Dispatchers.IO) {
        try {
            val ssdpQuery = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n\r\n"

            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val queryBytes = ssdpQuery.toByteArray()
                val packet = DatagramPacket(
                    queryBytes,
                    queryBytes.size,
                    InetAddress.getByName(SSDP_MULTICAST_IP),
                    SSDP_PORT
                )
                socket.send(packet)

                val buffer = ByteArray(2048)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)

                val response = String(responsePacket.data, 0, responsePacket.length)
                val locationRegex = Regex("(?i)LOCATION:\\s*(http://[^\\r\\n]+)")
                val match = locationRegex.find(response)
                val location = match?.groupValues?.get(1)
                Log.d(TAG, "Discovered UPnP Gateway at: $location")
                return@withContext location
            }
        } catch (e: Exception) {
            Log.w(TAG, "UPnP SSDP Discovery failed or timed out: ${e.message}")
            null
        }
    }

    /**
     * Attempts to request UPnP Port Mapping via SOAP XML payload to the IGD control URL.
     */
    suspend fun requestUpnpPortMapping(
        controlUrl: String,
        localIp: String,
        port: Int,
        protocol: String = "UDP",
        leaseDurationSec: Int = 3600
    ): PortMappingResult = withContext(Dispatchers.IO) {
        try {
            val soapBody = """
                <?xml version="1.0"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:AddPortMapping xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
                            <NewRemoteHost></NewRemoteHost>
                            <NewExternalPort>$port</NewExternalPort>
                            <NewProtocol>$protocol</NewProtocol>
                            <NewInternalPort>$port</NewInternalPort>
                            <NewInternalClient>$localIp</NewInternalClient>
                            <NewEnabled>1</NewEnabled>
                            <NewPortMappingDescription>GodModeNativeStream_$port</NewPortMappingDescription>
                            <NewLeaseDuration>$leaseDurationSec</NewLeaseDuration>
                        </u:AddPortMapping>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            val url = URL(controlUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping\"")

            connection.outputStream.use { os ->
                os.write(soapBody.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                Log.i(TAG, "UPnP Port Mapping success for $protocol:$port -> $localIp:$port")
                PortMappingResult(port, true, protocol, null, "UPnP mapped via HTTP 200")
            } else {
                Log.w(TAG, "UPnP responded with status $responseCode")
                PortMappingResult(port, false, protocol, null, "HTTP Error $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "UPnP SOAP Request Error for port $port: ${e.message}")
            PortMappingResult(port, false, protocol, null, "Exception: ${e.message}")
        }
    }

    /**
     * Executes NAT-PMP port mapping request to default gateway on UDP 5351.
     */
    suspend fun requestNatPmpMapping(
        gatewayIp: String,
        internalPort: Int,
        externalPort: Int,
        lifetimeSec: Int = 3600
    ): PortMappingResult = withContext(Dispatchers.IO) {
        try {
            // NAT-PMP Mapping Packet:
            // Version (0), Opcode (1=UDP, 2=TCP), Reserved (2 bytes), Internal Port (2 bytes), External Port (2 bytes), Lifetime (4 bytes)
            val request = ByteArray(12)
            request[0] = 0 // Vers 0
            request[1] = 1 // Opcode 1 = UDP
            request[2] = 0
            request[3] = 0
            request[4] = (internalPort shr 8).toByte()
            request[5] = (internalPort and 0xFF).toByte()
            request[6] = (externalPort shr 8).toByte()
            request[7] = (externalPort and 0xFF).toByte()
            request[8] = ((lifetimeSec shr 24) and 0xFF).toByte()
            request[9] = ((lifetimeSec shr 16) and 0xFF).toByte()
            request[10] = ((lifetimeSec shr 8) and 0xFF).toByte()
            request[11] = (lifetimeSec and 0xFF).toByte()

            DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                val packet = DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName(gatewayIp),
                    5351 // NAT-PMP Port
                )
                socket.send(packet)

                val responseBuffer = ByteArray(16)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)

                val resultCode = (responseBuffer[3].toInt() and 0xFF)
                val isSuccess = resultCode == 0
                PortMappingResult(
                    port = internalPort,
                    isMapped = isSuccess,
                    protocol = "UDP",
                    details = if (isSuccess) "NAT-PMP success" else "NAT-PMP error code $resultCode"
                )
            }
        } catch (e: Exception) {
            PortMappingResult(
                port = internalPort,
                isMapped = false,
                protocol = "UDP",
                details = "NAT-PMP exception: ${e.message}"
            )
        }
    }
}
