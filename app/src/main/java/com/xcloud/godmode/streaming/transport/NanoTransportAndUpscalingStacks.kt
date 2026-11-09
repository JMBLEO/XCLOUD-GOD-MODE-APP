package com.xcloud.godmode.streaming.transport

import com.xcloud.godmode.streaming.ForbiddenTechnologyGuard
import com.xcloud.godmode.models.GodModeGlobalConfig
import com.xcloud.godmode.streaming.decoder.HardwareDecoderDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class NanoTransportProtocolId(
    val wireProtocolId: String,
    val contractVersion: Int,
    val sdkSignature: String
) {
    PROJECT_NANO_V3_RAW("V3;NanoRawTransport.dll", 3, "NanoClient-Native/3.4.0"),
    PROJECT_NANO_V4_DIRECT("V4;NanoCloudDirect.dll", 4, "NanoDirect-Native/4.1.0"),
    SMARTGLASS_GAMESTREAM("V3;SmartGlassGamestream.dll", 3, "XboxSmartGlass-Native/3.2.0"),
    AZURE_MOQ_QUIC("V4;AzureMoqQuicTransport.dll", 4, "AzureMoQ-Native/1.2.0"),
    GSSV_SRTP_TUNNEL("V3;GssvSrtpNativeTunnel.dll", 3, "GssvNative-Tunnel/3.1.0")
}

enum class NetworkTransportStack(
    val displayName: String,
    val protocolId: NanoTransportProtocolId,
    val usesQuicDatagrams: Boolean,
    val usesRecvmmsgBatching: Boolean,
    val dscpTosByte: Int
) {
    NANO_V3_RAW_UDP_RFC7983(
        displayName = "Project NANO V3 Raw UDP (Native Low Latency)",
        protocolId = NanoTransportProtocolId.PROJECT_NANO_V3_RAW,
        usesQuicDatagrams = false,
        usesRecvmmsgBatching = true,
        dscpTosByte = 0xB8 // Expedited Forwarding (DSCP 46)
    ),
    NANO_V4_CLOUD_BLADE_DIRECT(
        displayName = "Project NANO V4 Cloud Direct (Zero-Overhead NAL/OBU)",
        protocolId = NanoTransportProtocolId.PROJECT_NANO_V4_DIRECT,
        usesQuicDatagrams = false,
        usesRecvmmsgBatching = true,
        dscpTosByte = 0xB8
    ),
    AZURE_MOQ_QUIC_0RTT(
        displayName = "Azure Media-over-QUIC (0-RTT + Migración 5G/Wi-Fi)",
        protocolId = NanoTransportProtocolId.AZURE_MOQ_QUIC,
        usesQuicDatagrams = true,
        usesRecvmmsgBatching = true,
        dscpTosByte = 0xB8
    ),
    SMARTGLASS_GAMESTREAM_AES_GCM(
        displayName = "Xbox SmartGlass Gamestream Core (AES-128-GCM Hardware)",
        protocolId = NanoTransportProtocolId.SMARTGLASS_GAMESTREAM,
        usesQuicDatagrams = false,
        usesRecvmmsgBatching = false,
        dscpTosByte = 0x88 // AF41 Video
    ),
    GSSV_SRTP_VYUKOV_RING(
        displayName = "GSSV Native SRTP + Vyukov Lock-Free RingBuffer",
        protocolId = NanoTransportProtocolId.GSSV_SRTP_TUNNEL,
        usesQuicDatagrams = false,
        usesRecvmmsgBatching = true,
        dscpTosByte = 0xB8
    )
}

enum class GraphicsUpscalingEngine(val displayName: String) {
    AMD_FSR_1_EASU_RCAS("AMD FidelityFX Super Resolution 1.0 (EASU + RCAS Vulkan)"),
    AMD_FSR_3_TEMPORAL("AMD FSR 3 Temporal Compute + Motion Vectors"),
    QUALCOMM_SNAPDRAGON_GSR_V2("Qualcomm Snapdragon Game Super Resolution v2 (Adreno FP16)"),
    ARM_ASR_IMMORTALIS("ARM Accuracy Super Resolution (Mali / Immortalis)"),
    CAS_VULKAN_SHARPENING("AMD FidelityFX Contrast Adaptive Sharpening (CAS)"),
    NATIVE_HWC2_BYPASS("Nativo 1:1 Directo en HWC2 Display Overlay (Sin Escalado)")
}

data class NanoBinaryStreamDescriptor(
    val sessionId: String,
    val remoteHostIp: String,
    val remoteUdpPort: Int,
    val srtpMasterKey: ByteArray,
    val srtpMasterSalt: ByteArray,
    val negotiatedDecoder: HardwareDecoderDescriptor,
    val transportStack: NetworkTransportStack,
    val upscalingEngine: GraphicsUpscalingEngine,
    val renderInputWidth: Int,
    val renderInputHeight: Int,
    val displayOutputWidth: Int,
    val displayOutputHeight: Int,
    val targetBitrateBps: Int,
    val targetFps: Int,
    val upscalerPushConstants80Bytes: ByteArray
)

/**
 * Negociador binario del protocolo NANO (Reemplaza al 100% cualquier uso de SDP o WebRTC).
 */
object NanoProtocolConfigNegotiator {

    fun buildNanoSessionCapabilitiesPayload(
        config: GodModeGlobalConfig,
        activeDecoder: HardwareDecoderDescriptor,
        availableDecoders: List<HardwareDecoderDescriptor>
    ): JSONObject {
        val targetBitrateBps = config.maxBitrateMbps.coerceIn(5, 150) * 1_000_000

        // Lista de perfiles de hardware soportados por el dispositivo (SOLO HEVC, AV1, VVC, AV2)
        val supportedVideoProfiles = JSONArray().apply {
            val ordered = listOf(activeDecoder) + availableDecoders.filter { it != activeDecoder }
            for (dec in ordered.distinctBy { it.generation }) {
                ForbiddenTechnologyGuard.assertCleanPayloadAndMime(dec.mimeType)
                put(JSONObject().apply {
                    put("codec", dec.generation.name)
                    put("mimeType", dec.mimeType)
                    put("hardwareCodecName", dec.codecName)
                    put("vendor", dec.vendor.name)
                    put("width", config.resolution.width)
                    put("height", config.resolution.height)
                    put("framerate", 60)
                    put("maxBitrateBps", targetBitrateBps)
                    put("minBitrateBps", (targetBitrateBps * 0.6).toInt())
                    put("bitDepth", if (dec.supports10BitHdr) 10 else 8)
                    put("lowLatency", dec.supportsLowLatencyFeature)
                })
            }
        }

        return JSONObject().apply {
            put("nanoProtocolRevision", config.transportStack.protocolId.wireProtocolId)
            put("transportMode", if (config.transportStack.usesQuicDatagrams) "MOQ_QUIC_DATAGRAM" else "NANO_RAW_UDP_SRTP")
            put("videoProfiles", supportedVideoProfiles)
            put("audioProfile", JSONObject().apply {
                put("codec", "OPUS_RAW_FRAMES")
                put("sampleRate", 48000)
                put("channels", 2)
                put("lowLatencyMmap", true)
            })
            put("upscaling", JSONObject().apply {
                put("engine", config.upscalingEngine.name)
                put("sharpness", config.fsrSharpnessStops)
            })
        }
    }

    fun buildUpscalerConstants(
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        sharpnessStops: Float
    ): ByteArray {
        val buf = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        val scaleX = inputWidth.toFloat() / outputWidth.toFloat()
        val scaleY = inputHeight.toFloat() / outputHeight.toFloat()
        buf.putFloat(scaleX).putFloat(scaleY)
        buf.putFloat(0.5f * scaleX - 0.5f).putFloat(0.5f * scaleY - 0.5f)

        val invW = 1.0f / inputWidth.toFloat()
        val invH = 1.0f / inputHeight.toFloat()
        buf.putFloat(invW).putFloat(invH).putFloat(invW).putFloat(-invH)
        buf.putFloat(-invW).putFloat(2.0f * invH).putFloat(invW).putFloat(2.0f * invH)
        buf.putFloat(0.0f).putFloat(4.0f * invH).putFloat(0.0f).putFloat(0.0f)

        val attenuation = java.lang.Math.pow(2.0, -sharpnessStops.coerceIn(0.0f, 2.0f).toDouble()).toFloat()
        buf.putFloat(attenuation).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f)
        return buf.array()
    }
}
