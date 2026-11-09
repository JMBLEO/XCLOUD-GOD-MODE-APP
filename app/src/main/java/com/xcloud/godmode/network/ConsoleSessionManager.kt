package com.xcloud.godmode.network

import android.util.Log

enum class ConsolePowerMode {
    SILENT_DIRECT_TO_STREAM, // Console wakes with HDMI/LED disabled for pure remote play
    FULL_POWER_ON            // Normal power-on with display and chime
}

enum class XHomeSignalingEndpoint(val host: String, val regionName: String) {
    WEST_US_2("westus2.signalserver.xbox.com", "West US 2"),
    EAST_US("eastus.signalserver.xbox.com", "East US"),
    UK_SOUTH("uksouth.signalserver.xbox.com", "UK South")
}

data class ConsoleDeviceInfo(
    val liveId: String,
    val consoleName: String,
    val localIp: String,
    val isOnline: Boolean,
    val powerState: String = "ConnectedStandby"
)

/**
 * ConsoleSessionManager: Manages direct remote play connection to physical Xbox One / Series consoles (xHome).
 * Controls silent power-on, signaling server routing, and Teredo traversal.
 */
class ConsoleSessionManager {
    companion object {
        private const val TAG = "ConsoleSessionManager"
    }

    var powerMode: ConsolePowerMode = ConsolePowerMode.SILENT_DIRECT_TO_STREAM
    var signalingEndpoint: XHomeSignalingEndpoint = XHomeSignalingEndpoint.WEST_US_2
    var preferIpv6: Boolean = true

    /**
     * Builds session payload to initiate remote play on local/remote console.
     */
    fun buildConnectPayload(console: ConsoleDeviceInfo): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "clientVersion" to "2.0.0",
            "serverPath" to console.localIp,
            "titleId" to "Home",
            "silentPowerOn" to (powerMode == ConsolePowerMode.SILENT_DIRECT_TO_STREAM),
            "signalingServer" to signalingEndpoint.host,
            "ipPreference" to (if (preferIpv6) "IPv6" else "IPv4")
        )
        Log.i(TAG, "Initiating xHome connection to ${console.consoleName} (${console.liveId}) - Mode: $powerMode")
        return payload
    }
}
