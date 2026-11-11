package com.xcloud.godmode.network

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

sealed interface StreamLaunchProgress {
    data object Idle : StreamLaunchProgress
    data object ValidatingCredentials : StreamLaunchProgress
    data object CreatingSession : StreamLaunchProgress
    data class WaitingInQueue(
        val sessionId: String,
        val estimatedWaitSeconds: Int,
        val suggestAlternativeNode: Boolean
    ) : StreamLaunchProgress
    data class ProvisioningBlade(val sessionId: String) : StreamLaunchProgress
    data class NegotiatingTransport(val sessionId: String, val step: String) : StreamLaunchProgress
    data class HandshakingChannels(val sessionId: String) : StreamLaunchProgress
    data class StreamingActive(val sessionId: String, val baseUri: String, val gsToken: String) : StreamLaunchProgress
    data class LaunchFailed(val title: String, val message: String, val requiresReLogin: Boolean) : StreamLaunchProgress
}

class CorrelationVectorGenerator {
    private val baseVector: String = run {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING).take(22)
    }
    private val counter = AtomicInteger(0)

    fun nextValue(): String = "$baseVector.${counter.getAndIncrement()}"
}

class StreamSessionLauncher(
    private val authEngine: UnifiedXboxAuthEngine
) {
    private val launchMutex = Mutex()
    private var activeLaunchJob: Job? = null
    private var keepAliveJob: Job? = null

    @Volatile
    private var currentSessionPath: String? = null
    @Volatile
    private var currentBaseUri: String? = null
    @Volatile
    private var currentGsToken: String? = null

    private val _progress = MutableStateFlow<StreamLaunchProgress>(StreamLaunchProgress.Idle)
    val progress: StateFlow<StreamLaunchProgress> = _progress.asStateFlow()

    /**
     * Punto de entrada único al pulsar "JUGAR AHORA".
     * Protegido contra doble clic y con soporte completo de los 12 subprocesos.
     */
    fun launchGameSession(
        scope: CoroutineScope,
        titleId: String,
        isFreeToPlay: Boolean = false,
        overrideDatacenterBaseUri: String? = null,
        spoofedForwardedForIp: String? = null,
        useTizenHighBitrate: Boolean = true,
        onCreateSdpOffer: suspend () -> String,
        onApplySdpAnswer: suspend (String) -> Unit,
        onGatherLocalIceCandidates: suspend () -> String,
        onApplyRemoteIceCandidates: suspend (String) -> Unit
    ) {
        if (!launchMutex.tryLock()) return

        activeLaunchJob?.cancel()
        activeLaunchJob = scope.launch {
            val cv = CorrelationVectorGenerator()
            try {
                _progress.value = StreamLaunchProgress.ValidatingCredentials

                // SUBPROCESO 1 y 2: Validar/Refrescar tokens y elegir oferta (Game Pass vs F2P)
                val authResult = authEngine.ensureReadyForStreamLaunch(spoofedForwardedForIp)
                val authorized = when (authResult) {
                    is StreamReadyAuthResult.Authorized -> authResult
                    is StreamReadyAuthResult.InteractiveLoginRequired -> {
                        _progress.value = StreamLaunchProgress.LaunchFailed(
                            title = "Inicio de Sesión Requerido",
                            message = authResult.message,
                            requiresReLogin = true
                        )
                        return@launch
                    }
                    is StreamReadyAuthResult.NetworkError -> {
                        _progress.value = StreamLaunchProgress.LaunchFailed(
                            title = "Error de Red",
                            message = authResult.message,
                            requiresReLogin = false
                        )
                        return@launch
                    }
                }

                val session = authorized.session
                val activeGsToken = if (isFreeToPlay && !session.f2pGsToken.isNullOrBlank()) {
                    session.f2pGsToken
                } else {
                    session.xgpuGsToken
                }
                val targetBaseUri = (overrideDatacenterBaseUri ?: session.preferredBaseUri).trimEnd('/')

                // Fase 1 Pre-Stream: Actualizar snapshot atómico de doble buffer
                AtomicAuthSnapshotManager.updateSnapshot(
                    gamertag = session.gamertag,
                    xuid = session.xuid,
                    uhs = session.uhs,
                    msaToken = session.msaAccessToken,
                    msaRefreshToken = session.msaRefreshToken,
                    xstsCoreToken = session.webXstsToken,
                    xstsStreamingToken = session.gssvXstsToken,
                    gsToken = activeGsToken,
                    preferredBaseUri = targetBaseUri,
                    expiresAtEpochMs = session.gsTokenExpiresAtEpochMs
                )

                // Limpiar sesión previa colgada si existía
                terminateActiveSessionSilently()

                _progress.value = StreamLaunchProgress.CreatingSession

                // SUBPROCESO 3: Crear sesión en /v5/sessions/cloud/play (con fallback automático si Tizen falla)
                val sessionPath = try {
                    requestCloudPlaySession(
                        baseUri = targetBaseUri,
                        gsToken = activeGsToken,
                        titleId = titleId,
                        useTizenProfile = useTizenHighBitrate,
                        spoofedIp = spoofedForwardedForIp,
                        cv = cv
                    )
                } catch (e: Exception) {
                    if (useTizenHighBitrate) {
                        requestCloudPlaySession(
                            baseUri = targetBaseUri,
                            gsToken = activeGsToken,
                            titleId = titleId,
                            useTizenProfile = false,
                            spoofedIp = spoofedForwardedForIp,
                            cv = cv
                        )
                    } else {
                        throw e
                    }
                }

                val sessionId = sessionPath.substringAfterLast("/")
                currentSessionPath = sessionPath
                currentBaseUri = targetBaseUri
                currentGsToken = activeGsToken

                // SUBPROCESO 4 y 5: Polling de /state + Inyección de LPT en /connect
                var lptSent = false
                var queueElapsedSeconds = 0

                while (isActive) {
                    val stateJson = getJson(
                        url = "$targetBaseUri/$sessionPath/state",
                        gsToken = activeGsToken,
                        cv = cv.nextValue()
                    )
                    val state = stateJson.optString("state")

                    when (state) {
                        "WaitingForResources" -> {
                            val waitSec = stateJson.optInt("estimatedWaitTimeInSeconds", 0)
                            _progress.value = StreamLaunchProgress.WaitingInQueue(
                                sessionId = sessionId,
                                estimatedWaitSeconds = waitSec,
                                suggestAlternativeNode = waitSec > 120
                            )
                            // Si la cola supera 4 minutos, refrescamos el LPT preventivamente
                            queueElapsedSeconds += 2
                            if (queueElapsedSeconds >= 240) {
                                queueElapsedSeconds = 0
                                val refreshedAuth = authEngine.ensureReadyForStreamLaunch(spoofedForwardedForIp)
                                if (refreshedAuth is StreamReadyAuthResult.Authorized) {
                                    sendConnectTransferToken(
                                        baseUri = targetBaseUri,
                                        sessionPath = sessionPath,
                                        gsToken = activeGsToken,
                                        lptToken = refreshedAuth.transferTokenLpt,
                                        cv = cv.nextValue()
                                    )
                                    lptSent = true
                                }
                            }
                            delay(2_000L)
                        }

                        "Provisioning" -> {
                            _progress.value = StreamLaunchProgress.ProvisioningBlade(sessionId)
                            if (!lptSent) {
                                sendConnectTransferToken(
                                    baseUri = targetBaseUri,
                                    sessionPath = sessionPath,
                                    gsToken = activeGsToken,
                                    lptToken = authorized.transferTokenLpt,
                                    cv = cv.nextValue()
                                )
                                lptSent = true
                            }
                            delay(1_000L)
                        }

                        "Provisioned" -> {
                            if (!lptSent) {
                                sendConnectTransferToken(
                                    baseUri = targetBaseUri,
                                    sessionPath = sessionPath,
                                    gsToken = activeGsToken,
                                    lptToken = authorized.transferTokenLpt,
                                    cv = cv.nextValue()
                                )
                                lptSent = true
                            }
                            break
                        }

                        "Failed" -> {
                            val errObj = stateJson.optJSONObject("errorDetails")
                            val code = errObj?.optString("code").orEmpty()
                            val msg = errObj?.optString("message").orEmpty()
                            throw IllegalStateException("El servidor rechazó la sesión ($code): $msg")
                        }

                        else -> delay(1_000L)
                    }
                }

                // SUBPROCESO 6: Intercambio SDP (Audio Opus + Video HEVC + 4 Canales de Datos)
                _progress.value = StreamLaunchProgress.NegotiatingTransport(sessionId, "Negociando SDP...")
                val localSdpOffer = onCreateSdpOffer()
                sendSdpOffer(
                    baseUri = targetBaseUri,
                    sessionPath = sessionPath,
                    gsToken = activeGsToken,
                    sdpOffer = localSdpOffer,
                    cv = cv.nextValue()
                )

                val sdpAnswerJson = pollExchangeResponse(
                    url = "$targetBaseUri/$sessionPath/sdp",
                    gsToken = activeGsToken,
                    cv = cv
                )
                onApplySdpAnswer(sdpAnswerJson)

                // SUBPROCESO 7: Intercambio de Candidatos ICE / Teredo
                _progress.value = StreamLaunchProgress.NegotiatingTransport(sessionId, "Estableciendo enlace UDP...")
                val localCandidatesJson = onGatherLocalIceCandidates()
                postJson(
                    url = "$targetBaseUri/$sessionPath/ice",
                    gsToken = activeGsToken,
                    body = JSONObject().put("messageType", "iceCandidate").put("candidate", localCandidatesJson),
                    cv = cv.nextValue()
                )

                val remoteIceJson = pollExchangeResponse(
                    url = "$targetBaseUri/$sessionPath/ice",
                    gsToken = activeGsToken,
                    cv = cv
                )
                onApplyRemoteIceCandidates(remoteIceJson)

                // SUBPROCESO 8, 9, 10 y 11: Handshake de canales y activación de KeepAlive
                _progress.value = StreamLaunchProgress.HandshakingChannels(sessionId)
                startKeepAliveLoop(scope, targetBaseUri, sessionPath, activeGsToken, cv)

                // Fase 2 Durante el Stream: Demonio silencioso de renovación atómica al 75% TTL
                AtomicAuthSnapshotManager.startSilentRenewalDaemon(scope) {
                    authEngine.forceRefreshTokenChain(spoofedForwardedForIp)
                }

                _progress.value = StreamLaunchProgress.StreamingActive(
                    sessionId = sessionId,
                    baseUri = targetBaseUri,
                    gsToken = activeGsToken
                )
            } catch (e: CancellationException) {
                terminateActiveSessionSilently()
                _progress.value = StreamLaunchProgress.Idle
            } catch (e: Exception) {
                terminateActiveSessionSilently()
                _progress.value = StreamLaunchProgress.LaunchFailed(
                    title = "No se pudo iniciar el stream",
                    message = e.message ?: "Error desconocido al aprovisionar el servidor.",
                    requiresReLogin = e.message?.contains("401") == true
                )
            } finally {
                launchMutex.unlock()
            }
        }
    }

    /**
     * Cierra cualquier diálogo de error y resetea el estado del lanzador al primer toque.
     */
    fun dismissLaunchErrorAndReset() {
        _progress.value = StreamLaunchProgress.Idle
    }

    /**
     * SUBPROCESO 12: Cierre limpio de la sesión en Azure al salir del juego o cancelar la cola.
     * Fase 3 Post-Stream: DELETE explícito en /v5/sessions/cloud/{sessionId} con Bearer gsToken.
     */
    suspend fun terminateActiveSessionSilently() = withContext(Dispatchers.IO) {
        keepAliveJob?.cancel()
        AtomicAuthSnapshotManager.stopSilentRenewalDaemon()
        val path = currentSessionPath ?: return@withContext
        val base = currentBaseUri ?: return@withContext
        val token = currentGsToken ?: return@withContext

        currentSessionPath = null
        runCatching {
            val conn = (URL("$base/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Authorization", "Bearer $token")
            }
            CloudTimeSynchronizer.captureHttpDate(conn)
            conn.responseCode
        }
    }

    private fun startKeepAliveLoop(
        scope: CoroutineScope,
        baseUri: String,
        sessionPath: String,
        gsToken: String,
        cv: CorrelationVectorGenerator
    ) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000L)
                runCatching {
                    postJson(
                        url = "$baseUri/$sessionPath/keepalive",
                        gsToken = gsToken,
                        body = JSONObject(),
                        cv = cv.nextValue()
                    )
                }
            }
        }
    }

    private suspend fun requestCloudPlaySession(
        baseUri: String,
        gsToken: String,
        titleId: String,
        useTizenProfile: Boolean,
        spoofedIp: String?,
        cv: CorrelationVectorGenerator
    ): String = withContext(Dispatchers.IO) {
        val tzOffsetMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val payload = JSONObject().apply {
            put("titleId", titleId)
            put("systemUpdateGroup", "")
            put("fallbackRegionNames", JSONArray())
            put("serverId", "")
            put("clientSessionId", UUID.randomUUID().toString())
            put("settings", JSONObject().apply {
                put("nanoVersion", "V3;WebrtcTransport.dll")
                put("enableTextToSpeech", false)
                put("highContrast", 0)
                put("locale", "es-ES")
                put("useIceConnection", false)
                put("timezoneOffsetMinutes", tzOffsetMin)
                put("sdkType", "web")
                put("osName", if (useTizenProfile) "tizen" else "windows")
            })
        }

        val headers = mutableMapOf(
            "X-MS-Device-Info" to SelfHealingSessionInterceptor.buildResilientDeviceInfoHeader(useTizenProfile)
        )
        if (!spoofedIp.isNullOrBlank()) {
            headers["X-Forwarded-For"] = spoofedIp
        }

        val resp = postJson(
            url = "$baseUri/v5/sessions/cloud/play",
            gsToken = gsToken,
            body = payload,
            cv = cv.nextValue(),
            extraHeaders = headers
        )
        resp.getString("sessionPath")
    }

    private suspend fun sendConnectTransferToken(
        baseUri: String,
        sessionPath: String,
        gsToken: String,
        lptToken: String,
        cv: String
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("userToken", lptToken)
        postJson(
            url = "$baseUri/$sessionPath/connect",
            gsToken = gsToken,
            body = body,
            cv = cv
        )
    }

    private suspend fun sendSdpOffer(
        baseUri: String,
        sessionPath: String,
        gsToken: String,
        sdpOffer: String,
        cv: String
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("messageType", "offer")
            put("sdp", sdpOffer)
            put("configuration", JSONObject().apply {
                put("chatConfiguration", JSONObject().apply {
                    put("bytesPerSample", 2)
                    put("expectedClipDurationMs", 20)
                    put("format", JSONObject().apply {
                        put("codec", "opus")
                        put("container", "webm")
                    })
                    put("numChannels", 1)
                    put("sampleFrequencyHz", 24000)
                })
                put("chat", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                put("control", JSONObject().put("minVersion", 1).put("maxVersion", 3))
                put("input", JSONObject().put("minVersion", 1).put("maxVersion", 9))
                put("message", JSONObject().put("minVersion", 1).put("maxVersion", 1))
            })
        }
        postJson("$baseUri/$sessionPath/sdp", gsToken, body, cv)
    }

    private suspend fun pollExchangeResponse(
        url: String,
        gsToken: String,
        cv: CorrelationVectorGenerator
    ): String = withContext(Dispatchers.IO) {
        repeat(24) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $gsToken")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("MS-CV", cv.nextValue())
            }
            val code = conn.responseCode
            if (code == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val exchange = json.optString("exchangeResponse")
                if (exchange.isNotBlank()) return@withContext exchange
            }
            delay(500L)
        }
        throw IllegalStateException("Timeout esperando respuesta de negociación desde el nodo de Azure ($url)")
    }

    private fun getJson(url: String, gsToken: String, cv: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $gsToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("MS-CV", cv)
        }
        val code = conn.responseCode
        CloudTimeSynchronizer.captureHttpDate(conn)
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException("GET $url devolvió HTTP $code: $text")
        return JSONObject(text)
    }

    private fun postJson(
        url: String,
        gsToken: String,
        body: JSONObject,
        cv: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $gsToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("MS-CV", cv)
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val payloadBytes = body.toString().toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(payloadBytes) }
        val code = conn.responseCode
        CloudTimeSynchronizer.captureHttpDate(conn)
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException("POST $url devolvió HTTP $code: $text")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
