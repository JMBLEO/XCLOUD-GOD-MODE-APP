package com.xcloud.godmode.config

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xcloud.godmode.launch.EngineId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.configDataStore by preferencesDataStore(name = "godmode_global_config")

/**
 * AppConfigurationRepository: Atomically persists and reacts to global game settings.
 * Replaces SharedPreferences with DataStore to ensure transactional integrity.
 */
class AppConfigurationRepository private constructor(val context: Context) {
    private val TAG = "ConfigRepo"
    private val mutex = Mutex()

    private val KEY_FORCE_AZURE = booleanPreferencesKey("force_azure")
    private val KEY_REGION_CODE = stringPreferencesKey("region_code")
    private val KEY_GATEWAY = stringPreferencesKey("azure_gateway")
    private val KEY_BYPASS_ROUTE = stringPreferencesKey("bypass_route")
    private val KEY_RESOLUTION = stringPreferencesKey("video_resolution")
    private val KEY_BITRATE = intPreferencesKey("video_bitrate")
    private val KEY_FPS = intPreferencesKey("video_fps")
    private val KEY_CODEC = stringPreferencesKey("video_codec")
    private val KEY_ENGINE = stringPreferencesKey("preferred_engine")
    private val KEY_TOUCH = booleanPreferencesKey("touch_default")
    private val KEY_HUD = booleanPreferencesKey("hud_default")
    private val KEY_TOUCH_SENSITIVITY = androidx.datastore.preferences.core.floatPreferencesKey("touch_sensitivity")
    private val KEY_TOUCH_DEADZONE = androidx.datastore.preferences.core.floatPreferencesKey("touch_deadzone")

    val configFlow: Flow<GodModeGlobalConfig> = context.configDataStore.data.map { prefs ->
        GodModeGlobalConfig(
            forceAzureServer = prefs[KEY_FORCE_AZURE] ?: true,
            forcedRegionCode = prefs[KEY_REGION_CODE] ?: "CHI",
            selectedAzureGateway = prefs[KEY_GATEWAY] ?: "Chile Central (Santiago - Quilicura)",
            bypassAndNetworkRoute = prefs[KEY_BYPASS_ROUTE] ?: "[MS Azure] Argentina - Mendoza / Cuyo (Enlace Transandino)",
            resolution = runCatching { VideoResolution.valueOf(prefs[KEY_RESOLUTION] ?: "RES_1080P") }.getOrDefault(VideoResolution.RES_1080P),
            maxBitrateMbps = prefs[KEY_BITRATE] ?: 50,
            targetFps = prefs[KEY_FPS] ?: 60,
            preferredCodec = runCatching { VideoCodecProfile.valueOf(prefs[KEY_CODEC] ?: "HEVC_H265") }.getOrDefault(VideoCodecProfile.HEVC_H265),
            preferredEngine = runCatching { EngineId.valueOf(prefs[KEY_ENGINE] ?: "MOTOR_4_MEDIACODEC_HWC2_DIRECT") }.getOrDefault(EngineId.MOTOR_4_MEDIACODEC_HWC2_DIRECT),
            enableTouchControlsByDefault = prefs[KEY_TOUCH] ?: false,
            enableTelemetryHudByDefault = prefs[KEY_HUD] ?: false,
            touchSensitivity = prefs[KEY_TOUCH_SENSITIVITY] ?: 1.0f,
            touchDeadzone = prefs[KEY_TOUCH_DEADZONE] ?: 0.1f
        )
    }

    suspend fun getCurrent(): GodModeGlobalConfig = configFlow.first()

    suspend fun saveConfig(cfg: GodModeGlobalConfig) = mutex.withLock {
        context.configDataStore.edit { prefs ->
            prefs[KEY_FORCE_AZURE] = cfg.forceAzureServer
            prefs[KEY_REGION_CODE] = cfg.forcedRegionCode
            prefs[KEY_GATEWAY] = cfg.selectedAzureGateway
            prefs[KEY_BYPASS_ROUTE] = cfg.bypassAndNetworkRoute
            prefs[KEY_RESOLUTION] = cfg.resolution.name
            prefs[KEY_BITRATE] = cfg.maxBitrateMbps
            prefs[KEY_FPS] = cfg.targetFps
            prefs[KEY_CODEC] = cfg.preferredCodec.name
            prefs[KEY_ENGINE] = cfg.preferredEngine.name
            prefs[KEY_TOUCH] = cfg.enableTouchControlsByDefault
            prefs[KEY_HUD] = cfg.enableTelemetryHudByDefault
            prefs[KEY_TOUCH_SENSITIVITY] = cfg.touchSensitivity
            prefs[KEY_TOUCH_DEADZONE] = cfg.touchDeadzone
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
