package com.xcloud.godmode.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display

data class DeviceHardwareProfile(
    val socModel: String,
    val cpuCores: Int,
    val maxCpuFreqKhz: Long,
    val hasHardwareAv1: Boolean,
    val hasHardwareHevcMain10Hdr: Boolean,
    val hasLowLatencyVpu: Boolean,
    val hasVulkanComputeFp16: Boolean,
    val hasVulkanVideoDecodeAsic: Boolean,
    val hasDedicatedNpu: Boolean,
    val hasHwc2SurfaceTransaction: Boolean,
    val supports120HzVrr: Boolean,
    val supportsAndroid15Arr: Boolean,
    val supportsHdr10Display: Boolean,
    val hgigMaxLuminanceNits: Float,
    val hgigMinLuminanceNits: Float,
    val supportsSpatialAudio3D: Boolean,
    val enabledEngines: Set<Int>,
    val guaranteedFallbackEngineId: Int
)

class HardwareCapabilityProfiler(private val context: Context) {

    init {
        try {
            System.loadLibrary("godmode_native")
        } catch (_: Throwable) {
            // Native library load fallback
        }
    }

    fun scanDeviceAndFilterEngines(): DeviceHardwareProfile {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val maxCpuFreq = readMaxCpuFrequencyKhz()

        var hwAv1 = false
        var hwHevcHdr10 = false
        var lowLatencyVpu = false

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        for (info in codecList) {
            if (info.isEncoder) continue
            val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated && !info.isSoftwareOnly
            } else {
                !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }
            if (!isHw) continue

            for (type in info.supportedTypes) {
                val caps = info.getCapabilitiesForType(type)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    lowLatencyVpu = true
                }
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true)) {
                    hwAv1 = true
                }
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                    val hasMain10 = caps.profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    }
                    if (hasMain10) hwHevcHdr10 = true
                }
            }
        }

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val maxRefreshRate = display.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f
        val supports120Hz = maxRefreshRate >= 119.0f
        
        val hdrCaps = display.hdrCapabilities
        val supportsHdr10 = hdrCaps?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
        val maxNits = hdrCaps?.desiredMaxLuminance?.takeIf { it > 0f } ?: 400f
        val minNits = hdrCaps?.desiredMinLuminance?.takeIf { it >= 0f } ?: 0.05f

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supportsSpatializer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val spatializer = audioManager.spatializer
            spatializer.isEnabled && spatializer.isAvailable
        } else false

        val nativeFlags = try {
            nativeProbeGpuAndNpuCapabilities()
        } catch (_: Throwable) {
            0
        }
        val hasVkFp16 = (nativeFlags and 0x01) != 0
        val hasVkVideoAsic = (nativeFlags and 0x02) != 0
        val hasDedicatedNpu = (nativeFlags and 0x04) != 0
        val hasHwc2 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val enabled = mutableSetOf<Int>()
        if (cpuCores >= 8 && maxCpuFreq >= 2_400_000L && hasVkFp16) enabled.add(1)
        if (hasVkVideoAsic) enabled.add(2)
        if (lowLatencyVpu && hasVkFp16) enabled.add(3)
        if (hasHwc2) enabled.add(4)
        if (hasDedicatedNpu) enabled.add(5)
        enabled.add(6)

        val fallbackEngine = if (enabled.contains(4)) 4 else 6

        return DeviceHardwareProfile(
            socModel = socModel ?: "Unknown",
            cpuCores = cpuCores,
            maxCpuFreqKhz = maxCpuFreq,
            hasHardwareAv1 = hwAv1,
            hasHardwareHevcMain10Hdr = hwHevcHdr10,
            hasLowLatencyVpu = lowLatencyVpu,
            hasVulkanComputeFp16 = hasVkFp16,
            hasVulkanVideoDecodeAsic = hasVkVideoAsic,
            hasDedicatedNpu = hasDedicatedNpu,
            hasHwc2SurfaceTransaction = hasHwc2,
            supports120HzVrr = supports120Hz,
            supportsAndroid15Arr = false,
            supportsHdr10Display = supportsHdr10,
            hgigMaxLuminanceNits = maxNits,
            hgigMinLuminanceNits = minNits,
            supportsSpatialAudio3D = supportsSpatializer,
            enabledEngines = enabled,
            guaranteedFallbackEngineId = fallbackEngine
        )
    }

    private fun readMaxCpuFrequencyKhz(): Long {
        var maxFreq = 2_000_000L
        val cores = Runtime.getRuntime().availableProcessors()
        for (i in 0 until cores) {
            try {
                val path = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val freq = java.io.File(path).readText().trim().toLong()
                if (freq > maxFreq) maxFreq = freq
            } catch (_: Exception) {}
        }
        return maxFreq
    }

    private external fun nativeProbeGpuAndNpuCapabilities(): Int
}
