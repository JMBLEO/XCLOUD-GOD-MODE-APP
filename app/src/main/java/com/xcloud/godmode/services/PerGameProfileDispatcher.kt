package com.xcloud.godmode.services

import android.util.Log

/**
 * Phase 92: Per-Game Dynamic Tuning Profile Dispatcher.
 * Automatically switches controller curves, deadzones, FSR sharpness, and haptic trigger profiles
 * when the cloud session transitions to a specific Xbox Title ID.
 */
class PerGameProfileDispatcher {

    companion object {
        private const val TAG = "PerGameProfile"
    }

    data class TuningProfile(
        val titleId: String,
        val displayName: String,
        val stickCurve: String = "EXPONENTIAL",
        val stickDeadzone: Float = 0.08f,
        val fsrSharpness: Float = 0.70f,
        val adaptiveTriggerMode: String = "DUAL_STAGE_SNAP",
        val audioGainBoostDb: Float = 0f
    )

    private val profileMap = mutableMapOf<String, TuningProfile>()

    init {
        // Pre-configure popular game templates
        registerProfile(
            TuningProfile(
                titleId = "DEFAULT",
                displayName = "Perfil Estándar",
                stickCurve = "EXPONENTIAL",
                stickDeadzone = 0.08f,
                fsrSharpness = 0.70f,
                adaptiveTriggerMode = "RESISTANCE"
            )
        )
        // Shooters (Halo / Gears / Doom)
        registerProfile(
            TuningProfile(
                titleId = "9NBLGGH42THR", // Halo
                displayName = "Halo Infinite (Shooter)",
                stickCurve = "EXPONENTIAL",
                stickDeadzone = 0.05f,
                fsrSharpness = 0.80f,
                adaptiveTriggerMode = "DUAL_STAGE_SNAP"
            )
        )
        // Racing (Forza)
        registerProfile(
            TuningProfile(
                titleId = "9NRPB8S5R77L", // Forza Horizon 5
                displayName = "Forza Horizon 5 (Racing)",
                stickCurve = "LINEAR",
                stickDeadzone = 0.02f,
                fsrSharpness = 0.65f,
                adaptiveTriggerMode = "CONTINUOUS_RESISTANCE"
            )
        )
    }

    fun registerProfile(profile: TuningProfile) {
        profileMap[profile.titleId.uppercase()] = profile
    }

    fun resolveProfileForTitle(titleId: String): TuningProfile {
        val found = profileMap[titleId.uppercase()]
        if (found != null) {
            Log.i(TAG, "Applied customized tuning profile for title '$titleId': ${found.displayName}")
            return found
        }
        return profileMap["DEFAULT"]!!
    }
}
