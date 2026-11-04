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

    fun notifySurfaceCreated(surface: Surface) {
        if (!surfaceReadyDeferred.isCompleted) {
            surfaceReadyDeferred.complete(surface)
        }
    }

    fun resetSurfaceBarrier() {
        surfaceReadyDeferred = CompletableDeferred()
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
            val validSurface = withTimeoutOrNull(5_000L) {
                surfaceReadyDeferred.await()
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

            Log.i(TAG, "[Signaling Phase] GameID '${config.titleId}' detectado. Auto-aplicando GameProfile: Res=${gameProfile.resolutionOverride} (${effectiveWidth}x${effectiveHeight}), Bitrate=${effectiveBitrateKbps}kbps, Motor=${gameProfile.preferredEngineId}, Shader=${gameProfile.shaderPreset}, AI Preset=${gameProfile.aiOptimizationPreset}, BBR Ultra=${gameProfile.bbrUltraPacing}")

            var currentGsToken = config.gsToken
            var currentRegionBaseUri = config.regionBaseUri.trimEnd('/')
            var currentOffering = config.offeringId
            val activeXstsToken = config.gssvXstsToken?.takeIf { it.isNotBlank() }
                ?: XboxFullAuthManager.latestGssvXstsToken.takeIf { it.isNotBlank() }

            val isKnownF2p = isLikelyFreeToPlay(config.titleId)
            val initialOffering = if (isKnownF2p) "xgpuwebf2p" else currentOffering
            val offeringHierarchy = listOf(initialOffering, "xgpuweb", "xgpuwebf2p", "xgpu", "xgpuconsole").distinct()

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

                val isOfferingMismatch = playBody.contains("OfferingDoesNotContainTitle", ignoreCase = true) ||
                        playBody.contains("Offering", ignoreCase = true) ||
                        playResponse.code == 400 || playResponse.code == 404

                if (!isOfferingMismatch) {
                    break
                }
            }

            if (!playSucceeded || sessionPath.isBlank()) {
                _launchState.value = StreamLaunchState.Failed(
                    reason = "Error al iniciar sesión cloud ($lastHttpCode): $lastErrorDetail",
                    httpCode = lastHttpCode,
                    canFallbackToMotor2 = false
                )
                return@withContext
            }

            // 4. POLLING DE ESTADO (/state) HASTA 'ReadyToConnect' O 'Provisioned'
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
                        val waitSec = stateJson.optInt("estimatedTotalWaitTimeInSeconds", stateJson.optInt("waitTimeInSeconds", 30))
                        _launchState.value = StreamLaunchState.InQueue(waitSec, elapsedQueueSec)
                        delay(2000)
                        elapsedQueueSec += 2
                    }
                    "Provisioning" -> {
                        _launchState.value = StreamLaunchState.Provisioning("Aprovisionando blade Xbox Series X ($state)...", config.regionName)
                        delay(1000)
                    }
                    "ReadyToConnect" -> {
                        if (config.isXHomeConsole) {
                            isReady = true
                        } else if (!connectSent) {
                            connectSent = true
                            _launchState.value = StreamLaunchState.Provisioning("Autenticando sesión en el blade (/connect)...", config.regionName)
                            val connectBody = JSONObject().apply {
                                if (!config.longPlayToken.isNullOrBlank()) {
                                    put("userToken", config.longPlayToken)
                                }
                            }.toString()
                            val connectReq = Request.Builder()
                                .url("$currentRegionBaseUri/$sessionPath/connect")
                                .addHeader("Authorization", "Bearer $currentGsToken")
                                .addHeader("Content-Type", "application/json")
                                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(effectiveWidth, effectiveHeight))
                                .post(connectBody.toRequestBody(jsonMediaType))
                                .build()
                            httpClient.newCall(connectReq).execute().close()
                        }
                    }
                    "Provisioned" -> {
                        isReady = true
                        // Extracción de configuración de sesión (/configuration): accessKey y llaves directas SRTP
                        try {
                            val configReq = Request.Builder()
                                .url("$currentRegionBaseUri/$sessionPath/configuration")
                                .addHeader("Authorization", "Bearer $currentGsToken")
                                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(effectiveWidth, effectiveHeight))
                                .get()
                                .build()
                            httpClient.newCall(configReq).execute().use { configRes ->
                                if (configRes.isSuccessful) {
                                    val configBody = configRes.body?.string().orEmpty()
                                    if (configBody.isNotBlank()) {
                                        val configJson = JSONObject(configBody)
                                        val serverDetails = configJson.optJSONObject("serverDetails")
                                        val accessKey = serverDetails?.optString("accessKey")
                                        if (!accessKey.isNullOrBlank()) {
                                            streamManager.setAccessKey(accessKey)
                                            Log.i(TAG, "Configuración xCloud: accessKey dinámica obtenida: $accessKey")
                                        }
                                        val srtpObj = serverDetails?.optJSONObject("srtp")
                                        val srtpKey = srtpObj?.optString("key")
                                        val srtpSalt = srtpObj?.optString("salt")
                                        if (!srtpKey.isNullOrBlank() && !srtpSalt.isNullOrBlank()) {
                                            NativeStreamBridge.injectDirectSrtpKeys(srtpKey, srtpSalt)
                                            Log.i(TAG, "Configuración xCloud: Llaves maestras SRTP directas inyectadas")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Consulta /configuration omitida o no disponible: ${e.message}")
                        }
                    }
                    "Failed", "Cancelled" -> {
                        val errDetails = stateJson.optJSONObject("errorDetails")
                        val code = errDetails?.optString("code") ?: stateJson.optString("code", "")
                        val msg = errDetails?.optString("message") ?: stateJson.optString("message", "Sesión cancelada por el servidor")
                        throw IllegalStateException("Sesión abortada por Xbox ($state): $code $msg".trim())
                    }
                    else -> delay(1000)
                }
            }

            // 5. GENERACIÓN Y ENVÍO DE SDP OFFER
            _launchState.value = StreamLaunchState.NegotiatingTunnel("Generando oferta SDP y abriendo canales SCTP...")
            
            val rawSdpOffer = streamManager.createOfferSync(
                targetWidth = effectiveWidth,
                targetHeight = effectiveHeight,
                maxBitrateKbps = effectiveBitrateKbps
            ) ?: throw IllegalStateException("Fallo al generar oferta SDP local")

            // Sanitización extrema de SDP para Azure: Eliminar msid/mslabel y forzar tie-breaker
            val sdpOffer = rawSdpOffer.lines().filter {
                !it.contains("a=msid:") && !it.contains("a=mslabel:")
            }.joinToString("\r\n")

            val sdpPostPayload = JSONObject().apply {
                put("messageType", "offer")
                put("sdp", sdpOffer)
                put("requestId", 1)
                put("configuration", JSONObject().apply {
                    put("chatConfiguration", JSONObject().apply {
                        put("bytesPerSample", 2)
                        put("expectedClipDurationMs", 20)
                        put("format", JSONObject().put("codec", "opus").put("container", "webm"))
                        put("numChannels", 1)
                        put("sampleFrequencyHz", 24000)
                    })
                    put("chat", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                    put("control", JSONObject().put("minVersion", 1).put("maxVersion", 3))
                    put("input", JSONObject().put("minVersion", 1).put("maxVersion", 8))
                    put("message", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                })
            }

            val sdpReq = Request.Builder()
                .url("$currentRegionBaseUri/$sessionPath/sdp")
                .addHeader("Authorization", "Bearer $currentGsToken")
                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(effectiveWidth, effectiveHeight))
                .post(sdpPostPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(sdpReq).execute().close()

            // 6. POLLING DE SDP ANSWER DEL SERVIDOR (Tolerando HTTP 204 No Content y respuestas vacías)
            _launchState.value = StreamLaunchState.NegotiatingTunnel("Esperando respuesta SDP de Xbox Cloud...")
            var remoteSdpApplied = false
            var sdpAttempts = 0
            var currentRemoteSdp = ""
            
            while (!remoteSdpApplied && sdpAttempts < 35) {
                sdpAttempts++
                val sdpGetRes = httpClient.newCall(
                    Request.Builder()
                        .url("$currentRegionBaseUri/$sessionPath/sdp")
                        .addHeader("Authorization", "Bearer $currentGsToken")
                        .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(effectiveWidth, effectiveHeight))
                        .get()
                        .build()
                ).execute()

                if (sdpGetRes.code == 200) {
                    val sdpBody = sdpGetRes.body?.string().orEmpty()
                    if (sdpBody.isNotBlank()) {
                        val exchangeJson = JSONObject(sdpBody)
                        val exchangeResponseStr = exchangeJson.optString("exchangeResponse")
                        
                        // DOBLE PARSEO CRÍTICO: Deserializar el JSON escapado dentro de exchangeResponse
                        val sdpStr = if (exchangeResponseStr.isNotBlank()) {
                            try {
                                JSONObject(exchangeResponseStr).optString("sdp")
                            } catch (e: Exception) {
                                // Fallback si no es un objeto JSON (algunas regiones devuelven solo SDP en el string)
                                exchangeResponseStr
                            }
                        } else {
                            exchangeJson.optString("sdp")
                        }
                        
                        if (sdpStr.isNotBlank()) {
                            currentRemoteSdp = sdpStr
                            streamManager.setRemoteDescriptionSync(sdpStr)
                            remoteSdpApplied = true
                        }
                    }
                } else if (sdpGetRes.code == 204) {
                    // HTTP 204 No Content es esperado mientras la máquina virtual procesa la oferta SDP
                }
                sdpGetRes.close()
                if (!remoteSdpApplied) delay(200)
            }

            if (!remoteSdpApplied) {
                throw IllegalStateException("Timeout esperando respuesta SDP del servidor Xbox")
            }

            // 7. INTERCAMBIO DE CANDIDATOS ICE (Limpiando prefijo "a=", filtrando Teredo y Double-Parsing)
            _launchState.value = StreamLaunchState.NegotiatingTunnel("Negociando ruta directa ICE (DTLS 1.2)...")
            
            delay(1000) 
            val localCandidates = streamManager.getLocalCandidates()
            
            // Randomized 64-bit tie-breaker (simulado por un long aleatorio)
            val tieBreaker = java.util.Random().nextLong().toString()

            val icePostPayload = JSONObject().apply {
                put("messageType", "iceCandidate")
                put("requestId", 2)
                put("candidates", JSONArray(localCandidates))
                put("tieBreaker", tieBreaker)
            }

            val icePostReq = Request.Builder()
                .url("$currentRegionBaseUri/$sessionPath/ice")
                .addHeader("Authorization", "Bearer $currentGsToken")
                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(effectiveWidth, effectiveHeight))
                .post(icePostPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(icePostReq).execute().close()

            // Polling de candidatos ICE remotos (Tolerando HTTP 204)
            var remoteIceDone = false
            var iceRetry = 0
            var finalRemoteIp = ""
            var finalRemotePort = 0
            
            while (!remoteIceDone && iceRetry < 25) {
                iceRetry++
                val iceGetRes = httpClient.newCall(
                    Request.Builder()
                        .url("$currentRegionBaseUri/$sessionPath/ice")
                        .addHeader("Authorization", "Bearer $currentGsToken")
                        .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(effectiveWidth, effectiveHeight))
                        .get()
                        .build()
                ).execute()

                if (iceGetRes.code == 200) {
                    val iceBody = iceGetRes.body?.string().orEmpty()
                    if (iceBody.isNotBlank()) {
                        val iceJson = JSONObject(iceBody)
                        val exchangeResponseStr = iceJson.optString("exchangeResponse")
                        
                        // DOBLE PARSEO CRÍTICO: El campo exchangeResponse contiene un string con el JSON de candidatos
                        val candidatesArr = if (exchangeResponseStr.isNotBlank()) {
                            try {
                                val innerVal = if (exchangeResponseStr.trim().startsWith("[")) {
                                    JSONArray(exchangeResponseStr)
                                } else {
                                    JSONObject(exchangeResponseStr).optJSONArray("candidates")
                                }
                                innerVal as? JSONArray
                            } catch (e: Exception) { null }
                        } else {
                            iceJson.optJSONArray("candidates")
                        }

                        if (candidatesArr != null && candidatesArr.length() > 0) {
                            // Estrategia de Selección Óptima: Priorizar IPv6 Native Host > IPv4 Host > Relay
                            val evaluatedCandidates = mutableListOf<Triple<JSONObject, Int, String>>()

                            for (i in 0 until candidatesArr.length()) {
                                val item = candidatesArr.optJSONObject(i) ?: continue
                                val rawCandidate = item.optString("candidate", "").trim()
                                
                                if (rawCandidate.isBlank() || rawCandidate.contains("end-of-candidates")) continue
                                
                                val cleanCandidate = if (rawCandidate.startsWith("a=")) rawCandidate.substring(2) else rawCandidate
                                if (cleanCandidate.contains("2001:0:", ignoreCase = true)) continue // Filter Teredo

                                // Calcular puntuación de preferencia
                                var score = 0
                                val isIpv6 = cleanCandidate.contains(":") && !cleanCandidate.contains(".")
                                val isHost = cleanCandidate.contains("typ host", ignoreCase = true)
                                val isSrflx = cleanCandidate.contains("typ srflx", ignoreCase = true)

                                if (isIpv6) score += 1000 // Máxima prioridad a IPv6 (No NAT)
                                if (isHost) score += 500  // Preferir conexión directa
                                if (isSrflx) score += 200 // NAT Transversal público

                                evaluatedCandidates.add(Triple(item, score, cleanCandidate))
                            }

                            // Ordenar por puntuación descendente
                            val sortedCandidates = evaluatedCandidates.sortedByDescending { it.second }
                            
                            val cleanCandidates = JSONArray()
                            for (eval in sortedCandidates) {
                                val newItem = JSONObject(eval.first.toString())
                                newItem.put("candidate", eval.third)
                                cleanCandidates.put(newItem)
                                Log.d("GssvSignaling", "Evaluated ICE Candidate: Score=${eval.second}, Candidate=${eval.third}")
                            }

                            if (cleanCandidates.length() > 0) {
                                val bestCandidate = sortedCandidates.first()
                                val parts = bestCandidate.third.split(" ")
                                if (parts.size >= 6) {
                                    finalRemoteIp = parts[4]
                                    finalRemotePort = parts[5].toInt()
                                }
                                
                                streamManager.addRemoteIceCandidatesSync(cleanCandidates)
                                remoteIceDone = true
                            }
                        }
                    }
                } else if (iceGetRes.code == 204) {
                    // HTTP 204 No Content
                }
                iceGetRes.close()
                if (!remoteIceDone) {
                    delay(200)
                }
            }

            // 8. TRANSICIÓN A STREAM ACTIVO Y ARRANQUE DEL REACTOR NATIVO (ESTADO 0)
            _launchState.value = StreamLaunchState.StreamingActive(sessionPath, currentRegionBaseUri)

            if (finalRemoteIp.isNotBlank()) {
                // Extraer ufrag/pwd del SDP remoto
                // Necesitamos el sdpStr que se obtuvo en el paso 6
                // Lo declararemos fuera del bucle en el paso 6 para que esté disponible aquí.
                
                val remoteUfrag = extractSdpAttribute(currentRemoteSdp, "a=ice-ufrag:") ?: ""
                val remotePwd = extractSdpAttribute(currentRemoteSdp, "a=ice-pwd:") ?: ""
                val localUfrag = extractSdpAttribute(sdpOffer, "a=ice-ufrag:") ?: "godmode-client"

                Log.i(TAG, "God Mode Reactor: Iniciando sobre $finalRemoteIp:$finalRemotePort (IPv6=${finalRemoteIp.contains(":")})")
                
                NativeStreamBridge.startNativeReactor(
                    remoteIp = finalRemoteIp,
                    remotePort = finalRemotePort,
                    remoteUfrag = remoteUfrag,
                    remotePwd = remotePwd,
                    localUfrag = localUfrag,
                    enableIPv6 = config.enableIPv6
                )
            }

            // 9. INICIAR PULSO KEEPALIVE (POST /keepalive)
            startKeepaliveLoop(currentRegionBaseUri, sessionPath, currentGsToken)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Stream start failed", e)
            val safeReason = e.message?.takeIf { it.isNotBlank() && it != "null" }
                ?: "${e::class.java.simpleName} (${e.cause?.message ?: e.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "error de inicio"})"
            _launchState.value = StreamLaunchState.Failed(
                reason = safeReason,
                canFallbackToMotor2 = false
            )
        }
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
                    val pId = item.optString("productId").ifEmpty {
                        item.optJSONObject("details")?.optString("productId").orEmpty()
                    }
                    val tId = item.optString("titleId").ifEmpty {
                        item.optJSONObject("details")?.optString("titleId").orEmpty()
                    }
                    val friendly = item.optJSONObject("details")?.optString("friendlyTitle").orEmpty()

                    if (pId.equals(productIdOrTitleId, ignoreCase = true) ||
                        tId.equals(productIdOrTitleId, ignoreCase = true) ||
                        (friendly.isNotBlank() && friendly.equals(productIdOrTitleId, ignoreCase = true))
                    ) {
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
