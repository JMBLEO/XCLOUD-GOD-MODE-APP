package com.aistudio.xcloudgodmode.ai.ui.viewmodels

import android.app.Application
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.xcloudgodmode.ai.GeminiAiAssistant
import com.aistudio.xcloudgodmode.ai.models.SettingsModel
import com.aistudio.xcloudgodmode.ai.services.GeminiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * OptimizationViewModel manages game streaming optimization profiles,
 * real-time hardware codec capability detection, and Gemini AI diagnostics.
 */
class OptimizationViewModel(
    private val geminiService: GeminiService = GeminiService()
) : ViewModel() {

    var appContext: Context? = null
        private set

    constructor(application: Application) : this(GeminiService()) {
        this.appContext = application.applicationContext
    }

    constructor(application: Application, geminiService: GeminiService) : this(geminiService) {
        this.appContext = application.applicationContext
    }

    private val _settings = MutableStateFlow(SettingsModel())
    val settings: StateFlow<SettingsModel> = _settings.asStateFlow()

    private val _recommendation = MutableStateFlow("Cargando análisis del Copiloto IA...")
    val recommendation: StateFlow<String> = _recommendation.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    val isAv1HardwareSupported: Boolean by lazy { checkHardwareDecoder("video/av01") }
    val isHevcHardwareSupported: Boolean by lazy { checkHardwareDecoder("video/hevc") }
    val isVp9HardwareSupported: Boolean by lazy { checkHardwareDecoder("video/x-vnd.on2.vp9") }
    val isH264HardwareSupported: Boolean by lazy { checkHardwareDecoder("video/avc") }

    fun updateSettings(newSettings: SettingsModel) {
        _settings.value = newSettings
        
        // Sync Sound Engine
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.isEnabled = newSettings.enableUiSounds
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.volumePercent = newSettings.uiSoundVolume
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.isHapticsCoupled = newSettings.enableHapticCoupling
        try {
            com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.activeTheme = 
                com.aistudio.xcloudgodmode.ai.audio.UiSoundTheme.valueOf(newSettings.uiSoundTheme)
        } catch (_: Exception) {}
    }

    fun applyPreset(presetName: String) {
        val current = _settings.value
        val newSettings = when (presetName) {
            "Battery Saver" -> current.copy(
                targetResProfile = "720p",
                optimizerProfile = "battery_saver",
                upscalerEngine = "Off",
                unlock120Fps = false,
                vibrationEnabled = false,
                audioCodecProfile = "Standard Opus"
            )
            "Balanced" -> current.copy(
                targetResProfile = "1080p",
                optimizerProfile = "balanced",
                upscalerEngine = "AMD FSR 3.1 RCAS WebGL2",
                fsrSharpness = 0.5f,
                unlock120Fps = true,
                vibrationEnabled = true,
                audioCodecProfile = "Opus Hi-Fi (510 kbps)"
            )
            "God Mode" -> current.copy(
                targetResProfile = "1440p",
                optimizerProfile = "god_mode",
                upscalerEngine = "Real-ESRGAN SRVGG",
                fsrSharpness = 0.8f,
                unlock120Fps = true,
                vibrationEnabled = true,
                vibrationIntensity = 120,
                audioCodecProfile = "Opus Hi-Fi (510 kbps)",
                forceSeriesX = true
            )
            else -> current
        }
        updateSettings(newSettings)
    }

    fun fetchRecommendation(gameTitle: String, networkType: String) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            try {
                val hwInfo = "AV1 HW: $isAv1HardwareSupported, HEVC HW: $isHevcHardwareSupported"
                val prompt = "Recommend optimization for $gameTitle on $networkType. HW: $hwInfo."
                val response = geminiService.getOptimizationRecommendation(gameTitle, networkType)
                _recommendation.value = response
            } catch (e: Exception) {
                _recommendation.value = "Recomendación: Perfil God Mode Ultra activo con HEVC 1080pHQ y nitidez AMD FSR 3.1."
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun testVibration(context: Context? = null) {
        val targetContext = context ?: appContext ?: return
        val intensity = _settings.value.vibrationIntensity
        if (intensity <= 0 || !_settings.value.vibrationEnabled) return

        try {
            val durationMs = 250L
            val amplitude = (255 * (intensity.coerceIn(10, 200) / 200f)).toInt().coerceIn(1, 255)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = targetContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = targetContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(durationMs)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun checkHardwareDecoder(mimeType: String): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos?.any { info ->
                if (info == null || info.isEncoder) return@any false
                val types = info.supportedTypes ?: return@any false
                val supportsType = types.any { it != null && it.equals(mimeType, ignoreCase = true) }
                if (!supportsType) return@any false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    val name = info.name ?: ""
                    !name.startsWith("omx.google.", ignoreCase = true) &&
                    !name.startsWith("c2.android.", ignoreCase = true)
                }
            } ?: true
        } catch (e: Throwable) {
            true
        }
    }
}
