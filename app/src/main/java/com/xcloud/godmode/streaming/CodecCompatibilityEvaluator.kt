package com.xcloud.godmode.streaming

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.xcloud.godmode.models.*
import java.util.Locale
import kotlin.math.ceil

private const val MIME_VIDEO_VVC = "video/vvc"

sealed interface StreamCompatibilityResult {
    data class Compatible(
        val selectedFormat: OfferedVideoFormat,
        val decoderName: String,
        val canonicalDecoderName: String,
        val matchedVariant: DecoderVariant,
        val matchedProfile: Int,
        val matchedLevel: Int,
        val performanceConfidence: PerformanceConfidence
    ) : StreamCompatibilityResult

    data class Unsupported(
        val reason: String
    ) : StreamCompatibilityResult
}

enum class DecoderVariant {
    AV1,
    HEVC_MAIN_10,
    HEVC_MAIN,
    VVC_EXPERIMENTAL
}

class HardwareDecoderRegistry(
    private val experimentalValidator: ExperimentalVideoFormatValidator = RejectAllExperimentalFormats()
) : DecoderCapabilitySource {

    private val locallyAllowedMimes = setOf(
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MIME_VIDEO_VVC
    )

    override fun findCompatibleDecoders(
        format: OfferedVideoFormat,
        allowExperimentalVvc: Boolean
    ): List<HardwareDecoderCapability> {
        val normalizedMime = format.mimeType.trim().lowercase(Locale.ROOT)
        if (normalizedMime !in locallyAllowedMimes) return emptyList()
        if (normalizedMime == MediaFormat.MIMETYPE_VIDEO_AVC) return emptyList()

        if (!isOfferedFormatStructurallyValid(normalizedMime, format, allowExperimentalVvc)) {
            return emptyList()
        }

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val capabilities = mutableListOf<HardwareDecoderCapability>()

        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            if (!isHardwareDecoder(info)) continue

            val canonicalName = getCanonicalName(info)
            val isVendor = isVendorProvided(info)

            for (type in info.supportedTypes) {
                val typeNormalized = type.lowercase(Locale.ROOT)
                if (typeNormalized == normalizedMime) {
                    val caps = runCatching {
                        info.getCapabilitiesForType(type)
                    }.getOrNull() ?: continue

                    val matchedProfileLevel = findMatchingProfileLevel(caps, format) ?: continue
                    val confidence = evaluateGeometryRateAndPerformance(caps, format) ?: continue

                    capabilities.add(
                        HardwareDecoderCapability(
                            codecName = info.name,
                            canonicalName = canonicalName,
                            mimeType = typeNormalized,
                            matchedProfile = matchedProfileLevel.profile,
                            matchedLevel = matchedProfileLevel.level,
                            isVendorProvided = isVendor,
                            performanceConfidence = confidence
                        )
                    )
                }
            }
        }
        return capabilities
    }

    private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (info.isAlias) return false
            return info.isHardwareAccelerated && !info.isSoftwareOnly
        }
        return !info.name.startsWith("OMX.google.", ignoreCase = true) &&
               !info.name.startsWith("c2.android.", ignoreCase = true)
    }

    private fun getCanonicalName(info: MediaCodecInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.canonicalName
        } else {
            info.name
        }
    }

    private fun isVendorProvided(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isVendor
        } else {
            !info.name.startsWith("OMX.google.", ignoreCase = true) &&
            !info.name.startsWith("c2.android.", ignoreCase = true)
        }
    }

    private fun isOfferedFormatStructurallyValid(
        normalizedMime: String,
        format: OfferedVideoFormat,
        allowExperimentalVvc: Boolean
    ): Boolean {
        return when (normalizedMime) {
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                isProfileConsistentWithKnownFormat(
                    mimeType = normalizedMime,
                    bitDepth = format.bitDepth,
                    profile = format.profile
                )
            }
            MIME_VIDEO_VVC -> {
                allowExperimentalVvc && experimentalValidator.isValid(format)
            }
            else -> false
        }
    }

    private fun isProfileConsistentWithKnownFormat(
        mimeType: String,
        bitDepth: Int,
        profile: Int
    ): Boolean {
        return when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_AV1 -> when (bitDepth) {
                8 -> profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8
                10 -> profile in setOf(
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                )
                else -> false
            }
            MediaFormat.MIMETYPE_VIDEO_HEVC -> when (bitDepth) {
                8 -> profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                10 -> profile in setOf(
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                )
                else -> false
            }
            else -> false
        }
    }

    private fun findMatchingProfileLevel(
        caps: MediaCodecInfo.CodecCapabilities,
        format: OfferedVideoFormat
    ): MediaCodecInfo.CodecProfileLevel? {
        return caps.profileLevels
            .asSequence()
            .filter { supported -> supported.profile == format.profile }
            .filter { supported -> supported.level >= format.level }
            .minByOrNull { supported -> supported.level }
    }

    private fun evaluateGeometryRateAndPerformance(
        caps: MediaCodecInfo.CodecCapabilities,
        format: OfferedVideoFormat
    ): PerformanceConfidence? {
        val videoCaps = caps.videoCapabilities ?: return null
        
        val sizeSupported = runCatching {
            videoCaps.isSizeSupported(format.width, format.height)
        }.getOrDefault(false)
        if (!sizeSupported) return null

        val rateSupported = runCatching {
            videoCaps.areSizeAndRateSupported(format.width, format.height, format.frameRate)
        }.getOrDefault(false)
        if (!rateSupported) return null

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return PerformanceConfidence.DECLARED_BY_SIZE_AND_RATE
        }

        val perfPoints = runCatching { videoCaps.supportedPerformancePoints }.getOrNull()
        if (perfPoints == null) {
            return PerformanceConfidence.DECLARED_BY_SIZE_AND_RATE
        }
        if (perfPoints.isEmpty()) {
            return null
        }

        val requiredFps = ceil(format.frameRate).toInt()
        val requestedPoint = MediaCodecInfo.VideoCapabilities.PerformancePoint(
            format.width, format.height, requiredFps
        )
        val covered = perfPoints.any { point ->
            runCatching { point.covers(requestedPoint) }.getOrDefault(false)
        }

        return if (covered) {
            PerformanceConfidence.VERIFIED_BY_PERFORMANCE_POINT
        } else {
            null
        }
    }
}

class CodecCompatibilityEvaluator(
    private val capabilitySource: DecoderCapabilitySource
) {
    companion object {
        private val capabilityComparator = compareByDescending<HardwareDecoderCapability> {
            it.performanceConfidence == PerformanceConfidence.VERIFIED_BY_PERFORMANCE_POINT
        }.thenByDescending {
            it.isVendorProvided
        }.thenBy {
            it.matchedLevel
        }.thenBy {
            it.canonicalName.lowercase(Locale.ROOT)
        }.thenBy {
            it.codecName.lowercase(Locale.ROOT)
        }

        private fun formatPriority(format: OfferedVideoFormat): Int {
            val mime = format.mimeType.trim().lowercase(Locale.ROOT)
            return when {
                mime == MediaFormat.MIMETYPE_VIDEO_AV1 -> 0
                mime == MediaFormat.MIMETYPE_VIDEO_HEVC && format.bitDepth == 10 &&
                    format.profile in setOf(
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    ) -> 1
                mime == MediaFormat.MIMETYPE_VIDEO_HEVC && format.bitDepth == 8 &&
                    format.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> 2
                mime == MIME_VIDEO_VVC -> 3
                else -> Int.MAX_VALUE
            }
        }

        private fun capabilityMatchesOffer(
            capability: HardwareDecoderCapability,
            format: OfferedVideoFormat
        ): Boolean {
            val normalizedMime = format.mimeType.trim().lowercase(Locale.ROOT)
            return capability.mimeType == normalizedMime &&
                   capability.matchedProfile == format.profile &&
                   capability.matchedLevel >= format.level
        }
    }

    fun evaluate(
        requirement: VideoStreamRequirement,
        preference: CodecPreference = CodecPreference.AUTO_MAXIMUM_VERIFIED
    ): StreamCompatibilityResult {
        val sortedFormats = requirement.offeredFormats
            .asSequence()
            .filter { formatPriority(it) != Int.MAX_VALUE }
            .filter { format ->
                when (preference) {
                    CodecPreference.AUTO_MAXIMUM_VERIFIED -> true
                    CodecPreference.AV1_HARDWARE -> format.mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true)
                    CodecPreference.HEVC_MAIN10_HARDWARE -> format.mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) && format.bitDepth >= 10
                    CodecPreference.HEVC_MAIN_HARDWARE -> format.mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) && format.bitDepth == 8
                    CodecPreference.VVC_EXPERIMENTAL -> format.mimeType.equals(MIME_VIDEO_VVC, ignoreCase = true)
                }
            }
            .sortedWith(
                compareBy<OfferedVideoFormat> { formatPriority(it) }
                    .thenByDescending { it.bitDepth }
                    .thenBy { it.level }
                    .thenByDescending { it.width.toLong() * it.height.toLong() }
                    .thenByDescending { it.frameRate }
            )
            .toList()

        for (format in sortedFormats) {
            val match = capabilitySource
                .findCompatibleDecoders(format, requirement.allowExperimentalVvc)
                .asSequence()
                .filter { capabilityMatchesOffer(it, format) }
                .sortedWith(capabilityComparator)
                .firstOrNull()

            if (match != null) {
                val variant = when (match.mimeType) {
                    MediaFormat.MIMETYPE_VIDEO_AV1 -> DecoderVariant.AV1
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                        if (format.bitDepth >= 10) DecoderVariant.HEVC_MAIN_10 else DecoderVariant.HEVC_MAIN
                    }
                    MIME_VIDEO_VVC -> DecoderVariant.VVC_EXPERIMENTAL
                    else -> continue
                }

                return StreamCompatibilityResult.Compatible(
                    selectedFormat = format,
                    decoderName = match.codecName,
                    canonicalDecoderName = match.canonicalName,
                    matchedVariant = variant,
                    matchedProfile = match.matchedProfile,
                    matchedLevel = match.matchedLevel,
                    performanceConfidence = match.performanceConfidence
                )
            }
        }

        return StreamCompatibilityResult.Unsupported(
            reason = "CLOUD_TRANSPORT_UNAVAILABLE: El dispositivo o el servidor no acreditan decodificación por hardware compatible con la preferencia (${preference.displayName}) y formato exacto requerido."
        )
    }
}
