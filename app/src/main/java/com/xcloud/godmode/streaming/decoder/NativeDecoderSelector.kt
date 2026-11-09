package com.xcloud.godmode.streaming.decoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

enum class NextGenVideoCodec(val mimeType: String, val priorityScore: Int, val label: String) {
    AV2_EXPERIMENTAL("video/av2", 100, "AV2 (Exp)"),
    VVC_H266("video/vvc", 90, "VVC / H.266"),
    AV1("video/av01", 80, "AV1"),
    HEVC_H265("video/hevc", 70, "HEVC / H.265");
    // H.264 (video/avc) queda excluido explícitamente por obsolescencia.
}

object NativeDecoderSelector {

    /**
     * Evalúa el hardware del dispositivo y selecciona estrictamente
     * el códec de nueva generación más avanzado soportado por el silicio.
     * Jerarquía obligatoria: AV2 -> VVC -> AV1 -> HEVC
     */
    fun selectOptimalNativeDecoder(): NextGenVideoCodec {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val supportedTypes = codecList.codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .toSet()

        return when {
            supportedTypes.contains("video/av2") -> NextGenVideoCodec.AV2_EXPERIMENTAL
            supportedTypes.contains("video/vvc") && Build.VERSION.SDK_INT >= 35 -> NextGenVideoCodec.VVC_H266
            supportedTypes.contains("video/av01") -> NextGenVideoCodec.AV1
            else -> NextGenVideoCodec.HEVC_H265
        }
    }
    
    fun isCodecSupported(codec: NextGenVideoCodec): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) }
        }
    }
}
