package com.xcloud.godmode.streaming.decoder

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.xcloud.godmode.streaming.ForbiddenTechnologyGuard

enum class DeviceFormFactor { PHONE, TABLET, ANDROID_TV }

enum class SiliconVendor(val prefixTokens: List<String>) {
    QUALCOMM_QTI(listOf("c2.qti.", "omx.qcom.")),
    SAMSUNG_EXYNOS(listOf("c2.exynos.", "c2.sec.", "omx.exynos.")),
    MEDIATEK_DIMENSITY(listOf("c2.mtk.", "omx.mtk.")),
    GOOGLE_TENSOR(listOf("c2.google.av1.bigwave", "c2.google.hevc", "c2.google.vvc")),
    NVIDIA_TEGRA_SHIELD(listOf("c2.nvidia.", "omx.nvidia.")),
    AMLOGIC_TV(listOf("c2.amlogic.", "omx.amlogic.")),
    REALTEK_TV(listOf("c2.realtek.", "omx.realtek.")),
    BROADCOM_TV(listOf("c2.brcm.", "omx.brcm.")),
    HISILICON_KIRIN(listOf("c2.hisi.", "omx.hisi.")),
    GENERIC_HARDWARE_C2(listOf("c2.hardware."))
}

/**
 * Códecs de nueva generación permitidos (H.264 / AVC erradicado por completo).
 */
enum class NextGenCodecGeneration(
    val rank: Int,
    val canonicalLabel: String,
    val candidateMimes: List<String>
) {
    AV2_EXP(4, "AV2 (Exp)", listOf("video/av2", "video/x-vnd.on2.av2")),
    VVC_H266(3, "VVC / H.266", listOf("video/vvc", "video/h266")),
    AV1(2, "AV1", listOf("video/av01", "video/av1")),
    HEVC_H265(1, "HEVC / H.265", listOf("video/hevc", "video/h265"))
}

data class HardwareDecoderDescriptor(
    val codecName: String,
    val mimeType: String,
    val generation: NextGenCodecGeneration,
    val vendor: SiliconVendor,
    val formFactor: DeviceFormFactor,
    val supportsLowLatencyFeature: Boolean,
    val supportsTunneledPlayback: Boolean,
    val supports10BitHdr: Boolean,
    val maxSupportedWidth: Int,
    val maxSupportedHeight: Int,
    val vendorLowLatencyParameterKey: String?
)

class DynamicHardwareDecoderLoader(private val context: Context) {

    fun detectFormFactor(): DeviceFormFactor {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return DeviceFormFactor.ANDROID_TV
        }
        val smallestScreenWidthDp = context.resources.configuration.smallestScreenWidthDp
        return if (smallestScreenWidthDp >= 600) DeviceFormFactor.TABLET else DeviceFormFactor.PHONE
    }

    /**
     * Escanea todos los decodificadores físicos instalados en el SoC del dispositivo
     * y devuelve la lista ordenada desde AV2 -> VVC (H.266) -> AV1 -> HEVC (H.265).
     */
    fun discoverAvailableHardwareDecoders(): List<HardwareDecoderDescriptor> {
        val formFactor = detectFormFactor()
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val discovered = mutableListOf<HardwareDecoderDescriptor>()

        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            val nameLower = info.name.lowercase()

            // Bloqueo absoluto de decodificadores por software, FFmpeg o emulados
            if (isSoftwareOrForbiddenCodec(info, nameLower)) continue

            val vendor = classifyVendor(nameLower)
            for (supportedMime in info.supportedTypes) {
                val generation = matchNextGenCodec(supportedMime) ?: continue
                ForbiddenTechnologyGuard.assertCleanPayloadAndMime(supportedMime)

                val caps = runCatching { info.getCapabilitiesForType(supportedMime) }.getOrNull() ?: continue
                val videoCaps = caps.videoCapabilities ?: continue

                val lowLatencySupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                } else {
                    nameLower.contains("low_latency")
                }

                val tunneledSupported =
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)

                val supports10Bit = caps.profileLevels?.any { pl ->
                    pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                } ?: false

                discovered.add(
                    HardwareDecoderDescriptor(
                        codecName = info.name,
                        mimeType = supportedMime,
                        generation = generation,
                        vendor = vendor,
                        formFactor = formFactor,
                        supportsLowLatencyFeature = lowLatencySupported,
                        supportsTunneledPlayback = tunneledSupported,
                        supports10BitHdr = supports10Bit,
                        maxSupportedWidth = videoCaps.supportedWidths.upper,
                        maxSupportedHeight = videoCaps.supportedHeights.upper,
                        vendorLowLatencyParameterKey = resolveVendorLowLatencyKey(vendor)
                    )
                )
            }
        }

        // Ordenación determinista:
        // 1. Generación más moderna primero (AV2 > VVC > AV1 > HEVC)
        // 2. Soporte nativo FEATURE_LowLatency
        // 3. Decodificadores propietarios del fabricante sobre genéricos
        return discovered.sortedWith(
            compareByDescending<HardwareDecoderDescriptor> { it.generation.rank }
                .thenByDescending { it.supportsLowLatencyFeature }
                .thenBy { it.vendor.ordinal }
        )
    }

    /**
     * Selecciona el mejor decodificador de hardware para la preferencia guardada en Ajustes.
     * Si el teléfono/tablet/TV no tiene silicio para el códec experimental pedido (ej. AV2 o VVC),
     * desciende automáticamente al siguiente códec de nueva generación soportado en hardware (AV1 o HEVC),
     * sin caer jamás en H.264.
     */
    fun resolveBestDecoderForDevice(
        preferredGeneration: NextGenCodecGeneration,
        targetWidth: Int,
        targetHeight: Int
    ): HardwareDecoderDescriptor {
        val available = discoverAvailableHardwareDecoders()
        check(available.isNotEmpty()) {
            "No se encontró ningún decodificador de hardware nativo (HEVC/AV1/VVC/AV2) en este dispositivo."
        }

        // 1. Intentar coincidencia exacta con la generación elegida en Ajustes que soporte la resolución
        val exactMatch = available.firstOrNull {
            it.generation == preferredGeneration &&
                it.maxSupportedWidth >= targetWidth &&
                it.maxSupportedHeight >= targetHeight
        } ?: available.firstOrNull { it.generation == preferredGeneration }

        if (exactMatch != null) return exactMatch

        // 2. Fallback progresivo exclusivamente entre códecs de nueva generación (AV2 -> VVC -> AV1 -> HEVC)
        return available.first()
    }

    /**
     * Construye el MediaFormat inyectando las banderas propietarias de ultra-baja latencia
     * específicas de Qualcomm QTI, Samsung Exynos, MediaTek, Google Tensor o Android TV.
     */
    fun buildHardwareMediaFormat(
        descriptor: HardwareDecoderDescriptor,
        width: Int,
        height: Int,
        targetFps: Int
    ): MediaFormat {
        ForbiddenTechnologyGuard.assertCleanPayloadAndMime(descriptor.mimeType)
        return MediaFormat.createVideoFormat(descriptor.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Prioridad de tiempo real (0 = máxima)
            setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps * 2)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // Cero B-frames para eliminar reordenamiento

            // Inyección del parámetro propietario del fabricante del SoC
            descriptor.vendorLowLatencyParameterKey?.let { vendorKey ->
                setInteger(vendorKey, 1)
            }

            // En Smart TVs / Android TV con soporte de túnel de hardware, activar sincronización directa
            if (descriptor.formFactor == DeviceFormFactor.ANDROID_TV && descriptor.supportsTunneledPlayback) {
                setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback, true)
            }
        }
    }

    private fun isSoftwareOrForbiddenCodec(info: MediaCodecInfo, nameLower: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !info.isHardwareAccelerated) return true
        return nameLower.startsWith("omx.google.") ||
            nameLower.startsWith("c2.android.") ||
            nameLower.contains("ffmpeg") ||
            nameLower.contains("sw") ||
            nameLower.contains("avc") ||
            nameLower.contains("h264")
    }

    private fun classifyVendor(nameLower: String): SiliconVendor =
        SiliconVendor.entries.firstOrNull { vendor ->
            vendor.prefixTokens.any { nameLower.startsWith(it) || nameLower.contains(it) }
        } ?: SiliconVendor.GENERIC_HARDWARE_C2

    private fun matchNextGenCodec(mime: String): NextGenCodecGeneration? {
        val m = mime.lowercase()
        if (m.contains("avc") || m.contains("h264")) return null // Bloqueo total de H.264
        return NextGenCodecGeneration.entries.firstOrNull { gen ->
            gen.candidateMimes.any { it.equals(m, ignoreCase = true) }
        }
    }

    private fun resolveVendorLowLatencyKey(vendor: SiliconVendor): String? = when (vendor) {
        SiliconVendor.QUALCOMM_QTI -> "vendor.qti-ext-dec-low-latency.enable"
        SiliconVendor.SAMSUNG_EXYNOS -> "vendor.sec-dec-output-control.low-latency"
        SiliconVendor.MEDIATEK_DIMENSITY -> "vendor.mtk.vdec.lowlatency.enable"
        SiliconVendor.GOOGLE_TENSOR -> "vendor.google-bigwave-low-latency.enable"
        SiliconVendor.NVIDIA_TEGRA_SHIELD -> "vendor.nvidia.low-latency.enable"
        SiliconVendor.AMLOGIC_TV -> "vendor.amlogic.vdec.low-latency"
        SiliconVendor.REALTEK_TV -> "vendor.realtek.low-latency.decode"
        else -> null
    }
}
