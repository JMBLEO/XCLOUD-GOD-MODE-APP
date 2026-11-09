package com.xcloud.godmode.streaming

/**
 * Guardián estricto en tiempo de ejecución: prohíbe terminantemente cualquier rastro de
 * WebRTC, WebView RTC, FFmpeg o el códec obsoleto H.264 / AVC.
 */
object ForbiddenTechnologyGuard {
    private val forbiddenTokens = listOf(
        "webrtc", "libwebrtc", "webrtctransport", "org.webrtc",
        "ffmpeg", "libavcodec", "libavformat", "libswscale",
        "video/avc", "h264", "avc1"
    )

    fun assertCleanPayloadAndMime(payloadOrMime: String) {
        val normalized = payloadOrMime.lowercase()
        for (forbidden in forbiddenTokens) {
            require(!normalized.contains(forbidden)) {
                "VIOLACIÓN DE ARQUITECTURA: Se detectó tecnología prohibida o vulnerable ('$forbidden'). " +
                    "Solo se permiten motores nativos y códecs desde HEVC en adelante."
            }
        }
    }
}
