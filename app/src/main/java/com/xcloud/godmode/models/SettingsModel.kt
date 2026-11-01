package com.xcloud.godmode.models

/**
 * SettingsModel defines the complete configuration for the God Mode engine.
 * Categorized by Infrastructure (Pre-Stream) and Real-Time (In-Stream).
 */
data class SettingsModel(
    // --- [A] ADVANCED SETTINGS (PRE-STREAM / HOME) ---
    
    // 1. Red, Servidores y Bypass IP
    val enableNetworkBypass: Boolean = true,
    val bypassIp: String = "20.143.92.1", // Chile Central default
    val customIp: String = "",
    val enableServerLock: Boolean = true,
    val forceServer: String = "ChileCentral",
    val storeLocale: String = "es-cl",
    val preferIPv6: Boolean = true,
    val disableNetworkCheck: Boolean = true,
    val remotePlayKeepAlive: Boolean = true,
    val cloudOffering: String = "xgpuweb", // "xgpuweb", "xgpu_ad_trial", "xhome_remote"

    // 2. Ingeniería de Stream y Negociación SDP
    val targetResProfile: String = "1080p", 
    val forceXRemoteRes: Boolean = true,
    val infiniteBitrate: Boolean = false,
    val unlock120Fps: Boolean = true,
    val forceSeriesX: Boolean = true,
    val codecPref: String = "🌟 Auto-Detect Máximo Soportado",
    val hdrMode: String = "off",
    val hardwareDecode: Boolean = true,
    val ultraLowLatencyDecoder: Boolean = true,
    val socVendorProfile: String = "AUTO_DETECT", // "AUTO_DETECT", "QUALCOMM", "MEDIATEK", "EXYNOS"

    // 3. Seguridad & Renderizado
    val enforceTls13Strict: Boolean = true,
    val anonymizeWebRtcLocalIps: Boolean = true,
    val filterTeredoCandidates: Boolean = true,
    val enableGpuRasterization: Boolean = true,
    val autoDarkModeWebContents: Boolean = true,
    val webrtcAudioDownmixMethod: String = "CENTER_VOICE_BOOST_3DB", // "ITU_R_BS775", "CENTER_VOICE_BOOST", "PASSTHROUGH"

    // 4. Pantalla & Escala UI
    val appLanguage: String = "AUTO", // "AUTO", "ES", "EN"
    val enableAutoScreenScale: Boolean = true,
    val textScalePercent: Int = 100, // 85 to 160
    val iconScalePercent: Int = 100,
    val posterScalePercent: Int = 100,
    val qrScalePercent: Int = 100, // 80 to 140
    val enableLeanbackFocus: Boolean = true,
    val homeLayoutStyle: String = "Cinema Hybrid",
    val enableXmbWaveAnimation: Boolean = true,
    val enableKenBurnsHero: Boolean = true,
    val skipSplash: Boolean = true,
    val blockAnalytics: Boolean = true,
    val antiKick: Boolean = true,
    val deviceOverride: String = "Auto",
    val showStats: Boolean = true,
    val hudPosition: String = "TOP", // "TOP", "BOTTOM"
    val hudShowRtt: Boolean = true,
    val hudShowJitter: Boolean = true,
    val hudShowPacketLoss: Boolean = true,
    val hudShowBitrate: Boolean = true,
    val hudShowProfilePill: Boolean = true,

    // --- [B] GAME OPTIMIZER (IN-STREAM / REAL-TIME) ---
    
    val optimizerProfile: String = "balanced",
    
    // Video Processing (AI & Shaders)
    val upscalerEngine: String = "AMD FSR 3.1 RCAS WebGL2",
    val fsrSharpness: Float = 0.65f,
    val lsfgMultiplier: String = "off", // "off", "2x_120fps", "3x_180fps"
    val adaptiveToneMapping: String = "aces_filmic_adaptive", // "off", "aces_filmic_adaptive", "reinhard_dynamic"
    val npuPowerMode: String = "balanced_npu",
    
    val seedvrColorFix: Boolean = true,
    val aspectRatio: String = "16:9",
    val videoStretch: String = "normal",
    val brightness: Float = 1.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,

    // Audio & Haptics
    val volumeBoostPercent: Int = 150,
    val audioCodecProfile: String = "Opus Hi-Fi (510 kbps)",
    val vibrationEnabled: Boolean = true,
    val vibrationIntensity: Int = 100,
    val vibProfile: String = "standard", 
    val enableHapticCoupling: Boolean = true,
    
    // UI Audio Synth
    val enableUiSounds: Boolean = true,
    val uiSoundTheme: String = "CINEMA_HYBRID",
    val uiSoundVolume: Int = 75,

    // Internal / Legacy
    val selectedMotor: Int = 1,
    val videoCodec: String = "HEVC / H.265", // Options: "AV1", "HEVC / H.265", "VVC / H.266", "AV2", "LCEVC / MPEG-5", "EVC", "AVS3", "NVC (Neural)", "VP9", "H.264 Baseline", "NVENC Ultra Low Latency", "Cloud G-Sync Direct"
    val maxBitrateMbps: Int = 50, // 15, 20, 30, 50, 75, 100, 150 (Ultra Uncapped)
    val audioChannelMode: String = "Stereo",
    val vsyncOff: Boolean = true,
    val bbrv2Enabled: Boolean = true,
    val casFilterEnabled: Boolean = true,
    val vrrEnabled: Boolean = true,
    
    // Missing fields for Advanced Settings & Engine
    val pollingRateMs: Int = 4, // 250Hz (4ms) by default for raw input
    val swapABXY: Boolean = false,
    val safeNativeModeOnly: Boolean = false,
    val userAgentProfile: String = "XboxApp/Android",

    // --- [C] GOD MODE ULTIMATE (ENGINE v3) ---
    val enableAudioHaptics: Boolean = true,
    val enableTcpFallback: Boolean = false,
    val enableDoh: Boolean = true,
    val enableDynamicPatcher: Boolean = true,
    val enableClarityBoost: Boolean = true,
    val enableWakeOnLan: Boolean = true,
    val enablePictureInPicture: Boolean = true,
    val enableXHomeUdpDirect: Boolean = true
) {
    fun toJson(): String {
        val json = org.json.JSONObject()
        json.put("appLanguage", appLanguage)
        json.put("enableNetworkBypass", enableNetworkBypass)
        json.put("bypassIp", bypassIp)
        json.put("forceServer", forceServer)
        json.put("targetResProfile", targetResProfile)
        json.put("textScalePercent", textScalePercent)
        json.put("iconScalePercent", iconScalePercent)
        json.put("posterScalePercent", posterScalePercent)
        json.put("qrScalePercent", qrScalePercent)
        json.put("enableLeanbackFocus", enableLeanbackFocus)
        json.put("hudPosition", hudPosition)
        json.put("showStats", showStats)
        json.put("hudShowRtt", hudShowRtt)
        json.put("hudShowJitter", hudShowJitter)
        json.put("hudShowPacketLoss", hudShowPacketLoss)
        json.put("hudShowBitrate", hudShowBitrate)
        json.put("hudShowProfilePill", hudShowProfilePill)
        json.put("upscalerEngine", upscalerEngine)
        json.put("fsrSharpness", fsrSharpness.toDouble())
        json.put("aspectRatio", aspectRatio)
        json.put("volumeBoostPercent", volumeBoostPercent)
        return json.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): SettingsModel {
            val json = org.json.JSONObject(jsonStr)
            val defaults = SettingsModel()
            return defaults.copy(
                appLanguage = json.optString("appLanguage", defaults.appLanguage),
                enableNetworkBypass = json.optBoolean("enableNetworkBypass", defaults.enableNetworkBypass),
                bypassIp = json.optString("bypassIp", defaults.bypassIp),
                forceServer = json.optString("forceServer", defaults.forceServer),
                targetResProfile = json.optString("targetResProfile", defaults.targetResProfile),
                textScalePercent = json.optInt("textScalePercent", defaults.textScalePercent),
                iconScalePercent = json.optInt("iconScalePercent", defaults.iconScalePercent),
                posterScalePercent = json.optInt("posterScalePercent", defaults.posterScalePercent),
                qrScalePercent = json.optInt("qrScalePercent", defaults.qrScalePercent),
                enableLeanbackFocus = json.optBoolean("enableLeanbackFocus", defaults.enableLeanbackFocus),
                hudPosition = json.optString("hudPosition", defaults.hudPosition),
                showStats = json.optBoolean("showStats", defaults.showStats),
                hudShowRtt = json.optBoolean("hudShowRtt", defaults.hudShowRtt),
                hudShowJitter = json.optBoolean("hudShowJitter", defaults.hudShowJitter),
                hudShowPacketLoss = json.optBoolean("hudShowPacketLoss", defaults.hudShowPacketLoss),
                hudShowBitrate = json.optBoolean("hudShowBitrate", defaults.hudShowBitrate),
                hudShowProfilePill = json.optBoolean("hudShowProfilePill", defaults.hudShowProfilePill),
                upscalerEngine = json.optString("upscalerEngine", defaults.upscalerEngine),
                fsrSharpness = json.optDouble("fsrSharpness", defaults.fsrSharpness.toDouble()).toFloat(),
                aspectRatio = json.optString("aspectRatio", defaults.aspectRatio),
                volumeBoostPercent = json.optInt("volumeBoostPercent", defaults.volumeBoostPercent)
            )
        }
    }
}

enum class DeviceType {
    PHONE,
    TABLET,
    TV
}

data class StreamingSettings(
    val resolution: String = "1440p (QHD)",
    val maxBitrateMbps: Int = 45,
    val preferredCodec: String = "HEVC",
    val vsyncOff: Boolean = true,
    val bbrv2Enabled: Boolean = true,
    val casFilterEnabled: Boolean = true
)

data class AudioSettings(
    val format: String = "Opus estéreo",
    val gainLevel: Float = 1.0f,
    val spatialBinaural: Boolean = true
)
