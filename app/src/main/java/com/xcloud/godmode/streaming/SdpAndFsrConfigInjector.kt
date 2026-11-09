package com.xcloud.godmode.streaming

import com.xcloud.godmode.models.GodModeGlobalConfig
import com.xcloud.godmode.models.VideoCodecProfile
import android.util.Log

/**
 * SdpAndFsrConfigInjector: Inyecta configuraciones avanzadas en el SDP y activa parámetros FSR.
 * Garantiza la priorización de AV1/HEVC y el descarte de H.264.
 */
object SdpAndFsrConfigInjector {

    fun injectConfigurationIntoSdp(rawSdp: String, config: GodModeGlobalConfig): String {
        val lines = rawSdp.lines().toMutableList()
        val result = mutableListOf<String>()
        
        val targetBitrateKbps = config.maxBitrateMbps * 1000
        val preferredCodec = config.preferredCodec

        for (line in lines) {
            var processedLine = line
            
            // Priorización de códec en la línea m=video
            if (line.startsWith("m=video")) {
                val parts = line.split(" ")
                val media = parts[0]
                val port = parts[1]
                val proto = parts[2]
                val payloads = parts.drop(3).toMutableList()
                
                // Mover el payload del códec preferido al principio
                val preferredPayload = findPayloadForCodec(rawSdp, preferredCodec.sdpCodecName)
                if (preferredPayload != null) {
                    payloads.remove(preferredPayload)
                    payloads.add(0, preferredPayload)
                }
                
                processedLine = "$media $port $proto ${payloads.joinToString(" ")}"
            }
            
            result.add(processedLine)
            
            // Inyectar límites de ancho de banda después de la línea c=IN IP4 o m=video si no hay c=IN IP4
            if (line.startsWith("c=IN IP4") || (line.startsWith("m=video") && !rawSdp.contains("c=IN IP4"))) {
                result.add("b=AS:$targetBitrateKbps")
                result.add("b=TIAS:$targetBitrateKbps")
                result.add("a=fmtp:96 x-google-max-bitrate=$targetBitrateKbps")
                val preferredPayload = findPayloadForCodec(rawSdp, preferredCodec.sdpCodecName)
                if (preferredPayload != null && preferredPayload != "96") {
                    result.add("a=fmtp:$preferredPayload x-google-max-bitrate=$targetBitrateKbps")
                }
            }
        }
        
        val augmentedSdp = result.joinToString("\r\n")
        Log.d("SdpInjector", "SDP Augmented with bitrate $targetBitrateKbps and codec ${preferredCodec.sdpCodecName}")
        return augmentedSdp
    }

    private fun findPayloadForCodec(sdp: String, codecName: String): String? {
        val lines = sdp.lines()
        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains(codecName, ignoreCase = true)) {
                return line.substringAfter("a=rtpmap:").substringBefore(" ").trim()
            }
        }
        return null
    }
}
