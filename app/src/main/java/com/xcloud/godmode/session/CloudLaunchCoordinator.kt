package com.xcloud.godmode.session

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xcloud.godmode.catalog.*
import com.xcloud.godmode.launch.*
import com.xcloud.godmode.network.SecureTokenEnvelope
import com.xcloud.godmode.network.StreamSessionContext
import com.xcloud.godmode.ui.StreamErrorAction
import com.xcloud.godmode.ui.StreamErrorUi
import com.xcloud.godmode.ui.StreamUiController
import com.xcloud.godmode.config.AppConfigurationRepository
import com.xcloud.godmode.security.UnifiedPersistentAuthVault
import com.xcloud.godmode.network.RegionalBypassHeaderInjector
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

data class SessionHttpResult(val statusCode: Int, val body: String, val correlationId: String?)
interface SessionHttpClient { 
    suspend fun postJson(url: String, bearerToken: CharArray, body: String, headers: Map<String, String>): SessionHttpResult 
    suspend fun getJson(url: String, bearerToken: CharArray, headers: Map<String, String>): SessionHttpResult
}
data class SessionCreationResponse(override val sessionPath: String, override val sessionId: String) : RemoteSessionHandle

fun JSONObject.findStringRecursively(vararg candidateKeys: String): String? {
    for (key in candidateKeys) {
        val direct = optString(key)
        if (direct.isNotBlank()) return direct
    }
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        when (val value = opt(key)) {
            is JSONObject -> value.findStringRecursively(*candidateKeys)?.let { return it }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val item = value.optJSONObject(index) ?: continue
                    item.findStringRecursively(*candidateKeys)?.let { return it }
                }
            }
        }
    }
    return null
}

class CloudSessionClient(private val http: SessionHttpClient, private val diagnostics: CloudLaunchDiagnostics) {
    suspend fun createSession(
        baseRegionUri: String, gsToken: CharArray, title: LaunchableCloudTitle,
        preflight: CloudLaunchPreflight, deviceInfoJson: String, clientSessionId: String
    ): SessionCreationResponse {
        val payload = JSONObject().apply {
            put("titleId", title.xCloudTitleId.value)
            if (title.productId != null) {
                put("productId", title.productId.value)
            }
            put("systemUpdateGroup", "")
            put("settings", JSONObject().apply {
                put("nanoVersion", "V3;NativeRawTransport")
                put("useIceConnection", true)
                put("sdkType", "native")
                put("osName", "android")
            })
        }
        val headers = mutableMapOf(
            "X-MS-Device-Info" to deviceInfoJson,
            "X-MS-Session-Id" to clientSessionId
        )
        // Solution 9: Regional Bypass
        headers.putAll(RegionalBypassHeaderInjector.getBypassHeaders(preflight.offeringId))

        val result = http.postJson("${baseRegionUri.trimEnd('/')}/v5/sessions/cloud/play", gsToken, payload.toString(), headers)
        val authStatus = parseAuthStatus(result.body)
        diagnostics.recordPreflightAndResponse(preflight, result.statusCode, result.correlationId, authStatus)
        if (result.statusCode !in 200..299) throw mapLaunchFailure(result, title, authStatus)
        val json = runCatching { JSONObject(result.body) }.getOrNull()
        val sessionPath = json?.optString("sessionPath").orEmpty()
        if (sessionPath.isBlank()) throw CloudLaunchException.ProtocolFailure(result.statusCode, "MISSING_SESSION_PATH", "Falta sessionPath", result.correlationId)
        return SessionCreationResponse(sessionPath, sessionPath.substringAfterLast('/'))
    }
    internal fun parseAuthStatus(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.findStringRecursively("authStatusCode") ?: json.findStringRecursively("errorCode") ?: json.findStringRecursively("code")
    }
    private fun mapLaunchFailure(result: SessionHttpResult, title: LaunchableCloudTitle, authStatus: String?): CloudLaunchException {
        val msg = runCatching { JSONObject(result.body) }.getOrNull()?.let { it.findStringRecursively("message") ?: it.findStringRecursively("error_description") }
        return when {
            authStatus.equals("NoEntitlement", ignoreCase = true) -> CloudLaunchException.NoEntitlement(title.displayName, title.xCloudTitleId.value, msg, result.correlationId)
            result.statusCode == 401 -> CloudLaunchException.AuthenticationExpired(result.correlationId)
            authStatus.equals("RegionRestricted", ignoreCase = true) || authStatus.equals("UnsupportedRegion", ignoreCase = true) -> CloudLaunchException.RegionUnavailable(result.correlationId)
            else -> CloudLaunchException.ProtocolFailure(result.statusCode, authStatus, msg, result.correlationId)
        }
    }
}

class SurfaceHostRegistry {
    private data class PendingSurface(val attemptId: LaunchAttemptId, val deferred: CompletableDeferred<SurfaceView>)
    private var pending: PendingSurface? = null
    @Synchronized fun prepareForAttempt(attemptId: LaunchAttemptId) {
        pending?.deferred?.cancel(CancellationException("Superficie reemplazada por intento ${attemptId.value}"))
        pending = PendingSurface(attemptId, CompletableDeferred())
    }
    @Synchronized fun onSurfaceViewCreated(attemptId: LaunchAttemptId, view: SurfaceView) {
        val current = pending ?: return
        if (current.attemptId == attemptId && !current.deferred.isCompleted) current.deferred.complete(view)
    }
    suspend fun awaitSurfaceView(attemptId: LaunchAttemptId): SurfaceView {
        val deferred = synchronized(this) {
            val current = pending ?: throw IllegalStateException("Sin solicitud activa")
            check(current.attemptId == attemptId) { "Mismatched attempt" }
            current.deferred
        }
        return deferred.await()
    }
    @Synchronized fun cancelAttempt(attemptId: LaunchAttemptId) {
        val current = pending ?: return
        if (current.attemptId == attemptId) {
            current.deferred.cancel(CancellationException("Intento cancelado"))
            pending = null
        }
    }
}

suspend fun SurfaceView.awaitValidSurface(): Surface = suspendCancellableCoroutine { continuation ->
    val current = holder.surface
    if (current != null && current.isValid) { continuation.resume(current); return@suspendCancellableCoroutine }
    val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) { val s = h.surface; if (s != null && s.isValid && continuation.isActive) { h.removeCallback(this); continuation.resume(s) } }
        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) = Unit
        override fun surfaceDestroyed(h: SurfaceHolder) = Unit
    }
    holder.addCallback(callback)
    continuation.invokeOnCancellation { holder.removeCallback(callback) }
}

class ActiveEngineSession {
    private val lock = Any()
    private var activeAttachment: EngineAttachment? = null
    suspend fun install(newAttachment: EngineAttachment) {
        val previous = synchronized(lock) { val old = activeAttachment; activeAttachment = newAttachment; old }
        previous?.stopSurfaceAccessImmediately()
        previous?.detach(EngineDetachReason.REPLACED_BY_NEW_ATTEMPT)
    }
    fun stopSurfaceAccessImmediately(attemptId: LaunchAttemptId): EngineAttachment? {
        val detached = synchronized(lock) { val current = activeAttachment; if (current?.attemptId != attemptId) return@synchronized null; activeAttachment = null; current }
        detached?.stopSurfaceAccessImmediately()
        return detached
    }
    suspend fun finishDetach(attachment: EngineAttachment?, reason: EngineDetachReason) { attachment?.detach(reason) }
    suspend fun detachCurrent(reason: EngineDetachReason) {
        val detached = synchronized(lock) { val current = activeAttachment; activeAttachment = null; current }
        detached?.stopSurfaceAccessImmediately()
        detached?.detach(reason)
    }
    fun currentAttemptId(): LaunchAttemptId? = synchronized(lock) { activeAttachment?.attemptId }
}

class SurfaceLifecycleBridge(
    private val attemptId: LaunchAttemptId,
    private val scope: CoroutineScope,
    private val activeEngineSession: ActiveEngineSession,
    private val uiController: StreamUiController,
    private val surfaceValidityRegistry: SurfaceValidityRegistry,
    private val onSurfaceLost: suspend (LaunchAttemptId, EngineAttachment?) -> Unit
) : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceValidityRegistry.invalidate(attemptId)
        val detached = activeEngineSession.stopSurfaceAccessImmediately(attemptId)
        uiController.enterSuspended(attemptId)
        scope.launch { onSurfaceLost(attemptId, detached) }
    }
}

class RegisteredSurfaceLifecycle(
    private val view: SurfaceView,
    private val bridge: SurfaceLifecycleBridge
) : SurfaceLifecycleRegistration {
    private val unregistered = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun unregister() {
        if (unregistered.compareAndSet(false, true)) {
            view.holder.removeCallback(bridge)
        }
    }
}

class ActiveStreamRuntimeRegistry {
    private val mutex = Mutex()
    private var activeRuntime: ActiveStreamRuntime? = null

    suspend fun install(newRuntime: ActiveStreamRuntime): ActiveStreamRuntime? = mutex.withLock {
        val old = activeRuntime
        activeRuntime = newRuntime
        old
    }
    suspend fun isCurrent(attemptId: LaunchAttemptId): Boolean = mutex.withLock { activeRuntime?.attemptId == attemptId }
    suspend fun takeIfCurrent(attemptId: LaunchAttemptId): ActiveStreamRuntime? = mutex.withLock {
        val current = activeRuntime
        if (current?.attemptId != attemptId) return@withLock null
        activeRuntime = null
        current
    }
    suspend fun take(): ActiveStreamRuntime? = mutex.withLock {
        val current = activeRuntime
        activeRuntime = null
        current
    }
}

interface TransportNegotiationPort {
    suspend fun awaitProvisioningAndNegotiate(attemptId: LaunchAttemptId, session: SessionCreationResponse, envelope: SecureTokenEnvelope, gateTracker: StreamStartupStateMachine): StreamSessionContext
}
interface AuthSessionPort {
    val activeOfferingId: OfferingId
    val currentAuthHealthScore: Int
    suspend fun requireHealthyEnvelope(): SecureTokenEnvelope
    suspend fun refreshCredentialsOnce(): SecureTokenEnvelope
}

suspend fun awaitFirstFrameOrThrow(attachment: EngineAttachment, engineId: EngineId, timeoutMs: Long): FirstFrameResult {
    val result = withTimeoutOrNull(timeoutMs) { attachment.awaitFirstFramePresented(timeoutMs) } ?: throw CloudLaunchException.FirstFrameTimeout(engineId, timeoutMs)
    if (!result.presented) throw CloudLaunchException.FirstFrameTimeout(engineId, timeoutMs)
    return result
}

class PlayNowCoordinator(
    private val coordinatorScope: CoroutineScope, private val catalogRepository: CloudCatalogRepository,
    private val preflightValidator: CloudLaunchPreflightValidator, private val authPort: AuthSessionPort,
    private val sessionClient: CloudSessionClient, private val transportNegotiator: TransportNegotiationPort,
    private val remoteSessionLifecycle: RemoteSessionLifecyclePort, private val surfaceRegistry: SurfaceHostRegistry,
    private val surfaceValidityRegistry: SurfaceValidityRegistry, private val activeEngineSession: ActiveEngineSession,
    private val activeStreamRegistry: ActiveStreamRuntimeRegistry, private val engineAttachPort: SixEngineAttachPort,
    private val startupGates: StreamStartupStateMachine, private val recoveryPolicy: CloudLaunchRecoveryPolicy,
    private val uiController: StreamUiController, private val deviceInfoProvider: () -> String,
    private val hardwareLocks: com.xcloud.godmode.hardware.LowLatencyHardwareLocks,
    private val httpClient: SessionHttpClient,
    private val clock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
) {
    private val launchMutex = Mutex()
    private val jobMutex = Mutex()
    private val stopMutex = Mutex()
    private var activeLaunchJob: Job? = null

    @Volatile
    private var lastRequestedRecordId: CatalogRecordId? = null

    companion object { private const val TAG = "GodModeLifecycle" }

    fun launch(recordId: CatalogRecordId, selectedEngine: EngineId) {
        lastRequestedRecordId = recordId
        coordinatorScope.launch {
            jobMutex.withLock {
                activeLaunchJob?.cancelAndJoin()
                withContext(NonCancellable) { stopActiveStream(EngineDetachReason.REPLACED_BY_NEW_ATTEMPT) }
                activeLaunchJob = coordinatorScope.launch {
                    launchMutex.withLock { executeLaunch(recordId, selectedEngine) }
                }
            }
        }
    }

    fun launchFromGlobalConfig(
        recordId: CatalogRecordId,
        configRepository: AppConfigurationRepository,
        onRequireLoginModal: () -> Unit
    ) {
        lastRequestedRecordId = recordId
        coordinatorScope.launch {
            val currentConfig = configRepository.getCurrent()
            if (authPort.currentAuthHealthScore < 100) {
                onRequireLoginModal()
            } else {
                launch(recordId = recordId, selectedEngine = currentConfig.preferredEngine)
            }
        }
    }

    fun handleErrorAction(
        action: com.xcloud.godmode.ui.StreamErrorAction,
        configRepository: AppConfigurationRepository?,
        onNavigateBackToHub: () -> Unit,
        onOpenLoginModal: () -> Unit
    ) {
        coordinatorScope.launch {
            when (action) {
                com.xcloud.godmode.ui.StreamErrorAction.RETRY_SERVICE,
                com.xcloud.godmode.ui.StreamErrorAction.REFRESH_CATALOG -> {
                    val recordToRetry = lastRequestedRecordId
                    if (authPort.currentAuthHealthScore < 100) {
                        uiController.enterIdle()
                        onNavigateBackToHub()
                        onOpenLoginModal()
                    } else if (recordToRetry != null) {
                        val config = configRepository?.getCurrent()
                        launch(recordToRetry, config?.preferredEngine ?: EngineId.MOTOR_4_MEDIACODEC_HWC2_DIRECT)
                    } else {
                        uiController.enterIdle()
                        onNavigateBackToHub()
                    }
                }
                com.xcloud.godmode.ui.StreamErrorAction.SIGN_IN_AGAIN -> {
                    uiController.enterIdle()
                    onNavigateBackToHub()
                    onOpenLoginModal()
                }
                com.xcloud.godmode.ui.StreamErrorAction.RETURN_TO_CATALOG -> {
                    uiController.enterIdle()
                    onNavigateBackToHub()
                }
            }
        }
    }

    fun cancelLaunch() {
        coordinatorScope.launch {
            jobMutex.withLock {
                activeLaunchJob?.cancelAndJoin()
                activeLaunchJob = null
            }
        }
    }

    private suspend fun detachWithDeadline(attachment: EngineAttachment?, reason: EngineDetachReason): Boolean {
        if (attachment == null) return true
        val completed = withTimeoutOrNull(3_000L) { runCatching { activeEngineSession.finishDetach(attachment, reason) }.isSuccess }
        if (completed != true) Log.e(TAG, "Engine detach timeout | attempt=${attachment?.attemptId?.value} | reason=${reason.name}")
        return completed == true
    }

    private suspend fun closeRemoteSessionWithDeadline(session: RemoteSessionHandle, credential: SessionCloseCredential, reason: String, attemptId: LaunchAttemptId): Boolean {
        val closed = withTimeoutOrNull(2_000L) { runCatching { remoteSessionLifecycle.closeSession(session, credential, reason) }.isSuccess }
        if (closed != true) Log.w(TAG, "Remote close timeout | attempt=${attemptId.value} | reason=$reason")
        return closed == true
    }

    private suspend fun closeDetachedRuntime(runtime: ActiveStreamRuntime, reason: EngineDetachReason) {
        try {
            val attachment = activeEngineSession.stopSurfaceAccessImmediately(runtime.attemptId)
            detachWithDeadline(attachment, reason)
            com.xcloud.godmode.ui.surface.HardwareSurfaceCleaner.destroyAndDetachSurfaceView(surfaceRegistry.awaitSurfaceView(runtime.attemptId))
            runCatching { runtime.surfaceLifecycle.unregister() }.onFailure { Log.w(TAG, "Surface callback unregister failed | attempt=${runtime.attemptId.value}", it) }
            surfaceRegistry.cancelAttempt(runtime.attemptId)
            surfaceValidityRegistry.clear(runtime.attemptId)
            uiController.releaseMediaSurface(runtime.attemptId)
            closeRemoteSessionWithDeadline(runtime.remoteSession, runtime.credential, reason.name.lowercase(), runtime.attemptId)
        } finally {
            runtime.credential.destroy()
        }
    }

    suspend fun stopActiveStream(reason: EngineDetachReason) {
        stopMutex.withLock {
            val runtime = activeStreamRegistry.take() ?: run { uiController.enterIdle(); return }
            withContext(NonCancellable) {
                try {
                    hardwareLocks.release()
                    runCatching { closeDetachedRuntime(runtime, reason) }
                } finally {
                    uiController.enterIdle()
                }
            }
        }
    }

    private suspend fun cleanupAbortedLaunch(
        attemptId: LaunchAttemptId, detachReason: EngineDetachReason,
        registeredLifecycle: SurfaceLifecycleRegistration?, remoteSession: SessionCreationResponse?,
        closeCredential: SessionCloseCredential?
    ) {
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(3_000L) { runCatching { activeEngineSession.detachCurrent(detachReason) } }
                runCatching { registeredLifecycle?.unregister() }
                surfaceRegistry.cancelAttempt(attemptId)
                surfaceValidityRegistry.clear(attemptId)
                uiController.releaseMediaSurface(attemptId)

                if (remoteSession != null && closeCredential != null) {
                    val reasonStr = if (detachReason == EngineDetachReason.CANCELLED) "launch_cancelled" else "launch_aborted_before_first_frame"
                    closeRemoteSessionWithDeadline(remoteSession, closeCredential, reasonStr, attemptId)
                }
            } finally {
                closeCredential?.destroy()
            }
        }
    }

    internal suspend fun executeLaunch(recordId: CatalogRecordId, selectedEngine: EngineId) {
        val attemptId = startupGates.beginAttempt()
        uiController.enterPreparingControlPlane(attemptId)

        var catalogRefreshed = false
        var credentialsRefreshed = false
        var activeCredentialForClose: SessionCloseCredential? = null
        var remoteSession: SessionCreationResponse? = null
        var registeredLifecycle: RegisteredSurfaceLifecycle? = null
        var launchCompleted = false
        var finalDetachReason = EngineDetachReason.SESSION_FAILED

        try {
            while (true) {
                coroutineContext.ensureActive()
                try {
                    val envelope = authPort.requireHealthyEnvelope()
                    coroutineContext.ensureActive()
                    
                    // Solution 12: Acquire Low Latency Locks
                    hardwareLocks.acquire()
                    
                    startupGates.complete(attemptId, StartupGate.AUTHENTICATED)

                    val title = catalogRepository.resolveForLaunch(recordId)
                    coroutineContext.ensureActive()
                    startupGates.complete(attemptId, StartupGate.CATALOG_RESOLVED)

                    val preflight = preflightValidator.validate(title, envelope, authPort.activeOfferingId, authPort.currentAuthHealthScore, clock.nowElapsedMs())
                    coroutineContext.ensureActive()

                    val sessionResponse = sessionClient.createSession(envelope.discoveredBaseRegionUri, envelope.gsStreamToken, title, preflight, deviceInfoProvider(), UUID.randomUUID().toString())
                    
                    remoteSession = sessionResponse
                    activeCredentialForClose?.destroy()
                    activeCredentialForClose = SessionCloseCredential.copyOf(envelope.gsStreamToken)
                    
                    coroutineContext.ensureActive()
                    startupGates.complete(attemptId, StartupGate.SESSION_ACCEPTED)

                    // Solution 6: Await real hardware provisioning (ReadyToConnect)
                    val provisioningPoller = com.xcloud.godmode.session.CloudProvisioningPoller(
                        object : com.xcloud.godmode.session.SessionStatePollingHttpClient {
                            override suspend fun getSessionStateJson(url: String, bearerToken: CharArray): Pair<Int, String> {
                                val res = httpClient.getJson(url, bearerToken, emptyMap())
                                return res.statusCode to res.body
                            }
                        }
                    )
                    provisioningPoller.awaitReadyToConnect(
                        attemptId = attemptId,
                        baseRegionUri = envelope.discoveredBaseRegionUri,
                        sessionPath = sessionResponse.sessionPath,
                        gsToken = envelope.gsStreamToken,
                        startupGates = startupGates,
                        onQueueProgress = { uiController.updateQueueProgress(it) }
                    )

                    val mediaContext = transportNegotiator.awaitProvisioningAndNegotiate(attemptId, sessionResponse, envelope, startupGates)
                    coroutineContext.ensureActive()

                    surfaceRegistry.prepareForAttempt(attemptId)
                    uiController.requestMediaSurface(attemptId)
                    val surfaceView = surfaceRegistry.awaitSurfaceView(attemptId)
                    coroutineContext.ensureActive()

                    val lifecycleBridge = SurfaceLifecycleBridge(
                        attemptId, coordinatorScope, activeEngineSession, uiController, surfaceValidityRegistry,
                        onSurfaceLost = { _, detachedAttachment ->
                            withContext(NonCancellable) {
                                detachWithDeadline(detachedAttachment, EngineDetachReason.SURFACE_DESTROYED)
                                stopActiveStream(EngineDetachReason.SURFACE_DESTROYED)
                            }
                        }
                    )
                    surfaceView.holder.addCallback(lifecycleBridge)
                    registeredLifecycle = RegisteredSurfaceLifecycle(surfaceView, lifecycleBridge)

                    val validSurface = surfaceView.awaitValidSurface()
                    coroutineContext.ensureActive()
                    surfaceValidityRegistry.markValid(attemptId)
                    startupGates.complete(attemptId, StartupGate.SURFACE_VALIDATED)

                    startupGates.requireBeforeEngineAttach(attemptId)
                    check(surfaceValidityRegistry.isValid(attemptId)) { "Surface invalidada antes de attachEngine" }
                    check(uiController.isSurfaceRequested(attemptId)) { "Surface retirada desde UI antes de attachEngine" }

                    val attachment = engineAttachPort.attachEngine(attemptId, selectedEngine, mediaContext, validSurface)
                    activeEngineSession.install(attachment)
                    startupGates.complete(attemptId, StartupGate.ENGINE_ATTACHED)

                    awaitFirstFrameOrThrow(attachment, selectedEngine, 5_000L)
                    startupGates.complete(attemptId, StartupGate.FIRST_FRAME_PRESENTED)
                    startupGates.requireBeforeStreaming(attemptId)
                    
                    check(surfaceValidityRegistry.isValid(attemptId)) { "Surface invalidada después de FIRST_FRAME_PRESENTED" }
                    check(uiController.isSurfaceRequested(attemptId)) { "Surface retirada desde UI tras FIRST_FRAME_PRESENTED" }

                    val lifecycleRegistration = checkNotNull(registeredLifecycle) { "Registro de ciclo de vida inexistente" }
                    val credentialToBind = checkNotNull(activeCredentialForClose) { "Credencial de cierre ausente" }

                    val newRuntime = ActiveStreamRuntime(attemptId, sessionResponse, credentialToBind, mediaContext, selectedEngine, lifecycleRegistration)
                    val previousRuntime = activeStreamRegistry.install(newRuntime)
                    previousRuntime?.let {
                        withContext(NonCancellable) { closeDetachedRuntime(it, EngineDetachReason.REPLACED_BY_NEW_ATTEMPT) }
                    }

                    // Validación post-commit estricta antes de abrir compuertas visuales
                    coroutineContext.ensureActive()
                    check(activeStreamRegistry.isCurrent(attemptId)) { "Runtime retirado durante commit" }
                    check(surfaceValidityRegistry.isValid(attemptId)) { "Surface invalidada durante commit" }
                    check(uiController.isSurfaceRequested(attemptId)) { "Surface retirada durante commit" }
                    check(activeEngineSession.currentAttemptId() == attemptId) { "Motor retirado concurrentemente" }

                    launchCompleted = true
                    activeCredentialForClose = null // Propiedad cedida y custodiada por ActiveStreamRuntime
                    uiController.enterStreaming(attemptId)
                    return

                } catch (cancelled: CancellationException) {
                    finalDetachReason = EngineDetachReason.CANCELLED
                    throw cancelled
                } catch (error: CloudLaunchException) {
                    when (recoveryPolicy.decide(error, catalogRefreshed, credentialsRefreshed)) {
                        LaunchRecoveryAction.REFRESH_CATALOG_ONCE -> { catalogRepository.refresh(); catalogRefreshed = true }
                        LaunchRecoveryAction.REFRESH_CREDENTIALS_ONCE -> { authPort.refreshCredentialsOnce(); credentialsRefreshed = true }
                        LaunchRecoveryAction.SHOW_ENTITLEMENT_MESSAGE -> {
                            uiController.showNonBlockingFailure(StreamErrorUi("Este juego no se puede iniciar", "La sesión fue rechazada (NoEntitlement). Verifica cuenta, suscripción y región.", StreamErrorAction.REFRESH_CATALOG, (error as? CloudLaunchException.NoEntitlement)?.correlationId))
                            return
                        }
                        LaunchRecoveryAction.SHOW_REGION_MESSAGE -> {
                            uiController.showNonBlockingFailure(StreamErrorUi("Región no disponible", "El servicio no se encuentra disponible en la región actual.", StreamErrorAction.RETURN_TO_CATALOG, (error as? CloudLaunchException.RegionUnavailable)?.correlationId))
                            return
                        }
                        LaunchRecoveryAction.ABORT -> {
                            uiController.showNonBlockingFailure(StreamErrorUi("No se pudo iniciar el stream", error.message ?: "Fallo irrecuperable de negociación de sesión.", StreamErrorAction.RETURN_TO_CATALOG))
                            return
                        }
                    }
                } catch (unexpected: Throwable) {
                    uiController.showNonBlockingFailure(StreamErrorUi("Fallo de preparación", unexpected.message ?: "Error interno antes de inicializar Media.", StreamErrorAction.RETRY_SERVICE))
                    return
                }
            }
        } finally {
            if (!launchCompleted) {
                cleanupAbortedLaunch(attemptId, finalDetachReason, registeredLifecycle, remoteSession, activeCredentialForClose)
            }
        }
    }
}
