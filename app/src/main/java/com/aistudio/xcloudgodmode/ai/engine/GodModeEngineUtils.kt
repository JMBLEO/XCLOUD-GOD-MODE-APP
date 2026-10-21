package com.aistudio.xcloudgodmode.ai.engine

import android.media.MediaFormat
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.UUID
import javax.net.ssl.SSLContext

/**
 * GodModeEngineUtils: Core logic for Ultra-Low Latency Decoding, 
 * WebRTC Security (mDNS Anonymization), and Rendering Engine Flags.
 */
object GodModeEngineUtils {

    /**
     * Applies Ultra-Low Latency flags to MediaFormat based on SoC vendor.
     */
    fun configureLowLatencyFormat(
        format: MediaFormat,
        enable: Boolean,
        vendorProfile: String = "AUTO_DETECT"
    ) {
        if (!enable) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        
        val soc = if (vendorProfile == "AUTO_DETECT") {
            when {
                hardware.contains("qcom") || board.contains("taro") || board.contains("kalama") || board.contains("pineapple") -> "QUALCOMM"
                hardware.contains("mt") || board.contains("mtk") -> "MEDIATEK"
                hardware.contains("exynos") || hardware.contains("s5e") || hardware.contains("tensor") -> "EXYNOS"
                else -> "GENERIC"
            }
        } else vendorProfile

        when (soc) {
            "QUALCOMM" -> {
                format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            }
            "MEDIATEK" -> {
                format.setInteger("vendor.mtk-ext-dec-low-latency", 1)
            }
            "EXYNOS" -> {
                format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                format.setInteger("vendor.sec-dec-output-order.enable", 1)
            }
        }
    }

    /**
     * Anonymizes local IPs in SDP using mDNS-like identifiers and filters Teredo candidates.
     */
    fun sanitizeSdp(
        rawSdp: String,
        anonymize: Boolean,
        filterTeredo: Boolean,
        isLocal: Boolean
    ): String {
        val ephemeralMdnsHost = "${UUID.randomUUID()}.local"
        val privateIpv4Regex = Regex("""\b(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[0-1])\.\d{1,3}\.\d{1,3})\b""")
        val linkLocalIpv6Regex = Regex("""\bfe80:[0-9a-fA-F:]+\b""")

        return rawSdp.lineSequence().mapNotNull { line ->
            // Filter Teredo (2001:0:...)
            if (filterTeredo && line.startsWith("a=candidate:") && line.contains(" 2001:0:")) {
                return@mapNotNull null
            }

            // Mask private IPs with mDNS (skip for local XRemote)
            if (anonymize && !isLocal && line.startsWith("a=candidate:")) {
                line.replace(privateIpv4Regex, ephemeralMdnsHost)
                    .replace(linkLocalIpv6Regex, ephemeralMdnsHost)
            } else {
                line
            }
        }.joinToString("\r\n")
    }

    /**
     * Applies GPU Rasterization, Auto Dark Mode, and TLS 1.3 flags to WebView.
     */
    fun applyWebViewFlags(
        webView: WebView,
        enableGpu: Boolean,
        enableDark: Boolean,
        enforceTls13: Boolean
    ) {
        if (enforceTls13) {
            try {
                val sslContext = SSLContext.getInstance("TLSv1.3")
                sslContext.init(null, null, null)
                SSLContext.setDefault(sslContext)
            } catch (_: Exception) {}
        }

        webView.setLayerType(
            if (enableGpu) WebView.LAYER_TYPE_HARDWARE else WebView.LAYER_TYPE_NONE,
            null
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.settings.forceDark = if (enableDark) {
                WebSettings.FORCE_DARK_ON
            } else {
                WebSettings.FORCE_DARK_OFF
            }
        }
        
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, enableDark)
        }
    }
}
