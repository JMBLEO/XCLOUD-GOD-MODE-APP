package com.xcloud.godmode.models

enum class EngineId {
    MEDIACODEC_DIRECT_SURFACE,
    MEDIACODEC_EGL_EXTERNAL_TEXTURE,
    MEDIACODEC_VULKAN_COMPUTE;

    companion object {
        val ENGINE_1_VULKAN_RAV1D = MEDIACODEC_DIRECT_SURFACE
        val ENGINE_2_VULKAN_ASIC = MEDIACODEC_DIRECT_SURFACE
        val ENGINE_3_MEDIACODEC_HARDWARE = MEDIACODEC_VULKAN_COMPUTE
        val ENGINE_4_HWC2_DIRECT = MEDIACODEC_DIRECT_SURFACE
        val ENGINE_5_NNAPI_ZEROCOPY = MEDIACODEC_EGL_EXTERNAL_TEXTURE
        val ENGINE_6_GLES_COMPUTE = MEDIACODEC_EGL_EXTERNAL_TEXTURE

        val MOTOR_1_RUST_VULKAN_IMMEDIATE = MEDIACODEC_DIRECT_SURFACE
        val MOTOR_2_VULKAN_VIDEO_ASIC = MEDIACODEC_DIRECT_SURFACE
        val MOTOR_3_MEDIACODEC_VULKAN_COMPUTE_AI = MEDIACODEC_VULKAN_COMPUTE
        val MOTOR_4_MEDIACODEC_HWC2_DIRECT = MEDIACODEC_DIRECT_SURFACE
        val MOTOR_5_MEDIACODEC_NPU_NNAPI = MEDIACODEC_EGL_EXTERNAL_TEXTURE
        val MOTOR_6_MEDIACODEC_EGL_OES = MEDIACODEC_EGL_EXTERNAL_TEXTURE
    }
}

enum class EngineReadiness {
    IMPLEMENTED,
    EXPERIMENTAL,
    UNAVAILABLE
}

enum class EngineCatalogVisibility {
    IMPLEMENTED_ONLY,
    INCLUDE_UNAVAILABLE
}

data class EngineMenuEntry(
    val engineId: EngineId,
    val title: String,
    val description: String,
    val readiness: EngineReadiness,
    val supportsSpatialUpscaling: Boolean,
    val supportsTemporalUpscaling: Boolean
) {
    val selectable: Boolean
        get() = readiness == EngineReadiness.IMPLEMENTED
}

sealed interface EngineMenuResult {
    data class Selected(
        val engineId: EngineId
    ) : EngineMenuResult

    data object Cancelled : EngineMenuResult

    data class Unavailable(
        val reason: String
    ) : EngineMenuResult
}
