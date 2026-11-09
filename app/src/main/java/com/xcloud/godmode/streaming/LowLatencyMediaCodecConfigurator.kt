package com.xcloud.godmode.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.xcloud.godmode.models.CodecPreference

/**
 * Hardware decoder configuration result.
 */
data class ConfiguredCodecResult(
    val codec: MediaCodec,
    val codecName: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val isLowLatencyActive: Boolean
)

/**
 * LowLatencyMediaCodecConfigurator: Tunes Android MediaCodec for ultra-low latency real-time video streaming.
 * Rejects video/avc (H.264), sets vendor low-latency flags, and binds zero-copy hardware surfaces.
 */
object LowLatencyMediaCodecConfigurator {
    private const val TAG = "LowLatencyCodec"

    // Vendor specific low-latency flag keys
    private const val KEY_QCOM_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
    private const val KEY_SAMSUNG_RTC_LOW_LATENCY = "vendor.rtc-ext-dec-low-latency.enable"
    private const val KEY_MTK_LOW_LATENCY = "vendor.mtk-ext-dec-low-latency.enable"

    /**
     * Finds and configures the lowest-latency hardware decoder for the requested codec preference.
     * Throws IllegalArgumentException if H.264 (video/avc) is attempted.
     */
    fun configureDecoder(
        preference: CodecPreference,
        targetSurface: Surface,
        width: Int = 1920,
        height: Int = 1080
    ): ConfiguredCodecResult {
        val mimeType = when (preference) {
            CodecPreference.AV1_HARDWARE -> "video/av01"
            CodecPreference.HEVC_MAIN10_HARDWARE, CodecPreference.HEVC_MAIN_HARDWARE -> "video/hevc"
            CodecPreference.VVC_EXPERIMENTAL -> "video/vvc"
            CodecPreference.AUTO_MAXIMUM_VERIFIED -> "video/hevc"
        }

        if (mimeType.contains("avc") || mimeType.contains("h264")) {
            throw IllegalArgumentException("Forbidden codec requested: H.264 / video/avc is strictly prohibited.")
        }

        val codecInfo = selectBestHardwareDecoder(mimeType)
            ?: throw IllegalStateException("No compatible hardware decoder found for $mimeType")

        val codec = MediaCodec.createByCodecName(codecInfo.name)
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            // Android 11+ (API 30+) universal low latency flag
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            // Real-time priority
            setInteger(MediaFormat.KEY_PRIORITY, 0)

            // Inject vendor-specific Silicon extensions
            setInteger(KEY_QCOM_LOW_LATENCY, 1)
            setInteger(KEY_SAMSUNG_RTC_LOW_LATENCY, 1)
            setInteger(KEY_MTK_LOW_LATENCY, 1)

            // 10-bit HDR profile configuration if applicable
            if (preference == CodecPreference.HEVC_MAIN10_HARDWARE) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            }
        }

        Log.i(TAG, "Configuring zero-copy hardware decoder ${codecInfo.name} for $mimeType ($width x $height)")
        codec.configure(format, targetSurface, null, 0)

        return ConfiguredCodecResult(
            codec = codec,
            codecName = codecInfo.name,
            mimeType = mimeType,
            isHardwareAccelerated = true,
            isLowLatencyActive = true
        )
    }

    private fun selectBestHardwareDecoder(mimeType: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val infos = codecList.codecInfos

        val candidates = infos.filter { info ->
            !info.isEncoder &&
            info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }

        // Filter out software codecs (c2.android.*, OMX.google.*)
        val hwCandidates = candidates.filter { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated && !info.isSoftwareOnly
            } else {
                val name = info.name.lowercase()
                !name.startsWith("omx.google.") && !name.contains(".sw.") && !name.startsWith("c2.android.")
            }
        }

        // Prioritize vendor low-latency silicon decoders (qcom, samsung, mediatek)
        return hwCandidates.minByOrNull { info ->
            val name = info.name.lowercase()
            when {
                name.contains("qcom") || name.contains("qti") -> 0
                name.contains("samsung") || name.contains("exynos") -> 1
                name.contains("mediatek") || name.contains("mtk") -> 2
                else -> 3
            }
        } ?: candidates.firstOrNull()
    }
}
