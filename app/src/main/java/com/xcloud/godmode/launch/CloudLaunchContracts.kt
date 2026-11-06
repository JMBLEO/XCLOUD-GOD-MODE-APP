package com.xcloud.godmode.launch

import android.view.Surface
import com.xcloud.godmode.network.SecureTokenEnvelope
import com.xcloud.godmode.network.StreamSessionContext
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

fun interface MonotonicClock {
    fun nowElapsedMs(): Long
}

@JvmInline
value class LaunchAttemptId(val value: Long)

enum class EngineId(val index: Int) {
    MOTOR_1_RUST_VULKAN_IMMEDIATE(0),
    MOTOR_2_VULKAN_VIDEO_ASIC(1),
    MOTOR_3_MEDIACODEC_VULKAN_COMPUTE_AI(2),
    MOTOR_4_MEDIACODEC_HWC2_DIRECT(3),
    MOTOR_5_MEDIACODEC_NPU_NNAPI(4),
    MOTOR_6_MEDIACODEC_EGL_OES(5)
}

enum class StartupGate {
    AUTHENTICATED,
    CATALOG_RESOLVED,
    SESSION_ACCEPTED,
    SESSION_PROVISIONED,
    SDP_ACCEPTED,
    ICE_CONNECTED,
    DTLS_CONNECTED,
    MEDIA_DESCRIPTOR_READY,
    SURFACE_VALIDATED,
    ENGINE_ATTACHED,
    FIRST_FRAME_PRESENTED
}

class StreamStartupStateMachine {
    private var currentAttempt = LaunchAttemptId(0L)
    private val completed = EnumSet.noneOf(StartupGate::class.java)

    @Synchronized
    fun beginAttempt(): LaunchAttemptId {
        currentAttempt = LaunchAttemptId(currentAttempt.value + 1L)
        completed.clear()
        return currentAttempt
    }

    @Synchronized
    fun complete(attemptId: LaunchAttemptId, gate: StartupGate) {
        check(attemptId == currentAttempt) {
            "Resultado obsoleto del intento ${attemptId.value}; intento vigente=${currentAttempt.value}"
        }
        completed.add(gate)
    }

    @Synchronized
    fun requireBeforeEngineAttach(attemptId: LaunchAttemptId) {
        check(attemptId == currentAttempt) {
            "Intento obsoleto para attach (${attemptId.value} != ${currentAttempt.value})"
        }
        val required = EnumSet.of(
            StartupGate.AUTHENTICATED, StartupGate.CATALOG_RESOLVED, StartupGate.SESSION_ACCEPTED,
            StartupGate.SESSION_PROVISIONED, StartupGate.SDP_ACCEPTED, StartupGate.ICE_CONNECTED,
            StartupGate.DTLS_CONNECTED, StartupGate.MEDIA_DESCRIPTOR_READY, StartupGate.SURFACE_VALIDATED
        )
        val missing = EnumSet.copyOf(required).apply { removeAll(completed) }
        check(missing.isEmpty()) { "Bloqueado; faltan compuertas para attach: $missing" }
    }

    @Synchronized
    fun requireBeforeStreaming(attemptId: LaunchAttemptId) {
        check(attemptId == currentAttempt) { "Intento obsoleto (${attemptId.value})" }
        check(completed.contains(StartupGate.FIRST_FRAME_PRESENTED)) { "Requiere FIRST_FRAME_PRESENTED" }
    }

    @Synchronized
    fun isCurrent(attemptId: LaunchAttemptId): Boolean = attemptId == currentAttempt
}

class SurfaceValidityRegistry {
    private val invalidAttempts = ConcurrentHashMap.newKeySet<LaunchAttemptId>()
    fun markValid(attemptId: LaunchAttemptId) { invalidAttempts.remove(attemptId) }
    fun invalidate(attemptId: LaunchAttemptId) { invalidAttempts.add(attemptId) }
    fun isValid(attemptId: LaunchAttemptId): Boolean = attemptId !in invalidAttempts
    fun clear(attemptId: LaunchAttemptId) { invalidAttempts.remove(attemptId) }
}

enum class EngineDetachReason { CANCELLED, SESSION_FAILED, SURFACE_DESTROYED, USER_EXIT, REPLACED_BY_NEW_ATTEMPT }

data class FirstFrameResult(
    val presented: Boolean, val frameId: Long, val presentationTimestampNs: Long, val failureReason: String? = null
)

interface EngineAttachment {
    val engineId: EngineId
    val attemptId: LaunchAttemptId

    suspend fun awaitFirstFramePresented(timeoutMs: Long): FirstFrameResult
    fun stopSurfaceAccessImmediately()
    suspend fun detach(reason: EngineDetachReason)
}

interface SixEngineAttachPort {
    suspend fun attachEngine(attemptId: LaunchAttemptId, engineId: EngineId, context: StreamSessionContext, surface: Surface): EngineAttachment
}

interface SurfaceLifecycleRegistration { fun unregister() }

interface RemoteSessionHandle { val sessionPath: String; val sessionId: String }

class SessionCloseCredential private constructor(private val token: CharArray) {
    private val destroyed = AtomicBoolean(false)

    suspend fun <T> useCopy(operation: suspend (CharArray) -> T): T {
        check(!destroyed.get()) { "Credencial de cierre ya fue destruida" }
        val workingCopy = synchronized(token) {
            check(!destroyed.get()) { "Credencial de cierre ya fue destruida" }
            token.copyOf()
        }
        return try {
            operation(workingCopy)
        } finally {
            workingCopy.fill('\u0000')
        }
    }

    fun destroy() {
        if (destroyed.compareAndSet(false, true)) {
            synchronized(token) { token.fill('\u0000') }
        }
    }

    internal fun isDestroyedForTest(): Boolean = destroyed.get()

    companion object {
        fun copyOf(source: CharArray): SessionCloseCredential = SessionCloseCredential(source.copyOf())
    }
}

interface RemoteSessionLifecyclePort {
    suspend fun closeSession(session: RemoteSessionHandle, credential: SessionCloseCredential, reason: String)
}

data class ActiveStreamRuntime(
    val attemptId: LaunchAttemptId, val remoteSession: RemoteSessionHandle,
    val credential: SessionCloseCredential, val mediaContext: StreamSessionContext,
    val engineId: EngineId, val surfaceLifecycle: SurfaceLifecycleRegistration
)

sealed class CloudLaunchException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class TitleNotInCurrentCatalog(val recordId: String) : CloudLaunchException("El registro $recordId no pertenece al catálogo activo.")
    data class TitleNotCloudEnabled(val titleName: String, val reason: String) : CloudLaunchException("$titleName no es ejecutable: $reason.")
    data class NoEntitlement(val titleName: String, val xCloudTitleId: String, val serverMessage: String?, val correlationId: String?) : CloudLaunchException("La cuenta actual no tiene autorización para iniciar $titleName.")
    data class AuthenticationExpired(val correlationId: String?) : CloudLaunchException("Las credenciales de sesión han expirado.")
    data class RegionUnavailable(val correlationId: String?) : CloudLaunchException("El servicio no está disponible en la región actual.")
    data class ProtocolFailure(val statusCode: Int, val responseCode: String?, val safeMessage: String?, val correlationId: String?) : CloudLaunchException("Error HTTP $statusCode.")
    data class FirstFrameTimeout(val engineId: EngineId, val timeoutMs: Long) : CloudLaunchException("El motor ${engineId.name} no presentó fotograma tras $timeoutMs ms.")
}

enum class LaunchRecoveryAction { REFRESH_CATALOG_ONCE, REFRESH_CREDENTIALS_ONCE, SHOW_ENTITLEMENT_MESSAGE, SHOW_REGION_MESSAGE, ABORT }

class CloudLaunchRecoveryPolicy {
    fun decide(error: CloudLaunchException, catalogRefreshed: Boolean, credentialsRefreshed: Boolean): LaunchRecoveryAction = when (error) {
        is CloudLaunchException.TitleNotInCurrentCatalog -> if (!catalogRefreshed) LaunchRecoveryAction.REFRESH_CATALOG_ONCE else LaunchRecoveryAction.ABORT
        is CloudLaunchException.AuthenticationExpired -> if (!credentialsRefreshed) LaunchRecoveryAction.REFRESH_CREDENTIALS_ONCE else LaunchRecoveryAction.ABORT
        is CloudLaunchException.NoEntitlement -> LaunchRecoveryAction.SHOW_ENTITLEMENT_MESSAGE
        is CloudLaunchException.RegionUnavailable -> LaunchRecoveryAction.SHOW_REGION_MESSAGE
        is CloudLaunchException.TitleNotCloudEnabled, is CloudLaunchException.ProtocolFailure, is CloudLaunchException.FirstFrameTimeout -> LaunchRecoveryAction.ABORT
    }
}
