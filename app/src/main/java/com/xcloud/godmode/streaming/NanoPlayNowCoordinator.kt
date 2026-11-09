package com.xcloud.godmode.streaming

import android.view.Surface
import com.xcloud.godmode.network.CatalogRecordId
import com.xcloud.godmode.network.CloudCatalogRepository
import com.xcloud.godmode.network.LaunchableCloudTitle
import com.xcloud.godmode.network.JoseCripto
import com.xcloud.godmode.engine.*
import com.xcloud.godmode.services.UnifiedPersistentAuthVault
import com.xcloud.godmode.services.AppConfigurationRepository
import com.xcloud.godmode.models.GodModeGlobalConfig
import com.xcloud.godmode.streaming.decoder.DynamicHardwareDecoderLoader
import com.xcloud.godmode.streaming.decoder.HardwareDecoderDescriptor
import com.xcloud.godmode.streaming.transport.NanoBinaryStreamDescriptor
import com.xcloud.godmode.streaming.transport.NanoProtocolConfigNegotiator
import com.xcloud.godmode.ui.StreamErrorAction
import com.xcloud.godmode.ui.StreamErrorUi
import com.xcloud.godmode.ui.StreamUiController
import com.xcloud.godmode.ui.surface.HardwareSurfaceCleaner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class SessionHttpResult(val statusCode: Int, val body: String, val correlationId: String?)
interface SessionHttpClient { 
    suspend fun postJson(url: String, bearerToken: CharArray, body: String, headers: Map<String, String>): SessionHttpResult 
    suspend fun getJson(url: String, bearerToken: CharArray, headers: Map<String, String>): SessionHttpResult
}

interface NanoNativeEngineRouter {
    suspend fun attachNanoStreamToHardwareEngine(
        attemptId: LaunchAttemptId,
        engineId: EngineId,
        descriptor: NanoBinaryStreamDescriptor,
        surface: Surface
    ): EngineAttachment
}

interface NanoBinaryTransportNegotiator {
    suspend fun establishDirectNanoTunnel(
        attemptId: LaunchAttemptId,
        baseRegionUri: String,
        sessionPath: String,
        gsToken: CharArray,
        negotiatedDecoder: HardwareDecoderDescriptor,
        startupGates: StreamStartupStateMachine
    ): Pair<String, Int>
}

class NanoPlayNowCoordinator(
    private val coordinatorScope: CoroutineScope,
    private val authVault: UnifiedPersistentAuthVault,
    private val configRepository: AppConfigurationRepository,
    private val catalogRepository: CloudCatalogRepository,
    private val decoderLoader: DynamicHardwareDecoderLoader,
    private val httpClient: SessionHttpClient,
    private val nanoNegotiator: NanoBinaryTransportNegotiator,
    private val nativeEngineRouter: NanoNativeEngineRouter,
    private val surfaceRegistry: SurfaceHostRegistry,
    private val surfaceValidityRegistry: SurfaceValidityRegistry,
    private val activeEngineSession: ActiveEngineSession,
    private val startupGates: StreamStartupStateMachine,
    private val uiController: StreamUiController
) {
    private val jobMutex = Mutex()
    private val launchMutex = Mutex()
    private var activeJob: Job? = null

    private var lastLaunchedRecordId: CatalogRecordId? = null

    fun launchWithCurrentSavedConfig(recordId: CatalogRecordId) {
        lastLaunchedRecordId = recordId
        coordinatorScope.launch {
            jobMutex.withLock {
                activeJob?.cancelAndJoin()
                activeJob = coordinatorScope.launch {
                    launchMutex.withLock {
                        executeNanoLaunch(recordId)
                    }
                }
            }
        }
    }

    fun restartActiveSession() {
        val lastId = lastLaunchedRecordId ?: return
        launchWithCurrentSavedConfig(lastId)
    }

    private suspend fun executeNanoLaunch(recordId: CatalogRecordId) {
        val attemptId = startupGates.beginAttempt()
        uiController.enterPreparingControlPlane(attemptId)

        var activeSurfaceView: android.view.SurfaceView? = null
        var launchCompleted = false

        try {
            // 1. Verificar sesión persistente en el Vault
            val envelope = authVault.requireHealthyEnvelope()
            coroutineContext.ensureActive()
            startupGates.complete(attemptId, StartupGate.AUTHENTICATED)

            // 2. Resolver título desde el catálogo autenticado
            val title = catalogRepository.resolveForLaunch(recordId)
            coroutineContext.ensureActive()
            startupGates.complete(attemptId, StartupGate.CATALOG_RESOLVED)

            // 3. Leer la configuración confirmada (Atomic DataStore)
            val config = configRepository.getCurrent()

            // 4. Descubrir los decodificadores físicos
            val allDeviceDecoders = decoderLoader.discoverAvailableHardwareDecoders()
            val selectedDecoder = decoderLoader.resolveBestDecoderForDevice(
                preferredGeneration = config.codecGeneration,
                targetWidth = config.resolution.width,
                targetHeight = config.resolution.height
            )
            ForbiddenTechnologyGuard.assertCleanPayloadAndMime(selectedDecoder.mimeType)

            // 5. Construir payload NANO
            val nanoCapabilities = NanoProtocolConfigNegotiator.buildNanoSessionCapabilitiesPayload(
                config = config,
                activeDecoder = selectedDecoder,
                availableDecoders = allDeviceDecoders
            )

            val playBody = buildSignedNanoPlayBody(title, config.transportStack.protocolId.wireProtocolId, nanoCapabilities)
            ForbiddenTechnologyGuard.assertCleanPayloadAndMime(playBody)

            val requestPath = "/v5/sessions/cloud/play"
            val targetRegionUri = if (config.forceAzureServer) {
                config.bypassRoute.baseUri
            } else {
                envelope.discoveredBaseRegionUri
            }
            val fullUrl = "${targetRegionUri.trimEnd('/')}$requestPath"

            val popSignature = try {
                JoseCripto.generateSignatureHeader("POST", fullUrl, playBody, "Bearer ${String(envelope.gsStreamToken)}")
            } catch (_: Exception) {
                ""
            }

            val headers = mutableMapOf(
                "X-Xbl-Contract-Version" to "2"
            )
            if (popSignature.isNotBlank()) {
                headers["Signature"] = popSignature
            }

            val response = httpClient.postJson(
                url = fullUrl,
                bearerToken = envelope.gsStreamToken,
                body = playBody,
                headers = headers
            )

            if (response.statusCode !in 200..299) {
                throw CloudLaunchException.ProtocolFailure(
                    statusCode = response.statusCode,
                    responseCode = "NANO_PLAY_REJECTED",
                    safeMessage = "El servidor rechazó la apertura de sesión (${response.statusCode})",
                    correlationId = response.correlationId
                )
            }
            startupGates.complete(attemptId, StartupGate.SESSION_ACCEPTED)

            val sessionPath = JSONObject(response.body).getString("sessionPath")

            // 6. Establecer el túnel directo
            val (remoteIp, remotePort) = nanoNegotiator.establishDirectNanoTunnel(
                attemptId = attemptId,
                baseRegionUri = targetRegionUri,
                sessionPath = sessionPath,
                gsToken = envelope.gsStreamToken,
                negotiatedDecoder = selectedDecoder,
                startupGates = startupGates
            )

            // 7. Crear la superficie nativa recién cuando el descriptor está listo
            surfaceRegistry.prepareForAttempt(attemptId)
            uiController.requestMediaSurface(attemptId)

            val surfaceView = surfaceRegistry.awaitSurfaceView(attemptId)
            activeSurfaceView = surfaceView
            val validSurface = surfaceView.awaitValidSurface()
            surfaceValidityRegistry.markValid(attemptId)
            startupGates.complete(attemptId, StartupGate.SURFACE_VALIDATED)

            // 8. Preparar constantes de escalado
            val upscalerConstants = NanoProtocolConfigNegotiator.buildUpscalerConstants(
                inputWidth = config.resolution.width,
                inputHeight = config.resolution.height,
                outputWidth = surfaceView.width.coerceAtLeast(config.resolution.width),
                outputHeight = surfaceView.height.coerceAtLeast(config.resolution.height),
                sharpnessStops = config.fsrSharpnessStops
            )

            val streamDescriptor = NanoBinaryStreamDescriptor(
                sessionId = sessionPath.substringAfterLast('/'),
                remoteHostIp = remoteIp,
                remoteUdpPort = remotePort,
                srtpMasterKey = ByteArray(16),
                srtpMasterSalt = ByteArray(14),
                negotiatedDecoder = selectedDecoder,
                transportStack = config.transportStack,
                upscalingEngine = config.upscalingEngine,
                renderInputWidth = config.resolution.width,
                renderInputHeight = config.resolution.height,
                displayOutputWidth = surfaceView.width.coerceAtLeast(config.resolution.width),
                displayOutputHeight = surfaceView.height.coerceAtLeast(config.resolution.height),
                targetBitrateBps = config.maxBitrateMbps * 1_000_000,
                targetFps = 60,
                upscalerPushConstants80Bytes = upscalerConstants
            )

            val attachment = nativeEngineRouter.attachNanoStreamToHardwareEngine(
                attemptId = attemptId,
                engineId = config.preferredEngine,
                descriptor = streamDescriptor,
                surface = validSurface
            )
            activeEngineSession.install(attachment)
            startupGates.complete(attemptId, StartupGate.ENGINE_ATTACHED)

            val firstFrame = awaitFirstFrameOrThrow(attachment, config.preferredEngine, 5_000L)
            check(firstFrame.presented) { "El decodificador ${selectedDecoder.codecName} no presentó el primer fotograma" }

            startupGates.complete(attemptId, StartupGate.FIRST_FRAME_PRESENTED)
            launchCompleted = true
            uiController.enterStreaming(attemptId)

        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (authError: CloudLaunchException.AuthenticationExpired) {
            uiController.showNonBlockingFailure(
                StreamErrorUi(
                    title = "Sesión requerida en el Vault",
                    message = authError.message ?: "Inicia sesión una vez para guardar tu perfil permanente.",
                    primaryAction = StreamErrorAction.SIGN_IN_AGAIN
                )
            )
        } catch (error: Throwable) {
            uiController.showNonBlockingFailure(
                StreamErrorUi(
                    title = "No se pudo iniciar el stream NANO",
                    message = error.message ?: "Error en la negociación nativa del protocolo.",
                    primaryAction = StreamErrorAction.RETURN_TO_CATALOG
                )
            )
        } finally {
            if (!launchCompleted) {
                withContext(NonCancellable) {
                    runCatching { activeEngineSession.detachCurrent(EngineDetachReason.SESSION_FAILED) }
                    HardwareSurfaceCleaner.destroyAndDetachSurfaceView(activeSurfaceView)
                    surfaceRegistry.cancelAttempt(attemptId)
                    surfaceValidityRegistry.clear(attemptId)
                    uiController.releaseMediaSurface(attemptId)
                }
            }
        }
    }

    private fun buildSignedNanoPlayBody(
        title: LaunchableCloudTitle,
        wireProtocolId: String,
        nanoCapabilities: JSONObject
    ): String {
        return JSONObject().apply {
            put("titleId", title.xCloudTitleId.value)
            if (title.productId != null && title.productId.value.isNotBlank()) {
                put("productId", title.productId.value)
            }
            put("systemUpdateGroup", "")
            put("clientSessionId", UUID.randomUUID().toString())
            put("settings", JSONObject().apply {
                put("nanoVersion", wireProtocolId)
                put("sdkType", "native")
                put("osName", "android")
                put("locale", "es-CL")
                put("timezoneOffsetMinutes", -180)
                put("nanoCapabilities", nanoCapabilities)
            })
        }.toString()
    }
    
    private suspend fun awaitFirstFrameOrThrow(attachment: EngineAttachment, engineId: EngineId, timeoutMs: Long): FirstFrameResult {
        return withTimeoutOrNull(timeoutMs) { attachment.awaitFirstFramePresented(timeoutMs) } ?: throw CloudLaunchException.FirstFrameTimeout(engineId, timeoutMs)
    }
}
