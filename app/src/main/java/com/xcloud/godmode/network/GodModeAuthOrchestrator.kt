package com.xcloud.godmode.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit

// ============================================================================
// 1. MODELOS DE ESTADO Y SESIÓN MULTI-OFFERING
// ============================================================================

enum class CloudOfferingTier(val offeringId: String, val label: String) {
    ULTIMATE("xgpuweb", "Xbox Game Pass Ultimate"),
    FREE_TO_PLAY("xgpuwebf2p", "Cloud Free-to-Play / Insider"),
    REMOTE_PLAY_ONLY("xhome", "Consola Remota (xHome)")
}

data class XboxRegionServer(
    val name: String,
    val baseUri: String,
    val isDefault: Boolean
)

data class AuthenticatedSession(
    val gamertag: String,
    val xuid: String,
    val uhs: String,
    val avatarUrl: String?,
    val gamerscore: String?,
    val xbl3Header: String,          // "XBL3.0 x=<uhs>;<webXstsToken>"
    val xCloudGsToken: String?,      // Bearer token para xCloud (/v2/titles, /v5/sessions/cloud/play)
    val xHomeGsToken: String?,       // Bearer token para Consola Remota (/v6/servers/home)
    val longPlayToken: String?,      // LPT (msalToken) para /connect en sesiones xCloud
    val refreshToken: String,
    val market: String,
    val activeTier: CloudOfferingTier,
    val regions: List<XboxRegionServer>,
    val defaultRegionUri: String,
    val expiresAtEpochMs: Long
)

data class QrDeviceCodeTicket(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val qrTargetUrl: String,         // https://www.microsoft.com/link?otc=<userCode>
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int
)

sealed class AuthUiState {
    object Idle : AuthUiState()
    data class Loading(val stepDescription: String) : AuthUiState()
    data class QrReady(val ticket: QrDeviceCodeTicket, val secondsRemaining: Int) : AuthUiState()
    data class AwaitingBrowser(val authorizeUrl: String) : AuthUiState()
    data class Success(val session: AuthenticatedSession) : AuthUiState()
    data class Error(val message: String, val canRetryWithBypass: Boolean = false) : AuthUiState()
}

// ============================================================================
// 3. ORQUESTADOR SINGLETON CON DOH, BYPASS REACTIVO 403 Y CASCADA MULTI-OFFERING
// ============================================================================

object GodModeAuthOrchestrator {
    // Client ID oficial para Xbox Consumer OAuth (login.live.com)
    private const val DEVICE_CODE_CLIENT_ID = PrimaryAppIdAuthEngine.PRIMARY_APP_ID
    private const val DEVICE_CODE_SCOPES = PrimaryAppIdAuthEngine.MBI_SSL_SCOPE

    // Client ID oficial para XAL / PKCE Navegador
    private const val XAL_CLIENT_ID = PrimaryAppIdAuthEngine.PRIMARY_APP_ID
    private const val XAL_REDIRECT_URI = "ms-xal-${PrimaryAppIdAuthEngine.PRIMARY_APP_ID}://auth"
    private const val XAL_SCOPES = PrimaryAppIdAuthEngine.MBI_SSL_SCOPE

    // Diccionario de IPs regionales para el Bypass Reactivo 403
    val BYPASS_REGION_IPS = mapOf(
        "Automatic" to "",
        "BrazilSouth" to "169.150.198.66",
        "ChileCentral" to "20.143.92.1",
        "EastUS" to "143.244.47.65",
        "JapanEast" to "138.199.21.239",
        "KoreaCentral" to "121.125.60.151",
        "EuropePoland" to "45.134.212.66"
    )

    // User-Agent de Smart TV Tizen 7.0 para desbloquear perfiles de alto bitrate
    private const val TIZEN_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) 149.0.0.0/7.0 TV Safari/537.36"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollMutex = Mutex()
    private var pollingJob: Job? = null
    private var countdownJob: Job? = null

    @Volatile
    var selectedBypassRegion: String = "BrazilSouth"
    @Volatile
    var reactiveBypassTriggered: Boolean = false

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    @Volatile
    private var activePkceVerifier: String? = null
    @Volatile
    private var activeClientIdUsed: String = DEVICE_CODE_CLIENT_ID

    // Cliente OkHttp con Cloudflare DNS over HTTPS (DoH 1.1.1.1)
    val httpClient: OkHttpClient by lazy {
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val dohDns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))
            )
            .build()

        bootstrapClient.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        dohDns.lookup(hostname)
                    } catch (_: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .build()
    }

    fun getProofKeyJwk(): JSONObject {
        return JoseCripto.getPublicJwk()
    }

    fun signXboxRequest(url: String, authHeader: String = "", bodyJson: String): String {
        return JoseCripto.generateSignatureHeader("POST", url, bodyJson, authHeader)
    }

    // ------------------------------------------------------------------------
    // VÍA 1: SMART TV / QR (OAUTH 2.0 v2.0 DEVICE CODE — CERO EXPIRACIÓN FALSA)
    // ------------------------------------------------------------------------
    fun startQrDeviceCodeFlow() {
        cancelActiveJobs()
        _authState.value = AuthUiState.Loading("Generando código QR oficial de Xbox...")

        pollingJob = scope.launch {
            try {
                // Intento Nivel 1: Cascada de IDs en endpoint de consumo MSA
                var ticket: QrDeviceCodeTicket? = null
                val cascadeIds = listOf(
                    PrimaryAppIdAuthEngine.PRIMARY_APP_ID,
                    PrimaryAppIdAuthEngine.FALLBACK_1_APP_ID,
                    PrimaryAppIdAuthEngine.FALLBACK_2_APP_ID
                )

                for (appId in cascadeIds) {
                    try {
                        val formBody = FormBody.Builder()
                            .add("client_id", appId)
                            .add("scope", PrimaryAppIdAuthEngine.MBI_SSL_SCOPE)
                            .build()

                        val req = Request.Builder()
                            .url("https://login.live.com/oauth20_device.srf")
                            .post(formBody)
                            .header("Accept", "application/json")
                            .build()

                        val resStr = httpClient.newCall(req).execute().use { res ->
                            if (!res.isSuccessful) null else res.body?.string()
                        } ?: continue

                        val json = JSONObject(resStr)
                        val userCode = json.optString("user_code")
                        val deviceCode = json.optString("device_code")
                        if (userCode.isBlank() || deviceCode.isBlank()) continue

                        val verificationUri = json.optString("verification_uri", PrimaryAppIdAuthEngine.CONSUMER_REMOTE_CONNECT_URL)
                            .let { if (it.contains("microsoft.com/link")) PrimaryAppIdAuthEngine.CONSUMER_REMOTE_CONNECT_URL else it }
                        val expiresIn = json.optInt("expires_in", 900)
                        val intervalSec = json.optInt("interval", 5).coerceAtLeast(5)

                        activeClientIdUsed = appId
                        ticket = QrDeviceCodeTicket(
                            userCode = userCode,
                            deviceCode = deviceCode,
                            verificationUri = verificationUri,
                            qrTargetUrl = PrimaryAppIdAuthEngine.getRemoteConnectCompleteUrl(userCode),
                            expiresInSeconds = expiresIn,
                            pollIntervalSeconds = intervalSec
                        )
                        break
                    } catch (_: Exception) {
                        continue
                    }
                }

                if (ticket != null) {
                    _authState.value = AuthUiState.QrReady(ticket, ticket.expiresInSeconds)
                    startVisualCountdown(ticket)
                    pollDeviceCodeUntilAuthorized(ticket)
                } else {
                    // Nivel 2: Fallback Instantáneo (URL preautorizada directa)
                    val directUrl = prepareBrowserPkceUrl()
                    val fallbackTicket = QrDeviceCodeTicket(
                        userCode = "ACCESO DIRECTO",
                        deviceCode = "",
                        verificationUri = "login.live.com",
                        qrTargetUrl = directUrl,
                        expiresInSeconds = 900,
                        pollIntervalSeconds = 999
                    )
                    _authState.value = AuthUiState.QrReady(fallbackTicket, 900)
                    startVisualCountdown(fallbackTicket)
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _authState.value = AuthUiState.Error("Error al generar QR: ${e.message}")
            }
        }
    }

    private fun startVisualCountdown(ticket: QrDeviceCodeTicket) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var remaining = ticket.expiresInSeconds
            while (remaining > 0 && isActive) {
                delay(1000L)
                remaining--
                val current = _authState.value
                if (current is AuthUiState.QrReady) {
                    _authState.value = current.copy(secondsRemaining = remaining)
                }
            }
            if (remaining <= 0 && _authState.value is AuthUiState.QrReady) {
                cancelActiveJobs()
                _authState.value = AuthUiState.Error("El código QR superó los 15 minutos. Genera uno nuevo.")
            }
        }
    }

    private suspend fun pollDeviceCodeUntilAuthorized(ticket: QrDeviceCodeTicket) {
        var currentIntervalMs = (ticket.pollIntervalSeconds * 1000L) + 500L

        while (currentCoroutineContext().isActive) {
            delay(currentIntervalMs)

            pollMutex.withLock {
                val form = FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("device_code", ticket.deviceCode)
                    .add("client_id", DEVICE_CODE_CLIENT_ID)
                    .build()

                val req = Request.Builder()
                    .url("https://login.live.com/oauth20_token.srf")
                    .post(form)
                    .header("Accept", "application/json")
                    .build()

                try {
                    httpClient.newCall(req).execute().use { res ->
                        val bodyTxt = res.body?.string() ?: "{}"
                        val data = JSONObject(bodyTxt)

                        if (res.isSuccessful && data.has("access_token")) {
                            countdownJob?.cancel()
                            val accessToken = data.getString("access_token")
                            val refreshToken = data.optString("refresh_token", "")
                            _authState.value = AuthUiState.Loading("¡QR verificado! Negociando tokens XSTS y GSSV...")
                            completeTokenChain(accessToken, refreshToken, isOAuthV2Token = true)
                            return
                        }

                        // Manejo estricto RFC 8628 para no abortar por authorization_pending o slow_down
                        val errCode = data.optString("error", "")
                        when (errCode) {
                            "authorization_pending", "" -> {
                                // El usuario sigue en su teléfono; continuar esperando en silencio.
                            }
                            "slow_down" -> {
                                // Microsoft solicita espaciar las consultas 5 segundos adicionales.
                                currentIntervalMs += 5000L
                            }
                            "authorization_declined" -> {
                                countdownJob?.cancel()
                                _authState.value = AuthUiState.Error("El inicio de sesión fue rechazado en el teléfono.")
                                return
                            }
                            "expired_token" -> {
                                countdownJob?.cancel()
                                _authState.value = AuthUiState.Error("El código QR expiró en el servidor. Genera uno nuevo.")
                                return
                            }
                            else -> {
                                // Ignorar respuestas transitorias mientras el contador siga activo
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignorar microcortes de red móviles/Wi-Fi durante el polling
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // VÍA 2: NAVEGADOR SEGURO / PASSKEYS (PKCE + DEEP LINK ms-xal://)
    // ------------------------------------------------------------------------
    fun prepareBrowserPkceUrl(): String {
        cancelActiveJobs()
        activeClientIdUsed = XAL_CLIENT_ID
        val verifierBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        activePkceVerifier = verifier

        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

        val url = "https://login.live.com/oauth20_authorize.srf".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", XAL_CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("approval_prompt", "auto")
            .addQueryParameter("scope", XAL_SCOPES)
            .addQueryParameter("redirect_uri", XAL_REDIRECT_URI)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("display", "android_phone")
            .build()
            .toString()

        _authState.value = AuthUiState.AwaitingBrowser(url)
        return url
    }

    fun exchangeBrowserRedirectUrlOrCode(redirectUrlOrCode: String) {
        cancelActiveJobs()
        _authState.value = AuthUiState.Loading("Canjeando código de autorización OAuth...")

        pollingJob = scope.launch {
            try {
                val code = Regex("[?&]code=([^&#\\s]+)").find(redirectUrlOrCode)?.groupValues?.get(1)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: redirectUrlOrCode.trim()

                val formBuilder = FormBody.Builder()
                    .add("client_id", XAL_CLIENT_ID)
                    .add("code", code)
                    .add("grant_type", "authorization_code")
                    .add("redirect_uri", XAL_REDIRECT_URI)
                    .add("scope", XAL_SCOPES)

                activePkceVerifier?.let { formBuilder.add("code_verifier", it) }

                val req = Request.Builder()
                    .url("https://login.live.com/oauth20_token.srf")
                    .post(formBuilder.build())
                    .build()

                httpClient.newCall(req).execute().use { res ->
                    val bodyStr = res.body?.string() ?: "{}"
                    if (!res.isSuccessful) {
                        throw IllegalStateException("Código OAuth expirado o inválido (${res.code})")
                    }
                    val json = JSONObject(bodyStr)
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.optString("refresh_token", "")
                    completeTokenChain(accessToken, refreshToken, isOAuthV2Token = false)
                }
            } catch (e: Exception) {
                _authState.value = AuthUiState.Error("Error al validar sesión del navegador: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------------
    // CADENA UNIFICADA: USER TOKEN (d= / t=) -> DUAL XSTS -> CASCADA GSSV ANTI-403
    // ------------------------------------------------------------------------
    private suspend fun completeTokenChain(
        msaAccessToken: String,
        msaRefreshToken: String,
        isOAuthV2Token: Boolean
    ) {
        try {
            val prefixes = if (isOAuthV2Token) listOf("d=", "t=") else listOf("t=", "d=")
            var userToken: String? = null

            for (prefix in prefixes) {
                val payload = JSONObject().apply {
                    put("Properties", JSONObject().apply {
                        put("AuthMethod", "RPS")
                        put("SiteName", "user.auth.xboxlive.com")
                        put("RpsTicket", "$prefix$msaAccessToken")
                    })
                    put("RelyingParty", "http://auth.xboxlive.com")
                    put("TokenType", "JWT")
                }

                val req = Request.Builder()
                    .url("https://user.auth.xboxlive.com/user/authenticate")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("x-xbl-contract-version", "1")
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val data = JSONObject(res.body?.string() ?: "{}")
                        userToken = data.optString("Token").takeIf { it.isNotEmpty() }
                    }
                }
                if (userToken != null) break
            }

            val validUserToken = userToken
                ?: throw IllegalStateException("user.auth.xboxlive.com rechazó el ticket RPS.")

            val webXsts = authorizeXsts(validUserToken, "http://xboxlive.com")
            val gssvXsts = authorizeXsts(validUserToken, "http://gssv.xboxlive.com/")

            val xui = webXsts.optJSONObject("DisplayClaims")
                ?.optJSONArray("xui")
                ?.optJSONObject(0) ?: JSONObject()

            val uhs = xui.optString("uhs", "")
            val gamertag = xui.optString("gtg", "Spartan")
            val xuid = xui.optString("xid", "")
            val webTokenStr = webXsts.getString("Token")
            val gssvTokenStr = gssvXsts.getString("Token")
            val xbl3Header = "XBL3.0 x=$uhs;$webTokenStr"

            _authState.value = AuthUiState.Loading("Autenticando en servidores de Xbox Cloud y Consola...")

            val gssvResult = executeGssvCascadeWithReactiveBypass(gssvTokenStr)

            val lptToken = if (msaRefreshToken.isNotEmpty()) {
                fetchLongPlayToken(msaRefreshToken)
            } else null

            val profileExtras = fetchPublicGamerpic(gamertag)

            val finalSession = AuthenticatedSession(
                gamertag = gamertag,
                xuid = xuid,
                uhs = uhs,
                avatarUrl = profileExtras.first,
                gamerscore = profileExtras.second,
                xbl3Header = xbl3Header,
                xCloudGsToken = gssvResult.xCloudGsToken,
                xHomeGsToken = gssvResult.xHomeGsToken,
                longPlayToken = lptToken,
                refreshToken = msaRefreshToken,
                market = gssvResult.market,
                activeTier = gssvResult.activeTier,
                regions = gssvResult.regions,
                defaultRegionUri = gssvResult.defaultRegionUri,
                expiresAtEpochMs = System.currentTimeMillis() + (gssvResult.durationSeconds * 1000L)
            )

            _authState.value = AuthUiState.Success(finalSession)

        } catch (e: Exception) {
            _authState.value = AuthUiState.Error(e.message ?: "Error desconocido en cadena XSTS/GSSV")
        }
    }

    private fun authorizeXsts(userToken: String, relyingParty: String): JSONObject {
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().apply { put(userToken) })
            })
            put("RelyingParty", relyingParty)
            put("TokenType", "JWT")
        }

        val req = Request.Builder()
            .url("https://xsts.auth.xboxlive.com/xsts/authorize")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("x-xbl-contract-version", "1")
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(req).execute().use { res ->
            val txt = res.body?.string() ?: "{}"
            if (!res.isSuccessful) {
                val errJson = runCatching { JSONObject(txt) }.getOrNull()
                val xErr = errJson?.optLong("XErr", 0L) ?: 0L
                val msg = when (xErr) {
                    2148916233L -> "Esta cuenta de Microsoft aún no tiene un perfil de Xbox (Gamertag). Entra una vez a xbox.com."
                    2148916235L -> "Xbox Live no está disponible en la región de esta cuenta."
                    2148916238L -> "Cuenta menor de edad: requiere que un adulto la agregue a una familia en Xbox."
                    else -> "Error XSTS (${res.code}) en $relyingParty"
                }
                throw IllegalStateException(msg)
            }
            return JSONObject(txt)
        }
    }

    private data class GssvCascadeResult(
        val xCloudGsToken: String?,
        val xHomeGsToken: String?,
        val market: String,
        val activeTier: CloudOfferingTier,
        val regions: List<XboxRegionServer>,
        val defaultRegionUri: String,
        val durationSeconds: Long
    )

    private fun executeGssvCascadeWithReactiveBypass(gssvXstsToken: String): GssvCascadeResult {
        val xHomeJson = callGssvEndpoint(
            url = "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user",
            gssvXstsToken = gssvXstsToken,
            offeringId = "xhome",
            injectBypassIp = false
        ) ?: callGssvEndpoint(
            url = "https://xhome.gssv-play-prodz.xboxlive.com/v2/login/user",
            gssvXstsToken = gssvXstsToken,
            offeringId = "xhome",
            injectBypassIp = false
        )

        var cloudTier = CloudOfferingTier.ULTIMATE
        var cloudJson = callGssvEndpoint(
            url = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
            gssvXstsToken = gssvXstsToken,
            offeringId = "xgpuweb",
            injectBypassIp = reactiveBypassTriggered
        )

        if (cloudJson == null && !reactiveBypassTriggered) {
            reactiveBypassTriggered = true
            cloudJson = callGssvEndpoint(
                url = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
                gssvXstsToken = gssvXstsToken,
                offeringId = "xgpuweb",
                injectBypassIp = true
            )
        }

        if (cloudJson == null) {
            cloudTier = CloudOfferingTier.FREE_TO_PLAY
            cloudJson = callGssvEndpoint(
                url = "https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user",
                gssvXstsToken = gssvXstsToken,
                offeringId = "xgpuwebf2p",
                injectBypassIp = true
            )
        }

        val primaryJson = cloudJson ?: xHomeJson
            ?: throw IllegalStateException("GSSV rechazó todos los offerings. Verifica tu conexión o cambia la región de Bypass.")

        if (cloudJson == null) {
            cloudTier = CloudOfferingTier.REMOTE_PLAY_ONLY
        }

        val regionsArr = primaryJson.optJSONObject("offeringSettings")?.optJSONArray("regions") ?: JSONArray()
        val parsedRegions = mutableListOf<XboxRegionServer>()
        for (i in 0 until regionsArr.length()) {
            val r = regionsArr.getJSONObject(i)
            parsedRegions.add(
                XboxRegionServer(
                    name = r.optString("name", "Region-$i"),
                    baseUri = r.optString("baseUri", ""),
                    isDefault = r.optBoolean("isDefault", false)
                )
            )
        }

        val defaultUri = parsedRegions.firstOrNull { it.isDefault }?.baseUri
            ?: parsedRegions.firstOrNull()?.baseUri
            ?: "https://brs.core.gssv-play-prod.xboxlive.com"

        return GssvCascadeResult(
            xCloudGsToken = cloudJson?.optString("gsToken"),
            xHomeGsToken = xHomeJson?.optString("gsToken"),
            market = primaryJson.optString("market", "US"),
            activeTier = cloudTier,
            regions = parsedRegions,
            defaultRegionUri = defaultUri,
            durationSeconds = primaryJson.optLong("durationInSeconds", 14400L)
        )
    }

    private fun callGssvEndpoint(
        url: String,
        gssvXstsToken: String,
        offeringId: String,
        injectBypassIp: Boolean
    ): JSONObject? {
        val body = JSONObject().apply {
            put("token", gssvXstsToken)
            put("offeringId", offeringId)
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store, must-revalidate, no-cache")
            .header("x-gssv-client", "XboxComBrowser")
            .header("User-Agent", TIZEN_USER_AGENT)

        if (injectBypassIp) {
            val ip = BYPASS_REGION_IPS[selectedBypassRegion] ?: "169.150.198.66"
            if (ip.isNotEmpty()) {
                reqBuilder.header("X-Forwarded-For", ip)
            }
        }

        return try {
            httpClient.newCall(reqBuilder.build()).execute().use { res ->
                if (res.isSuccessful) {
                    JSONObject(res.body?.string() ?: "{}")
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchLongPlayToken(refreshToken: String): String? {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", activeClientIdUsed)
            .add("scope", PrimaryAppIdAuthEngine.MBI_SSL_SCOPE)
            .build()

        val req = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(form)
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                JSONObject(res.body?.string() ?: "{}").optString("access_token").takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchPublicGamerpic(gamertag: String): Pair<String?, String?> {
        val encodedGt = java.net.URLEncoder.encode(gamertag, "UTF-8")
        val req = Request.Builder()
            .url("https://peoplehub-public.xboxlive.com/people/gt($encodedGt)")
            .header("x-xbl-contract-version", "3")
            .header("Accept-Language", "es-CL,es;q=0.9,en-US;q=0.8")
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return Pair(null, null)
                val person = JSONObject(res.body?.string() ?: "{}")
                    .optJSONArray("people")?.optJSONObject(0)
                Pair(
                    person?.optString("displayPicRaw")?.takeIf { it.isNotEmpty() },
                    person?.optString("gamerScore")?.takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    fun cancelActiveJobs() {
        pollingJob?.cancel()
        countdownJob?.cancel()
        pollingJob = null
        countdownJob = null
    }

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
