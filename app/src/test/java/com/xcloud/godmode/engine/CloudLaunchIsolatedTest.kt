package com.xcloud.godmode.engine

import com.xcloud.godmode.ui.StreamErrorAction
import com.xcloud.godmode.ui.StreamErrorUi
import com.xcloud.godmode.ui.StreamPhase
import com.xcloud.godmode.ui.StreamUiController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLaunchIsolatedTest {

    @Test
    fun surfaceValidityRegistryInvalidatesAttemptSynchronously() {
        val registry = SurfaceValidityRegistry()
        val attemptId = LaunchAttemptId(77L)

        registry.markValid(attemptId)
        assertTrue(registry.isValid(attemptId))

        registry.invalidate(attemptId)
        assertFalse(registry.isValid(attemptId))
    }

    @Test
    fun activeEngineSessionLifecycleAndDetachment() = runBlocking {
        var engineDetached = false
        var stopAccessCalled = false

        val testAttachment = object : EngineAttachment {
            override val engineId = EngineId.ENGINE_4_HWC2_DIRECT
            override val attemptId = LaunchAttemptId(101L)
            override suspend fun awaitFirstFramePresented(timeoutMs: Long): FirstFrameResult =
                FirstFrameResult(presented = true, frameId = 1L, presentationTimestampNs = 1000L)
            override fun stopSurfaceAccessImmediately() { stopAccessCalled = true }
            override suspend fun detach(reason: EngineDetachReason) { engineDetached = true }
        }

        val activeSession = ActiveEngineSession()
        assertNull(activeSession.currentAttemptId())

        activeSession.install(testAttachment)
        assertEquals(LaunchAttemptId(101L), activeSession.currentAttemptId())

        activeSession.detachCurrent(EngineDetachReason.USER_EXIT)
        assertTrue(engineDetached)
        assertTrue(stopAccessCalled)
        assertNull(activeSession.currentAttemptId())
    }

    @Test
    fun streamStartupStateMachineEnforcesPrerequisites() {
        val fsm = StreamStartupStateMachine()
        val attemptId = fsm.beginAttempt()
        assertEquals(1L, attemptId.value)

        // Verifies check throws if gates are missing before attach
        assertThrows(IllegalStateException::class.java) {
            fsm.requireBeforeEngineAttach(attemptId)
        }

        // Complete all mandatory gates
        fsm.complete(attemptId, StartupGate.AUTHENTICATED)
        fsm.complete(attemptId, StartupGate.CATALOG_RESOLVED)
        fsm.complete(attemptId, StartupGate.SESSION_ACCEPTED)
        fsm.complete(attemptId, StartupGate.SESSION_PROVISIONED)
        fsm.complete(attemptId, StartupGate.SDP_ACCEPTED)
        fsm.complete(attemptId, StartupGate.ICE_CONNECTED)
        fsm.complete(attemptId, StartupGate.DTLS_CONNECTED)
        fsm.complete(attemptId, StartupGate.MEDIA_DESCRIPTOR_READY)
        fsm.complete(attemptId, StartupGate.SURFACE_VALIDATED)

        // Now should succeed without throwing
        fsm.requireBeforeEngineAttach(attemptId)
    }

    @Test
    fun nativeEngineGateEnforcesHonestAvailabilityFailure() {
        val availability = ProductionEngineAvailability()
        assertEquals(com.xcloud.godmode.models.EngineReadiness.UNAVAILABLE, availability.readiness(EngineId.ENGINE_1_VULKAN_RAV1D))
        val gate = NativeEngineGate(availability)
        val ex = assertThrows(NativeEngineUnavailableException::class.java) {
            gate.verifyAndAcquireEngine(EngineId.ENGINE_1_VULKAN_RAV1D)
        }
        assertTrue(ex.message?.contains("NATIVE_ENGINE_UNAVAILABLE") == true)
    }

    @Test
    fun streamUiControllerTransitionsCleanly() {
        val controller = StreamUiController()
        assertEquals(StreamPhase.IDLE, controller.state.value.phase)

        val attemptId = LaunchAttemptId(42L)
        controller.enterPreparingControlPlane(attemptId, "Halo Infinite", "9NBLGGH4NX7P")
        assertEquals(StreamPhase.PREPARING, controller.state.value.phase)
        assertEquals(attemptId, controller.state.value.launchAttemptId)
        assertEquals("Halo Infinite", controller.state.value.gameTitle)

        controller.requestMediaSurface(attemptId)
        assertTrue(controller.isSurfaceRequested(attemptId))

        controller.enterStreaming(attemptId)
        assertEquals(StreamPhase.STREAMING, controller.state.value.phase)

        controller.enterSuspended(attemptId)
        assertEquals(StreamPhase.SUSPENDED, controller.state.value.phase)
        assertFalse(controller.state.value.surfaceRequired)

        controller.showNonBlockingFailure(
            StreamErrorUi("Error de conexión", "Fallo al conectar con Azure", StreamErrorAction.RETRY_SERVICE)
        )
        assertEquals(StreamPhase.FAILED, controller.state.value.phase)
        assertNotNull(controller.state.value.error)

        controller.enterIdle()
        assertEquals(StreamPhase.IDLE, controller.state.value.phase)
    }
}
