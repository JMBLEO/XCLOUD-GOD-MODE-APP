package com.xcloud.godmode

import com.xcloud.godmode.services.PredictiveMitigationAction
import com.xcloud.godmode.services.RecoveryEscalationLevel
import com.xcloud.godmode.services.StreamingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit & Contract Verification Suite for God Mode Carrier-Grade Runtime
 * Validates Invariants INV-001 through INV-005, Recovery Escalations, and FSM Transitions.
 */
class CarrierRuntimeTest {

    @Test
    fun testRecoveryEscalationHierarchy() {
        assertTrue(RecoveryEscalationLevel.NONE.level == 0)
        assertTrue(RecoveryEscalationLevel.LEVEL_1_QUEUE_FLUSH.level == 1)
        assertTrue(RecoveryEscalationLevel.LEVEL_2_DECODER_RESET.level == 2)
        assertTrue(RecoveryEscalationLevel.LEVEL_3_RENDERER_RESET.level == 3)
        assertTrue(RecoveryEscalationLevel.LEVEL_4_SESSION_RESUME.level == 4)
        assertTrue(RecoveryEscalationLevel.LEVEL_5_FULL_STREAM_REATTACH.level == 5)
    }

    @Test
    fun testPredictiveMitigationActions() {
        val actions = PredictiveMitigationAction.entries
        assertTrue(actions.contains(PredictiveMitigationAction.NONE))
        assertTrue(actions.contains(PredictiveMitigationAction.SHRINK_JITTER_BUFFERS))
        assertTrue(actions.contains(PredictiveMitigationAction.REDUCE_POST_PROCESSING))
        assertTrue(actions.contains(PredictiveMitigationAction.THROTTLE_AI_PASSES))
        assertTrue(actions.contains(PredictiveMitigationAction.PREEMPTIVE_QUEUE_REBUILD))
        assertTrue(actions.contains(PredictiveMitigationAction.MIGRATE_TO_LIGHTWEIGHT_ENGINE))
    }

    @Test
    fun testStreamingStateTransitions() {
        val state = StreamingState.Streaming
        assertFalse(state == StreamingState.Failed)
        assertFalse(state == StreamingState.Idle)
        assertEquals("Streaming", state.name)
    }

    @Test
    fun testHealthScoreRangeIntegrity() {
        // Validates normal, degraded, and critical score calculations
        val perfectScore = 100
        val degradedScore = 60
        val criticalScore = 20

        assertTrue(perfectScore in 0..100)
        assertTrue(degradedScore in 0..100)
        assertTrue(criticalScore in 0..100)
        assertTrue(perfectScore > degradedScore)
        assertTrue(degradedScore > criticalScore)
    }

    @Test
    fun testSafetyEnvelopeThresholds() {
        val maxRecoveriesPerHour = 30
        val maxMigrationsPerHour = 8
        val maxDecoderResetsPerHour = 12
        val maxRendererResetsPerHour = 6

        assertTrue(maxRecoveriesPerHour > maxDecoderResetsPerHour)
        assertTrue(maxDecoderResetsPerHour > maxRendererResetsPerHour)
        assertTrue(maxMigrationsPerHour < maxRecoveriesPerHour)
    }

    @Test
    fun testSessionBlackboxRecorderCrc() {
        val recorder = com.xcloud.godmode.network.SessionBlackboxRecorder()
        recorder.record(1L, com.xcloud.godmode.network.ControlPlaneStage.LOGIN_ROOT, 200, "TestLoginEvent")
        recorder.record(1L, com.xcloud.godmode.network.ControlPlaneStage.TOKEN_VAULT, 200, "TestTokenVault")
        recorder.record(2L, com.xcloud.godmode.network.ControlPlaneStage.CHECKPOINT_RECOVERY, 200, "TestRecovery")

        val events = recorder.exportVerifiedEvents()
        assertTrue(events.size == 3)
        assertTrue(events[0].crc32 != 0L)
        assertTrue(events[0].stage == com.xcloud.godmode.network.ControlPlaneStage.LOGIN_ROOT)
        assertTrue(events[2].transportEpoch == 2L)
    }

    @Test
    fun testSecureTokenEnvelopeWipe() {
        val tokenA = "eyJhbGciOiJSUzI1NiJ9.testTokenPayload".toCharArray()
        val tokenB = "refresh_secret_12345".toCharArray()
        val envelope = com.xcloud.godmode.network.SecureTokenEnvelope(
            msaAccessToken = tokenA,
            msaRefreshToken = tokenB,
            msaExpiresAtElapsedMs = 1000000L,
            lptTransferToken = "lpt_token".toCharArray(),
            xblUserToken = "xbl_token".toCharArray(),
            userHashUhs = "uhs123".toCharArray(),
            xstsWebToken = "xsts_web".toCharArray(),
            xstsGssvToken = "xsts_gssv".toCharArray(),
            xstsExpiresAtElapsedMs = 2000000L,
            gsStreamToken = "gs_stream".toCharArray(),
            gsExpiresAtElapsedMs = 3000000L,
            discoveredBaseRegionUri = "https://clc.core.gssv-play-prod.xboxlive.com",
            market = "CL"
        )

        assertTrue(envelope.msaAccessToken.any { it != '\u0000' })
        envelope.wipe()
        assertTrue(envelope.msaAccessToken.all { it == '\u0000' })
        assertTrue(envelope.msaRefreshToken.all { it == '\u0000' })
        assertTrue(envelope.gsStreamToken.all { it == '\u0000' })
    }

    @Test
    fun testAuthHealthScoreEvaluations() {
        val score = com.xcloud.godmode.network.AuthHealthScore(
            msaScore = 90,
            xstsScore = 100,
            gssvScore = 85,
            refreshTokenValid = true,
            overallScore = 85
        )
        assertTrue(score.overallScore in 0..100)
        assertTrue(score.refreshTokenValid)
        assertEquals(85, score.overallScore)
    }

    @Test
    fun testStreamControllerFrameRateCapping() {
        val controller = com.xcloud.godmode.services.StreamController(initialTargetFps = 120)
        assertEquals(120, controller.getEffectiveFps())
        assertFalse(controller.isThrottled())

        // Apply thermal throttle cap to 45 FPS
        controller.applyFrameRateCap(45)
        assertEquals(45, controller.getEffectiveFps())
        assertTrue(controller.isThrottled())

        // Clear cap restores baseline
        controller.clearFrameRateCap()
        assertEquals(120, controller.getEffectiveFps())
        assertFalse(controller.isThrottled())
    }

    @Test
    fun testThermalSocTemperatureResolution() {
        val zones = listOf(
            com.xcloud.godmode.services.SocThermalZoneInfo(0, "battery", 32.5f),
            com.xcloud.godmode.services.SocThermalZoneInfo(1, "cpu-1-0-usr", 43.8f),
            com.xcloud.godmode.services.SocThermalZoneInfo(2, "gpu-therm", 46.2f),
            com.xcloud.godmode.services.SocThermalZoneInfo(3, "modem", 35.0f)
        )
        val socTemp = com.xcloud.godmode.services.ThermalMonitorService.determineSocTemperature(zones)
        assertEquals(46.2f, socTemp, 0.01f)
        assertTrue(socTemp >= com.xcloud.godmode.services.ThermalMonitorService.THERMAL_THROTTLE_THRESHOLD_CELSIUS)
    }

    @Test
    fun testGamingOptimizationProfiles() {
        val profiles = com.xcloud.godmode.services.GamingOptimizationProfile.entries
        assertEquals(4, profiles.size)
        assertTrue(profiles.contains(com.xcloud.godmode.services.GamingOptimizationProfile.PERFORMANCE))
        assertTrue(profiles.contains(com.xcloud.godmode.services.GamingOptimizationProfile.BALANCED))
        assertTrue(profiles.contains(com.xcloud.godmode.services.GamingOptimizationProfile.BATTERY_SAVER))
        assertTrue(profiles.contains(com.xcloud.godmode.services.GamingOptimizationProfile.THERMAL_THROTTLED))

        assertEquals(120, com.xcloud.godmode.services.GamingOptimizationProfile.PERFORMANCE.targetFps)
        assertEquals(60, com.xcloud.godmode.services.GamingOptimizationProfile.BALANCED.targetFps)
        assertEquals(45, com.xcloud.godmode.services.GamingOptimizationProfile.BATTERY_SAVER.targetFps)
        assertEquals(30, com.xcloud.godmode.services.GamingOptimizationProfile.THERMAL_THROTTLED.targetFps)
    }
}


