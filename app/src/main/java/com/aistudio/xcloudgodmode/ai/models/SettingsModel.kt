package com.aistudio.xcloudgodmode.ai.models

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
    val enableAutoScreenScale: Boolean = true,
    val textScalePercent: Int = 100, // 85 to 160
    val iconScalePercent: Int = 100,
    val posterScalePercent: Int = 100,
    val homeLayoutStyle: String = "PlayStaxBox Hybrid",
    val enableXmbWaveAnimation: Boolean = true,
    val enableKenBurnsHero: Boolean = true,
    val skipSplash: Boolean = true,
    val blockAnalytics: Boolean = true,
    val antiKick: Boolean = true,
    val deviceOverride: String = "Auto",
    val showStats: Boolean = true, 

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
    val uiSoundTheme: String = "PLAYSTAX_HYBRID",
    val uiSoundVolume: Int = 75,

    // Internal / Legacy
    val selectedMotor: Int = 1,
    val videoCodec: String = "HEVC / H.265",
    val maxBitrateMbps: Int = 20,
    val audioChannelMode: String = "Stereo",
    
    // Missing fields for Advanced Settings & Engine
    val pollingRateMs: Int = 8,
    val swapABXY: Boolean = false,
    val safeNativeModeOnly: Boolean = false,
    val userAgentProfile: String = "XboxApp/Android"
)

enum class DeviceType {
    PHONE,
    TABLET,
    TV
}
