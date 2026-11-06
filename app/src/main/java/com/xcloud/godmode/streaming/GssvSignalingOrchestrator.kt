package com.xcloud.godmode.streaming

import android.util.Log
import android.view.Surface
import com.xcloud.godmode.network.XboxFullAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed class StreamLaunchState {
    object Idle : StreamLaunchState()
    object RequireLogin : StreamLaunchState()
    data class Authenticating(val step: String) : StreamLaunchState()
    data class InQueue(val waitTimeSeconds: Int, val elapsedSeconds: Int) : StreamLaunchState()
    data class Provisioning(val serverState: String, val regionName: String) : StreamLaunchState()
    data class NegotiatingTunnel(val step: String) : StreamLaunchState()
    data class StreamingActive(val sessionPath: String, val regionBaseUri: String) : StreamLaunchState()
    data class Failed(
        val reason: String,
        val httpCode: Int = 0,
        val canFallbackToMotor2: Boolean = false
    ) : StreamLaunchState()
}

data class GssvSessionConfig(
    val gsToken: String,
    val regionName: String,
    val regionBaseUri: String, // SIEMPRE extraído de offeringSettings.regions (ej. BrazilSouth), NUNCA inventado
    val bypassIp: String?,     // IP pública de región soportada para X-Forwarded-For (ej. Brasil: 200.221.2.45)
    val titleId: String,       // Ej. "Starfield" -> productId / xcloudTitleId real
    val locale: String = "es-MX",
    val targetWidth: Int = 1920,
    val targetHeight: Int = 1080,
    val isXHomeConsole: Boolean = false,
    val longPlayToken: String? = null,
    val gssvXstsToken: String? = null,
    val fallbackGsToken: String? = null,
    val fallbackRegionUri: String? = null,
    val offeringId: String = "xgpuweb",
    val enableIPv6: Boolean = true
)

class GssvSignalingOrchestrator(
    private val httpClient: OkHttpClient
) {
    private val TAG = "GssvSignalingOrchestrator"
    private val _launchState = MutableStateFlow<StreamLaunchState>(StreamLaunchState.Idle)
    val launchState: StateFlow<StreamLaunchState> = _launchState

    private var keepaliveJob: Job? = null

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Barrera asíncrona para evitar NullPointerException cuando el SurfaceView aún no se ha creado
    private var surfaceReadyDeferred = CompletableDeferred<Surface>()
    private var lastKnownSurface: Surface? = null

    fun notifySurfaceCreated(surface: Surface) {
        lastKnownSurface = surface
        if (!surfaceReadyDeferred.isCompleted) {
            surfaceReadyDeferred.complete(surface)
        }
    }

    fun resetSurfaceBarrier() {
        surfaceReadyDeferred = CompletableDeferred()
        val surface = lastKnownSurface
        if (surface != null && surface.isValid) {
            surfaceReadyDeferred.complete(surface)
        }
    }

    // Cabecera oficial X-MS-Device-Info que desbloquea 1080p60 / 1440p en los servidores de Microsoft
    private fun buildDeviceInfoHeader(width: Int, height: Int): String {
        return JSONObject().apply {
            put("appInfo", JSONObject().apply {
                put("env", JSONObject().apply {
                    put("clientAppId", "XboxApp")
                    put("clientAppType", "native")
                    put("clientAppVersion", "2403.1001.4.0")
                    put("clientSdkVersion", "10.3.7")
                    put("httpEnvironment", "prod")
                    put("sdkInstallId", "")
                })
            })
            put("dev", JSONObject().apply {
                put("hw", JSONObject().apply {
                    put("make", "Microsoft")
                    put("model", "Surface Pro 9")
                    put("sdktype", "native")
                })
                put("os", JSONObject().apply {
                    put("name", "windows")
                    put("ver", "22631.2715")
                    put("platform", "desktop")
                })
                put("displayInfo", JSONObject().apply {
                    put("dimensions", JSONObject().apply {
                        put("widthInPixels", width)
                        put("heightInPixels", height)
                    })
                    put("pixelDensity", JSONObject().apply {
                        put("dpiX", 96)
                        put("dpiY", 96)
                    })
                })
                put("browser", JSONObject().apply {
                    put("browserName", "chrome")
                    put("browserVersion", "130.0")
                })
            })
        }.toString()
    }

    suspend fun startCloudStream(
        config: GssvSessionConfig?,
        streamManager: NativeStreamPipelineManager
    ) = withContext(Dispatchers.IO) {
        // 1. VALIDACIÓN ESTRICTA DE SESIÓN (Evita abrir reproductor en negro sin login)
        if (config == null || config.gsToken.isBlank()) {
            _launchState.value = StreamLaunchState.RequireLogin
            return@withContext
        }

        try {
            _launchState.value = StreamLaunchState.Provisioning("Inicializando superficie de hardware Zero-Copy...", config.regionName)

            // Esperar de forma asíncrona a que el SurfaceView entregue la superficie nativa de Android
            val validSurface = if (lastKnownSurface?.isValid == true) {
                lastKnownSurface
            } else {
                withTimeoutOrNull(5_000L) {
                    surfaceReadyDeferred.await()
                }
            }
            if (validSurface == null) {
                Log.w(TAG, "Superficie de hardware no entregada a tiempo, procediendo con inicialización de red...")
            }

            // 2. AUTO-APPLY GAME PROFILE (Room DB & Predefined Presets per GameID)
            val profileManager = com.xcloud.godmode.services.GameProfileManager(streamManager.context)
            val gameProfile = profileManager.getProfile(config.titleId, "")

            val (effectiveWidth, effectiveHeight) = when {
                gameProfile.resolutionOverride.contains("1440") || gameProfile.resolutionOverride.contains("2K") -> 2560 to 1440
                gameProfile.resolutionOverride.contains("4K") || gameProfile.resolutionOverride.contains("2160") -> 3840 to 2160
                gameProfile.resolutionOverride.contains("1080") -> 1920 to 1080
                gameProfile.resolutionOverride.contains("720") -> 1280 to 720
                else -> config.targetWidth to config.targetHeight
            }
            val effectiveBitrateKbps = gameProfile.targetBitrateKbps.coerceIn(15000, 50000)

            // Auto-compensate audio lip-sync clock drift
            NativeStreamBridge.setPtsOffsetUs(gameProfile.audioPtsOffsetUs)

            Log.i(TAG, "[Signaling Phase] GameID '${config.titleId}' detectado. Auto-aplicando GameProfile: Res=${gameProfile.resolutionOverride} (${effectiveWidth}x${effectiveHeight}), Bitrate=${effectiveBitrateKbps}kbps, Motor=${gameProfile.preferredEngineId}")

            var currentGsToken = config.gsToken
            var currentRegionBaseUri = config.regionBaseUri.trimEnd('/')
            var currentOffering = config.offeringId
            val activeXstsToken = config.gssvXstsToken?.takeIf { it.isNotBlank() }
                ?: XboxFullAuthManager.latestGssvXstsToken.takeIf { it.isNotBlank() }

            val isKnownF2p = isLikelyFreeToPlay(config.titleId)
            val initialOffering = if (isKnownF2p) "xgpuwebf2p" else currentOffering
            val offeringHierarchy = if (isKnownF2p) {
                listOf(initialOffering, "xgpuwebf2p", "xgpuweb").distinct()
            } else {
                listOf(initialOffering, "xgpuweb", "xgpu", "xgpuconsole").distinct()
            }

            _launchState.value = StreamLaunchState.Provisioning("Solicitando consola en ${config.regionName}...", config.regionName)

            var sessionPath = ""
            var lastHttpCode = 0
            var lastErrorDetail = ""
            var playSucceeded = false

            for (offering in offeringHierarchy) {
                if (offering != currentOffering || currentGsToken.isBlank()) {
                    _launchState.value = StreamLaunchState.Provisioning(
                        "Conmutando pool de licencias ($offering)...",
                        config.regionName
                    )
                    val newCredentials = getOfferingCredentials(
                        offering = offering,
                        xstsToken = activeXstsToken,
                        fallbackGsToken = config.fallbackGsToken,
                        fallbackRegionUri = config.fallbackRegionUri,
                        bypassIp = config.bypassIp
                    )
                    if (newCredentials != null) {
                        currentGsToken = newCredentials.first
                        currentRegionBaseUri = newCredentials.second.trimEnd('/')
                        currentOffering = offering
                    } else if (offering != initialOffering) {
                        Log.d(TAG, "No se obtuvieron credenciales para offering secundario $offering, continuando...")
                        continue
                    }
                }

                // Resolver TitleID real para el offering activo
                val resolvedTitleId = resolveTitleId(currentRegionBaseUri, currentGsToken, config.titleId, effectiveWidth, effectiveHeight)
                    ?: config.titleId.takeIf { it.isNotBlank() && it != "null" }
                    ?: "9NKX70BBCDRN"

                // CREAR SESIÓN: POST <baseUri>/v5/sessions/cloud/play
                val playPayload = JSONObject().apply {
                    put("titleId", resolvedTitleId)
                    put("systemUpdateGroup", "")
                    put("clientSessionId", UUID.randomUUID().toString())
                    put("settings", JSONObject().apply {
                        put("nanoVersion", "V3;RtspOverUdp")
                        put("supportedInputConfiguration", "ControllerAndKeyboardAndMouse")
                        put("enableTextToSpeech", false)
                        put("highContrast", 0)
                        put("locale", config.locale)
                        put("useIceConnection", false)
                        put("timezoneOffsetMinutes", -180)
                        put("sdkType", "native")
                        put("osName", "windows")
                        put("osVersion", "22631.2715")
                        put("screenWidth", effectiveWidth)
                        put("screenHeight", effectiveHeight)
                    })
                    put("serverId", "")
                    put("fallbackRegionNames", JSONArray())
                }

                val playReqBuilder = Request.Builder()
                    .url("$currentRegionBaseUri/v5/sessions/cloud/play")
                    .addHeader("Authorization", "Bearer $currentGsToken")
                    .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(effectiveWidth, effectiveHeight))
                    .addHeader("x-gssv-client", "XboxNative")
                    .post(playPayload.toString().toRequestBody(jsonMediaType))

                if (!config.bypassIp.isNullOrBlank()) {
                    playReqBuilder.addHeader("X-Forwarded-For", config.bypassIp)
                }

                val playResponse = httpClient.newCall(playReqBuilder.build()).execute()
                val playBody = playResponse.body?.string().orEmpty()
                lastHttpCode = playResponse.code

                if (playResponse.isSuccessful && playBody.isNotBlank()) {
                    val playJson = JSONObject(playBody)
                    val sPath = playJson.optString("sessionPath", "")
                    if (sPath.isNotBlank()) {
                        sessionPath = sPath
                        playSucceeded = true
                        Log.i(TAG, "Sesión GSSV creada con éxito (Offering: $currentOffering, TitleId: $resolvedTitleId): $sessionPath")
                        break
                    }
                }

                lastErrorDetail = extractXboxErrorDetail(playBody)
                Log.w(TAG, "Intento /play con offering $offering falló (${playResponse.code}): $lastErrorDetail")

                if (playBody.contains("NoEntitlement", ignoreCase = true) || lastErrorDetail.contains("NoEntitlement", ignoreCase = true)) {
                    Log.w(TAG, "Rechazo 'NoEntitlement' detectado: la cuenta no posee la licencia de este título.")
                    break
                }
            }

            if (!playSucceeded || sessionPath.isBlank()) {
                _launchState.value = StreamLaunchState.Failed(
                    reason = "Error al iniciar sesión cloud ($lastHttpCode): $lastErrorDetail",
                    httpCode = lastHttpCode
                )
                return@withContext
            }

            negotiateSession(
                config = config.copy(gsToken = currentGsToken, regionBaseUri = currentRegionBaseUri),
                sessionPath = sessionPath,
                streamManager = streamManager
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Stream start failed", e)
            _launchState.value = StreamLaunchState.Failed(e.message ?: "Error de inicio")
        }
    }

    suspend fun negotiateSession(
        config: GssvSessionConfig,
        sessionPath: String,
        streamManager: NativeStreamPipelineManager,
        gateTracker: com.xcloud.godmode.launch.StreamStartupStateMachine? = null,
        attemptId: com.xcloud.godmode.launch.LaunchAttemptId? = null
    ): com.xcloud.godmode.network.StreamSessionContext = withContext(Dispatchers.IO) {
        val currentGsToken = config.gsToken
        val currentRegionBaseUri = config.regionBaseUri.trimEnd('/')
        val effectiveWidth = config.targetWidth
        val effectiveHeight = config.targetHeight
        val effectiveBitrateKbps = 20000

        // 4. POLLING DE ESTADO (/state)
        var isReady = false
        var elapsedQueueSec = 0
        var connectSent = false

        while (!isReady) {
            val stateReq = Request.Builder()
                .url("$currentRegionBaseUri/$sessionPath/state")
                .addHeader("Authorization", "Bearer $currentGsToken")
                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(effectiveWidth, effectiveHeight))
                .get()
                .build()

            val stateRes = httpClient.newCall(stateReq).execute()
            val stateBody = stateRes.body?.string().orEmpty()
            if (!stateRes.isSuccessful || stateBody.isBlank()) {
                delay(1000)
                continue
            }
            val stateJson = JSONObject(stateBody)
            val state = stateJson.optString("state", "")

            when (state) {
                "WaitingForResources" -> {
                    val waitSec = stateJson.optInt("estimatedTotalWaitTimeInSeconds", 30)
                    _launchState.value = StreamLaunchState.InQueue(waitSec, elapsedQueueSec)
                    delay(2000)
                    elapsedQueueSec += 2
                }
                "Provisioning" -> {
                    _launchState.value = StreamLaunchState.Provisioning("Aprovisionando blade Xbox ($state)...", config.regionName)
                    delay(1000)
                }
                "ReadyToConnect" -> {
                    if (config.isXHomeConsole) {
                        isReady = true
                    } else if (!connectSent) {
                        connectSent = true
                        _launchState.value = StreamLaunchState.Provisioning("Autenticando sesión en el blade...", config.regionName)
                        val connectBody = "{}"
                        val connectReq = Request.Builder()
                            .url("$currentRegionBaseUri/$sessionPath/connect")
                            .addHeader("Authorization", "Bearer $currentGsToken")
                            .post(connectBody.toRequestBody(jsonMediaType))
                            .build()
                        httpClient.newCall(connectReq).execute().close()
                    }
                }
                "Provisioned" -> {
                    isReady = true
                    gateTracker?.let { if (attemptId != null) it.complete(attemptId, com.xcloud.godmode.launch.StartupGate.SESSION_PROVISIONED) }
                    // Fetch configuration
                    runCatching {
                        val configReq = Request.Builder()
                            .url("$currentRegionBaseUri/$sessionPath/configuration")
                            .addHeader("Authorization", "Bearer $currentGsToken")
                            .get().build()
                        httpClient.newCall(configReq).execute().use { configRes ->
                            if (configRes.isSuccessful) {
                                val configJson = JSONObject(configRes.body?.string().orEmpty())
                                val serverDetails = configJson.optJSONObject("serverDetails")
                                serverDetails?.optString("accessKey")?.let { streamManager.setAccessKey(it) }
                                
                                val srtpObj = serverDetails?.optJSONObject("srtp")
                                val srtpKey = srtpObj?.optString("key")
                                val srtpSalt = srtpObj?.optString("salt")
                                if (!srtpKey.isNullOrBlank() && !srtpSalt.isNullOrBlank()) {
                                    NativeStreamBridge.injectDirectSrtpKeys(srtpKey, srtpSalt)
                                }
                            }
                        }
                    }
                }
                "Failed", "Cancelled" -> throw IllegalStateException("Sesión abortada por Xbox: $state")
                else -> delay(1000)
            }
        }

        // 5. SDP OFFER
        _launchState.value = StreamLaunchState.NegotiatingTunnel("Generando oferta SDP...")
        val sdpOffer = streamManager.createOfferSync(effectiveWidth, effectiveHeight, effectiveBitrateKbps)
            ?: throw IllegalStateException("Fallo al generar SDP")

        val sdpPostPayload = JSONObject().apply {
            put("messageType", "offer")
            put("sdp", sdpOffer)
            put("requestId", 1)
        }

        httpClient.newCall(Request.Builder()
            .url("$currentRegionBaseUri/$sessionPath/sdp")
            .addHeader("Authorization", "Bearer $currentGsToken")
            .post(sdpPostPayload.toString().toRequestBody(jsonMediaType))
            .build()).execute().close()

        gateTracker?.let { if (attemptId != null) it.complete(attemptId, com.xcloud.godmode.launch.StartupGate.SDP_ACCEPTED) }

        // 6. SDP ANSWER
        _launchState.value = StreamLaunchState.NegotiatingTunnel("Esperando respuesta SDP...")
        var remoteSdpApplied = false
        var sdpAttempts = 0
        var currentRemoteSdp = ""
        while (!remoteSdpApplied && sdpAttempts < 30) {
            sdpAttempts++
            val res = httpClient.newCall(Request.Builder()
                .url("$currentRegionBaseUri/$sessionPath/sdp")
                .addHeader("Authorization", "Bearer $currentGsToken")
                .get().build()).execute()
            if (res.code == 200) {
                val body = res.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val sdp = json.optString("sdp").ifBlank { 
                        runCatching { JSONObject(json.optString("exchangeResponse")).optString("sdp") }.getOrDefault("")
                    }
                    if (sdp.isNotBlank()) {
                        currentRemoteSdp = sdp
                        streamManager.setRemoteDescriptionSync(sdp)
                        remoteSdpApplied = true
                        gateTracker?.let { if (attemptId != null) it.complete(attemptId, com.xcloud.godmode.launch.StartupGate.MEDIA_DESCRIPTOR_READY) }
                    }
                }
            }
            res.close()
            if (!remoteSdpApplied) delay(500)
        }

        // 7. ICE
        _launchState.value = StreamLaunchState.NegotiatingTunnel("Negociando ruta ICE...")
        val icePostPayload = JSONObject().apply {
            put("messageType", "iceCandidate")
            put("candidates", JSONArray(streamManager.getLocalCandidates()))
        }
        httpClient.newCall(Request.Builder()
            .url("$currentRegionBaseUri/$sessionPath/ice")
            .addHeader("Authorization", "Bearer $currentGsToken")
            .post(icePostPayload.toString().toRequestBody(jsonMediaType))
            .build()).execute().close()

        var remoteIceDone = false
        var iceRetry = 0
        var finalRemoteIp = "0.0.0.0"
        var finalRemotePort = 9002
        while (!remoteIceDone && iceRetry < 20) {
            iceRetry++
            val res = httpClient.newCall(Request.Builder()
                .url("$currentRegionBaseUri/$sessionPath/ice")
                .addHeader("Authorization", "Bearer $currentGsToken")
                .get().build()).execute()
            if (res.code == 200) {
                val body = res.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val candidates = json.optJSONArray("candidates") ?: runCatching { JSONArray(json.optString("exchangeResponse")) }.getOrNull()
                    if (candidates != null && candidates.length() > 0) {
                        streamManager.addRemoteIceCandidatesSync(candidates)
                        remoteIceDone = true
                        
                        val firstCandidate = candidates.optJSONObject(0).optString("candidate")
                        val parts = firstCandidate.split(" ")
                        if (parts.size >= 6) {
                            finalRemoteIp = parts[4]
                            finalRemotePort = parts[5].toInt()
                        }
                    }
                }
            }
            res.close()
            if (!remoteIceDone) delay(500)
        }

        val remoteUfrag = extractSdpAttribute(currentRemoteSdp, "a=ice-ufrag:") ?: ""
        val remotePwd = extractSdpAttribute(currentRemoteSdp, "a=ice-pwd:") ?: ""
        val localUfrag = extractSdpAttribute(sdpOffer, "a=ice-ufrag:") ?: "godmode-client"
        val localPwd = extractSdpAttribute(sdpOffer, "a=ice-pwd:") ?: "godmode-pwd"

        gateTracker?.let { if (attemptId != null) {
            it.complete(attemptId, com.xcloud.godmode.launch.StartupGate.ICE_CONNECTED)
            it.complete(attemptId, com.xcloud.godmode.launch.StartupGate.DTLS_CONNECTED)
        }}

        _launchState.value = StreamLaunchState.StreamingActive(sessionPath, currentRegionBaseUri)
        startKeepaliveLoop(currentRegionBaseUri, sessionPath, currentGsToken)

        return@withContext com.xcloud.godmode.network.StreamSessionContext(
            sessionId = sessionPath.substringAfterLast('/'),
            transportEpoch = attemptId?.value ?: 1L,
            ephemeralStreamTicket = java.util.UUID.randomUUID().toString(),
            ice = com.xcloud.godmode.network.IceTransportParameters(
                remoteIp = finalRemoteIp, remotePort = finalRemotePort,
                localUfrag = localUfrag, localPwd = localPwd,
                remoteUfrag = remoteUfrag, remotePwd = remotePwd,
                remoteDtlsFingerprint = ""
            ),
            profile = com.xcloud.godmode.network.NegotiatedVideoProfile(
                width = effectiveWidth, height = effectiveHeight, targetFps = 60,
                isHevcEnabled = true, videoPayloadType = 96, audioPayloadType = 111,
                videoSsrc = 1L, audioSsrc = 2L
            ),
            transport = com.xcloud.godmode.network.TransportConfiguration(transportEpoch = attemptId?.value ?: 1L)
        )
    }

    private fun startKeepaliveLoop(baseUri: String, sessionPath: String, token: String) {
        keepaliveJob?.cancel()
        // Use a background scope for the heartbeat
        keepaliveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val req = Request.Builder()
                        .url("$baseUri/$sessionPath/keepalive")
                        .addHeader("Authorization", "Bearer $token")
                        .post("{}".toRequestBody(jsonMediaType))
                        .build()
                    httpClient.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "Keepalive failed: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in keepalive loop", e)
                }
                delay(30_000L) // Azure default pulse is 30s
            }
        }
    }

    fun stopSignaling() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private fun isLikelyFreeToPlay(titleIdOrName: String): Boolean {
        val lower = titleIdOrName.lowercase()
        return lower.contains("fortnite") ||
                lower.contains("warzone") ||
                lower.contains("apex") ||
                lower.contains("halo infinite") ||
                lower.contains("roblox") ||
                lower.contains("destiny") ||
                lower.contains("fall guys") ||
                lower.contains("finals") ||
                lower == "9nblggh1zbkw"
    }

    private suspend fun getOfferingCredentials(
        offering: String,
        xstsToken: String?,
        fallbackGsToken: String?,
        fallbackRegionUri: String?,
        bypassIp: String?
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (offering == "xgpuwebf2p" && !fallbackGsToken.isNullOrBlank() && !fallbackRegionUri.isNullOrBlank()) {
            return@withContext Pair(fallbackGsToken, fallbackRegionUri)
        }
        if (xstsToken.isNullOrBlank()) return@withContext null

        return@withContext requestOfferingLogin(
            offeringId = offering,
            xstsToken = xstsToken,
            bypassIp = bypassIp
        )
    }

    private suspend fun requestOfferingLogin(
        offeringId: String,
        xstsToken: String,
        bypassIp: String?
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val url = "https://$offeringId.gssv-play-prod.xboxlive.com/v2/login/user"
        val payload = JSONObject().apply {
            put("token", xstsToken)
            put("offeringId", offeringId)
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("x-gssv-client", "XboxNative")
            .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(1920, 1080)) // Default for login
            .post(payload.toString().toRequestBody(jsonMediaType))

        if (!bypassIp.isNullOrBlank()) {
            reqBuilder.addHeader("X-Forwarded-For", bypassIp)
        }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "requestOfferingLogin $offeringId falló con HTTP ${res.code}")
                    return@withContext null
                }
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val token = json.optString("gsToken")
                if (token.isBlank()) return@withContext null

                val regions = json.optJSONObject("offeringSettings")?.optJSONArray("regions")
                var baseUri = url.substringBefore("/v2/login/user")
                if (regions != null && regions.length() > 0) {
                    for (i in 0 until regions.length()) {
                        val r = regions.getJSONObject(i)
                        if (r.optBoolean("isDefault", false)) {
                            baseUri = r.optString("baseUri", baseUri)
                            break
                        }
                    }
                    if (baseUri == url.substringBefore("/v2/login/user")) {
                        baseUri = regions.getJSONObject(0).optString("baseUri", baseUri)
                    }
                }
                Pair(token, baseUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en requestOfferingLogin para $offeringId", e)
            null
        }
    }

    private fun resolveTitleId(baseRegionUri: String, gsToken: String, productIdOrTitleId: String, width: Int = 1920, height: Int = 1080): String? {
        if (productIdOrTitleId.isBlank() || productIdOrTitleId == "null") return null
        val normalizedQuery = productIdOrTitleId.filter { it.isLetterOrDigit() }.lowercase()
        val req = Request.Builder()
            .url("${baseRegionUri.trimEnd('/')}/v2/titles")
            .addHeader("Authorization", "Bearer $gsToken")
            .addHeader("Accept", "application/json")
            .addHeader("x-gssv-client", "XboxNative")
            .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(width, height))
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val root = JSONObject(res.body?.string() ?: "{}")
                val results = root.optJSONArray("results") ?: return null
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val details = item.optJSONObject("details")
                    val pId = item.optString("productId").ifEmpty {
                        details?.optString("productId").orEmpty()
                    }
                    val tId = item.optString("titleId").ifEmpty {
                        details?.optString("titleId").orEmpty()
                    }
                    val friendly = details?.optString("friendlyTitle").orEmpty()
                    val titleName = details?.optString("title").orEmpty()
                    val productTitle = details?.optString("ProductTitle").orEmpty()
                    val genericName = details?.optString("name").orEmpty()

                    val matchFound = pId.equals(productIdOrTitleId, ignoreCase = true) ||
                        tId.equals(productIdOrTitleId, ignoreCase = true) ||
                        (friendly.isNotBlank() && friendly.filter { it.isLetterOrDigit() }.equals(normalizedQuery, ignoreCase = true)) ||
                        (titleName.isNotBlank() && titleName.filter { it.isLetterOrDigit() }.equals(normalizedQuery, ignoreCase = true)) ||
                        (productTitle.isNotBlank() && productTitle.filter { it.isLetterOrDigit() }.equals(normalizedQuery, ignoreCase = true)) ||
                        (genericName.isNotBlank() && genericName.filter { it.isLetterOrDigit() }.equals(normalizedQuery, ignoreCase = true))

                    if (matchFound) {
                        if (tId.isNotBlank()) return tId
                        if (pId.isNotBlank()) return pId
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolviendo titleId para $productIdOrTitleId", e)
            null
        }
    }

    private fun extractXboxErrorDetail(rawBody: String): String {
        if (rawBody.isBlank()) return "Respuesta vacía del servidor"
        return try {
            val json = JSONObject(rawBody)
            val details = json.optJSONObject("errorDetails")
            val code = details?.optString("code") ?: json.optString("code")
            val msg = details?.optString("message") ?: json.optString("message")
            listOf(code, msg).filter { it.isNotBlank() }.joinToString(": ").ifEmpty { rawBody.take(120) }
        } catch (_: Exception) {
            rawBody.take(120)
        }
    }

    private fun extractSdpAttribute(sdp: String, attributePrefix: String): String? {
        return sdp.lines()
            .firstOrNull { it.trim().startsWith(attributePrefix) }
            ?.substringAfter(attributePrefix)
            ?.trim()
    }
}
