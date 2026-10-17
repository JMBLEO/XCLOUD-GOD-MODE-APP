package com.jmbleo.xcloudgodmode.config

/**
 * Curvas de sensibilidad aplicables a las palancas analógicas.
 */
enum class ResponseCurve {
    LINEAR,
    ENHANCED,
    AGGRESSIVE,
    EXPONENTIAL
}

/**
 * Esquinas de anclaje para el HUD flotante de telemetría.
 */
enum class HudPosition {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

/**
 * Perfiles de post-procesado y escalado de vídeo local.
 */
enum class ScalingProfile {
    OFF,
    CAS,
    FSR_1
}

/**
 * Modelo de datos inmutable para la configuración de God Mode Native.
 */
data class GodModeConfig(
    // Controles y Zonas Muertas
    val innerDeadzone: Float = 0.08f,       // 8% de zona muerta interior
    val outerDeadzone: Float = 0.95f,       // 95% de límite exterior (saturación al 100%)
    val responseCurve: ResponseCurve = ResponseCurve.LINEAR,
    val swapAB: Boolean = false,            // Mapeo estilo Nintendo Switch
    val swapXY: Boolean = false,
    val comboMenuEnabled: Boolean = true,   // Atajo View + Menu simultáneos

    // Telemetría & HUD
    val hudEnabled: Boolean = true,
    val hudPosition: HudPosition = HudPosition.TOP_RIGHT,
    val showDetailedStats: Boolean = false,

    // Post-procesado de Imagen
    val brightness: Int = 100,              // 50% a 150% (100 = neutro)
    val contrast: Int = 100,                // 50% a 150% (100 = neutro)
    val saturation: Int = 100,              // 50% a 150% (100 = neutro)
    val sharpness: Int = 0,                 // 0 a 100 (filtro CAS/FSR)
    val scalingProfile: ScalingProfile = ScalingProfile.CAS,

    // Red y Rendimiento
    val lowLatencyWifi: Boolean = true,
    val thermalWatchdog: Boolean = true
) {
    /**
     * Sanitiza los valores numéricos para prevenir estados inválidos o NaN.
     */
    fun sanitized(): GodModeConfig {
        val safeInner = innerDeadzone.coerceIn(0.0f, 0.35f)
        val safeOuter = outerDeadzone.coerceIn(safeInner + 0.10f, 1.0f)
        return copy(
            innerDeadzone = safeInner,
            outerDeadzone = safeOuter,
            brightness = brightness.coerceIn(50, 150),
            contrast = contrast.coerceIn(50, 150),
            saturation = saturation.coerceIn(50, 150),
            sharpness = sharpness.coerceIn(0, 100)
        )
    }
}
