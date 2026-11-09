package com.xcloud.godmode.models

import com.xcloud.godmode.engine.EngineId
import com.xcloud.godmode.streaming.decoder.NextGenCodecGeneration
import com.xcloud.godmode.streaming.transport.GraphicsUpscalingEngine
import com.xcloud.godmode.streaming.transport.NetworkTransportStack

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    RES_720P("720p", 1280, 720),
    RES_1080P("1080p", 1920, 1080),
    RES_1440P("1440p (QHD)", 2560, 1440),
    RES_4K("4K (UHD)", 3840, 2160)
}

enum class VideoCodecProfile(val label: String, val sdpCodecName: String) {
    HEVC_H265("HEVC / H.265", "H265"),
    AV1("AV1 (Next Gen)", "AV1"),
    VVC_H266("VVC / H.266", "VVC"),
    AV2_EXP("AV2 (Experimental)", "AV2")
}

enum class AzureBypassRoute(val displayName: String, val regionCode: String, val baseUri: String) {
    CHILE_CENTRAL(
        "Chile Central (Santiago - Quilicura)",
        "CHI",
        "https://chilecentral.core.gssv-play-prod.xboxlive.com"
    ),
    ARGENTINA_CUYO(
        "[MS Azure] Argentina - Mendoza / Cuyo (Enlace Transandino)",
        "ARG",
        "https://brazilsouth.core.gssv-play-prod.xboxlive.com"
    ),
    BRAZIL_SOUTH(
        "Brasil Sur (São Paulo)",
        "BRS",
        "https://brazilsouth.core.gssv-play-prod.xboxlive.com"
    ),
    US_EAST(
        "EE. UU. Este (Virginia)",
        "EUS",
        "https://eastus.core.gssv-play-prod.xboxlive.com"
    )
}

data class GodModeGlobalConfig(
    val revision: Long = 1L,
    val forceAzureServer: Boolean = true,
    val forcedRegionCode: String = "CHI",
    val bypassRoute: AzureBypassRoute = AzureBypassRoute.CHILE_CENTRAL,
    val selectedAzureGateway: String = "Chile Central (Santiago - Quilicura)",
    val bypassAndNetworkRoute: String = "[MS Azure] Argentina - Mendoza / Cuyo (Enlace Transandino)",
    val resolution: VideoResolution = VideoResolution.RES_1080P,
    val maxBitrateMbps: Int = 50,
    val targetFps: Int = 60,
    val preferredCodec: VideoCodecProfile = VideoCodecProfile.HEVC_H265,
    val preferredEngine: EngineId = EngineId.ENGINE_4_HWC2_DIRECT,
    val enableTouchControlsByDefault: Boolean = false,
    val enableTelemetryHudByDefault: Boolean = false,
    val touchSensitivity: Float = 1.0f,
    val touchDeadzone: Float = 0.1f,
    val enableAbraController: Boolean = true,
    val useRawTransport: Boolean = true,
    
    // NANO specific
    val codecGeneration: NextGenCodecGeneration = NextGenCodecGeneration.HEVC_H265,
    val transportStack: NetworkTransportStack = NetworkTransportStack.NANO_V3_RAW_UDP_RFC7983,
    val upscalingEngine: GraphicsUpscalingEngine = GraphicsUpscalingEngine.AMD_FSR_1_EASU_RCAS,
    val fsrSharpnessStops: Float = 0.2f,

    // Audio Tab Parameters
    val audioGainBoost: Float = 1.0f,
    val audioDuckingDb: Float = -9.0f,
    val enableAudioRumble: Boolean = true,
    val audioRumbleThreshold: Float = 0.25f,

    // Video & Shaders Tab Parameters
    val enableFsr: Boolean = true,
    val fsrMode: String = "EASU_RCAS",
    val enableLsfg: Boolean = false,
    val lsfgVersion: String = "V3_1_PRECISION",
    val lsfgMultiplier: String = "X2",
    val colorSaturation: Float = 1.0f,
    val colorContrast: Float = 1.0f,
    val colorBrightness: Float = 0.0f,
    val enableTizenSpoofing: Boolean = true,

    // Gamepad & Input Tab Parameters
    val gamepadPollingRateHz: Int = 250,
    val enableAutoSprintL3: Boolean = true,
    val enableKbmEmulator: Boolean = false,
    val mouseSensitivity: Float = 1.0f,

    // Sensors & Gyroscope Tab Parameters
    val enableGyroscope: Boolean = false,
    val gyroSource: String = "AUTO_PREFER_GAMEPAD",
    val gyroGate: String = "HOLD_LEFT_TRIGGER",
    val gyroSensitivityX: Float = 1.0f,
    val gyroSensitivityY: Float = 1.0f,
    val gyroInvertX: Boolean = false,
    val gyroInvertY: Boolean = false,

    // DualSense & Haptics Tab Parameters
    val dualSenseTriggerMode: String = "SECTION_SNAP",
    val dualSenseForce: Int = 128,
    val enableImpulseTriggers: Boolean = true,
    val hapticDestination: String = "CONTROLLER_NATIVE",

    // Console Remote Play Tab Parameters
    val xHomePowerMode: String = "SILENT_DIRECT_TO_STREAM",
    val xHomeSignalingServer: String = "westus2.signalserver.xbox.com",
    val xHomePreferIpv6: Boolean = true,

    // Advanced & System
    val enableZeroCopySurface: Boolean = true,
    val enableSessionHandover: Boolean = true
) {
    fun toDeviceInfoJson(): String {
        return org.json.JSONObject().apply {
            put("appInfo", org.json.JSONObject().apply {
                put("env", org.json.JSONObject().apply {
                    put("clientAppId", "XboxApp")
                    put("clientAppType", "native")
                    put("clientAppVersion", "2403.1001.4.0")
                    put("clientSdkVersion", "10.3.7")
                    put("httpEnvironment", "prod")
                    put("sdkInstallId", "")
                })
            })
            put("dev", org.json.JSONObject().apply {
                put("hw", org.json.JSONObject().apply {
                    put("make", "Microsoft")
                    put("model", "Surface Pro 9")
                    put("sdktype", "native")
                })
                put("os", org.json.JSONObject().apply {
                    put("name", "windows")
                    put("ver", "22631.2715")
                    put("platform", "desktop")
                })
                put("displayInfo", org.json.JSONObject().apply {
                    put("dimensions", org.json.JSONObject().apply {
                        put("widthInPixels", resolution.width)
                        put("heightInPixels", resolution.height)
                    })
                    put("pixelDensity", org.json.JSONObject().apply {
                        put("dpiX", 96)
                        put("dpiY", 96)
                    })
                })
                put("browser", org.json.JSONObject().apply {
                    put("browserName", "chrome")
                    put("browserVersion", "130.0")
                })
            })
        }.toString()
    }
}
