package com.xcloud.godmode.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

data class CompleteXboxSession(
    val gamertag: String,
    val xuid: String,
    val uhs: String,
    val clientIdUsed: String,
    val msaAccessToken: String,
    val msaRefreshToken: String,
    val deviceToken: String,
    val webXstsToken: String,
    val gssvXstsToken: String,
    val xgpuGsToken: String,
    val xhomeGsToken: String?,
    val f2pGsToken: String?,
    val lptToken: String?,
    val preferredBaseUri: String,
    val regionsJson: String,
    val gsTokenExpiresAtEpochMs: Long
) {
    fun isStreamingReady(safetyMarginMs: Long = 900_000L): Boolean {
        val now = CloudTimeSynchronizer.getCloudEpochMs()
        return xgpuGsToken.isNotBlank() &&
            preferredBaseUri.isNotBlank() &&
            (now + safetyMarginMs) < gsTokenExpiresAtEpochMs
    }
}

data class ConsumerDeviceCodeTicket(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

sealed interface StreamReadyAuthResult {
    data class Authorized(
        val session: CompleteXboxSession,
        val transferTokenLpt: String
    ) : StreamReadyAuthResult

    data class InteractiveLoginRequired(val message: String) : StreamReadyAuthResult
    data class NetworkError(val message: String) : StreamReadyAuthResult
}

class AuthTokenRevokedException(message: String) : Exception(message)

interface SessionVaultPersistence {
    suspend fun loadSession(): CompleteXboxSession?
    suspend fun saveSession(session: CompleteXboxSession)
    suspend fun clearSession()
}

/**
 * UnifiedXboxAuthEngine: Complete 5-path Xbox Live & xCloud authentication engine.
 * Fully native, zero-webview, and free from Azure AD corporate device code restrictions.
 * Implements the full 6-link token chain with Proof-of-Possession signatures.
 */
class UnifiedXboxAuthEngine(
    private val storage: SessionVaultPersistence
) {
    private val mutex = Mutex()

    companion object {
        private const val TAG = "UnifiedXboxAuth"

        // Hierarchical App IDs (Principal, Fallback 1, Fallback 2)
        const val PRIMARY_APP_ID = PrimaryAppIdAuthEngine.PRIMARY_APP_ID
        const val FALLBACK_1_APP_ID = PrimaryAppIdAuthEngine.FALLBACK_1_APP_ID
        const val FALLBACK_2_APP_ID = PrimaryAppIdAuthEngine.FALLBACK_2_APP_ID

        // Backward compatibility constants
        const val XBOX_UNIFIED_APP_ID = PrimaryAppIdAuthEngine.FALLBACK_2_APP_ID
        const val XBOX_COMPANION_APP_ID = PrimaryAppIdAuthEngine.FALLBACK_1_APP_ID
        const val XBOX_UNIFIED_TITLE_ID = PrimaryAppIdAuthEngine.TITLE_ID_UNIFIED
        const val XBOX_REDIRECT_SCHEME = "ms-xal-$PRIMARY_APP_ID://auth"
        const val XBOX_COMPANION_REDIRECT_SCHEME = "ms-xal-$FALLBACK_1_APP_ID://auth"

        // Consumer scopes (bypassing corporate first-party Azure AD consent blocks)
        const val MBI_SSL_SCOPE = PrimaryAppIdAuthEngine.MBI_SSL_SCOPE
        const val LPT_TRANSFER_SCOPE = PrimaryAppIdAuthEngine.LPT_TRANSFER_SCOPE

        private const val WINDOWS_EPOCH_OFFSET = 11644473600L

        // Global event flow and singleton reference for deep link integration
        private val _authEvents = MutableSharedFlow<CompleteXboxSession>(extraBufferCapacity = 1)
        val authEvents: SharedFlow<CompleteXboxSession> = _authEvents.asSharedFlow()

        @Volatile
        var globalInstance: UnifiedXboxAuthEngine? = null
    }

    init {
        globalInstance = this
    }

    private val ecKeyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
    }

    val publicKeyJwk: JSONObject by lazy {
        val pub = ecKeyPair.public as ECPublicKey
        JSONObject().apply {
            put("kty", "EC")
            put("x", base64Url(to32Bytes(pub.w.affineX)))
            put("y", base64Url(to32Bytes(pub.w.affineY)))
            put("crv", "P-256")
            put("alg", "ES256")
            put("use", "sig")
        }
    }

    @Volatile
    private var pendingCodeVerifier: String? = null
    @Volatile
    private var pendingSisuSessionId: String? = null
    @Volatile
    private var pendingDeviceToken: String? = null

    /**
     * VÍA 1: Flujo Oficial XAL + SISU con Deep Link Automático (1 Clic en el teléfono).
     * Devuelve la URL oficial de Microsoft que se abre en CustomTab/Navegador y regresa sola a la app.
     * Utiliza la jerarquía de App IDs con fallback en cascada.
     */
    suspend fun prepareOneClickSisuLogin(): String = withContext(Dispatchers.IO) {
        val deviceToken = obtainSignedDeviceToken()
        val verifier = generatePkceVerifier()
        val challenge = generatePkceChallenge(verifier)
        val state = base64Url(UUID.randomUUID().toString().toByteArray())

        pendingCodeVerifier = verifier
        pendingDeviceToken = deviceToken

        try {
            PrimaryAppIdAuthEngine.executeWithHierarchyFallback(storage) { appId, redirectUri, titleId ->
                val body = JSONObject().apply {
                    put("AppId", appId)
                    put("TitleId", titleId)
                    put("RedirectUri", redirectUri)
                    put("DeviceToken", deviceToken)
                    put("Sandbox", "RETAIL")
                    put("TokenType", "code")
                    put("Offers", JSONArray().put(MBI_SSL_SCOPE))
                    put("Query", JSONObject().apply {
                        put("display", "android_phone")
                        put("code_challenge", challenge)
                        put("code_challenge_method", "S256")
                        put("state", state)
                        put("prompt", "select_account")
                    })
                }.toString()

                val url = "https://sisu.xboxlive.com/authenticate"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-xbl-contract-version", "1")
                    setRequestProperty("Signature", signRequest(url, "", body))
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()

                if (code !in 200..299) {
                    throw IllegalStateException("SISU Authenticate HTTP $code: $respText")
                }

                pendingSisuSessionId = conn.getHeaderField("X-SessionId")
                JSONObject(respText).getString("MsaOauthRedirect")
            }
        } catch (e: Exception) {
            Log.w(TAG, "SISU cascade failed (${e.message}), executing Direct MSA fallback...")
            val activeId = PrimaryAppIdAuthEngine.getActiveAppId(storage)
            val redirectUri = PrimaryAppIdAuthEngine.getRedirectUri(activeId)
            buildDirectMsaAuthorizeUrl(challenge, state, redirectUri, activeId)
        }
    }

    /**
     * Se ejecuta automáticamente cuando MainActivity recibe un Intent "ms-xal-<AppId>://auth/?code=..."
     */
    suspend fun handleAutomaticDeepLinkCode(
        authorizationCode: String,
        spoofedForwardedForIp: String? = null,
        incomingUri: String? = null
    ): CompleteXboxSession = withContext(Dispatchers.IO) {
        val verifier = pendingCodeVerifier ?: generatePkceVerifier()
        val detectedAppId = incomingUri?.let { PrimaryAppIdAuthEngine.extractAppIdFromUri(it) }

        PrimaryAppIdAuthEngine.executeWithHierarchyFallback(storage, forcedAppId = detectedAppId) { appId, redirectUri, _ ->
            val tokenForm = postUrlEncoded(
                url = "https://login.live.com/oauth20_token.srf",
                params = mapOf(
                    "client_id" to appId,
                    "grant_type" to "authorization_code",
                    "code" to authorizationCode,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to verifier,
                    "scope" to MBI_SSL_SCOPE
                )
            )

            val msaAccess = tokenForm.getString("access_token")
            val msaRefresh = tokenForm.getString("refresh_token")

            val fullSession = buildCompleteTokenPipeline(
                msaAccessToken = msaAccess,
                msaRefreshToken = msaRefresh,
                clientIdUsed = appId,
                spoofedIp = spoofedForwardedForIp
            )
            storage.saveSession(fullSession)
            _authEvents.tryEmit(fullSession)
            fullSession
        }
    }

    /**
     * VÍA 2: Flujo Oficial MSA Consumer Device Code (login.live.com/oauth20_device.srf).
     * Diseñado para TV / Consolas portátiles mediante QR sin tocar Azure AD.
     */
    suspend fun startConsumerQrDeviceCode(): ConsumerDeviceCodeTicket = withContext(Dispatchers.IO) {
        val activeAppId = PrimaryAppIdAuthEngine.getActiveAppId(storage)
        PrimaryAppIdAuthEngine.executeWithHierarchyFallback(storage, forcedAppId = activeAppId) { appId, _, _ ->
            val json = postUrlEncoded(
                url = "https://login.live.com/oauth20_device.srf",
                params = mapOf(
                    "client_id" to appId,
                    "scope" to MBI_SSL_SCOPE,
                    "response_type" to "device_code"
                )
            )
            val userCode = json.getString("user_code")
            ConsumerDeviceCodeTicket(
                deviceCode = json.getString("device_code"),
                userCode = userCode,
                verificationUri = PrimaryAppIdAuthEngine.CONSUMER_REMOTE_CONNECT_URL,
                expiresInSeconds = json.optInt("expires_in", 900),
                intervalSeconds = json.optInt("interval", 5).coerceAtLeast(3)
            )
        }
    }

    /**
     * Sondeo automático en segundo plano del código QR hasta que el usuario acepta en su móvil.
     */
    suspend fun pollConsumerQrUntilAuthorized(
        ticket: ConsumerDeviceCodeTicket,
        spoofedForwardedForIp: String? = null
    ): CompleteXboxSession = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + (ticket.expiresInSeconds * 1000L)
        var currentIntervalMs = ticket.intervalSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(currentIntervalMs)
            val result = runCatching {
                postUrlEncoded(
                    url = "https://login.live.com/oauth20_token.srf",
                    params = mapOf(
                        "client_id" to XBOX_UNIFIED_APP_ID,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code" to ticket.deviceCode
                    )
                )
            }

            if (result.isSuccess) {
                val tokenJson = result.getOrThrow()
                val access = tokenJson.getString("access_token")
                val refresh = tokenJson.getString("refresh_token")
                val session = buildCompleteTokenPipeline(
                    msaAccessToken = access,
                    msaRefreshToken = refresh,
                    clientIdUsed = XBOX_UNIFIED_APP_ID,
                    spoofedIp = spoofedForwardedForIp
                )
                storage.saveSession(session)
                _authEvents.tryEmit(session)
                return@withContext session
            } else {
                val msg = result.exceptionOrNull()?.message.orEmpty()
                if (msg.contains("slow_down")) {
                    currentIntervalMs += 5_000L
                } else if (!msg.contains("authorization_pending")) {
                    throw IllegalStateException("Autorización QR cancelada o expirada: $msg")
                }
            }
        }
        throw IllegalStateException("El tiempo del código QR ha expirado.")
    }

    /**
     * VÍA 3: Servidor Loopback Local Automático (http://127.0.0.1:PORT/callback RFC 8252).
     * Captura el código en segundo plano y regresa automáticamente a la app sin que el usuario copie nada.
     */
    suspend fun startZeroTouchLoopbackLogin(
        onOpenBrowserUrl: (String) -> Unit,
        spoofedForwardedForIp: String? = null
    ): CompleteXboxSession = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val redirectUri = "http://127.0.0.1:$port/callback"
        val verifier = generatePkceVerifier()
        val challenge = generatePkceChallenge(verifier)
        val state = base64Url(UUID.randomUUID().toString().toByteArray())

        val authUrl = buildDirectMsaAuthorizeUrl(challenge, state, redirectUri)
        onOpenBrowserUrl(authUrl)

        val code = serverSocket.use { server ->
            server.soTimeout = 180_000 // 3 minutos
            val client = server.accept()
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine().orEmpty()
                val extractedCode = extractQueryParam(requestLine, "code")

                val htmlResponse = """
                    HTTP/1.1 200 OK
                    Content-Type: text/html; charset=utf-8
                    Connection: close

                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <script>
                            window.location.replace("xcloudgodmode://auth-complete");
                            setTimeout(function() { window.close(); }, 300);
                        </script>
                    </head>
                    <body style="background:#101216;color:#fff;font-family:sans-serif;text-align:center;padding-top:20%;">
                        <h2 style="color:#4CAF50;">¡Cuenta Xbox vinculada con éxito!</h2>
                        <p>Regresando automáticamente a God Mode...</p>
                    </body>
                    </html>
                """.trimIndent()

                PrintWriter(socket.getOutputStream(), true).apply {
                    print(htmlResponse)
                    flush()
                }
                extractedCode ?: throw IllegalStateException("No se recibió código OAuth en loopback.")
            }
        }

        val tokenForm = postUrlEncoded(
            url = "https://login.live.com/oauth20_token.srf",
            params = mapOf(
                "client_id" to XBOX_UNIFIED_APP_ID,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "code_verifier" to verifier,
                "scope" to MBI_SSL_SCOPE
            )
        )

        val session = buildCompleteTokenPipeline(
            msaAccessToken = tokenForm.getString("access_token"),
            msaRefreshToken = tokenForm.getString("refresh_token"),
            clientIdUsed = XBOX_UNIFIED_APP_ID,
            spoofedIp = spoofedForwardedForIp
        )
        storage.saveSession(session)
        _authEvents.tryEmit(session)
        session
    }

    /**
     * VÍA 5 (ORQUESTADOR DE JUEGO): Se invoca SIEMPRE al pulsar "JUGAR AHORA".
     * Garantiza que el gsToken y el Long Play Token (LPT) estén frescos antes de iniciar el stream.
     */
    suspend fun ensureReadyForStreamLaunch(
        spoofedForwardedForIp: String? = null
    ): StreamReadyAuthResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val saved = storage.loadSession()
                ?: return@withContext StreamReadyAuthResult.InteractiveLoginRequired(
                    "Inicia sesión con tu cuenta de Xbox para comenzar a jugar."
                )

            try {
                val validSession = if (saved.isStreamingReady()) {
                    saved
                } else {
                    val currentEpoch = System.currentTimeMillis()
                    val tokenAge = currentEpoch - (saved.gsTokenExpiresAtEpochMs - 14_400_000L)
                    if (saved.xgpuGsToken.isNotBlank() && tokenAge in 0..60_000L) {
                        saved
                    } else if (saved.msaRefreshToken.isBlank()) {
                        storage.clearSession()
                        return@withContext StreamReadyAuthResult.InteractiveLoginRequired(
                            "Tu sesión ha expirado. Vincula tu cuenta con 1 clic."
                        )
                    } else {
                        Log.d(TAG, "Renovando silenciosamente token en login.live.com...")
                        val refreshedJson = postUrlEncoded(
                            url = "https://login.live.com/oauth20_token.srf",
                            params = mapOf(
                                "client_id" to saved.clientIdUsed,
                                "grant_type" to "refresh_token",
                                "refresh_token" to saved.msaRefreshToken,
                                "scope" to MBI_SSL_SCOPE
                            )
                        )
                        val newAccess = refreshedJson.getString("access_token")
                        val newRefresh = refreshedJson.optString("refresh_token")
                            .ifBlank { saved.msaRefreshToken }

                        val rebuilt = buildCompleteTokenPipeline(
                            msaAccessToken = newAccess,
                            msaRefreshToken = newRefresh,
                            clientIdUsed = saved.clientIdUsed,
                            spoofedIp = spoofedForwardedForIp
                        )
                        storage.saveSession(rebuilt)
                        rebuilt
                    }
                }

                // Obtener el Long Play Token (LPT / Transfer Token) para la llamada /connect del stream
                val lptToken = runCatching {
                    val lptResp = postUrlEncoded(
                        url = "https://login.live.com/oauth20_token.srf",
                        params = mapOf(
                            "client_id" to validSession.clientIdUsed,
                            "grant_type" to "refresh_token",
                            "refresh_token" to validSession.msaRefreshToken,
                            "scope" to LPT_TRANSFER_SCOPE
                        )
                    )
                    lptResp.optString("access_token")
                }.getOrDefault(validSession.msaAccessToken)

                StreamReadyAuthResult.Authorized(
                    session = validSession,
                    transferTokenLpt = lptToken
                )
            } catch (e: AuthTokenRevokedException) {
                storage.clearSession()
                StreamReadyAuthResult.InteractiveLoginRequired(
                    "Tu sesión de Xbox necesita actualizarse. Inicia sesión con un toque."
                )
            } catch (e: Exception) {
                StreamReadyAuthResult.NetworkError(
                    "Error de conexión con Xbox Live: ${e.message}"
                )
            }
        }
    }

    /**
     * Forzado síncrono de la cadena de tokens para la barrera de frescura pre-stream o silent renewal.
     */
    suspend fun forceRefreshTokenChain(
        spoofedForwardedForIp: String? = null
    ): CompleteXboxSession? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val saved = storage.loadSession() ?: return@withContext null
            if (saved.msaRefreshToken.isBlank()) return@withContext null
            try {
                Log.d(TAG, "Forzando renovación de tokens...")
                val refreshedJson = postUrlEncoded(
                    url = "https://login.live.com/oauth20_token.srf",
                    params = mapOf(
                        "client_id" to saved.clientIdUsed,
                        "grant_type" to "refresh_token",
                        "refresh_token" to saved.msaRefreshToken,
                        "scope" to MBI_SSL_SCOPE
                    )
                )
                val newAccess = refreshedJson.getString("access_token")
                val newRefresh = refreshedJson.optString("refresh_token").ifBlank { saved.msaRefreshToken }
                val rebuilt = buildCompleteTokenPipeline(
                    msaAccessToken = newAccess,
                    msaRefreshToken = newRefresh,
                    clientIdUsed = saved.clientIdUsed,
                    spoofedIp = spoofedForwardedForIp
                )
                storage.saveSession(rebuilt)
                rebuilt
            } catch (e: Exception) {
                Log.w(TAG, "Error en renovación forzada: ${e.message}")
                null
            }
        }
    }

    /**
     * Ejecuta los Eslabones 2, 3, 4A, 4B, 5A y 5B completos.
     */
    fun buildCompleteTokenPipeline(
        msaAccessToken: String,
        msaRefreshToken: String,
        clientIdUsed: String,
        spoofedIp: String?
    ): CompleteXboxSession {
        val deviceToken = runCatching { obtainSignedDeviceToken() }.getOrDefault("")

        // Eslabón 3: RPS User Token (Prefijo obligatorio "d=" cuando viene de MBI_SSL)
        val formattedTicket = if (msaAccessToken.startsWith("d=") || msaAccessToken.startsWith("t=")) {
            msaAccessToken
        } else {
            "d=$msaAccessToken"
        }

        val rpsPayload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", formattedTicket)
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }.toString()

        val rpsResp = postJsonSigned("https://user.auth.xboxlive.com/user/authenticate", rpsPayload)
        val userToken = rpsResp.getString("Token")
        val uhs = rpsResp.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
            .getString("uhs")

        // Eslabón 4A: Web XSTS Token (Perfil, Gamertag, Logros)
        val webXsts = obtainXstsToken(userToken, deviceToken, "http://xboxlive.com")
        val webToken = webXsts.getString("Token")
        val xui = webXsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)
        val gamertag = xui.optString("gtg", "Xbox Player")
        val xuid = xui.optString("xid", "")

        // Eslabón 4B: GSSV XSTS Token (Streaming en la Nube)
        val gssvXsts = obtainXstsToken(userToken, deviceToken, "http://gssv.xboxlive.com/")
        val gssvToken = gssvXsts.getString("Token")

        val extraHeaders = mutableMapOf("x-gssv-client" to "XboxComBrowser")
        if (!spoofedIp.isNullOrBlank()) {
            extraHeaders["X-Forwarded-For"] = spoofedIp
        }

        // Eslabón 5A: gsToken para Game Pass Ultimate (xgpuweb) con autoreparación geográfica
        val xgpuBody = JSONObject().apply {
            put("token", gssvToken)
            put("offeringId", "xgpuweb")
        }.toString()

        val xgpuResult = runCatching {
            try {
                postJsonSigned(
                    url = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
                    body = xgpuBody,
                    extraHeaders = extraHeaders
                )
            } catch (e: Exception) {
                if (e.message?.contains("RegionNotSupported") == true || e.message?.contains("403") == true) {
                    val bypassHeaders = HashMap(extraHeaders).apply {
                        put("X-Forwarded-For", "200.221.11.101")
                    }
                    postJsonSigned(
                        url = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
                        body = xgpuBody,
                        extraHeaders = bypassHeaders
                    )
                } else {
                    throw e
                }
            }
        }

        // Eslabón 5B: gsToken para Free-to-Play (xgpuwebf2p - Fortnite, Warzone, etc.)
        val f2pResult = runCatching {
            val f2pBody = JSONObject().apply {
                put("token", gssvToken)
                put("offeringId", "xgpuwebf2p")
            }.toString()
            postJsonSigned(
                url = "https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user",
                body = f2pBody,
                extraHeaders = extraHeaders
            )
        }

        val xhomeGsToken = runCatching {
            postJsonSigned(
                url = "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user",
                body = JSONObject().put("token", gssvToken).put("offeringId", "xhome").toString(),
                extraHeaders = extraHeaders
            ).optString("gsToken").takeIf { it.isNotBlank() }
        }.getOrNull()

        val lptToken = runCatching {
            val lptResp = postUrlEncoded(
                url = "https://login.live.com/oauth20_token.srf",
                params = mapOf(
                    "client_id" to clientIdUsed,
                    "grant_type" to "refresh_token",
                    "refresh_token" to msaRefreshToken,
                    "scope" to "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"
                )
            )
            lptResp.getString("access_token")
        }.getOrNull()

        val xgpuResp = xgpuResult.getOrNull()
        val f2pResp = f2pResult.getOrNull()

        if (xgpuResp == null && f2pResp == null) {
            val err = xgpuResult.exceptionOrNull() ?: f2pResult.exceptionOrNull()
            throw err ?: IllegalStateException("Fallo al obtener credenciales de streaming en GSSV.")
        }

        val primaryResp = xgpuResp ?: f2pResp!!
        val xgpuGsToken = xgpuResp?.optString("gsToken").orEmpty()
        val f2pGsToken = f2pResp?.optString("gsToken")?.takeIf { it.isNotBlank() }
        val durationSec = primaryResp.optLong("durationInSeconds", 14_400L)
        val regions = primaryResp.getJSONObject("offeringSettings").getJSONArray("regions")

        var baseUri = ""
        for (i in 0 until regions.length()) {
            val r = regions.getJSONObject(i)
            if (r.optBoolean("isDefault", false) || baseUri.isEmpty()) {
                baseUri = r.getString("baseUri")
            }
        }

        return CompleteXboxSession(
            gamertag = gamertag,
            xuid = xuid,
            uhs = uhs,
            clientIdUsed = clientIdUsed,
            msaAccessToken = msaAccessToken,
            msaRefreshToken = msaRefreshToken,
            deviceToken = deviceToken,
            webXstsToken = webToken,
            gssvXstsToken = gssvToken,
            xgpuGsToken = xgpuGsToken,
            xhomeGsToken = xhomeGsToken,
            f2pGsToken = f2pGsToken,
            lptToken = lptToken,
            preferredBaseUri = baseUri,
            regionsJson = regions.toString(),
            gsTokenExpiresAtEpochMs = System.currentTimeMillis() + (durationSec * 1000L)
        )
    }

    private fun obtainSignedDeviceToken(): String {
        val payload = JSONObject().apply {
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("AuthMethod", "ProofOfPossession")
                put("Id", "{${UUID.randomUUID()}}")
                put("DeviceType", "Android")
                put("SerialNumber", "{${UUID.randomUUID()}}")
                put("Version", "15.0")
                put("ProofKey", publicKeyJwk)
            })
        }.toString()
        val resp = postJsonSigned("https://device.auth.xboxlive.com/device/authenticate", payload)
        return resp.getString("Token")
    }

    private fun obtainXstsToken(userToken: String, deviceToken: String, relyingParty: String): JSONObject {
        val props = JSONObject().apply {
            put("SandboxId", "RETAIL")
            put("UserTokens", JSONArray().put(userToken))
            if (deviceToken.isNotBlank()) {
                put("DeviceToken", deviceToken)
            }
        }
        val payload = JSONObject().apply {
            put("Properties", props)
            put("RelyingParty", relyingParty)
            put("TokenType", "JWT")
        }.toString()
        return postJsonSigned("https://xsts.auth.xboxlive.com/xsts/authorize", payload)
    }

    suspend fun buildDirectMsaAuthorizeUrl(
        challenge: String,
        state: String,
        redirectUri: String? = null,
        appId: String? = null
    ): String {
        val effectiveAppId = appId ?: PrimaryAppIdAuthEngine.getActiveAppId(storage)
        val effectiveRedirect = redirectUri ?: PrimaryAppIdAuthEngine.getRedirectUri(effectiveAppId)
        return "https://login.live.com/oauth20_authorize.srf" +
            "?client_id=${URLEncoder.encode(effectiveAppId, "UTF-8")}" +
            "&response_type=code" +
            "&approval_prompt=auto" +
            "&scope=${URLEncoder.encode(MBI_SSL_SCOPE, "UTF-8")}" +
            "&redirect_uri=${URLEncoder.encode(effectiveRedirect, "UTF-8")}" +
            "&code_challenge=${URLEncoder.encode(challenge, "UTF-8")}" +
            "&code_challenge_method=S256" +
            "&state=${URLEncoder.encode(state, "UTF-8")}"
    }

    private fun postUrlEncoded(url: String, params: Map<String, String>): JSONObject {
        val encoded = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store, must-revalidate, no-cache")
        }
        conn.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        SelfHealingSessionInterceptor.captureServerClockOffset(conn)
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code == 400 || code == 401) {
            if (text.contains("invalid_grant")) throw AuthTokenRevokedException(text)
            throw IllegalStateException("OAuth HTTP $code: $text")
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun postJsonSigned(
        url: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        val signature = signRequest(url, "", body)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-xbl-contract-version", "1")
            setRequestProperty("Signature", signature)
            setRequestProperty("Cache-Control", "no-store, must-revalidate, no-cache")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        SelfHealingSessionInterceptor.captureServerClockOffset(conn)
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code == 401 || code == 403) {
            // Caso 10: Intérprete automático de códigos XErr de XSTS
            if (text.contains("XErr")) {
                val action = SelfHealingSessionInterceptor.parseXstsErrorResponse(text)
                when (action) {
                    is XstsErrorAction.OpenBrowserResolution -> throw IllegalStateException("${action.userMessage} [${action.redirectUrl}]")
                    is XstsErrorAction.FatalAccountIssue -> throw IllegalStateException(action.userMessage)
                }
            }
            if (text.contains("2148916238") || text.contains("8015DC0E")) {
                throw IllegalStateException("Cuenta infantil sin grupo familiar configurado en Xbox.")
            }
            throw AuthTokenRevokedException("XSTS/GSSV $code: $text")
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun signRequest(url: String, authHeader: String, body: String): String {
        // Caso 6: Reloj corregido dinámicamente con offset de servidor
        val windowsTime = SelfHealingSessionInterceptor.getCorrectedWindowsFileTime()
        val pathAndQuery = URL(url).let { u ->
            if (u.query.isNullOrEmpty()) u.path else "${u.path}?${u.query}"
        }
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).use { dos ->
            dos.writeInt(1)
            dos.writeByte(0)
            dos.writeLong(windowsTime)
            dos.writeByte(0)
            dos.write("POST".toByteArray(Charsets.UTF_8))
            dos.writeByte(0)
            dos.write(pathAndQuery.toByteArray(Charsets.UTF_8))
            dos.writeByte(0)
            dos.write(authHeader.toByteArray(Charsets.UTF_8))
            dos.writeByte(0)
            dos.write(body.toByteArray(Charsets.UTF_8))
            dos.writeByte(0)
        }
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(ecKeyPair.private)
            update(buf.toByteArray())
        }.sign()
        val p1363 = derToP1363(sig)

        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeInt(1)
            dos.writeLong(windowsTime)
            dos.write(p1363)
        }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var idx = 2
        if (der[1].toInt() and 0x80 != 0) idx += (der[1].toInt() and 0x7F)
        idx++
        val rLen = der[idx++].toInt()
        val r = der.copyOfRange(idx, idx + rLen)
        idx += rLen + 1
        val sLen = der[idx++].toInt()
        val s = der.copyOfRange(idx, idx + sLen)
        val res = ByteArray(64)
        copy32(r, res, 0)
        copy32(s, res, 32)
        return res
    }

    private fun copy32(src: ByteArray, dst: ByteArray, offset: Int) {
        val clean = if (src.size > 32 && src[0] == 0.toByte()) src.copyOfRange(1, src.size) else src
        val start = offset + (32 - clean.size).coerceAtLeast(0)
        System.arraycopy(clean, 0, dst, start, clean.size.coerceAtMost(32))
    }

    private fun to32Bytes(v: BigInteger): ByteArray {
        val out = ByteArray(32)
        copy32(v.toByteArray(), out, 0)
        return out
    }

    fun generatePkceVerifier(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return base64Url(b)
    }

    fun generatePkceChallenge(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(hash)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun extractQueryParam(requestLine: String, key: String): String? {
        val path = requestLine.split(" ").getOrNull(1) ?: return null
        val query = path.substringAfter("?", "")
        return query.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it[0] == key }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }
}
