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

    // 2. Ingeniería de Stream y Negociación SDP
    val targetResProfile: String = "1080p", // "720p", "1080p", "1440p", "4k", "8k"
    val forceXRemoteRes: Boolean = true,
    val infiniteBitrate: Boolean = false,
    val unlock120Fps: Boolean = true,
    val forceSeriesX: Boolean = true,
    val codecPref: String = "🌟 Auto-Detect Máximo Soportado",
    val hdrMode: String = "off", // "off", "hdr10", "dolby", "hlg"
    val hardwareDecode: Boolean = true,

    // 3. Controles Base e Integridad
    val pollingRateMs: Int = 4,
    val deadzoneL: Int = 5,
    val deadzoneR: Int = 5,
    val swapABXY: Boolean = false,
    val safeNativeModeOnly: Boolean = true,
    val forceKeyboardConsole: Boolean = false,
    val enableMnKEmulation: Boolean = false,
    val gamepadKernel: String = "Standard Gamepad",

    // 4. Sistema, QoL y Configuración OSD
    val homeLayoutStyle: String = "PlayStaxBox Hybrid",
    val enableXmbWaveAnimation: Boolean = true,
    val enableKenBurnsHero: Boolean = true,
    val smartTvMode: Boolean = false,
    val skipSplash: Boolean = true,
    val skipFeedback: Boolean = true,
    val blockAnalytics: Boolean = true,
    val antiKick: Boolean = true,
    val deviceOverride: String = "Auto",
    val showStats: Boolean = true, // Ghost HUD master switch

    // --- [B] GAME OPTIMIZER (IN-STREAM / REAL-TIME) ---
    
    val optimizerProfile: String = "balanced", // "battery_saver", "balanced", "god_mode"
    
    // Video Processing (WebGL / Shaders)
    val upscalerEngine: String = "AMD FSR 3.1 RCAS WebGL2",
    val fsrSharpness: Float = 0.65f,
    val seedvrColorFix: Boolean = true,
    val aspectRatio: String = "16:9",
    val videoStretch: String = "normal", // "normal", "fill", "custom"
    val brightness: Float = 1.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,

    // Audio & Haptics
    val volumeBoostPercent: Int = 150,
    val audioCodecProfile: String = "Opus Hi-Fi (510 kbps)",
    val vibrationEnabled: Boolean = true,
    val vibrationIntensity: Int = 100,
    val vibProfile: String = "standard", // "standard", "dualsense", "pro"
    val enableHapticCoupling: Boolean = true,
    
    // --- [C] Legacy / Missing Fields for compatibility ---
    val videoCodec: String = "HEVC / H.265",
    val maxBitrateMbps: Int = 20,
    val audioChannelMode: String = "Stereo",
    val userAgentProfile: String = "Xbox Windows 11 App",
    val selectedMotor: Int = 1,
    
    // UI Audio Synth
    val enableUiSounds: Boolean = true,
    val uiSoundTheme: String = "PLAYSTAX_HYBRID",
    val uiSoundVolume: Int = 75
)

enum class DeviceType {
    PHONE,
    TABLET,
    TV
}
