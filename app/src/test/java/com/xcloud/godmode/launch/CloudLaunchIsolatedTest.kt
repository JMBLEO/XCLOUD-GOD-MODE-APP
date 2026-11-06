package com.xcloud.godmode.launch

import com.xcloud.godmode.catalog.*
import com.xcloud.godmode.network.SecureTokenEnvelope
import com.xcloud.godmode.network.StreamSessionContext
import com.xcloud.godmode.session.*
import com.xcloud.godmode.ui.StreamErrorAction
import com.xcloud.godmode.ui.StreamPhase
import com.xcloud.godmode.ui.StreamUiController
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeSurfaceRegistration : SurfaceLifecycleRegistration {
    var unregistered = false
        private set
    override fun unregister() { unregistered = true }
}

class CloudLaunchIsolatedTest {

    @Test
    fun stopActiveStreamExecutesCompleteTeardownThroughCoordinator() = runTest {
        var engineDetached = false
        var closedSessionId: String? = null
        var closeReason: String? = null
        val fakeRegistration = FakeSurfaceRegistration()

        val attachment = object : EngineAttachment {
            override val engineId = EngineId.MOTOR_4_MEDIACODEC_HWC2_DIRECT
            override val attemptId = LaunchAttemptId(101L)
            override suspend fun awaitFirstFramePresented(timeoutMs: Long): FirstFrameResult = FirstFrameResult(true, 1L, 1000L)
            override fun stopSurfaceAccessImmediately() = Unit
            override suspend fun detach(reason: EngineDetachReason) { engineDetached = true }
        }

        val activeSession = ActiveEngineSession()
        activeSession.install(attachment)

        val activeRegistry = ActiveStreamRuntimeRegistry()
        val dummyRemoteSession = object : RemoteSessionHandle {
            override val sessionPath = "v5/sessions/cloud/abc"
            override val sessionId = "abc"
        }

        val envelope = SecureTokenEnvelope(
            msaAccessToken = charArrayOf(), msaRefreshToken = charArrayOf(), msaExpiresAtElapsedMs = 0L,
            xblUserToken = charArrayOf(), userHashUhs = charArrayOf(), xstsGssvToken = charArrayOf(),
            xstsExpiresAtElapsedMs = 0L, gsStreamToken = charArrayOf('t'), gsExpiresAtElapsedMs = 0L,
            discoveredBaseRegionUri = "https://example.invalid", market = "CL"
        )
        val credential = SessionCloseCredential.copyOf(charArrayOf('x'))

        val dummyContext = StreamSessionContext(
            sessionId = "abc", transportEpoch = 1L, ephemeralStreamTicket = "ticket",
            ice = com.xcloud.godmode.network.IceTransportParameters("127.0.0.1", 9000, "u", "p", "ru", "rp", "fp"),
            profile = com.xcloud.godmode.network.NegotiatedVideoProfile(1920, 1080, 60, false, 96, 111, 1L, 2L),
            transport = com.xcloud.godmode.network.TransportConfiguration(1L)
        )

        val initialRuntime = ActiveStreamRuntime(
            attemptId = LaunchAttemptId(101L), remoteSession = dummyRemoteSession, credential = credential,
            mediaContext = dummyContext, engineId = EngineId.MOTOR_4_MEDIACODEC_HWC2_DIRECT, surfaceLifecycle = fakeRegistration
        )
        activeRegistry.install(initialRuntime)

        val uiController = StreamUiController()
        val attemptId = LaunchAttemptId(101L)
        uiController.enterPreparingControlPlane(attemptId)
        uiController.requestMediaSurface(attemptId)
        uiController.enterStreaming(attemptId)

        val remoteSessionLifecycle = object : RemoteSessionLifecyclePort {
            override suspend fun closeSession(session: RemoteSessionHandle, credential: SessionCloseCredential, reason: String) {
                closedSessionId = session.sessionId
                closeReason = reason
            }
        }

        val coordinator = PlayNowCoordinator(
            coordinatorScope = this,
            catalogRepository = CloudCatalogRepository(object : CloudCatalogApi { override suspend fun fetchLaunchableTitles() = CloudCatalogSnapshotDto(null, OfferingId("xgpuweb"), emptyList()) }),
            preflightValidator = CloudLaunchPreflightValidator(),
            authPort = object : AuthSessionPort { override val activeOfferingId = OfferingId("xgpuweb"); override val currentAuthHealthScore = 100; override suspend fun requireHealthyEnvelope() = envelope; override suspend fun refreshCredentialsOnce() = envelope },
            sessionClient = CloudSessionClient(object : SessionHttpClient { override suspend fun postJson(url: String, bearerToken: CharArray, body: String, headers: Map<String, String>) = SessionHttpResult(200, "{}", null) }, CloudLaunchDiagnostics()),
            transportNegotiator = object : TransportNegotiationPort { override suspend fun awaitProvisioningAndNegotiate(attemptId: LaunchAttemptId, session: SessionCreationResponse, env: SecureTokenEnvelope, gateTracker: StreamStartupStateMachine) = dummyContext },
            remoteSessionLifecycle = remoteSessionLifecycle, surfaceRegistry = SurfaceHostRegistry(), surfaceValidityRegistry = SurfaceValidityRegistry(),
            activeEngineSession = activeSession, activeStreamRegistry = activeRegistry,
            engineAttachPort = object : SixEngineAttachPort { override suspend fun attachEngine(attemptId: LaunchAttemptId, engineId: EngineId, context: StreamSessionContext, surface: android.view.Surface) = attachment },
            startupGates = StreamStartupStateMachine(), recoveryPolicy = CloudLaunchRecoveryPolicy(), uiController = uiController, deviceInfoProvider = { "{}" }
        )

        coordinator.stopActiveStream(EngineDetachReason.USER_EXIT)

        assertTrue(engineDetached)
        assertEquals("abc", closedSessionId)
        assertEquals("user_exit", closeReason)
        assertTrue(fakeRegistration.unregistered)
        assertTrue(credential.isDestroyedForTest())
        assertEquals(StreamPhase.IDLE, uiController.state.value.phase)
    }

    @Test
    fun stopActiveStreamTransitionsToIdleEvenOnException() = runTest {
        val fakeRegistration = object : SurfaceLifecycleRegistration {
            override fun unregister() { throw RuntimeException("Simulated unregister crash") }
        }

        val activeRegistry = ActiveStreamRuntimeRegistry()
        val dummySession = object : RemoteSessionHandle { override val sessionPath = "abc"; override val sessionId = "abc" }
        val credential = SessionCloseCredential.copyOf(charArrayOf('x'))
        val dummyContext = StreamSessionContext("abc", 1L, "ticket", 
            com.xcloud.godmode.network.IceTransportParameters("1", 1, "u", "p", "ru", "rp", "fp"),
            com.xcloud.godmode.network.NegotiatedVideoProfile(1920, 1080, 60, false, 96, 111, 1L, 2L),
            com.xcloud.godmode.network.TransportConfiguration(1L))

        val initialRuntime = ActiveStreamRuntime(LaunchAttemptId(101L), dummySession, credential, dummyContext, EngineId.MOTOR_4_MEDIACODEC_HWC2_DIRECT, fakeRegistration)
        activeRegistry.install(initialRuntime)

        val uiController = StreamUiController()
        val attemptId = LaunchAttemptId(101L)
        uiController.enterPreparingControlPlane(attemptId)
        uiController.requestMediaSurface(attemptId)
        uiController.enterStreaming(attemptId)

        val coordinator = PlayNowCoordinator(
            coordinatorScope = this, catalogRepository = CloudCatalogRepository(object : CloudCatalogApi { override suspend fun fetchLaunchableTitles() = CloudCatalogSnapshotDto(null, OfferingId(""), emptyList()) }),
            preflightValidator = CloudLaunchPreflightValidator(), authPort = object : AuthSessionPort { override val activeOfferingId = OfferingId(""); override val currentAuthHealthScore = 100; override suspend fun requireHealthyEnvelope() = SecureTokenEnvelope(charArrayOf(), charArrayOf(), 0L, charArrayOf(), charArrayOf(), charArrayOf(), 0L, charArrayOf(), 0L, "", ""); override suspend fun refreshCredentialsOnce() = requireHealthyEnvelope() },
            sessionClient = CloudSessionClient(object : SessionHttpClient { override suspend fun postJson(url: String, bearerToken: CharArray, body: String, headers: Map<String, String>) = SessionHttpResult(200, "{}", null) }, CloudLaunchDiagnostics()),
            transportNegotiator = object : TransportNegotiationPort { override suspend fun awaitProvisioningAndNegotiate(attemptId: LaunchAttemptId, session: SessionCreationResponse, env: SecureTokenEnvelope, gateTracker: StreamStartupStateMachine) = dummyContext },
            remoteSessionLifecycle = object : RemoteSessionLifecyclePort { override suspend fun closeSession(session: RemoteSessionHandle, credential: SessionCloseCredential, reason: String) {} },
            surfaceRegistry = SurfaceHostRegistry(), surfaceValidityRegistry = SurfaceValidityRegistry(), activeEngineSession = ActiveEngineSession(), activeStreamRegistry = activeRegistry,
            engineAttachPort = object : SixEngineAttachPort { override suspend fun attachEngine(attemptId: LaunchAttemptId, engineId: EngineId, context: StreamSessionContext, surface: android.view.Surface) = object : EngineAttachment { override val engineId = engineId; override val attemptId = attemptId; override suspend fun awaitFirstFramePresented(t: Long) = FirstFrameResult(true, 1L, 1L); override fun stopSurfaceAccessImmediately() {}; override suspend fun detach(r: EngineDetachReason) {} } },
            startupGates = StreamStartupStateMachine(), recoveryPolicy = CloudLaunchRecoveryPolicy(), uiController = uiController, deviceInfoProvider = { "{}" }
        )

        coordinator.stopActiveStream(EngineDetachReason.USER_EXIT)
        assertEquals(StreamPhase.IDLE, uiController.state.value.phase)
    }

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
    fun latestLaunchWaitsForCancelledLaunchCleanupAndExecutes() = runTest {
        val events = mutableListOf<String>()
        val launchMutex = Mutex()
        val jobMutex = Mutex()
        var activeJob: Job? = null

        fun triggerLaunch(name: String) {
            launch {
                jobMutex.withLock {
                    activeJob?.cancelAndJoin()
                    activeJob = launch { launchMutex.withLock { events += "$name-start"; delay(50); events += "$name-end" } }
                }
            }
        }

        triggerLaunch("first")
        delay(10)
        triggerLaunch("second")
        delay(120)

        assertTrue(events.contains("second-start"))
        assertTrue(events.contains("second-end"))
    }
}
