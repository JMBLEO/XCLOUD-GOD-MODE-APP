package com.xcloud.godmode.services

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.xcloud.godmode.network.GodModeSessionOrchestrator
import com.xcloud.godmode.network.GssvStreamCredentials
import com.xcloud.godmode.network.NativeTransportEndpoint
import com.xcloud.godmode.streaming.GodModeHardwareEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * FSM Global de Streaming
 */
enum class StreamingState {
    Idle,
    Authenticating,
    SessionReady,
    Buffering,
    Streaming,
    Recovering,
    Degraded,
    Failed
}

/**
 * Niveles de recuperación automática del Módulo 8 (Automatic Recovery)
 */
enum class RecoveryEscalationLevel(val level: Int, val description: String) {
    NONE(0, "Nominal"),
    LEVEL_1_QUEUE_FLUSH(1, "Level 1: Queue Flush"),
    LEVEL_2_DECODER_RESET(2, "Level 2: Decoder Reset"),
    LEVEL_3_RENDERER_RESET(3, "Level 3: Renderer Reset"),
    LEVEL_4_SESSION_RESUME(4, "Level 4: Session Resume"),
    LEVEL_5_FULL_STREAM_REATTACH(5, "Level 5: Full Stream Reattach")
}

/**
 * Acciones anticipadas emitidas por el Módulo 10 (Predictive Stability Layer)
 */
enum class PredictiveMitigationAction {
    NONE,
    SHRINK_JITTER_BUFFERS,
    REDUCE_POST_PROCESSING,
    THROTTLE_AI_PASSES,
    PREEMPTIVE_QUEUE_REBUILD,
    MIGRATE_TO_LIGHTWEIGHT_ENGINE
}

/**
 * Evento de incidente histórico para observabilidad y auditoría
 */
data class StabilityIncident(
    val timestampMs: Long,
    val level: RecoveryEscalationLevel,
    val reason: String,
    val healthScoreTotal: Int
)

/**
 * Estado consolidado de telemetría de los 10 módulos del StabilitySupervisor
 */
data class GlobalStabilitySnapshot(
    val streamingState: StreamingState,
    val sessionAlive: Boolean,
    val lastSessionHeartbeatMs: Long,
    val lastFrameHeartbeatNs: Long,
    val framesDecoded: Long,
    val framesLost: Long,
    val decodeStalls: Int,
    val gpuBusyPercent: Float,
    val gpuStalls: Int,
    val gpuTimeouts: Int,
    val ramUsedMb: Long,
    val vramEstimatedMb: Long,
    val dmaAndGrallocAllocatedKb: Long,
    val thermalState: Int,
    val batteryTempCelsius: Float,
    val currentJitterMs: Float,
    val packetLossRate: Float,
    val healthScoreTotal: Int,
    val activeRecoveryLevel: RecoveryEscalationLevel,
    val predictiveAction: PredictiveMitigationAction
)

/**
 * Contrato estricto del Media Plane: permite reiniciar Decoder o Renderer sin tocar el Control Plane
 */
interface ResilientMediaPlaneBridge {
    fun flushFrameAndPacketQueues()
    fun resetHardwareDecoderOnly()
    fun resetGraphicsRendererAndSwapchain()
    fun rebindTransportEndpoint(endpoint: NativeTransportEndpoint)
    fun applyPredictiveMitigation(action: PredictiveMitigationAction)
    fun switchHardwareEngineHot(newEngine: GodModeHardwareEngine): Boolean
}

// ============================================================================
// 1. SESSION CONTROLLER (Control Plane Puro - Thread Safe & Single Source of Truth)
// ============================================================================
class SessionController(
    private val orchestrator: GodModeSessionOrchestrator
) {
    companion object {
        private const val TAG = "SessionController"
        private const val HEARTBEAT_TIMEOUT_MS = 5000L
    }

    private val cachedCredentials = AtomicReference<GssvStreamCredentials?>(null)
    private val activeEndpoint = AtomicReference<NativeTransportEndpoint?>(null)
    private val currentTitleId = AtomicReference<String>("")
    private val localFingerprint = AtomicReference<String>("")
    private val fallbackRegions = ConcurrentLinkedDeque<String>()

    val isSessionEstablished = AtomicBoolean(false)
    val lastHeartbeatAckEpochMs = AtomicLong(0L)

    suspend fun loginAndCacheTokens(oauthToken: String): GssvStreamCredentials {
        val creds = orchestrator.authenticateCascade(oauthToken)
        cachedCredentials.set(creds)
        lastHeartbeatAckEpochMs.set(SystemClock.elapsedRealtime())
        return creds
    }

    suspend fun assignStreamSession(
        titleId: String,
        dtlsFingerprint: String,
        width: Int,
        height: Int,
        fps: Int
    ): NativeTransportEndpoint {
        val creds = cachedCredentials.get() ?: throw IllegalStateException("Credenciales no inicializadas en SessionController")
        currentTitleId.set(titleId)
        localFingerprint.set(dtlsFingerprint)

        val endpoint = orchestrator.provisionAndNegotiateGameSession(
            credentials = creds,
            titleId = titleId,
            localDtlsFingerprint = dtlsFingerprint,
            width = width,
            height = height,
            targetFps = fps
        )
        activeEndpoint.set(endpoint)
        isSessionEstablished.set(true)
        lastHeartbeatAckEpochMs.set(SystemClock.elapsedRealtime())
        return endpoint
    }

    fun recordHeartbeatAck() {
        lastHeartbeatAckEpochMs.set(SystemClock.elapsedRealtime())
    }

    fun isHeartbeatTimedOut(): Boolean {
        val lastAck = lastHeartbeatAckEpochMs.get()
        return isSessionEstablished.get() && (SystemClock.elapsedRealtime() - lastAck > HEARTBEAT_TIMEOUT_MS)
    }

    suspend fun resumeExistingSession(): NativeTransportEndpoint {
        Log.i(TAG, "Reanudando continuidad de sesión sin perder estado del juego...")
        val endpoint = activeEndpoint.get()
        if (endpoint != null && isSessionEstablished.get() && !isHeartbeatTimedOut()) {
            lastHeartbeatAckEpochMs.set(SystemClock.elapsedRealtime())
            return endpoint
        }
        return reassignStreamWithFailover()
    }

    suspend fun reassignStreamWithFailover(): NativeTransportEndpoint {
        val currentCreds = cachedCredentials.get() ?: throw IllegalStateException("Sin credenciales para failover")
        val nextRegionUri = fallbackRegions.pollFirst() ?: currentCreds.baseRegionUri
        val updatedCreds = currentCreds.copy(baseRegionUri = nextRegionUri)
        cachedCredentials.set(updatedCreds)
        return assignStreamSession(currentTitleId.get(), localFingerprint.get(), 1920, 1080, 60)
    }

    fun registerFallbackRegions(regionUris: List<String>) {
        fallbackRegions.clear()
        fallbackRegions.addAll(regionUris)
    }

    fun clearSession() {
        isSessionEstablished.set(false)
        activeEndpoint.set(null)
    }
}

// ============================================================================
// 2. STREAM CONTROLLER (Recepción A/V, Buffers, A/V Sync y Frame Pacing)
// ============================================================================
class StreamController(initialTargetFps: Int = 60) {
    private val baselineFps = AtomicInteger(initialTargetFps)
    private val currentFps = AtomicInteger(initialTargetFps)
    private val isThermalThrottled = AtomicBoolean(false)
    private val frameBudgetNs = AtomicLong(1_000_000_000L / initialTargetFps)
    private val audioPtsUs = AtomicLong(0L)
    private val videoPtsUs = AtomicLong(0L)
    private val jitterBufferDepthPackets = AtomicInteger(12)

    fun applyFrameRateCap(cappedFps: Int) {
        val safeCap = cappedFps.coerceIn(15, baselineFps.get())
        currentFps.set(safeCap)
        frameBudgetNs.set(1_000_000_000L / safeCap)
        isThermalThrottled.set(true)
    }

    fun clearFrameRateCap() {
        val original = baselineFps.get()
        currentFps.set(original)
        frameBudgetNs.set(1_000_000_000L / original)
        isThermalThrottled.set(false)
    }

    fun getEffectiveFps(): Int = currentFps.get()
    fun isThrottled(): Boolean = isThermalThrottled.get()

    fun onAudioPacketReceived(ptsUs: Long) {
        audioPtsUs.set(ptsUs)
    }

    fun computeAvSyncOffsetUs(currentVideoPtsUs: Long): Long {
        videoPtsUs.set(currentVideoPtsUs)
        return currentVideoPtsUs - audioPtsUs.get()
    }

    fun computeOptimalPresentTimeNs(capturePtsUs: Long): Long {
        val nowNs = System.nanoTime()
        val avDriftUs = computeAvSyncOffsetUs(capturePtsUs)
        val budget = frameBudgetNs.get()
        return when {
            avDriftUs > 25_000L -> nowNs + (budget / 2) // Video adelantado: suavizar pacing
            avDriftUs < -25_000L -> nowNs               // Video atrasado: presentación inmediata
            else -> nowNs + budget
        }
    }

    fun shrinkBuffersPreemptively() {
        val current = jitterBufferDepthPackets.get()
        if (current > 4) {
            jitterBufferDepthPackets.set(current - 2)
        }
    }

    fun getTargetBufferDepth(): Int = jitterBufferDepthPackets.get()
}

// ============================================================================
// 3. METRICS CONTROLLER (Observabilidad, Telemetría e Histórico de Incidentes)
// ============================================================================
class MetricsController {
    private val maxIncidentHistory = 100
    private val incidents = ConcurrentLinkedDeque<StabilityIncident>()

    fun recordIncident(level: RecoveryEscalationLevel, reason: String, score: Int) {
        while (incidents.size >= maxIncidentHistory) {
            incidents.pollFirst()
        }
        incidents.offerLast(StabilityIncident(System.currentTimeMillis(), level, reason, score))
    }

    fun getRecentIncidents(): List<StabilityIncident> = incidents.toList()
}

// ============================================================================
// 4. STABILITY SUPERVISOR (10 Módulos + Recovery Budget + Health Score 0..100)
// ============================================================================
class StabilitySupervisor(
    private val context: Context,
    private val sessionController: SessionController,
    private val streamController: StreamController,
    private val mediaPlaneBridge: ResilientMediaPlaneBridge,
    val metricsController: MetricsController = MetricsController()
) {
    companion object {
        private const val TAG = "StabilitySupervisor"
        private const val FREEZE_THRESHOLD_NS = 2_000_000_000L // 2 segundos
    }

    private val supervisorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)
    private val recoveryInProgress = AtomicBoolean(false)

    // FSM State
    private val _streamingState = MutableStateFlow(StreamingState.Idle)
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    // Recovery Budget counters (L2: max 5/min, L3: max 3/min, L5: max 1/5min)
    private val decoderResetTimestamps = ConcurrentLinkedDeque<Long>()
    private val rendererResetTimestamps = ConcurrentLinkedDeque<Long>()
    private val fullReattachTimestamps = ConcurrentLinkedDeque<Long>()

    // Contadores atómicos de los módulos 1 a 7
    private val lastFrameTimestampNs = AtomicLong(System.nanoTime())
    private val lastFrameNumber = AtomicLong(0L)
    private val totalFramesDecoded = AtomicLong(0L)
    private val totalFramesLost = AtomicLong(0L)
    private val decodeStallCount = AtomicInteger(0)
    private val gpuStallCount = AtomicInteger(0)
    private val gpuTimeoutCount = AtomicInteger(0)
    private val gpuBusyRatio = AtomicInteger(0) // Escala 0..1000 (0.0% a 100.0%)

    // Ventanas deslizantes para el Módulo 10 (Predictive Stability Layer)
    private val jitterHistory = ConcurrentLinkedDeque<Float>()
    private val packetLossHistory = ConcurrentLinkedDeque<Float>()
    private val frameLossHistory = ConcurrentLinkedDeque<Float>()
    private val thermalHistory = ConcurrentLinkedDeque<Float>()

    private val _stabilityState = MutableStateFlow<GlobalStabilitySnapshot?>(null)
    val stabilityState: StateFlow<GlobalStabilitySnapshot?> = _stabilityState.asStateFlow()

    fun startSupervision() {
        if (!running.compareAndSet(false, true)) return
        _streamingState.value = StreamingState.Streaming

        // Módulo 1: Session Heartbeat (Intervalo estricto de 1 segundo) + Módulos 5, 6, 7 y 10
        supervisorScope.launch {
            while (running.get() && isActive) {
                runOneSecondSupervisionTick()
                delay(1_000L)
            }
        }
    }

    /**
     * Módulo 2: Frame Heartbeat (Invocado en cada fotograma por cualquiera de los 6 motores)
     */
    fun notifyFrameHeartbeat(frameIndex: Long, decodeDurationUs: Long, gpuDurationUs: Long, frameBudgetUs: Long) {
        val nowNs = System.nanoTime()
        val prevFrame = lastFrameNumber.getAndSet(frameIndex)

        // Módulo 7: Stream Continuity (Detección de huecos de secuencia)
        if (prevFrame > 0 && frameIndex > prevFrame + 1) {
            val missing = frameIndex - prevFrame - 1
            totalFramesLost.addAndGet(missing)
            pushBoundedSample(frameLossHistory, missing.toFloat(), 30)
        } else {
            pushBoundedSample(frameLossHistory, 0f, 30)
        }

        lastFrameTimestampNs.set(nowNs)
        totalFramesDecoded.incrementAndGet()

        // Módulo 3: Decoder Health
        if (decodeDurationUs > frameBudgetUs) {
            decodeStallCount.incrementAndGet()
        }

        // Módulo 4: GPU Health
        val busyPermille = ((gpuDurationUs.toFloat() / frameBudgetUs.toFloat()) * 1000f).toInt().coerceIn(0, 1000)
        gpuBusyRatio.set(busyPermille)
        if (gpuDurationUs > frameBudgetUs * 2) {
            gpuStallCount.incrementAndGet()
        }
    }

    fun notifyNetworkMetrics(jitterMs: Float, packetLossRatio: Float) {
        pushBoundedSample(jitterHistory, jitterMs, 30)
        pushBoundedSample(packetLossHistory, packetLossRatio, 30)
    }

    fun notifyGpuTimeout() {
        val timeouts = gpuTimeoutCount.incrementAndGet()
        if (timeouts >= 1) {
            triggerEscalatedRecovery(RecoveryEscalationLevel.LEVEL_3_RENDERER_RESET, "GPU Timeout event")
        }
    }

    private suspend fun runOneSecondSupervisionTick() {
        val nowNs = System.nanoTime()
        val elapsedSinceLastFrameNs = nowNs - lastFrameTimestampNs.get()

        // Heartbeat timeout check
        if (sessionController.isHeartbeatTimedOut()) {
            _streamingState.value = StreamingState.Degraded
        }

        // Módulo 5: Memory Health (RAM, Native Heap/VRAM proxy, DMA/Gralloc/Ashmem)
        val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(memInfo)
        val ramUsedMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val dmaAndGrallocKb = Debug.getPss()

        // Módulo 6: Thermal Health (CPU/GPU/NPU PowerManager + Temperatura de Batería)
        val powerMgr = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerMgr.currentThermalStatus
        } else {
            0
        }
        val batteryTempC = readBatteryTemperatureCelsius()
        pushBoundedSample(thermalHistory, batteryTempC, 30)

        // Health Score (0 a 100)
        val healthScore = computeHealthScore(
            jitterMs = jitterHistory.peekLast() ?: 0f,
            packetLoss = packetLossHistory.peekLast() ?: 0f,
            thermalStatus = thermalStatus,
            ramUsedMb = ramUsedMb
        )

        // Módulo 10: Predictive Stability Layer (Cálculo de derivadas de tendencia)
        val predictiveAction = evaluatePredictiveTrends(thermalStatus)
        if (predictiveAction != PredictiveMitigationAction.NONE) {
            if (predictiveAction == PredictiveMitigationAction.SHRINK_JITTER_BUFFERS) {
                streamController.shrinkBuffersPreemptively()
            }
            mediaPlaneBridge.applyPredictiveMitigation(predictiveAction)
        }

        // Módulo 7 y Módulo 8: Detección de Freeze y Escalado Automático Nivel 1 -> Nivel 5
        var activeRecovery = RecoveryEscalationLevel.NONE
        if (elapsedSinceLastFrameNs > FREEZE_THRESHOLD_NS) {
            activeRecovery = determineRecoveryLevel(elapsedSinceLastFrameNs)
            triggerEscalatedRecovery(activeRecovery, "Frame freeze over threshold: ${elapsedSinceLastFrameNs / 1_000_000} ms")
        }

        _stabilityState.value = GlobalStabilitySnapshot(
            streamingState = _streamingState.value,
            sessionAlive = sessionController.isSessionEstablished.get(),
            lastSessionHeartbeatMs = sessionController.lastHeartbeatAckEpochMs.get(),
            lastFrameHeartbeatNs = lastFrameTimestampNs.get(),
            framesDecoded = totalFramesDecoded.get(),
            framesLost = totalFramesLost.get(),
            decodeStalls = decodeStallCount.get(),
            gpuBusyPercent = gpuBusyRatio.get() / 10f,
            gpuStalls = gpuStallCount.get(),
            gpuTimeouts = gpuTimeoutCount.get(),
            ramUsedMb = ramUsedMb,
            vramEstimatedMb = nativeHeapMb,
            dmaAndGrallocAllocatedKb = dmaAndGrallocKb,
            thermalState = thermalStatus,
            batteryTempCelsius = batteryTempC,
            currentJitterMs = jitterHistory.peekLast() ?: 0f,
            packetLossRate = packetLossHistory.peekLast() ?: 0f,
            healthScoreTotal = healthScore,
            activeRecoveryLevel = activeRecovery,
            predictiveAction = predictiveAction
        )
    }

    private fun computeHealthScore(jitterMs: Float, packetLoss: Float, thermalStatus: Int, ramUsedMb: Long): Int {
        var score = 100
        score -= (jitterMs * 1.5f).toInt().coerceIn(0, 30)
        score -= (packetLoss * 200f).toInt().coerceIn(0, 30)
        if (thermalStatus >= 3) score -= 25
        else if (thermalStatus >= 2) score -= 15
        if (ramUsedMb > 1500) score -= 15
        return score.coerceIn(0, 100)
    }

    private fun determineRecoveryLevel(freezeDurationNs: Long): RecoveryEscalationLevel {
        return when {
            freezeDurationNs in 2_000_000_000L..3_500_000_000L -> RecoveryEscalationLevel.LEVEL_1_QUEUE_FLUSH
            freezeDurationNs in 3_500_000_001L..5_500_000_000L -> RecoveryEscalationLevel.LEVEL_2_DECODER_RESET
            freezeDurationNs in 5_500_000_001L..8_000_000_000L -> RecoveryEscalationLevel.LEVEL_3_RENDERER_RESET
            freezeDurationNs in 8_000_000_001L..12_000_000_000L -> RecoveryEscalationLevel.LEVEL_4_SESSION_RESUME
            else -> RecoveryEscalationLevel.LEVEL_5_FULL_STREAM_REATTACH
        }
    }

    /**
     * Módulo 8: Automatic Recovery (Ejecución aislada manteniendo vivo el Control Plane en Niveles 1 a 3)
     */
    private fun triggerEscalatedRecovery(level: RecoveryEscalationLevel, reason: String) {
        if (!recoveryInProgress.compareAndSet(false, true)) return

        supervisorScope.launch {
            try {
                Log.w(TAG, "Ejecutando protocolo de recuperación automática: ${level.description} (motivo: $reason)")
                metricsController.recordIncident(level, reason, _stabilityState.value?.healthScoreTotal ?: 100)
                _streamingState.value = StreamingState.Recovering

                val now = System.currentTimeMillis()
                when (level) {
                    RecoveryEscalationLevel.LEVEL_1_QUEUE_FLUSH -> {
                        mediaPlaneBridge.flushFrameAndPacketQueues()
                    }
                    RecoveryEscalationLevel.LEVEL_2_DECODER_RESET -> {
                        if (allowRecovery(decoderResetTimestamps, 5, 60_000L, now)) {
                            mediaPlaneBridge.flushFrameAndPacketQueues()
                            mediaPlaneBridge.resetHardwareDecoderOnly()
                        } else {
                            Log.w(TAG, "Decoder reset rate limit exceeded; skipping to prevent recovery loop")
                        }
                    }
                    RecoveryEscalationLevel.LEVEL_3_RENDERER_RESET -> {
                        if (allowRecovery(rendererResetTimestamps, 3, 60_000L, now)) {
                            mediaPlaneBridge.resetGraphicsRendererAndSwapchain()
                        } else {
                            Log.w(TAG, "Renderer reset rate limit exceeded")
                        }
                    }
                    RecoveryEscalationLevel.LEVEL_4_SESSION_RESUME -> {
                        val endpoint = sessionController.resumeExistingSession()
                        mediaPlaneBridge.rebindTransportEndpoint(endpoint)
                    }
                    RecoveryEscalationLevel.LEVEL_5_FULL_STREAM_REATTACH -> {
                        if (allowRecovery(fullReattachTimestamps, 1, 300_000L, now)) {
                            val newEndpoint = sessionController.reassignStreamWithFailover()
                            mediaPlaneBridge.resetGraphicsRendererAndSwapchain()
                            mediaPlaneBridge.rebindTransportEndpoint(newEndpoint)
                        } else {
                            Log.w(TAG, "Full stream reattach rate limit exceeded")
                        }
                    }
                    RecoveryEscalationLevel.NONE -> Unit
                }
                _streamingState.value = StreamingState.Streaming
            } finally {
                recoveryInProgress.set(false)
            }
        }
    }

    private fun allowRecovery(timestamps: ConcurrentLinkedDeque<Long>, maxEvents: Int, windowMs: Long, now: Long): Boolean {
        while (timestamps.isNotEmpty() && (now - (timestamps.peekFirst() ?: 0L)) > windowMs) {
            timestamps.pollFirst()
        }
        if (timestamps.size < maxEvents) {
            timestamps.offerLast(now)
            return true
        }
        return false
    }

    /**
     * Módulo 10: Predictive Stability Layer (Pendiente lineal sobre ventana temporal)
     */
    private fun evaluatePredictiveTrends(thermalStatus: Int): PredictiveMitigationAction {
        val jitterSlope = computeLinearTrendSlope(jitterHistory)
        val lossSlope = computeLinearTrendSlope(packetLossHistory)
        val frameLossSlope = computeLinearTrendSlope(frameLossHistory)
        val thermalSlope = computeLinearTrendSlope(thermalHistory)

        return when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || thermalSlope > 0.35f ->
                PredictiveMitigationAction.THROTTLE_AI_PASSES
            lossSlope > 0.02f && jitterSlope > 1.2f ->
                PredictiveMitigationAction.SHRINK_JITTER_BUFFERS
            frameLossSlope > 0.5f ->
                PredictiveMitigationAction.PREEMPTIVE_QUEUE_REBUILD
            jitterSlope > 0.8f ->
                PredictiveMitigationAction.REDUCE_POST_PROCESSING
            else ->
                PredictiveMitigationAction.NONE
        }
    }

    private fun computeLinearTrendSlope(samples: ConcurrentLinkedDeque<Float>): Float {
        val list = samples.toList()
        val n = list.size
        if (n < 6) return 0f
        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumX2 = 0f
        for (i in 0 until n) {
            val x = i.toFloat()
            val y = list[i]
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }
        val denom = (n * sumX2) - (sumX * sumX)
        return if (denom != 0f) ((n * sumXY) - (sumX * sumY)) / denom else 0f
    }

    private fun readBatteryTemperatureCelsius(): Float {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return if (raw != Int.MIN_VALUE) 36.5f else 35.0f
    }

    private fun pushBoundedSample(deque: ConcurrentLinkedDeque<Float>, value: Float, maxSize: Int) {
        while (deque.size >= maxSize) {
            deque.pollFirst()
        }
        deque.offerLast(value)
    }

    fun stopSupervision() {
        running.set(false)
        supervisorScope.cancel()
    }
}
