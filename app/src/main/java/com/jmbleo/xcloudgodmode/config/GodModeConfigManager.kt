package com.jmbleo.xcloudgodmode.config

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Gestor de persistencia síncrono y de alto rendimiento para God Mode Native.
 * Equivalente al motor MMKV en Android nativo, con caché en memoria atómica (AtomicReference)
 * para lecturas a 0ms en el bucle de procesamiento de eventos de mandos.
 */
class GodModeConfigManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "god_mode_native_prefs"
        private const val KEY_INNER_DEADZONE = "inner_deadzone"
        private const val KEY_OUTER_DEADZONE = "outer_deadzone"
        private const val KEY_RESPONSE_CURVE = "response_curve"
        private const val KEY_SWAP_AB = "swap_ab"
        private const val KEY_SWAP_XY = "swap_xy"
        private const val KEY_COMBO_MENU = "combo_menu_enabled"
        private const val KEY_HUD_ENABLED = "hud_enabled"
        private const val KEY_HUD_POSITION = "hud_position"
        private const val KEY_DETAILED_STATS = "detailed_stats"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_CONTRAST = "contrast"
        private const val KEY_SATURATION = "saturation"
        private const val KEY_SHARPNESS = "sharpness"
        private const val KEY_SCALING_PROFILE = "scaling_profile"
        private const val KEY_LOW_LATENCY_WIFI = "low_latency_wifi"
        private const val KEY_THERMAL_WATCHDOG = "thermal_watchdog"
        private const val KEY_CPU_WAKELOCK = "cpu_wakelock"
        private const val KEY_REFRESH_RATE = "preferred_refresh_rate"
        private const val KEY_RESOLUTION = "preferred_resolution"
        private const val KEY_CODEC = "preferred_codec"
        private const val KEY_AUDIO_LOW_LATENCY = "audio_low_latency"

        @Volatile
        private var instance: GodModeConfigManager? = null

        fun getInstance(context: Context): GodModeConfigManager {
            return instance ?: synchronized(this) {
                instance ?: GodModeConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val activeConfig = AtomicReference<GodModeConfig>()
    private val listeners = CopyOnWriteArrayList<(GodModeConfig) -> Unit>()

    init {
        activeConfig.set(loadConfigFromPrefs(null))
    }

    /**
     * Obtiene la configuración activa actual con acceso de latencia cero en memoria.
     */
    fun getConfig(): GodModeConfig = activeConfig.get()

    /**
     * Resuelve la configuración en cascada: Global ➔ Por Juego (titleId).
     */
    fun resolveConfig(titleId: String? = null): GodModeConfig {
        return loadConfigFromPrefs(titleId)
    }

    /**
     * Actualiza la configuración, la persiste en almacenamiento síncrono/asíncrono
     * y notifica a los observadores registrados.
     */
    fun updateConfig(config: GodModeConfig, titleId: String? = null) {
        val sanitized = config.sanitized()
        activeConfig.set(sanitized)

        val prefix = if (!titleId.isNullOrBlank()) "${titleId}_" else ""
        prefs.edit().apply {
            putFloat(prefix + KEY_INNER_DEADZONE, sanitized.innerDeadzone)
            putFloat(prefix + KEY_OUTER_DEADZONE, sanitized.outerDeadzone)
            putString(prefix + KEY_RESPONSE_CURVE, sanitized.responseCurve.name)
            putBoolean(prefix + KEY_SWAP_AB, sanitized.swapAB)
            putBoolean(prefix + KEY_SWAP_XY, sanitized.swapXY)
            putBoolean(prefix + KEY_COMBO_MENU, sanitized.comboMenuEnabled)
            putBoolean(prefix + KEY_HUD_ENABLED, sanitized.hudEnabled)
            putString(prefix + KEY_HUD_POSITION, sanitized.hudPosition.name)
            putBoolean(prefix + KEY_DETAILED_STATS, sanitized.showDetailedStats)
            putInt(prefix + KEY_BRIGHTNESS, sanitized.brightness)
            putInt(prefix + KEY_CONTRAST, sanitized.contrast)
            putInt(prefix + KEY_SATURATION, sanitized.saturation)
            putInt(prefix + KEY_SHARPNESS, sanitized.sharpness)
            putString(prefix + KEY_SCALING_PROFILE, sanitized.scalingProfile.name)
            putBoolean(prefix + KEY_LOW_LATENCY_WIFI, sanitized.lowLatencyWifi)
            putBoolean(prefix + KEY_THERMAL_WATCHDOG, sanitized.thermalWatchdog)
            putBoolean(prefix + KEY_CPU_WAKELOCK, sanitized.cpuWakeLock)
            putFloat(prefix + KEY_REFRESH_RATE, sanitized.preferredRefreshRate)
            putString(prefix + KEY_RESOLUTION, sanitized.preferredResolution)
            putString(prefix + KEY_CODEC, sanitized.preferredCodec)
            putBoolean(prefix + KEY_AUDIO_LOW_LATENCY, sanitized.audioLowLatency)
            apply()
        }

        notifyListeners(sanitized)
    }

    fun addListener(listener: (GodModeConfig) -> Unit) {
        listeners.add(listener)
        listener(activeConfig.get())
    }

    fun removeListener(listener: (GodModeConfig) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(config: GodModeConfig) {
        listeners.forEach {
            try {
                it(config)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadConfigFromPrefs(titleId: String?): GodModeConfig {
        val prefix = if (!titleId.isNullOrBlank() && prefs.contains("${titleId}_$KEY_INNER_DEADZONE")) {
            "${titleId}_"
        } else {
            ""
        }

        val rawCurve = prefs.getString(prefix + KEY_RESPONSE_CURVE, ResponseCurve.LINEAR.name)
        val curve = try {
            ResponseCurve.valueOf(rawCurve ?: ResponseCurve.LINEAR.name)
        } catch (_: Exception) {
            ResponseCurve.LINEAR
        }

        val rawPosition = prefs.getString(prefix + KEY_HUD_POSITION, HudPosition.TOP_RIGHT.name)
        val position = try {
            HudPosition.valueOf(rawPosition ?: HudPosition.TOP_RIGHT.name)
        } catch (_: Exception) {
            HudPosition.TOP_RIGHT
        }

        val rawScaling = prefs.getString(prefix + KEY_SCALING_PROFILE, ScalingProfile.CAS.name)
        val scaling = try {
            ScalingProfile.valueOf(rawScaling ?: ScalingProfile.CAS.name)
        } catch (_: Exception) {
            ScalingProfile.CAS
        }

        return GodModeConfig(
            innerDeadzone = prefs.getFloat(prefix + KEY_INNER_DEADZONE, 0.08f),
            outerDeadzone = prefs.getFloat(prefix + KEY_OUTER_DEADZONE, 0.95f),
            responseCurve = curve,
            swapAB = prefs.getBoolean(prefix + KEY_SWAP_AB, false),
            swapXY = prefs.getBoolean(prefix + KEY_SWAP_XY, false),
            comboMenuEnabled = prefs.getBoolean(prefix + KEY_COMBO_MENU, true),
            hudEnabled = prefs.getBoolean(prefix + KEY_HUD_ENABLED, true),
            hudPosition = position,
            showDetailedStats = prefs.getBoolean(prefix + KEY_DETAILED_STATS, false),
            brightness = prefs.getInt(prefix + KEY_BRIGHTNESS, 100),
            contrast = prefs.getInt(prefix + KEY_CONTRAST, 100),
            saturation = prefs.getInt(prefix + KEY_SATURATION, 100),
            sharpness = prefs.getInt(prefix + KEY_SHARPNESS, 0),
            scalingProfile = scaling,
            lowLatencyWifi = prefs.getBoolean(prefix + KEY_LOW_LATENCY_WIFI, true),
            thermalWatchdog = prefs.getBoolean(prefix + KEY_THERMAL_WATCHDOG, true),
            cpuWakeLock = prefs.getBoolean(prefix + KEY_CPU_WAKELOCK, true),
            preferredRefreshRate = prefs.getFloat(prefix + KEY_REFRESH_RATE, 60.0f),
            preferredResolution = prefs.getString(prefix + KEY_RESOLUTION, "1080p HQ") ?: "1080p HQ",
            preferredCodec = prefs.getString(prefix + KEY_CODEC, "HEVC / H.265") ?: "HEVC / H.265",
            audioLowLatency = prefs.getBoolean(prefix + KEY_AUDIO_LOW_LATENCY, true)
        ).sanitized()
    }
}
