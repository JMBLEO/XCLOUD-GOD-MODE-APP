package com.xcloud.godmode.config

import com.xcloud.godmode.launch.EngineId
import org.json.JSONObject

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    RES_720P("720p", 1280, 720),
    RES_1080P("1080p", 1920, 1080),
    RES_1440P("1440p", 2560, 1440),
    RES_4K("4k", 3840, 2160)
}

enum class VideoCodecProfile(val label: String, val mimeType: String, val sdpCodecName: String) {
    H264_SVC("H.264 SVC", "video/avc", "H264"),
    HEVC_H265("HEVC / H.265", "video/hevc", "HEVC"),
    AV1("AV1", "video/av01", "AV1"),
    VVC_H266("VVC / H.266", "video/vvc", "VVC"),
    AV2_EXP("AV2 (Exp)", "video/av2", "AV2")
}

data class GodModeGlobalConfig(
    val forceAzureServer: Boolean = true,
    val forcedRegionCode: String = "CHI",
    val selectedAzureGateway: String = "Chile Central (Santiago - Quilicura)",
    val bypassAndNetworkRoute: String = "[MS Azure] Argentina - Mendoza / Cuyo (Enlace Transandino)",
    val resolution: VideoResolution = VideoResolution.RES_1080P,
    val maxBitrateMbps: Int = 50,
    val targetFps: Int = 60,
    val preferredCodec: VideoCodecProfile = VideoCodecProfile.HEVC_H265,
    val preferredEngine: EngineId = EngineId.MOTOR_4_MEDIACODEC_HWC2_DIRECT,
    val enableTouchControlsByDefault: Boolean = false,
    val enableTelemetryHudByDefault: Boolean = false,
    val touchSensitivity: Float = 1.0f,
    val touchDeadzone: Float = 0.1f
) {
    /**
     * Genera el JSON X-MS-Device-Info inyectado directamente en CloudSessionClient y CapabilityNegotiationEngine
     */
    fun toDeviceInfoJson(): String = JSONObject().apply {
        put("appInfo", JSONObject().apply {
            put("env", JSONObject().apply {
                put("clientAppId", "com.xcloud.godmode")
                put("clientAppType", "native")
                put("clientAppVersion", "10.0.0-carrier")
                put(" clientSdkVersion", "10.0.0")
                put("httpEnvironment", "prod")
                put("sdkInstallId", "godmode-static-install-id")
            })
        })
        put("dev", JSONObject().apply {
            put("hw", JSONObject().apply {
                put("make", android.os.Build.MANUFACTURER)
                put("model", android.os.Build.MODEL)
                put("sdktype", "native")
            })
            put("os", JSONObject().apply {
                put("name", "android")
                put("ver", android.os.Build.VERSION.RELEASE)
            })
            put("displayInfo", JSONObject().apply {
                put("dimensions", JSONObject().apply {
                    put("widthInPixels", resolution.width)
                    put("heightInPixels", resolution.height)
                })
                put("pixelDensity", JSONObject().apply {
                    put("dpiX", 400)
                    put("dpiY", 400)
                })
            })
        })
        put("streamConfig", JSONObject().apply {
            put("maxBitrateKbps", maxBitrateMbps * 1000)
            put("targetFps", targetFps)
            put("preferredCodec", preferredCodec.sdpCodecName)
            put("forcedRegionCode", if (forceAzureServer) forcedRegionCode else "")
            put("selectedEngineIndex", preferredEngine.index)
        })
    }.toString()

    /**
     * Resuelve la URI regional de Azure inyectada en Login, Catálogo y Sesión
     */
    fun resolveOverrideRegionUri(defaultDiscoveredUri: String): String {
        if (!forceAzureServer) return defaultDiscoveredUri
        return when (forcedRegionCode.trim().uppercase()) {
            "CHI", "CL" -> "https://uksouth.core.gssv-play-prodxhome.xboxlive.com" 
                .let { defaultDiscoveredUri.ifBlank { "https://brazilsouth.core.gssv-play-prod.xboxlive.com" } }
            "ARG", "AR", "BR" -> "https://brazilsouth.core.gssv-play-prod.xboxlive.com"
            "US", "EASTUS" -> "https://eastus.core.gssv-play-prod.xboxlive.com"
            else -> defaultDiscoveredUri.ifBlank { "https://brazilsouth.core.gssv-play-prod.xboxlive.com" }
        }
    }
}
