package com.xcloud.godmode.services

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.xcloud.godmode.engine.EngineId
import com.xcloud.godmode.models.*
import com.xcloud.godmode.streaming.decoder.NextGenCodecGeneration
import com.xcloud.godmode.streaming.transport.GraphicsUpscalingEngine
import com.xcloud.godmode.streaming.transport.NetworkTransportStack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.configDataStore by preferencesDataStore(name = "godmode_global_config")

/**
 * AppConfigurationRepository: Atomically persists and reacts to global game settings.
 * Uses DataStore for transactional integrity.
 */
class AppConfigurationRepository private constructor(val context: Context) {
    private val TAG = "ConfigRepo"
    private val mutex = Mutex()

    private val KEY_FORCE_AZURE = booleanPreferencesKey("force_azure")
    private val KEY_REGION_CODE = stringPreferencesKey("region_code")
    private val KEY_GATEWAY = stringPreferencesKey("azure_gateway")
    private val KEY_BYPASS_ROUTE = stringPreferencesKey("bypass_route")
    private val KEY_BYPASS_ROUTE_ENUM = stringPreferencesKey("bypass_route_enum")
    private val KEY_RESOLUTION = stringPreferencesKey("video_resolution")
    private val KEY_BITRATE = intPreferencesKey("video_bitrate")
    private val KEY_FPS = intPreferencesKey("video_fps")
    private val KEY_CODEC = stringPreferencesKey("video_codec")
    private val KEY_ENGINE = stringPreferencesKey("preferred_engine")
    private val KEY_TOUCH = booleanPreferencesKey("touch_default")
    private val KEY_HUD = booleanPreferencesKey("hud_default")
    private val KEY_TOUCH_SENSITIVITY = floatPreferencesKey("touch_sensitivity")
    private val KEY_TOUCH_DEADZONE = floatPreferencesKey("touch_deadzone")
    private val KEY_ABRA = booleanPreferencesKey("abra_enabled")
    private val KEY_RAW_TRANSPORT = booleanPreferencesKey("raw_transport_enabled")
    
    // NANO
    private val KEY_CODEC_GEN = stringPreferencesKey("codec_generation")
    private val KEY_TRANSPORT_STACK = stringPreferencesKey("transport_stack")
    private val KEY_UPSCALING = stringPreferencesKey("upscaling_engine")
    private val KEY_FSR_SHARPNESS = floatPreferencesKey("fsr_sharpness_stops")

    val configFlow: Flow<GodModeGlobalConfig> = context.configDataStore.data.map { prefs ->
        GodModeGlobalConfig(
            forceAzureServer = prefs[KEY_FORCE_AZURE] ?: true,
            forcedRegionCode = prefs[KEY_REGION_CODE] ?: "CHI",
            selectedAzureGateway = prefs[KEY_GATEWAY] ?: "Chile Central (Santiago - Quilicura)",
            bypassAndNetworkRoute = prefs[KEY_BYPASS_ROUTE] ?: "[MS Azure] Argentina - Mendoza / Cuyo (Enlace Transandino)",
            bypassRoute = runCatching { AzureBypassRoute.valueOf(prefs[KEY_BYPASS_ROUTE_ENUM] ?: "CHILE_CENTRAL") }.getOrDefault(AzureBypassRoute.CHILE_CENTRAL),
            resolution = runCatching { VideoResolution.valueOf(prefs[KEY_RESOLUTION] ?: "RES_1080P") }.getOrDefault(VideoResolution.RES_1080P),
            maxBitrateMbps = prefs[KEY_BITRATE] ?: 50,
            targetFps = prefs[KEY_FPS] ?: 60,
            preferredCodec = runCatching { VideoCodecProfile.valueOf(prefs[KEY_CODEC] ?: "HEVC_H265") }.getOrDefault(VideoCodecProfile.HEVC_H265),
            preferredEngine = runCatching { 
                val raw = prefs[KEY_ENGINE] ?: "ENGINE_4_HWC2_DIRECT"
                when (raw) {
                    "MOTOR_1_RUST_VULKAN_IMMEDIATE" -> EngineId.ENGINE_1_VULKAN_RAV1D
                    "MOTOR_2_VULKAN_VIDEO_ASIC" -> EngineId.ENGINE_2_VULKAN_ASIC
                    "MOTOR_3_MEDIACODEC_VULKAN_COMPUTE_AI" -> EngineId.ENGINE_3_MEDIACODEC_HARDWARE
                    "MOTOR_4_MEDIACODEC_HWC2_DIRECT" -> EngineId.ENGINE_4_HWC2_DIRECT
                    "MOTOR_5_MEDIACODEC_NPU_NNAPI" -> EngineId.ENGINE_5_NNAPI_ZEROCOPY
                    "MOTOR_6_MEDIACODEC_EGL_OES" -> EngineId.ENGINE_6_GLES_COMPUTE
                    else -> EngineId.valueOf(raw)
                }
            }.getOrDefault(EngineId.ENGINE_4_HWC2_DIRECT),
            enableTouchControlsByDefault = prefs[KEY_TOUCH] ?: false,
            enableTelemetryHudByDefault = prefs[KEY_HUD] ?: false,
            touchSensitivity = prefs[KEY_TOUCH_SENSITIVITY] ?: 1.0f,
            touchDeadzone = prefs[KEY_TOUCH_DEADZONE] ?: 0.1f,
            enableAbraController = prefs[KEY_ABRA] ?: true,
            useRawTransport = prefs[KEY_RAW_TRANSPORT] ?: true,
            
            codecGeneration = runCatching { NextGenCodecGeneration.valueOf(prefs[KEY_CODEC_GEN] ?: "HEVC_H265") }.getOrDefault(NextGenCodecGeneration.HEVC_H265),
            transportStack = runCatching { NetworkTransportStack.valueOf(prefs[KEY_TRANSPORT_STACK] ?: "NANO_V3_RAW_UDP_RFC7983") }.getOrDefault(NetworkTransportStack.NANO_V3_RAW_UDP_RFC7983),
            upscalingEngine = runCatching { GraphicsUpscalingEngine.valueOf(prefs[KEY_UPSCALING] ?: "AMD_FSR_1_EASU_RCAS") }.getOrDefault(GraphicsUpscalingEngine.AMD_FSR_1_EASU_RCAS),
            fsrSharpnessStops = prefs[KEY_FSR_SHARPNESS] ?: 0.2f
        )
    }

    suspend fun getCurrent(): GodModeGlobalConfig = configFlow.first()

    suspend fun saveConfig(cfg: GodModeGlobalConfig) = mutex.withLock {
        context.configDataStore.edit { prefs ->
            prefs[KEY_FORCE_AZURE] = cfg.forceAzureServer
            prefs[KEY_REGION_CODE] = cfg.forcedRegionCode
            prefs[KEY_GATEWAY] = cfg.selectedAzureGateway
            prefs[KEY_BYPASS_ROUTE] = cfg.bypassAndNetworkRoute
            prefs[KEY_BYPASS_ROUTE_ENUM] = cfg.bypassRoute.name
            prefs[KEY_RESOLUTION] = cfg.resolution.name
            prefs[KEY_BITRATE] = cfg.maxBitrateMbps
            prefs[KEY_FPS] = cfg.targetFps
            prefs[KEY_CODEC] = cfg.preferredCodec.name
            prefs[KEY_ENGINE] = cfg.preferredEngine.name
            prefs[KEY_TOUCH] = cfg.enableTouchControlsByDefault
            prefs[KEY_HUD] = cfg.enableTelemetryHudByDefault
            prefs[KEY_TOUCH_SENSITIVITY] = cfg.touchSensitivity
            prefs[KEY_TOUCH_DEADZONE] = cfg.touchDeadzone
            prefs[KEY_ABRA] = cfg.enableAbraController
            prefs[KEY_RAW_TRANSPORT] = cfg.useRawTransport
            
            prefs[KEY_CODEC_GEN] = cfg.codecGeneration.name
            prefs[KEY_TRANSPORT_STACK] = cfg.transportStack.name
            prefs[KEY_UPSCALING] = cfg.upscalingEngine.name
            prefs[KEY_FSR_SHARPNESS] = cfg.fsrSharpnessStops
        }
        Log.d(TAG, "Config committed to DataStore.")
    }

    companion object {
        @Volatile
        private var instance: AppConfigurationRepository? = null

        fun getInstance(context: Context): AppConfigurationRepository =
            instance ?: synchronized(this) {
                instance ?: AppConfigurationRepository(context.applicationContext).also { instance = it }
            }
    }
}
