package com.xcloud.godmode.streaming

import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
    data class NegotiatingWebRtc(val step: String) : StreamLaunchState()
    data class StreamingActive(val sessionPath: String, val regionBaseUri: String) : StreamLaunchState()
    data class Failed(
        val reason: String,
        val httpCode: Int = 0,
        val canFallbackToMotor2: Boolean = true
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
    val longPlayToken: String? = null
)

class GssvSignalingOrchestrator(
    private val httpClient: OkHttpClient
) {
    private val TAG = "GssvSignalingOrchestrator"
    private val _launchState = MutableStateFlow<StreamLaunchState>(StreamLaunchState.Idle)
    val launchState: StateFlow<StreamLaunchState> = _launchState

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
                    put("clientAppId", "www.xbox.com")
                    put("clientAppType", "browser")
                    put("clientAppVersion", "29.19.17")
                    put("clientSdkVersion", "10.3.7")
                    put("httpEnvironment", "prod")
                    put("sdkInstallId", "")
                })
            })
            put("dev", JSONObject().apply {
                put("hw", JSONObject().apply {
                    put("make", "Microsoft")
                    put("model", "Surface Pro 9")
                    put("sdktype", "web")
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
        streamManager: WebRTCStreamManager
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

            // 2. RESOLVER TitleID REAL SI SOLO SE POSEE ProductID
            val resolvedTitleId = if (config.titleId.isBlank() || config.titleId == "null") {
                resolveTitleIdFromProductId(config.regionBaseUri, config.gsToken, config.titleId)
                    ?: config.titleId.takeIf { it.isNotBlank() && it != "null" }
                    ?: "9NKX70BBCDRN"
            } else {
                config.titleId
            }

            _launchState.value = StreamLaunchState.Provisioning("Solicitando consola en ${config.regionName}...", config.regionName)

            // 3. CREAR SESIÓN: POST <baseUri>/v5/sessions/cloud/play
            val playPayload = JSONObject().apply {
                put("titleId", resolvedTitleId)
                put("systemUpdateGroup", "")
                put("clientSessionId", UUID.randomUUID().toString())
                put("settings", JSONObject().apply {
                    put("nanoVersion", "V3;")
                    put("supportedInputConfiguration", "ControllerAndKeyboardAndMouse")
                    put("enableTextToSpeech", false)
                    put("highContrast", 0)
                    put("locale", config.locale)
                    put("useIceConnection", false)
                    put("timezoneOffsetMinutes", -180)
                    put("sdkType", "android")
                    put("osName", "android")
                    put("osVersion", "34")
                    put("screenWidth", config.targetWidth)
                    put("screenHeight", config.targetHeight)
                })
                put("serverId", "")
                put("fallbackRegionNames", JSONArray())
            }

            val playReqBuilder = Request.Builder()
                .url("${config.regionBaseUri.trimEnd('/')}/v5/sessions/cloud/play")
                .addHeader("Authorization", "Bearer ${config.gsToken}")
                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(config.targetWidth, config.targetHeight))
                .post(playPayload.toString().toRequestBody(jsonMediaType))

            if (!config.bypassIp.isNullOrBlank()) {
                playReqBuilder.addHeader("X-Forwarded-For", config.bypassIp)
            }

            val playResponse = httpClient.newCall(playReqBuilder.build()).execute()
            val playBody = playResponse.body?.string().orEmpty()
            if (!playResponse.isSuccessful || playBody.isBlank()) {
                val errDetail = extractXboxErrorDetail(playBody)
                _launchState.value = StreamLaunchState.Failed(
                    "Error al iniciar sesión cloud (${playResponse.code}): $errDetail",
                    playResponse.code
                )
                return@withContext
            }

            val playJson = JSONObject(playBody)
            val sessionPath = playJson.optString("sessionPath", "")
            if (sessionPath.isBlank()) {
                val errDetail = extractXboxErrorDetail(playBody)
                _launchState.value = StreamLaunchState.Failed("El servidor de Xbox no entregó sessionPath: $errDetail")
                return@withContext
            }

            // 4. POLLING DE ESTADO (/state) HASTA 'ReadyToConnect' O 'Provisioned'
            var isReady = false
            var elapsedQueueSec = 0
            var connectSent = false

            while (!isReady) {
                val stateReq = Request.Builder()
                    .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/state")
                    .addHeader("Authorization", "Bearer ${config.gsToken}")
                    .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(config.targetWidth, config.targetHeight))
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
                                .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/connect")
                                .addHeader("Authorization", "Bearer ${config.gsToken}")
                                .addHeader("Content-Type", "application/json")
                                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(config.targetWidth, config.targetHeight))
                                .post(connectBody.toRequestBody(jsonMediaType))
                                .build()
                            httpClient.newCall(connectReq).execute().close()
                        }
                    }
                    "Provisioned" -> {
                        isReady = true
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
            _launchState.value = StreamLaunchState.NegotiatingWebRtc("Generando oferta SDP y abriendo canales SCTP...")
            
            val localSdpOffer = streamManager.createOfferSync(
                targetWidth = config.targetWidth,
                targetHeight = config.targetHeight,
                maxBitrateKbps = 20000
            ) ?: throw IllegalStateException("Fallo al generar oferta SDP local")

            val sdpPostPayload = JSONObject().apply {
                put("messageType", "offer")
                put("sdp", localSdpOffer)
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
                .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/sdp")
                .addHeader("Authorization", "Bearer ${config.gsToken}")
                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(config.targetWidth, config.targetHeight))
                .post(sdpPostPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(sdpReq).execute().close()

            // 6. POLLING DE SDP ANSWER DEL SERVIDOR (Tolerando HTTP 204 No Content y respuestas vacías)
            _launchState.value = StreamLaunchState.NegotiatingWebRtc("Esperando respuesta SDP de Xbox Cloud...")
            var remoteSdpApplied = false
            var sdpAttempts = 0
            while (!remoteSdpApplied && sdpAttempts < 35) {
                sdpAttempts++
                val sdpGetRes = httpClient.newCall(
                    Request.Builder()
                        .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/sdp")
                        .addHeader("Authorization", "Bearer ${config.gsToken}")
                        .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(config.targetWidth, config.targetHeight))
                        .get()
                        .build()
                ).execute()

                if (sdpGetRes.code == 200) {
                    val sdpBody = sdpGetRes.body?.string().orEmpty()
                    if (sdpBody.isNotBlank()) {
                        val exchangeJson = JSONObject(sdpBody)
                        val exchangeResponseStr = exchangeJson.optString("exchangeResponse")
                        val sdpStr = if (exchangeResponseStr.isNotBlank()) {
                            JSONObject(exchangeResponseStr).optString("sdp")
                        } else {
                            exchangeJson.optString("sdp")
                        }
                        if (sdpStr.isNotBlank()) {
                            streamManager.setRemoteDescriptionSync(sdpStr)
                            remoteSdpApplied = true
                        }
                    }
                } else if (sdpGetRes.code == 204) {
                    // HTTP 204 No Content es esperado mientras la máquina virtual procesa la oferta SDP
                }
                sdpGetRes.close()
                if (!remoteSdpApplied) delay(1000)
            }

            if (!remoteSdpApplied) {
                throw IllegalStateException("Timeout esperando respuesta SDP del servidor Xbox")
            }

            // 7. INTERCAMBIO DE CANDIDATOS ICE (Limpiando prefijo "a=")
            _launchState.value = StreamLaunchState.NegotiatingWebRtc("Negociando ruta directa ICE (DTLS 1.2)...")
            
            delay(1000) 
            val localCandidates = streamManager.getLocalCandidates()
            val icePostPayload = JSONObject().apply {
                put("messageType", "iceCandidate")
                put("requestId", 2)
                put("candidates", JSONArray(localCandidates))
            }

            val icePostReq = Request.Builder()
                .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/ice")
                .addHeader("Authorization", "Bearer ${config.gsToken}")
                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(config.targetWidth, config.targetHeight))
                .post(icePostPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(icePostReq).execute().close()

            // Polling de candidatos ICE remotos (Tolerando HTTP 204)
            var remoteIceDone = false
            var iceRetry = 0
            while (!remoteIceDone && iceRetry < 25) {
                iceRetry++
                val iceGetRes = httpClient.newCall(
                    Request.Builder()
                        .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/ice")
                        .addHeader("Authorization", "Bearer ${config.gsToken}")
                        .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(config.targetWidth, config.targetHeight))
                        .get()
                        .build()
                ).execute()

                if (iceGetRes.code == 200) {
                    val iceBody = iceGetRes.body?.string().orEmpty()
                    if (iceBody.isNotBlank()) {
                        val iceJson = JSONObject(iceBody)
                        val exchangeResponseStr = iceJson.optString("exchangeResponse")
                        val candidatesArr = when {
                            exchangeResponseStr.trim().startsWith("[") -> JSONArray(exchangeResponseStr)
                            exchangeResponseStr.trim().startsWith("{") -> JSONObject(exchangeResponseStr).optJSONArray("candidates")
                            else -> iceJson.optJSONArray("candidates")
                        }
                        if (candidatesArr != null && candidatesArr.length() > 0) {
                            // Limpiar prefijo "a=" de cada candidato
                            val cleanCandidates = JSONArray()
                            for (i in 0 until candidatesArr.length()) {
                                val item = candidatesArr.optJSONObject(i)
                                if (item != null) {
                                    val rawCandidate = item.optString("candidate", "")
                                    val cleanCandidate = if (rawCandidate.startsWith("a=")) rawCandidate.substring(2) else rawCandidate
                                    val newItem = JSONObject(item.toString())
                                    newItem.put("candidate", cleanCandidate)
                                    cleanCandidates.put(newItem)
                                } else {
                                    val rawCandidate = candidatesArr.optString(i, "")
                                    val cleanCandidate = if (rawCandidate.startsWith("a=")) rawCandidate.substring(2) else rawCandidate
                                    cleanCandidates.put(cleanCandidate)
                                }
                            }
                            streamManager.addRemoteIceCandidatesSync(cleanCandidates)
                            remoteIceDone = true
                        }
                    }
                } else if (iceGetRes.code == 204) {
                    // HTTP 204 No Content
                }
                iceGetRes.close()
                if (!remoteIceDone) {
                    delay(800)
                }
            }

            // 8. TRANSICIÓN A STREAM ACTIVO
            _launchState.value = StreamLaunchState.StreamingActive(sessionPath, config.regionBaseUri)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Stream start failed", e)
            val safeReason = e.message?.takeIf { it.isNotBlank() && it != "null" }
                ?: "${e::class.java.simpleName} (${e.cause?.message ?: e.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "error de inicio"})"
            _launchState.value = StreamLaunchState.Failed(
                reason = safeReason,
                canFallbackToMotor2 = true
            )
        }
    }

    private fun resolveTitleIdFromProductId(baseRegionUri: String, gsToken: String, productId: String): String? {
        if (productId.isBlank() || productId == "null") return null
        val req = Request.Builder()
            .url("${baseRegionUri.trimEnd('/')}/v2/titles")
            .addHeader("Authorization", "Bearer $gsToken")
            .addHeader("Accept", "application/json")
            .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(1920, 1080))
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
                    if (pId.equals(productId, ignoreCase = true)) {
                        val tId = item.optString("titleId")
                        if (tId.isNotBlank()) return tId
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolviendo titleId para $productId", e)
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
}
