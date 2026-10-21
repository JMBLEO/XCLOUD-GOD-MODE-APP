package com.jmbleo.xcloudgodmode.config

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

enum class ResponseCurve {
    LINEAR,
    EXPONENTIAL,
    AGGRESSIVE
}

data class GodModeConfig(
    val innerDeadzone: Float = 0.08f,
    val outerDeadzone: Float = 0.95f,
    val brightness: Int = 100,
    val contrast: Int = 100,
    val saturation: Int = 100,
    val sharpness: Int = 50,
    val responseCurve: ResponseCurve = ResponseCurve.LINEAR,
    val swapAB: Boolean = false,
    val swapXY: Boolean = false
) {
    fun sanitized(): GodModeConfig {
        return copy(
            innerDeadzone = innerDeadzone.coerceIn(0.0f, 0.4f),
            outerDeadzone = outerDeadzone.coerceIn(0.6f, 1.0f),
            brightness = brightness.coerceIn(50, 150),
            contrast = contrast.coerceIn(50, 150),
            saturation = saturation.coerceIn(50, 150),
            sharpness = sharpness.coerceIn(0, 100)
        )
    }
}

class GodModeConfigManager private constructor(private val context: Context) {
    private var currentConfig = GodModeConfig()
    private val listeners = CopyOnWriteArrayList<(GodModeConfig) -> Unit>()

    fun getConfig(): GodModeConfig = currentConfig

    fun updateConfig(config: GodModeConfig) {
        val sanitized = config.sanitized()
        currentConfig = sanitized
        listeners.forEach { it(sanitized) }
    }

    fun addListener(listener: (GodModeConfig) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (GodModeConfig) -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        @Volatile
        private var instance: GodModeConfigManager? = null

        fun getInstance(context: Context): GodModeConfigManager {
            return instance ?: synchronized(this) {
                instance ?: GodModeConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
