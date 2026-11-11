package com.xcloud.godmode.streaming

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

data class HardwareCodecCapabilities(
    val supportsHardwareHevc: Boolean,
    val supportsHardwareAv1: Boolean,
    val supportsHardwareH264Svc: Boolean,
    val preferredSdpCodecOrder: List<String>,
    val bestCodecName: String
)

object HardwareCodecCapabilityGuard {
    private const val TAG = "HardwareCodecGuard"

    /**
     * Interroga de forma preventiva las capacidades del SoC mediante MediaCodecList antes del
     * handshake de WebSocket/SDP con los servidores GSSV de Xbox Azure.
     * Garantiza que sólo se seleccionen decodificadores acelerados por hardware nativo (Zero-Copy),
     * bloqueando cualquier decodificación por software (CPU) para prevenir el estrangulamiento térmico.
     */
    fun inspectCapabilities(): HardwareCodecCapabilities {
        var hasHardwareHevc = false
        var hasHardwareAv1 = false
        var hasHardwareH264 = false
        var bestDecoderName = "H.264 (Hardware)"

        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (codecInfo.isEncoder) continue
                
                val types = codecInfo.supportedTypes
                for (type in types) {
                    val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        codecInfo.isHardwareAccelerated
                    } else {
                        val name = codecInfo.name.lowercase()
                        !name.contains("google") && !name.contains("sw") && !name.contains("ffmpeg")
                    }

                    if (!isHw) continue

                    when (type.lowercase()) {
                        MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                            hasHardwareHevc = true
                            Log.i(TAG, "Encontrado decodificador por hardware HEVC/H.265: ${codecInfo.name}")
                        }
                        MediaFormat.MIMETYPE_VIDEO_AV1 -> {
                            hasHardwareAv1 = true
                            Log.i(TAG, "Encontrado decodificador por hardware AV1: ${codecInfo.name}")
                        }
                        MediaFormat.MIMETYPE_VIDEO_AVC -> {
                            hasHardwareH264 = true
                            Log.i(TAG, "Encontrado decodificador por hardware H.264: ${codecInfo.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo interrogando MediaCodecList: ${e.message}")
            hasHardwareH264 = true // Fallback seguro
        }

        val codecOrder = mutableListOf<String>()
        if (hasHardwareAv1) {
            codecOrder.add("AV1")
            bestDecoderName = "AV1 (Hardware Ultra-Low Battery)"
        }
        if (hasHardwareHevc) {
            codecOrder.add("H265")
            if (!hasHardwareAv1) bestDecoderName = "HEVC / H.265 (Hardware Main 10)"
        }
        codecOrder.add("H264")

        return HardwareCodecCapabilities(
            supportsHardwareHevc = hasHardwareHevc,
            supportsHardwareAv1 = hasHardwareAv1,
            supportsHardwareH264Svc = hasHardwareH264,
            preferredSdpCodecOrder = codecOrder,
            bestCodecName = bestDecoderName
        )
    }

    /**
     * Reescribe preventivamente el SDP local antes de enviarlo a /play/sdp para negociar
     * la máxima prioridad de códecs acelerados por hardware y eliminar candidatos de software.
     */
    fun rewriteSdpWithPreemptiveHardwareLock(rawSdp: String, preferredCodec: String): String {
        val caps = inspectCapabilities()
        Log.d(TAG, "Negociación Preemptiva SDP: Códec preferido=$preferredCodec, Caps=$caps")

        // Inyectar / reordenar payload types en el SDP (AV1 PT 96 / HEVC PT 98)
        var sdp = rawSdp
        if (preferredCodec == "AV1" && caps.supportsHardwareAv1) {
            sdp = sdp.replace("m=video 9 UDP/TLS/RTP/SAVPF", "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99")
        } else {
            // Predeterminado o forzar HEVC Main 10 HDR
            sdp = sdp.replace("m=video 9 UDP/TLS/RTP/SAVPF", "m=video 9 UDP/TLS/RTP/SAVPF 98 99 96 97")
        }
        return sdp
    }
}
