package com.xcloud.godmode.models

import java.util.Locale

enum class PerformanceConfidence {
    VERIFIED_BY_PERFORMANCE_POINT,
    DECLARED_BY_SIZE_AND_RATE
}

sealed interface OfferedVideoFormatParseResult {
    data class Valid(val format: OfferedVideoFormat) : OfferedVideoFormatParseResult
    data class Invalid(val reason: String) : OfferedVideoFormatParseResult
}

class OfferedVideoFormat private constructor(
    val mimeType: String,
    val bitDepth: Int,
    val profile: Int,
    val level: Int,
    val width: Int,
    val height: Int,
    val frameRate: Double
) {
    companion object {
        fun create(
            mimeType: String?,
            bitDepth: Int?,
            profile: Int?,
            level: Int?,
            width: Int?,
            height: Int?,
            frameRate: Double?
        ): OfferedVideoFormatParseResult {
            val normalizedMime = mimeType?.trim()?.lowercase(Locale.ROOT)
            if (
                normalizedMime.isNullOrEmpty() ||
                bitDepth == null || (bitDepth != 8 && bitDepth != 10) ||
                profile == null || profile <= 0 ||
                level == null || level <= 0 ||
                width == null || width <= 0 ||
                height == null || height <= 0 ||
                frameRate == null ||
                !frameRate.isFinite() ||
                frameRate <= 0.0
            ) {
                return OfferedVideoFormatParseResult.Invalid(
                    reason = "INCOMPLETE_SERVER_FORMAT_OFFER"
                )
            }
            return OfferedVideoFormatParseResult.Valid(
                format = OfferedVideoFormat(
                    mimeType = normalizedMime,
                    bitDepth = bitDepth,
                    profile = profile,
                    level = level,
                    width = width,
                    height = height,
                    frameRate = frameRate
                )
            )
        }
    }
}

sealed interface VideoStreamRequirementCreateResult {
    data class Valid(val requirement: VideoStreamRequirement) : VideoStreamRequirementCreateResult
    data class Invalid(val reason: String) : VideoStreamRequirementCreateResult
}

class VideoStreamRequirement private constructor(
    val offeredFormats: List<OfferedVideoFormat>,
    val allowExperimentalVvc: Boolean
) {
    companion object {
        fun create(
            formats: List<OfferedVideoFormat>,
            allowExperimentalVvc: Boolean = false
        ): VideoStreamRequirementCreateResult {
            if (formats.isEmpty()) {
                return VideoStreamRequirementCreateResult.Invalid(
                    reason = "INCOMPLETE_SERVER_FORMAT_OFFER: La respuesta no contiene formatos de vídeo completos y válidos."
                )
            }
            return VideoStreamRequirementCreateResult.Valid(
                requirement = VideoStreamRequirement(
                    offeredFormats = formats.toList(),
                    allowExperimentalVvc = allowExperimentalVvc
                )
            )
        }
    }
}

data class HardwareDecoderCapability(
    val codecName: String,
    val canonicalName: String,
    val mimeType: String,
    val matchedProfile: Int,
    val matchedLevel: Int,
    val isVendorProvided: Boolean,
    val performanceConfidence: PerformanceConfidence
)

interface DecoderCapabilitySource {
    fun findCompatibleDecoders(
        format: OfferedVideoFormat,
        allowExperimentalVvc: Boolean
    ): List<HardwareDecoderCapability>
}

interface ExperimentalVideoFormatValidator {
    fun isValid(format: OfferedVideoFormat): Boolean
}

class RejectAllExperimentalFormats : ExperimentalVideoFormatValidator {
    override fun isValid(format: OfferedVideoFormat): Boolean = false
}
