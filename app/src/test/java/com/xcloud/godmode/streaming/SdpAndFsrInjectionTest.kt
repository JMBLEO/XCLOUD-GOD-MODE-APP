package com.xcloud.godmode.streaming

import com.xcloud.godmode.models.GodModeGlobalConfig
import com.xcloud.godmode.models.VideoCodecProfile
import org.junit.Test
import org.junit.Assert.assertTrue

class SdpAndFsrInjectionTest {

    @Test
    fun sdpInjectorPrioritizesAv1AndInjectsMaxBitrate() {
        val dummySdp = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96 97 98
            a=rtpmap:96 H264/90000
            a=rtpmap:97 HEVC/90000
            a=rtpmap:98 AV1/90000
        """.trimIndent()

        val config = GodModeGlobalConfig(
            maxBitrateMbps = 150,
            preferredCodec = VideoCodecProfile.AV1
        )

        val augmentedSdp = SdpAndFsrConfigInjector.injectConfigurationIntoSdp(dummySdp, config)

        // Verificar que AV1 (98) es el primer payload en la línea m=video
        assertTrue(augmentedSdp.contains("m=video 9 UDP/TLS/RTP/SAVPF 98"))
        // Verificar inyección de bitrate
        assertTrue(augmentedSdp.contains("b=AS:150000"))
        assertTrue(augmentedSdp.contains("x-google-max-bitrate=150000"))
    }
}
