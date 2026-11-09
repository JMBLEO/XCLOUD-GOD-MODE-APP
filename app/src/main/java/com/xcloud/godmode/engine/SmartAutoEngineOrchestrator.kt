package com.xcloud.godmode.engine

import android.app.AlertDialog
import android.content.Context

enum class GameStyleProfile(val label: String) {
    AUTO_DETECT_BY_NETWORK("⚡ Automático Total (Red + Silicio)"),
    COMPETITIVE_ESPORTS_120HZ("🎯 Shooter / Competitivo / Racing (Ultra-Baja Latencia 120Hz)"),
    CINEMATIC_AAA_HDR_AI("🎬 RPG / Cinemático AAA (1440p + HDR10 HGIG + IA Upscaling)"),
    MOBILE_DATA_RESILIENT("📶 Red Inestable / 5G (Anti-Cortes + Jitter Buffer)"),
    BATTERY_SAVER_NPU("🔋 Eficiencia Térmica (HWC2 / NPU Bajo Consumo)")
}

data class LiveNetworkTelemetry(
    val estimatedBandwidthMbps: Float,
    val rttMs: Float,
    val jitterMs: Float,
    val packetLossPercent: Float
)

class SmartAutoEngineOrchestrator(
    private val context: Context,
    private val hwProfile: DeviceHardwareProfile
) {

    fun showEngineSelectionDialog(
        currentNet: LiveNetworkTelemetry,
        onEngineSelected: (resolvedEngineId: Int, codec: String, force1440p: Boolean, enableHdr10: Boolean) -> Unit
    ) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        GameStyleProfile.entries.forEach { style ->
            options.add(style.label)
            actions.add {
                val decision = resolveOptimalEngine(style, currentNet)
                onEngineSelected(decision.first, decision.second, decision.third, hwProfile.supportsHdr10Display)
            }
        }

        val engineNames = mapOf(
            1 to "Motor 1: Rust Zero-Trust + Vulkan FSR 4",
            2 to "Motor 2: Vulkan Video ASIC + Snapdragon GSR 2.0",
            3 to "Motor 3: AMediaCodec + Vulkan Compute AI",
            4 to "Motor 4: AMediaCodec + HWC2 Directo (Zero-Shader)",
            5 to "Motor 5: AMediaCodec + NPU NNAPI Super-Res",
            6 to "Motor 6: AMediaCodec + EGLImageKHR OES (100% Compatible)"
        )

        engineNames.forEach { (id, name) ->
            if (hwProfile.enabledEngines.contains(id)) {
                options.add("✅ [Manual] $name")
                actions.add {
                    val bestCodec = selectBestHardwareCodec()
                    onEngineSelected(id, bestCodec, currentNet.estimatedBandwidthMbps >= 20f, hwProfile.supportsHdr10Display)
                }
            } else {
                options.add("🚫 [Deshabilitado] $name -> Usar Compatible (#${hwProfile.guaranteedFallbackEngineId})")
                actions.add {
                    val bestCodec = selectBestHardwareCodec()
                    onEngineSelected(hwProfile.guaranteedFallbackEngineId, bestCodec, false, false)
                }
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Motor de Reproducción (${hwProfile.socModel})")
            .setItems(options.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    fun resolveOptimalEngine(
        style: GameStyleProfile,
        net: LiveNetworkTelemetry
    ): Triple<Int, String, Boolean> {
        val preferredCodec = selectBestHardwareCodec()
        val canHandle1440p = net.estimatedBandwidthMbps >= 22.0f && net.packetLossPercent < 2.0f

        if (style == GameStyleProfile.MOBILE_DATA_RESILIENT || net.packetLossPercent >= 2.5f || net.jitterMs > 15.0f) {
            val resilientEngine = when {
                hwProfile.enabledEngines.contains(1) -> 1
                hwProfile.enabledEngines.contains(6) -> 6
                else -> hwProfile.guaranteedFallbackEngineId
            }
            return Triple(resilientEngine, "HEVC", false)
        }

        val targetEngine = when (style) {
            GameStyleProfile.COMPETITIVE_ESPORTS_120HZ -> {
                when {
                    hwProfile.enabledEngines.contains(4) -> 4
                    hwProfile.enabledEngines.contains(2) -> 2
                    hwProfile.enabledEngines.contains(3) -> 3
                    else -> hwProfile.guaranteedFallbackEngineId
                }
            }
            GameStyleProfile.CINEMATIC_AAA_HDR_AI -> {
                when {
                    hwProfile.enabledEngines.contains(3) -> 3
                    hwProfile.enabledEngines.contains(5) -> 5
                    hwProfile.enabledEngines.contains(4) -> 4
                    else -> hwProfile.guaranteedFallbackEngineId
                }
            }
            GameStyleProfile.BATTERY_SAVER_NPU -> {
                when {
                    hwProfile.enabledEngines.contains(4) -> 4
                    hwProfile.enabledEngines.contains(5) -> 5
                    else -> hwProfile.guaranteedFallbackEngineId
                }
            }
            GameStyleProfile.AUTO_DETECT_BY_NETWORK -> {
                if (net.rttMs < 25f && hwProfile.supports120HzVrr && hwProfile.enabledEngines.contains(4)) {
                    4
                } else if (canHandle1440p && hwProfile.enabledEngines.contains(3)) {
                    3
                } else {
                    hwProfile.guaranteedFallbackEngineId
                }
            }
            GameStyleProfile.MOBILE_DATA_RESILIENT -> {
                hwProfile.guaranteedFallbackEngineId
            }
        }

        return Triple(targetEngine, preferredCodec, canHandle1440p)
    }

    private fun selectBestHardwareCodec(): String {
        return when {
            hwProfile.hasHardwareAv1 -> "AV1"
            hwProfile.hasHardwareHevcMain10Hdr -> "HEVC"
            else -> "HEVC"
        }
    }
}
