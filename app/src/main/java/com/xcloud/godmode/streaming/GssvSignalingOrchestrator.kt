package com.xcloud.godmode.streaming

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
    data class Failed(val reason: String, val httpCode: Int = 0) : StreamLaunchState()
}

data class GssvSessionConfig(
    val gsToken: String,
    val regionName: String,
    val regionBaseUri: String, // SIEMPRE extraído de offeringSettings.regions (ej. BrazilSouth), NUNCA inventado
    val bypassIp: String?,     // IP pública de región soportada para X-Forwarded-For (ej. Brasil: 200.221.2.45)
    val titleId: String,       // Ej. "Starfield" -> productId / xcloudTitleId real
    val locale: String = "es-CL",
    val targetWidth: Int = 1920,
    val targetHeight: Int = 1080
)

class GssvSignalingOrchestrator(
    private val httpClient: OkHttpClient
) {
    private val TAG = "GssvSignalingOrchestrator"
    private val _launchState = MutableStateFlow<StreamLaunchState>(StreamLaunchState.Idle)
    val launchState: StateFlow<StreamLaunchState> = _launchState

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Cabecera oficial X-MS-Device-Info que desbloquea 1080p60 en los servidores de Microsoft
    private fun buildDeviceInfoHeader(width: Int, height: Int): String {
        return JSONObject().apply {
            put("appInfo", JSONObject().apply {
                put("env", JSONObject().put("clientAppId", "Microsoft.GamingApp").put("clientAppType", "native").put("clientAppVersion", "2403.1001.4.0"))
            })
            put("dev", JSONObject().apply {
                put("hw", JSONObject().put("make", "Microsoft").put("model", "Surface Pro 9").put("sdktype", "native"))
                put("os", JSONObject().put("name", "windows").put("ver", "22631.1.amd64fre.ni_release.220506-1250"))
                put("displayInfo", JSONObject().apply {
                    put("dimensions", JSONObject().put("widthInPixels", width).put("heightInPixels", height))
                    put("pixelDensity", JSONObject().put("dpiX", 96).put("dpiY", 96))
                })
            })
        }.toString()
    }

    suspend fun startCloudStream(
        config: GssvSessionConfig?,
        streamManager: WebRTCStreamManager
    ) {
        // 1. VALIDACIÓN ESTRICTA DE SESIÓN (Evita abrir reproductor en negro sin login)
        if (config == null || config.gsToken.isBlank()) {
            _launchState.value = StreamLaunchState.RequireLogin
            return
        }

        try {
            _launchState.value = StreamLaunchState.Provisioning("Solicitando consola en ${config.regionName}...", config.regionName)

            // 2. CREAR SESIÓN: POST <baseUri>/v5/sessions/cloud/play
            val playPayload = JSONObject().apply {
                put("titleId", config.titleId)
                put("systemUpdateGroup", "")
                put("clientSessionId", UUID.randomUUID().toString())
                put("settings", JSONObject().apply {
                    put("nanoVersion", "V3;WebrtcTransport.dll")
                    put("enableTextToSpeech", false)
                    put("highContrast", 0)
                    put("locale", config.locale)
                    put("useIceConnection", false)
                    put("timezoneOffsetMinutes", -180)
                    put("sdkType", "web")
                    put("osName", "windows")
                })
                put("serverId", "")
                put("fallbackRegionNames", JSONArray())
            }

            val playReqBuilder = Request.Builder()
                .url("${config.regionBaseUri.trimEnd('/')}/v5/sessions/cloud/play")
                .addHeader("Authorization", "Bearer ${config.gsToken}")
                .addHeader("X-MS-Device-Info", buildDeviceInfoHeader(config.targetWidth, config.targetHeight))
                .post(playPayload.toString().toRequestBody(jsonMediaType))

            // Si estamos en Chile usando servidor de Brasil, el X-Forwarded-For debe ser una IP de Brasil (región soportada)
            if (!config.bypassIp.isNullOrBlank()) {
                playReqBuilder.addHeader("X-Forwarded-For", config.bypassIp)
            }

            val playResponse = httpClient.newCall(playReqBuilder.build()).execute()
            val playBody = playResponse.body?.string().orEmpty()
            if (!playResponse.isSuccessful) {
                _launchState.value = StreamLaunchState.Failed(
                    "Error al iniciar sesión cloud (${playResponse.code}): $playBody",
                    playResponse.code
                )
                return
            }

            val sessionPath = JSONObject(playBody).getString("sessionPath")

            // 3. POLLING DE ESTADO (/state) HASTA 'ReadyToConnect'
            var isReady = false
            var elapsedQueueSec = 0
            while (!isReady) {
                val stateReq = Request.Builder()
                    .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/state")
                    .addHeader("Authorization", "Bearer ${config.gsToken}")
                    .get()
                    .build()

                val stateRes = httpClient.newCall(stateReq).execute()
                val stateBody = stateRes.body?.string().orEmpty()
                if (stateBody.isBlank()) {
                    delay(1000)
                    continue
                }
                val stateJson = JSONObject(stateBody)
                val state = stateJson.optString("state", "")

                when (state) {
                    "WaitingForResources" -> {
                        val waitSec = stateJson.optInt("waitTimeInSeconds", 30)
                        _launchState.value = StreamLaunchState.InQueue(waitSec, elapsedQueueSec)
                        delay(2000)
                        elapsedQueueSec += 2
                    }
                    "Provisioning", "Provisioned" -> {
                        _launchState.value = StreamLaunchState.Provisioning("Aprovisionando blade Xbox Series X ($state)...", config.regionName)
                        delay(1000)
                    }
                    "ReadyToConnect" -> {
                        isReady = true
                    }
                    "Failed", "Cancelled" -> {
                        val errDetails = stateJson.optJSONObject("errorDetails")?.optString("message") ?: "Sesión cancelada por el servidor"
                        _launchState.value = StreamLaunchState.Failed(errDetails)
                        return
                    }
                    else -> delay(1000)
                }
            }

            // 4. GENERACIÓN Y ENVÍO DE SDP OFFER (Manteniendo H.264 obligatorio para Xbox)
            _launchState.value = StreamLaunchState.NegotiatingWebRtc("Generando oferta SDP y abriendo canales SCTP...")
            
            // Note: We need to prepare PeerConnection in streamManager first
            val localSdpOffer = streamManager.createOfferSync(
                targetWidth = config.targetWidth,
                targetHeight = config.targetHeight,
                maxBitrateKbps = 20000
            ) ?: throw Exception("Failed to generate local SDP offer")

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

            httpClient.newCall(
                Request.Builder()
                    .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/sdp")
                    .addHeader("Authorization", "Bearer ${config.gsToken}")
                    .post(sdpPostPayload.toString().toRequestBody(jsonMediaType))
                    .build()
            ).execute().close()

            // 5. POLLING DE SDP ANSWER DEL SERVIDOR DE MICROSOFT
            _launchState.value = StreamLaunchState.NegotiatingWebRtc("Esperando respuesta SDP de Xbox Cloud...")
            var remoteSdpApplied = false
            while (!remoteSdpApplied) {
                val sdpGetRes = httpClient.newCall(
                    Request.Builder()
                        .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/sdp")
                        .addHeader("Authorization", "Bearer ${config.gsToken}")
                        .get()
                        .build()
                ).execute()

                if (sdpGetRes.code == 200) {
                    val sdpBody = sdpGetRes.body?.string().orEmpty()
                    if (sdpBody.isNotBlank()) {
                        val exchangeJson = JSONObject(sdpBody)
                        val exchangeResponseStr = exchangeJson.optString("exchangeResponse")
                        if (exchangeResponseStr.isNotBlank()) {
                            val exchangeResponse = JSONObject(exchangeResponseStr)
                            val remoteSdp = exchangeResponse.getString("sdp")
                            streamManager.setRemoteDescriptionSync(remoteSdp)
                            remoteSdpApplied = true
                        }
                    }
                }
                sdpGetRes.close()
                if (!remoteSdpApplied) delay(500)
            }

            // 6. INTERCAMBIO DE CANDIDATOS ICE (Sin IPs anonimizadas .local)
            _launchState.value = StreamLaunchState.NegotiatingWebRtc("Negociando ruta directa ICE (DTLS 1.2)...")
            
            // Wait a bit for local candidates
            delay(1000) 
            val localCandidates = streamManager.getLocalCandidates()
            val icePostPayload = JSONObject().apply {
                put("messageType", "iceCandidate")
                put("requestId", 2)
                put("candidates", JSONArray(localCandidates))
            }

            httpClient.newCall(
                Request.Builder()
                    .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/ice")
                    .addHeader("Authorization", "Bearer ${config.gsToken}")
                    .post(icePostPayload.toString().toRequestBody(jsonMediaType))
                    .build()
            ).execute().close()

            // Polling de candidatos ICE remotos
            var remoteIceDone = false
            var iceRetry = 0
            while (!remoteIceDone && iceRetry < 10) {
                val iceGetRes = httpClient.newCall(
                    Request.Builder()
                        .url("${config.regionBaseUri.trimEnd('/')}/$sessionPath/ice")
                        .addHeader("Authorization", "Bearer ${config.gsToken}")
                        .get()
                        .build()
                ).execute()

                if (iceGetRes.code == 200) {
                    val iceBody = iceGetRes.body?.string().orEmpty()
                    if (iceBody.isNotBlank()) {
                        val iceJson = JSONObject(iceBody)
                        val exchangeResponseStr = iceJson.optString("exchangeResponse")
                        if (exchangeResponseStr.isNotBlank()) {
                            val candidatesArr = JSONArray(exchangeResponseStr)
                            streamManager.addRemoteIceCandidatesSync(candidatesArr)
                            remoteIceDone = true
                        }
                    }
                }
                iceGetRes.close()
                if (!remoteIceDone) {
                    delay(800)
                    iceRetry++
                }
            }

            // 7. TRANSICIÓN A STREAM ACTIVO (El handshake de DataChannels despierta el video)
            _launchState.value = StreamLaunchState.StreamingActive(sessionPath, config.regionBaseUri)

        } catch (e: Exception) {
            Log.e(TAG, "Stream start failed", e)
            _launchState.value = StreamLaunchState.Failed("Excepción en el arranque del stream: ${e.message}")
        }
    }
}
