package com.xcloud.godmode.streaming

import android.view.Surface
import com.xcloud.godmode.network.AuthenticatedSession
import com.xcloud.godmode.network.GodModeAuthOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class StreamLaunchPhase {
    object Idle : StreamLaunchPhase()
    data class Initializing(val detail: String, val queueWaitSeconds: Int? = null) : StreamLaunchPhase()
    data class ReadyForWebRtc(
        val sessionId: String,
        val sessionPath: String,
        val serverSdpOfferOrAnswer: String,
        val serverIceCandidates: List<String>
    ) : StreamLaunchPhase()
    data class FallbackToWebViewMotor2(val targetPlayUrl: String, val reason: String) : StreamLaunchPhase()
    data class Failed(
        val title: String,
        val technicalDetail: String,
        val canFallbackToMotor2: Boolean = true
    ) : StreamLaunchPhase()
}

class XboxStreamSessionLauncher {

    private val _phase = MutableStateFlow<StreamLaunchPhase>(StreamLaunchPhase.Idle)
    val phase: StateFlow<StreamLaunchPhase> = _phase.asStateFlow()

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

    /**
     * Construye el encabezado obligatorio 'x-ms-device-info' requerido por los servidores GSSV de Xbox
     * para autorizar sesiones de streaming 1080p / 1440p sin rechazos (Ref: Better-xCloud / Stratix).
     */
    private fun buildDeviceInfoHeaderJson(): String {
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
                        put("widthInPixels", 1920)
                        put("heightInPixels", 1080)
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

    /**
     * Inicia una sesión de juego en la nube (xCloud) o consola remota (xHome) validando cada etapa
     * para que jamás ocurra un NullPointerException silencioso.
     */
    suspend fun startStreamSession(
        isXHomeConsole: Boolean,
        productId: String,
        rawTitleId: String?,
        serverOrConsoleId: String?,
        session: AuthenticatedSession?,
        createLocalSdpOffer: suspend () -> String,
        gatherLocalIceCandidates: suspend () -> List<String>,
        applyRemoteSdpAndIce: suspend (remoteSdp: String, cleanIceCandidates: List<String>, surface: Surface) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Validación preventiva de sesión (Evita gsToken == null)
            if (session == null) {
                val webFallbackUrl = RegionBypassWebViewHelper.buildSupportedPlayUrl(productId, "es-MX")
                _phase.value = StreamLaunchPhase.FallbackToWebViewMotor2(
                    targetPlayUrl = webFallbackUrl,
                    reason = "Sin token GSSV nativo activo: conmutando automáticamente a Motor 2 (WebView)."
                )
                return@withContext
            }

            val gsToken = if (isXHomeConsole) session.xHomeGsToken else session.xCloudGsToken
            if (gsToken.isNullOrBlank()) {
                val webFallbackUrl = RegionBypassWebViewHelper.buildSupportedPlayUrl(productId, "es-MX")
                _phase.value = StreamLaunchPhase.FallbackToWebViewMotor2(
                    targetPlayUrl = webFallbackUrl,
                    reason = "La cuenta actual no tiene gsToken para este modo. Abriendo en Motor 2."
                )
                return@withContext
            }

            val baseRegionUri = session.defaultRegionUri.trimEnd('/')

            // 2. Esperar hasta 5 segundos a que el SurfaceView de Android entregue la Surface nativa
            _phase.value = StreamLaunchPhase.Initializing("Inicializando superficie de hardware Zero-Copy...")
            val validSurface = withTimeoutOrNull(5_000L) {
                surfaceReadyDeferred.await()
            } ?: throw IllegalStateException("El SurfaceView nativo no entregó una superficie gráfica a tiempo.")

            // 3. Resolver el titleId real si el catálogo solo tenía productId
            val resolvedTitleId = if (!isXHomeConsole) {
                if (!rawTitleId.isNullOrBlank() && rawTitleId != "null") {
                    rawTitleId
                } else {
                    _phase.value = StreamLaunchPhase.Initializing("Resolviendo TitleID en el catálogo de xCloud...")
                    resolveTitleIdFromProductId(baseRegionUri, gsToken, productId)
                        ?: productId
                }
            } else ""

            // 4. Crear la sesión en el servidor de Xbox (/play)
            _phase.value = StreamLaunchPhase.Initializing("Solicitando servidor blade Xbox Series X...")
            val playEndpoint = if (isXHomeConsole) {
                "$baseRegionUri/v5/sessions/home/play"
            } else {
                "$baseRegionUri/v5/sessions/cloud/play"
            }

            val playBody = JSONObject().apply {
                put("titleId", resolvedTitleId)
                put("systemUpdateGroup", "")
                put("fallbackRegionNames", JSONArray())
                put("serverId", serverOrConsoleId ?: "")
                put("settings", JSONObject().apply {
                    put("nanoVersion", "V3;")
                    put("supportedInputConfiguration", "ControllerAndKeyboardAndMouse")
                    put("enableTextToSpeech", false)
                    put("highContrast", 0)
                    put("locale", "es-MX")
                    put("useIceConnection", false)
                    put("timezoneOffsetMinutes", -180)
                    put("sdkType", "android")
                    put("osName", "android")
                    put("osVersion", "34")
                    put("screenWidth", 1920)
                    put("screenHeight", 1080)
                })
            }.toString()

            val playReq = Request.Builder()
                .url(playEndpoint)
                .post(playBody.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $gsToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-ms-device-info", buildDeviceInfoHeaderJson())
                .build()

            val sessionPath = GodModeAuthOrchestrator.httpClient.newCall(playReq).execute().use { res ->
                val txt = res.body?.string().orEmpty()
                if (!res.isSuccessful || txt.isBlank()) {
                    throw IllegalStateException("El servidor rechazó /play (HTTP ${res.code}): ${extractXboxErrorDetail(txt)}")
                }
                JSONObject(txt).optString("sessionPath").takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("La respuesta de /play no incluyó sessionPath.")
            }

            val sessionBaseUrl = "$baseRegionUri/$sessionPath"

            // 5. Polling del estado de la sesión (/state)
            pollSessionUntilProvisioned(
                sessionBaseUrl = sessionBaseUrl,
                gsToken = gsToken,
                isXHomeConsole = isXHomeConsole,
                longPlayToken = session.longPlayToken
            )

            // 6. Negociación SDP
            _phase.value = StreamLaunchPhase.Initializing("Negociando descriptores multimedia WebRTC (SDP)...")
            val localSdpOffer = createLocalSdpOffer()
            val sdpPostBody = JSONObject().apply {
                put("messageType", "offer")
                put("sdp", localSdpOffer)
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
                    put("chat", JSONObject().apply { put("minVersion", 1); put("maxVersion", 1) })
                    put("control", JSONObject().apply { put("minVersion", 1); put("maxVersion", 3) })
                    put("input", JSONObject().apply { put("minVersion", 1); put("maxVersion", 8) })
                    put("message", JSONObject().apply { put("minVersion", 1); put("maxVersion", 1) })
                })
            }.toString()

            postJson(
                url = "$sessionBaseUrl/sdp",
                gsToken = gsToken,
                bodyJson = sdpPostBody
            )

            val remoteSdp = pollGetJsonUntilPresent(
                url = "$sessionBaseUrl/sdp",
                gsToken = gsToken,
                stepName = "SDP Answer"
            ).let { sdpJson ->
                val exchangeStr = sdpJson.optString("exchangeResponse", "")
                if (exchangeStr.isNotEmpty()) {
                    JSONObject(exchangeStr).optString("sdp", "")
                } else {
                    sdpJson.optString("sdp", "")
                }
            }

            if (remoteSdp.isBlank()) {
                throw IllegalStateException("El servidor de Xbox devolvió un SDP vacío.")
            }

            // 7. Intercambio ICE y limpieza del prefijo "a="
            _phase.value = StreamLaunchPhase.Initializing("Estableciendo túneles ICE / Teredo de baja latencia...")
            val localCandidates = gatherLocalIceCandidates()
            val icePostBody = JSONObject().apply {
                put("messageType", "iceCandidate")
                put("candidates", JSONArray(localCandidates))
            }.toString()

            postJson(
                url = "$sessionBaseUrl/ice",
                gsToken = gsToken,
                bodyJson = icePostBody
            )

            val iceRespJson = pollGetJsonUntilPresent(
                url = "$sessionBaseUrl/ice",
                gsToken = gsToken,
                stepName = "ICE Candidates"
            )

            val cleanRemoteCandidates = parseAndStripAEqualsPrefix(iceRespJson)

            // 8. Entregar SDP y Candidatos ICE al motor WebRTC / NDK
            _phase.value = StreamLaunchPhase.Initializing("Conectando flujo de video a superficie Zero-Copy...")
            applyRemoteSdpAndIce(remoteSdp, cleanRemoteCandidates, validSurface)

            _phase.value = StreamLaunchPhase.ReadyForWebRtc(
                sessionId = sessionPath.substringAfterLast("/"),
                sessionPath = sessionPath,
                serverSdpOfferOrAnswer = remoteSdp,
                serverIceCandidates = cleanRemoteCandidates
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val safeReason = e.message?.takeIf { it.isNotBlank() && it != "null" }
                ?: "${e::class.java.simpleName} (${e.cause?.message ?: e.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "error de inicio"})"

            _phase.value = StreamLaunchPhase.Failed(
                title = "No se pudo iniciar el Stream",
                technicalDetail = safeReason,
                canFallbackToMotor2 = true
            )
        }
    }

    private suspend fun pollSessionUntilProvisioned(
        sessionBaseUrl: String,
        gsToken: String,
        isXHomeConsole: Boolean,
        longPlayToken: String?
    ) {
        var connectSent = false
        repeat(180) {
            val req = Request.Builder()
                .url("$sessionBaseUrl/state")
                .get()
                .header("Authorization", "Bearer $gsToken")
                .header("Accept", "application/json")
                .header("x-ms-device-info", buildDeviceInfoHeaderJson())
                .build()

            val stateJson = GodModeAuthOrchestrator.httpClient.newCall(req).execute().use { res ->
                val txt = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Error en /state (HTTP ${res.code}): ${extractXboxErrorDetail(txt)}")
                }
                if (txt.isBlank()) JSONObject() else JSONObject(txt)
            }

            val state = stateJson.optString("state", "")
            val waitSec = stateJson.optInt("estimatedTotalWaitTimeInSeconds", 0)

            when (state) {
                "WaitingForResources" -> {
                    _phase.value = StreamLaunchPhase.Initializing(
                        detail = "En cola de servidores Xbox...",
                        queueWaitSeconds = waitSec.takeIf { it > 0 }
                    )
                }
                "Provisioning" -> {
                    _phase.value = StreamLaunchPhase.Initializing("Aprovisionando hardware Xbox Series X...")
                }
                "ReadyToConnect" -> {
                    if (isXHomeConsole) {
                        return
                    }
                    if (!connectSent) {
                        connectSent = true
                        _phase.value = StreamLaunchPhase.Initializing("Autenticando sesión en el blade (/connect)...")
                        val connectBody = JSONObject().apply {
                            if (!longPlayToken.isNullOrBlank()) {
                                put("userToken", longPlayToken)
                            }
                        }.toString()
                        postJson("$sessionBaseUrl/connect", gsToken, connectBody)
                    }
                }
                "Provisioned" -> {
                    return
                }
                "Failed", "Cancelled" -> {
                    val errDetails = stateJson.optJSONObject("errorDetails")
                    val code = errDetails?.optString("code").orEmpty()
                    val msg = errDetails?.optString("message").orEmpty()
                    throw IllegalStateException("Sesión abortada por Xbox ($state): $code $msg".trim())
                }
            }
            delay(1000L)
        }
        throw IllegalStateException("Tiempo de espera agotado esperando el estado Provisioned.")
    }

    private suspend fun pollGetJsonUntilPresent(
        url: String,
        gsToken: String,
        stepName: String
    ): JSONObject {
        repeat(35) {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $gsToken")
                .header("Accept", "application/json")
                .header("x-ms-device-info", buildDeviceInfoHeaderJson())
                .build()

            GodModeAuthOrchestrator.httpClient.newCall(req).execute().use { res ->
                if (res.code == 204) {
                    // HTTP 204 No Content
                } else if (res.isSuccessful) {
                    val txt = res.body?.string().orEmpty().trim()
                    if (txt.isNotEmpty() && txt != "{}") {
                        return JSONObject(txt)
                    }
                } else {
                    throw IllegalStateException("Fallo en GET $stepName (HTTP ${res.code})")
                }
            }
            delay(1000L)
        }
        throw IllegalStateException("Timeout esperando respuesta de $stepName.")
    }

    private fun postJson(url: String, gsToken: String, bodyJson: String) {
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $gsToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-ms-device-info", buildDeviceInfoHeaderJson())
            .build()

        GodModeAuthOrchestrator.httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful && res.code != 202 && res.code != 204) {
                val errTxt = res.body?.string().orEmpty()
                throw IllegalStateException("POST ${url.substringAfterLast("/")} falló (HTTP ${res.code}): ${extractXboxErrorDetail(errTxt)}")
            }
        }
    }

    private fun parseAndStripAEqualsPrefix(iceJson: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val exchangeStr = iceJson.optString("exchangeResponse", "")
        val candidatesArr = when {
            exchangeStr.trim().startsWith("[") -> JSONArray(exchangeStr)
            exchangeStr.trim().startsWith("{") -> JSONObject(exchangeStr).optJSONArray("candidates")
            else -> iceJson.optJSONArray("candidates")
        } ?: return out

        for (i in 0 until candidatesArr.length()) {
            val item = candidatesArr.optJSONObject(i)
            val rawCand = item?.optString("candidate") ?: candidatesArr.optString(i, "")
            if (rawCand.isNotBlank() && !rawCand.contains("end-of-candidates")) {
                val clean = if (rawCand.startsWith("a=")) rawCand.removePrefix("a=") else rawCand
                out.add(clean)
            }
        }
        return out
    }

    private fun resolveTitleIdFromProductId(baseRegionUri: String, gsToken: String, productId: String): String? {
        val req = Request.Builder()
            .url("$baseRegionUri/v2/titles")
            .get()
            .header("Authorization", "Bearer $gsToken")
            .header("Accept", "application/json")
            .build()

        return try {
            GodModeAuthOrchestrator.httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val root = JSONObject(res.body?.string() ?: "{}")
                val results = root.optJSONArray("results") ?: return null
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val pId = item.optString("productId").ifEmpty {
                        item.optJSONObject("details")?.optString("productId").orEmpty()
                    }
                    if (pId.equals(productId, ignoreCase = true)) {
                        return item.optString("titleId").takeIf { it.isNotEmpty() }
                    }
                }
                null
            }
        } catch (_: Exception) {
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
