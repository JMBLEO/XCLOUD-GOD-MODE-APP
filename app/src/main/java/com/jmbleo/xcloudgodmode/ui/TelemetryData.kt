package com.jmbleo.xcloudgodmode.ui

import android.graphics.Color

/**
 * Estado semántico de la calidad de conexión y decodificación.
 */
enum class HealthState(val colorInt: Int, val label: String) {
    OPTIMAL(Color.parseColor("#52E172"), "Óptimo"),
    WARNING(Color.parseColor("#ECDE38"), "Precaución"),
    CRITICAL(Color.parseColor("#E74040"), "Crítico")
}

/**
 * Modelo de datos de telemetría de streaming WebRTC en tiempo real.
 */
data class TelemetryData(
    val fps: Int = 60,
    val rttMs: Int = 24,                    // Latencia RTT / Ping en ms
    val jitterMs: Int = 2,                  // Variación de latencia en ms
    val packetLossPercent: Float = 0.0f,    // Pérdida de paquetes (%)
    val bitrateMbps: Float = 14.5f,         // Tasa de transferencia en Mbps
    val resolution: String = "1080p HQ",    // Resolución del stream WebRTC
    val decodeTimeMs: Float = 4.2f,         // Tiempo de decodificación de fotograma en GPU
    val batteryPercent: Int = 85,           // Batería del dispositivo móvil / consola
    val isCharging: Boolean = false
) {
    /**
     * Determina el estado de salud de la sesión basándose en umbrales de jitter, RTT y pérdida de paquetes.
     */
    val healthState: HealthState
        get() = when {
            packetLossPercent > 2.0f || rttMs > 75 || jitterMs > 12 -> HealthState.CRITICAL
            packetLossPercent > 0.5f || rttMs > 40 || jitterMs > 4 -> HealthState.WARNING
            else -> HealthState.OPTIMAL
        }
}
