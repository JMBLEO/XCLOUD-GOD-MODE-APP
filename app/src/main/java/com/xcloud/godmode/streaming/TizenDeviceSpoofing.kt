package com.xcloud.godmode.streaming

import org.json.JSONObject

/**
 * Tizen Samsung SmartTV Device Identity Spoofing.
 * Injects verified Samsung SmartTV hardware headers to unlock high-bitrate HQ (15 - 20+ Mbps / 1440p)
 * streaming profiles on Azure Cloud Gaming servers.
 */
object TizenDeviceSpoofing {
    const val HEADER_X_MS_DEVICE_INFO = "X-MS-Device-Info"
    const val HEADER_USER_AGENT = "User-Agent"

    const val TIZEN_USER_AGENT = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/5.0 Chrome/85.0.4183.93 TV Safari/537.36"

    /**
     * Builds serialized JSON payload for X-MS-Device-Info.
     */
    fun buildDeviceInfoPayload(isHdrSupported: Boolean = true): String {
        return JSONObject().apply {
            put("osName", "Tizen")
            put("make", "Samsung")
            put("model", "SmartTV")
            put("sdkVersion", "6.0")
            put("browserVersion", "SamsungBrowser/5.0")
            put("screenResolution", "3840x2160")
            put("colorSpace", if (isHdrSupported) "BT2020" else "BT709")
            put("audioCapabilities", "Stereo,Surround5.1")
            put("clientPlatform", "SamsungTVNative")
            put("hardwareDecoding", "HEVC,AV1")
            put("maxBitrateKbps", 25000)
        }.toString()
    }

    /**
     * Injects device spoofing headers into HTTP requests.
     */
    fun injectHeaders(headersBuilder: okhttp3.Headers.Builder, isHdrSupported: Boolean = true) {
        headersBuilder.set(HEADER_X_MS_DEVICE_INFO, buildDeviceInfoPayload(isHdrSupported))
        headersBuilder.set(HEADER_USER_AGENT, TIZEN_USER_AGENT)
    }
}
