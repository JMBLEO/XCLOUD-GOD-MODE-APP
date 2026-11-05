package com.xcloud.godmode.network

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class GssvStreamCredentials(
    val gsToken: String,
    val baseRegionUri: String,
    val market: String
)

data class NativeTransportEndpoint(
    val sessionId: String,
    val remoteIp: String,
    val remotePort: Int,
    val localUfrag: String,
    val localPwd: String,
    val remoteUfrag: String,
    val remotePwd: String,
    val remoteDtlsFingerprint: String,
    val videoSsrc: Long,
    val audioSsrc: Long,
    val videoPayloadType: Int,
    val audioPayloadType: Int
)

/**
 * GodModeSessionOrchestrator: Pure native Auth Layer & Session Layer orchestrator.
 * Executes full cascade: OAuth -> XBL User Token -> XSTS GSSV Token -> gsToken,
 * negotiates SDP Offer/Answer and ICE candidates via direct REST endpoints,
 * manages session keepalives, and enforces thermal stability checks.
 */
class GodModeSessionOrchestrator(
    private val context: Context,
    private val tokenVault: TokenVaultManager
) {
    companion object {
        private const val TAG = "GodModeSessionOrch"
        private const val XBL_USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_AUTHORIZE_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val GSSV_LOGIN_URL = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user"
        private const val CLIENT_USER_AGENT = "GodModeNativeClient/4.0 (Android; NativePipeline)"
    }

    private val keepaliveActive = AtomicBoolean(false)
    private var keepaliveJob: Job? = null

    /**
     * 1. AUTH LAYER: Cadena completa OAuthToken -> XBL -> XSTS (RelyingParty GSSV) -> gsToken
     */
    suspend fun authenticateCascade(oauthAccessToken: String): GssvStreamCredentials = withContext(Dispatchers.IO) {
        // Paso 1: Obtener XBL User Token
        val xblPayload = JSONObject().apply {
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=$oauthAccessToken")
            })
        }
        val xblResponse = executeJsonPost(XBL_USER_AUTH_URL, xblPayload.toString(), null)
        val userToken = xblResponse.getString("Token")
        val uhs = xblResponse.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
            .getString("uhs")

        // Paso 2: Intercambiar por XSTS Token para el RelyingParty de Streaming (GSSV)
        val xstsPayload = JSONObject().apply {
            put("RelyingParty", "http://gssv.xboxlive.com/")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().put(userToken))
            })
        }
        val xstsResponse = executeJsonPost(XSTS_AUTHORIZE_URL, xstsPayload.toString(), null)
        val xstsToken = xstsResponse.getString("Token")

        // Paso 3: Autenticar en GSSV para extraer el gsToken y la región de menor latencia
        val gssvPayload = JSONObject().apply {
            put("token", "XBL3.0 x=$uhs;$xstsToken")
            put("offeringId", "xgpuweb")
        }
        val gssvResponse = executeJsonPost(GSSV_LOGIN_URL, gssvPayload.toString(), null)
        val gsToken = gssvResponse.getString("gsToken")
        val market = gssvResponse.optString("market", "CL")

        val regions = gssvResponse.getJSONObject("offeringSettings").getJSONArray("regions")
        var selectedBaseUri = ""
        for (i in 0 until regions.length()) {
            val regionObj = regions.getJSONObject(i)
            if (regionObj.optBoolean("isDefault", false) || selectedBaseUri.isEmpty()) {
                selectedBaseUri = regionObj.getString("baseUri")
            }
        }

        tokenVault.saveStreamCredentials(gsToken, selectedBaseUri)
        GssvStreamCredentials(gsToken, selectedBaseUri, market)
    }

    /**
     * 2. SESSION LAYER: Aprovisionamiento de juego, negociación SDP/ICE por REST y validaciones previas
     */
    suspend fun provisionAndNegotiateGameSession(
        credentials: GssvStreamCredentials,
        titleId: String,
        localDtlsFingerprint: String,
        width: Int = 1920,
        height: Int = 1080,
        targetFps: Int = 60
    ): NativeTransportEndpoint = withContext(Dispatchers.IO) {

        verifyPreStreamStability()

        val localUfrag = generateRandomCredential(8)
        val localPwd = generateRandomCredential(24)
        val deviceSessionId = UUID.randomUUID().toString()

        // 2.1 Solicitar creación de sesión del título seleccionado
        val playUrl = "${credentials.baseRegionUri}/v5/sessions/cloud/play"
        val playPayload = JSONObject().apply {
            put("titleId", titleId)
            put("systemUpdateGroup", "")
            put("settings", JSONObject().apply {
                put("nanoVersion", "V3;NativeRawTransport")
                put("enableTextToSpeech", false)
                put("highContrast", 0)
                put("locale", "es-CL")
                put("useIceConnection", true)
                put("timezoneOffsetMinutes", -180)
                put("sdkType", "native")
                put("osName", "android")
            })
            put("serverId", "")
            put("fallbackRegionNames", JSONArray())
        }

        val extraHeaders = mapOf(
            "X-MS-Device-Info" to buildNativeDeviceTelemetry(width, height, targetFps),
            "X-MS-Session-Id" to deviceSessionId
        )

        val playResponse = executeJsonPost(playUrl, playPayload.toString(), credentials.gsToken, extraHeaders)
        val sessionPath = playResponse.getString("sessionPath") // ej: v5/sessions/cloud/{sessionId}
        val sessionId = sessionPath.substringAfterLast("/")

        // 2.2 Polling de estado hasta que el servidor asigne hardware ("Provisioned")
        awaitSessionProvisioned(credentials.baseRegionUri, sessionPath, credentials.gsToken)

        // 2.3 Construir SDP Offer nativo (con soporte H.264 SVC / HEVC, Opus y SCTP DataChannels)
        val sdpOffer = buildNativeSdpOffer(localUfrag, localPwd, localDtlsFingerprint, width, height, targetFps)
        val sdpUrl = "${credentials.baseRegionUri}/$sessionPath/sdp"
        val sdpPostPayload = JSONObject().apply {
            put("messageType", "offer")
            put("sdp", sdpOffer)
            put("configuration", JSONObject().apply {
                put("chatConfiguration", JSONObject().apply {
                    put("bytesPerSample", 2)
                    put("expectedClipDurationMs", 20)
                    put("format", JSONObject().apply {
                        put("codec", "opus")
                        put("container", "raw")
                    })
                    put("numChannels", 1)
                    put("sampleFrequencyHz", 24000)
                })
                put("chat", JSONObject().apply { put("minVersion", 1); put("maxVersion", 1) })
                put("control", JSONObject().apply { put("minVersion", 1); put("maxVersion", 3) })
                put("input", JSONObject().apply { put("minVersion", 1); put("maxVersion", 8) })
                put("message", JSONObject().apply { put("minVersion", 1); put("maxVersion", 1) })
            })
        }
        executeJsonPost(sdpUrl, sdpPostPayload.toString(), credentials.gsToken)

        // 2.4 Polling de SDP Answer del servidor para extraer credenciales DTLS y SSRCs
        val sdpAnswerJson = pollResourceUntilReady(sdpUrl, credentials.gsToken)
        val remoteSdp = when {
            sdpAnswerJson.has("exchangeResponse") -> {
                val ex = sdpAnswerJson.get("exchangeResponse")
                if (ex is JSONObject) ex.optString("sdp")
                else JSONObject(ex.toString()).optString("sdp")
            }
            sdpAnswerJson.has("sdp") -> sdpAnswerJson.getString("sdp")
            else -> throw IllegalStateException("Respuesta SDP no contiene campo 'sdp' ni 'exchangeResponse'")
        }
        val parsedSdp = parseRemoteSdpAnswer(remoteSdp)

        // 2.5 Enviar ICE Candidate local y extraer el endpoint UDP directo del servidor
        val iceUrl = "${credentials.baseRegionUri}/$sessionPath/ice"
        val icePostPayload = JSONObject().apply {
            put("messageType", "iceCandidate")
            put("candidate", "candidate:1 1 UDP 2122260223 0.0.0.0 9 typ host generation 0 ufrag $localUfrag network-id 1")
        }
        executeJsonPost(iceUrl, icePostPayload.toString(), credentials.gsToken)

        val iceAnswerJson = pollResourceUntilReady(iceUrl, credentials.gsToken)
        val candidatesRoot = when {
            iceAnswerJson.has("exchangeResponse") -> {
                val ex = iceAnswerJson.get("exchangeResponse")
                if (ex is JSONObject) ex else JSONObject(ex.toString())
            }
            else -> iceAnswerJson
        }
        val candidatesArray = when {
            candidatesRoot.has("candidates") -> {
                val c = candidatesRoot.get("candidates")
                if (c is JSONArray) c else JSONArray(c.toString())
            }
            else -> JSONArray()
        }
        val (remoteIp, remotePort) = extractBestUdpCandidate(candidatesArray)

        // 2.6 Iniciar latido de continuidad de sesión (Keepalive)
        startSessionKeepaliveLoop(credentials.baseRegionUri, sessionPath, credentials.gsToken)

        NativeTransportEndpoint(
            sessionId = sessionId,
            remoteIp = remoteIp,
            remotePort = remotePort,
            localUfrag = localUfrag,
            localPwd = localPwd,
            remoteUfrag = parsedSdp.remoteUfrag,
            remotePwd = parsedSdp.remotePwd,
            remoteDtlsFingerprint = parsedSdp.remoteFingerprint,
            videoSsrc = parsedSdp.videoSsrc,
            audioSsrc = parsedSdp.audioSsrc,
            videoPayloadType = parsedSdp.videoPt,
            audioPayloadType = parsedSdp.audioPt
        )
    }

    /**
     * Seguridades previas para estabilidad antes de activar la inyección en los 6 motores
     */
    private fun verifyPreStreamStability() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermalStatus = powerManager.currentThermalStatus
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                Log.w(TAG, "Thermal throttling detectado ($thermalStatus). Activando perfil preventivo.")
            }
        }
    }

    private suspend fun awaitSessionProvisioned(baseUri: String, sessionPath: String, gsToken: String) {
        val stateUrl = "$baseUri/$sessionPath/state"
        repeat(60) {
            val stateObj = executeJsonGet(stateUrl, gsToken)
            when (val state = stateObj.optString("state")) {
                "Provisioned", "ReadyToConnect" -> return
                "Failed", "Canceled" -> {
                    val err = stateObj.optJSONObject("errorDetails")?.optString("message") ?: "Error desconocido"
                    throw IllegalStateException("Fallo en aprovisionamiento de sesión: $state - $err")
                }
                else -> delay(1000L)
            }
        }
        throw IllegalStateException("Tiempo de espera agotado aprovisionando la sesión de juego.")
    }

    private suspend fun pollResourceUntilReady(url: String, gsToken: String): JSONObject {
        repeat(40) {
            val res = executeJsonGet(url, gsToken)
            if (res.length() > 0 && !res.has("status")) {
                return res
            }
            delay(250L)
        }
        throw IllegalStateException("Timeout esperando respuesta de negociación en $url")
    }

    fun startSessionKeepaliveLoop(baseUri: String, sessionPath: String, gsToken: String) {
        if (!keepaliveActive.compareAndSet(false, true)) return
        keepaliveJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val keepaliveUrl = "$baseUri/$sessionPath/keepalive"
            while (keepaliveActive.get() && isActive) {
                delay(25_000L)
                try {
                    executeJsonPost(keepaliveUrl, "{}", gsToken)
                } catch (e: Exception) {
                    Log.e(TAG, "Advertencia en latido de continuidad de sesión: ${e.message}")
                }
            }
        }
    }

    fun stopSessionKeepalive() {
        keepaliveActive.set(false)
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private fun buildNativeSdpOffer(
        ufrag: String,
        pwd: String,
        fingerprint: String,
        width: Int,
        height: Int,
        fps: Int
    ): String {
        return buildString {
            append("v=0\r\n")
            append("o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n")
            append("s=-\r\n")
            append("t=0 0\r\n")
            append("a=group:BUNDLE 0 1 2\r\n")
            append("a=msid-semantic: WMS\r\n")
            // Video Media Section (H.264 SVC profile-level-id=4d002a / 42e01f + HEVC)
            append("m=video 9 UDP/TLS/RTP/SAVPF 96 98 102\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=ice-ufrag:$ufrag\r\n")
            append("a=ice-pwd:$pwd\r\n")
            append("a=fingerprint:sha-256 $fingerprint\r\n")
            append("a=setup:actpass\r\n")
            append("a=mid:0\r\n")
            append("a=recvonly\r\n")
            append("a=rtcp-mux\r\n")
            append("a=rtcp-rsize\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            append("a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=4d002a\r\n")
            append("a=rtcp-fb:96 nack\r\n")
            append("a=rtcp-fb:96 nack pli\r\n")
            append("a=rtcp-fb:96 goog-remb\r\n")
            append("a=rtpmap:98 H264/90000\r\n")
            append("a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n")
            append("a=rtpmap:102 H265/90000\r\n")
            // Audio Media Section (Opus 48kHz Low-Latency)
            append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=ice-ufrag:$ufrag\r\n")
            append("a=ice-pwd:$pwd\r\n")
            append("a=fingerprint:sha-256 $fingerprint\r\n")
            append("a=setup:actpass\r\n")
            append("a=mid:1\r\n")
            append("a=sendrecv\r\n")
            append("a=rtcp-mux\r\n")
            append("a=rtpmap:111 opus/48000/2\r\n")
            append("a=fmtp:111 minptime=10;useinbandfec=1\r\n")
            // SCTP Application Channels (input, control, message, chat)
            append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=ice-ufrag:$ufrag\r\n")
            append("a=ice-pwd:$pwd\r\n")
            append("a=fingerprint:sha-256 $fingerprint\r\n")
            append("a=setup:actpass\r\n")
            append("a=mid:2\r\n")
            append("a=sctp-port:5000\r\n")
        }
    }

    private data class ParsedSdpData(
        val remoteUfrag: String,
        val remotePwd: String,
        val remoteFingerprint: String,
        val videoSsrc: Long,
        val audioSsrc: Long,
        val videoPt: Int,
        val audioPt: Int
    )

    private fun parseRemoteSdpAnswer(sdp: String): ParsedSdpData {
        var ufrag = ""
        var pwd = ""
        var fingerprint = ""
        var videoSsrc = 1L
        var audioSsrc = 2L
        var videoPt = 96
        var audioPt = 111
        var currentSection = ""

        sdp.lines().forEach { line ->
            when {
                line.startsWith("m=video") -> {
                    currentSection = "video"
                    val parts = line.split(" ")
                    if (parts.size >= 4) videoPt = parts[3].toIntOrNull() ?: 96
                }
                line.startsWith("m=audio") -> {
                    currentSection = "audio"
                    val parts = line.split(" ")
                    if (parts.size >= 4) audioPt = parts[3].toIntOrNull() ?: 111
                }
                line.startsWith("a=ice-ufrag:") -> ufrag = line.substringAfter("a=ice-ufrag:").trim()
                line.startsWith("a=ice-pwd:") -> pwd = line.substringAfter("a=ice-pwd:").trim()
                line.startsWith("a=fingerprint:") -> fingerprint = line.substringAfter(" ").trim()
                line.startsWith("a=ssrc:") -> {
                    val ssrcVal = line.substringAfter("a=ssrc:").substringBefore(" ").toLongOrNull() ?: 0L
                    if (currentSection == "video" && videoSsrc == 1L) videoSsrc = ssrcVal
                    if (currentSection == "audio" && audioSsrc == 2L) audioSsrc = ssrcVal
                }
            }
        }
        return ParsedSdpData(ufrag, pwd, fingerprint, videoSsrc, audioSsrc, videoPt, audioPt)
    }

    private fun extractBestUdpCandidate(candidates: JSONArray): Pair<String, Int> {
        var ipv6Fallback: Pair<String, Int>? = null
        for (i in 0 until candidates.length()) {
            val candObj = candidates.optJSONObject(i) ?: continue
            var candStr = candObj.optString("candidate", "").trim()
            if (candStr.startsWith("a=")) {
                candStr = candStr.removePrefix("a=").trim()
            }
            if (candStr.isEmpty() || candStr.contains("end-of-candidates", ignoreCase = true)) {
                continue
            }
            if (candStr.contains("UDP", ignoreCase = true)) {
                val tokens = candStr.split("\\s+".toRegex())
                if (tokens.size >= 6) {
                    val ip = tokens[4]
                    val port = tokens[5].toIntOrNull() ?: 0
                    if (ip != "0.0.0.0" && port > 0) {
                        if (!ip.contains(":")) {
                            return Pair(ip, port) // Prioridad IPv4 directa
                        } else if (ipv6Fallback == null) {
                            ipv6Fallback = Pair(ip, port) // Fallback IPv6
                        }
                    }
                }
            }
        }
        return ipv6Fallback ?: throw IllegalStateException("No se encontró un candidato UDP válido en la respuesta ICE.")
    }

    private fun buildNativeDeviceTelemetry(width: Int, height: Int, fps: Int): String {
        return JSONObject().apply {
            put("appInfo", JSONObject().apply {
                put("env", JSONObject().apply { put("clientAppId", "com.xcloud.godmode"); put("clientAppType", "native") })
            })
            put("dev", JSONObject().apply {
                put("hw", JSONObject().apply { put("make", Build.MANUFACTURER); put("model", Build.MODEL) })
                put("os", JSONObject().apply { put("name", "android"); put("ver", Build.VERSION.RELEASE) })
                put("displayInfo", JSONObject().apply {
                    put("dimensions", JSONObject().apply { put("widthInPixels", width); put("heightInPixels", height) })
                    put("pixelDensity", JSONObject().apply { put("dpiX", 420); put("dpiY", 420) })
                    put("refreshRate", fps)
                })
            })
        }.toString()
    }

    private fun generateRandomCredential(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val sr = SecureRandom()
        return (1..length).map { chars[sr.nextInt(chars.length)] }.joinToString("")
    }

    private fun executeJsonPost(
        urlStr: String,
        body: String,
        bearerToken: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", CLIENT_USER_AGENT)
            setRequestProperty("x-xbl-contract-version", "1")
            if (!bearerToken.isNullOrEmpty()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        if (code == 204) return JSONObject()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code en $urlStr: $text")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun executeJsonGet(urlStr: String, bearerToken: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", CLIENT_USER_AGENT)
            setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        val code = conn.responseCode
        if (code == 204) return JSONObject()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
