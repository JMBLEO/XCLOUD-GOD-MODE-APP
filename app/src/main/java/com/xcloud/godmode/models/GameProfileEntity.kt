package com.xcloud.godmode.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xcloud.godmode.services.GameProfile

/**
 * GameProfileEntity: Room database entity for persistent storage of game-specific configurations.
 * Stores shader settings, resolution overrides (e.g. 1440p), bitrate targets, preferred rendering engine,
 * and AI optimization presets keyed by GameID (titleId).
 */
@Entity(tableName = "game_profiles")
data class GameProfileEntity(
    @PrimaryKey
    val gameId: String,
    val gameTitle: String = "",
    val resolutionOverride: String = "1440p",
    val targetFps: Int = 60,
    val targetBitrateKbps: Int = 35000,
    val preferredEngineId: Int = 3,
    val shaderPreset: String = "CAS Adaptive",
    val sharpeningStrength: Float = 0.65f,
    val contrastBoost: Float = 1.05f,
    val saturationBoost: Float = 1.10f,
    val bbrUltraPacing: Boolean = true,
    val enableAiUpscale: Boolean = true,
    val lowLatencyVsyncOff: Boolean = true,
    val kalmanMouseSmoothing: Boolean = false,
    val aiOptimizationPreset: String = "Ultra Low Latency + CAS",
    val audioPtsOffsetUs: Long = 0L,
    val controllerProfile: String = "Standard",
    val stickDeadzoneLeft: Float = 0.06f,
    val stickDeadzoneRight: Float = 0.06f,
    val stickResponseCurve: String = "Linear",
    val vibrationEnabled: Boolean = true,
    val vibrationIntensity: Int = 100,
    val mkbEnabled: Boolean = false,
    val mouseSensitivity: Float = 1.0f,
    val volumeBoostPercent: Int = 120,
    val isCustom: Boolean = false,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis()
) {
    fun toGameProfile(): GameProfile {
        return GameProfile(
            titleId = gameId,
            gameTitle = gameTitle,
            resolutionOverride = resolutionOverride,
            targetFps = targetFps,
            targetBitrateKbps = targetBitrateKbps,
            preferredEngineId = preferredEngineId,
            shaderPreset = shaderPreset,
            sharpeningStrength = sharpeningStrength,
            contrastBoost = contrastBoost,
            saturationBoost = saturationBoost,
            bbrUltraPacing = bbrUltraPacing,
            enableAiUpscale = enableAiUpscale,
            lowLatencyVsyncOff = lowLatencyVsyncOff,
            kalmanMouseSmoothing = kalmanMouseSmoothing,
            aiOptimizationPreset = aiOptimizationPreset,
            audioPtsOffsetUs = audioPtsOffsetUs,
            controllerProfile = controllerProfile,
            stickDeadzoneLeft = stickDeadzoneLeft,
            stickDeadzoneRight = stickDeadzoneRight,
            stickResponseCurve = stickResponseCurve,
            vibrationEnabled = vibrationEnabled,
            vibrationIntensity = vibrationIntensity,
            mkbEnabled = mkbEnabled,
            mouseSensitivity = mouseSensitivity,
            volumeBoostPercent = volumeBoostPercent,
            isCustom = isCustom
        )
    }

    companion object {
        fun fromGameProfile(profile: GameProfile): GameProfileEntity {
            return GameProfileEntity(
                gameId = profile.titleId,
                gameTitle = profile.gameTitle,
                resolutionOverride = profile.resolutionOverride,
                targetFps = profile.targetFps,
                targetBitrateKbps = profile.targetBitrateKbps,
                preferredEngineId = profile.preferredEngineId,
                shaderPreset = profile.shaderPreset,
                sharpeningStrength = profile.sharpeningStrength,
                contrastBoost = profile.contrastBoost,
                saturationBoost = profile.saturationBoost,
                bbrUltraPacing = profile.bbrUltraPacing,
                enableAiUpscale = profile.enableAiUpscale,
                lowLatencyVsyncOff = profile.lowLatencyVsyncOff,
                kalmanMouseSmoothing = profile.kalmanMouseSmoothing,
                aiOptimizationPreset = profile.aiOptimizationPreset,
                audioPtsOffsetUs = profile.audioPtsOffsetUs,
                controllerProfile = profile.controllerProfile,
                stickDeadzoneLeft = profile.stickDeadzoneLeft,
                stickDeadzoneRight = profile.stickDeadzoneRight,
                stickResponseCurve = profile.stickResponseCurve,
                vibrationEnabled = profile.vibrationEnabled,
                vibrationIntensity = profile.vibrationIntensity,
                mkbEnabled = profile.mkbEnabled,
                mouseSensitivity = profile.mouseSensitivity,
                volumeBoostPercent = profile.volumeBoostPercent,
                isCustom = profile.isCustom,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
        }
    }
}
