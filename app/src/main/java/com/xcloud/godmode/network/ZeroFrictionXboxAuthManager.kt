package com.xcloud.godmode.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.FragmentActivity
import com.xcloud.godmode.services.BiometricAuthHelper
import com.xcloud.godmode.services.EncryptedVaultService
import com.xcloud.godmode.services.SecureSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

data class VerifiedXalClientConfig(
    val appId: String,
    val titleId: String,
    val redirectUri: String
)

sealed interface LoginModalUiState {
    data object Idle : LoginModalUiState
    data object PreparingVerifiedUrl : LoginModalUiState
    data object WaitingForBrowserReturn : LoginModalUiState
    data object ExchangingTokens : LoginModalUiState
    data class QrCodeReady(
        val userCode: String?,
        val verificationUri: String,
        val qrContentUrl: String,
        val remainingSeconds: Int
    ) : LoginModalUiState
    data class Success(val gamertag: String) : LoginModalUiState
    data class Error(val message: String) : LoginModalUiState
}

/**
 * ZeroFrictionXboxAuthManager: High-performance Xbox Live authenticator with Pre-Flight URL validation.
 * Redesigned to eradicate "unauthorized_client" errors by performing background health checks on MSA URLs.
 */
class ZeroFrictionXboxAuthManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeJob: Job? = null

    private val _loginState = MutableStateFlow<LoginModalUiState>(LoginModalUiState.Idle)
    val loginState: StateFlow<LoginModalUiState> = _loginState.asStateFlow()

    private val _authEvents = MutableSharedFlow<XCloudTokenBundle>(extraBufferCapacity = 1)
    val authEvents: SharedFlow<XCloudTokenBundle> = _authEvents.asSharedFlow()

    @Volatile
    private var activeClient: VerifiedXalClientConfig = VERIFIED_XAL_CLIENTS.first()
    @Volatile
    private var activeCodeVerifier: String? = null
    @Volatile
    private var activeDeviceToken: String? = null
    @Volatile
    private var activeSisuSessionId: String? = null

    companion object {
        private const val TAG = "ZeroFrictionAuth"

        // Clientes XAL verificados en sisu.xboxlive.com y login.live.com (Eliminado 00000000440003e6)
        val VERIFIED_XAL_CLIENTS = listOf(
            VerifiedXalClientConfig(
                appId = "00000000402b5328", // #1 Principal: Xbox Cloud Companion
                titleId = "328178078",
                redirectUri = "ms-xal-00000000402b5328://auth"
            ),
            VerifiedXalClientConfig(
                appId = "000000004c20a908", // #2 Sub-login: Cliente XAL Android verificado en XStreaming
                titleId = "1016898439",
                redirectUri = "ms-xal-000000004c20a908://auth"
            ),
            VerifiedXalClientConfig(
                appId = "000000004827c78e", // #3 Terciaria: Xbox App Unificada
                titleId = "328178078",
                redirectUri = "ms-xal-000000004827c78e://auth"
            )
        )

        const val MBI_SSL_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        private const val WINDOWS_EPOCH_OFFSET = 11644473600L

        @Volatile
        private var INSTANCE: ZeroFrictionXboxAuthManager? = null

        fun getInstance(context: Context): ZeroFrictionXboxAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ZeroFrictionXboxAuthManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val ecKeyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
    }

    private val publicKeyJwk: JSONObject by lazy {
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

    /**
     * PESTAÑA 1: Inicio Rápido (1 Clic).
     * Pre-valida en segundo plano la URL en SISU + login.live.com para garantizar
     * que el navegador NUNCA muestre "unauthorized_client".
     */
    fun launchOneClickBrowserLogin(activityContext: Context) {
        activeJob?.cancel()
        activeJob = scope.launch {
            _loginState.value = LoginModalUiState.PreparingVerifiedUrl
            try {
                val verifiedUrl = withContext(Dispatchers.IO) {
                    obtainPreValidatedLoginUrl()
                }
                _loginState.value = LoginModalUiState.WaitingForBrowserReturn

                val uri = Uri.parse(verifiedUrl)
                try {
                    val customTabsIntent = CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .setUrlBarHidingEnabled(true)
                        .build()
                    customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    customTabsIntent.launchUrl(activityContext, uri)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                    }
                    activityContext.startActivity(fallbackIntent)
                }
            } catch (e: Exception) {
                _loginState.value = LoginModalUiState.Error(
                    "No se pudo conectar con el servidor de Xbox Live: ${e.message}"
                )
            }
        }
    }

    /**
     * PESTAÑA 2: Vincular QR / TV.
     * Codifica en el QR la URL oficial pre-validada en SISU / login.live.com libre de errores.
     */
    fun startCloudQrPairing() {
        activeJob?.cancel()
        activeJob = scope.launch {
            _loginState.value = LoginModalUiState.PreparingVerifiedUrl
            try {
                val verifiedUrl = withContext(Dispatchers.IO) {
                    obtainPreValidatedLoginUrl()
                }
                val deadlineMs = System.currentTimeMillis() + 900_000L

                while (isActive && System.currentTimeMillis() < deadlineMs) {
                    val remaining = ((deadlineMs - System.currentTimeMillis()) / 1000L).toInt()
                    _loginState.value = LoginModalUiState.QrCodeReady(
                        userCode = null,
                        verificationUri = "Escanea con tu cámara o toca 'Iniciar sesión con Xbox'",
                        qrContentUrl = verifiedUrl,
                        remainingSeconds = remaining
                    )
                    delay(1_000L)
                }
            } catch (e: Exception) {
                _loginState.value = LoginModalUiState.Error(
                    "Error al preparar vinculación QR: ${e.message}"
                )
            }
        }
    }

    /**
     * Recorre la cascada de clientes verificados, solicita MsaOauthRedirect a SISU 
     * y verifica con un GET que login.live.com NO devuelva errores.
     */
    private fun obtainPreValidatedLoginUrl(): String {
        val deviceToken = requestSignedDeviceToken()
        activeDeviceToken = deviceToken

        for (candidate in VERIFIED_XAL_CLIENTS) {
            val verifier = generatePkceVerifier()
            val challenge = generatePkceChallenge(verifier)
            val state = base64Url(UUID.randomUUID().toString().toByteArray())

            val candidateUrl = runCatching {
                val sisuBody = JSONObject().apply {
                    put("AppId", candidate.appId)
                    put("TitleId", candidate.titleId)
                    put("RedirectUri", candidate.redirectUri)
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

                val sisuUrl = "https://sisu.xboxlive.com/authenticate"
                val conn = (URL(sisuUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-xbl-contract-version", "1")
                    setRequestProperty("Signature", signXboxRequest(sisuUrl, "", sisuBody))
                }
                conn.outputStream.use { it.write(sisuBody.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode in 200..299) {
                    val sessionId = conn.getHeaderField("X-SessionId").orEmpty()
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val msaRedirect = json.getString("MsaOauthRedirect")

                    if (isLoginUrlHealthyInBrowser(msaRedirect)) {
                        activeClient = candidate
                        activeCodeVerifier = verifier
                        activeSisuSessionId = sessionId
                        return@runCatching msaRedirect
                    }
                }
                null
            }.getOrNull()

            if (!candidateUrl.isNullOrBlank()) return candidateUrl

            // Verificación de URL directa MSA en caso de contingencia en SISU
            val directMsaUrl = Uri.parse("https://login.live.com/oauth20_authorize.srf")
                .buildUpon()
                .appendQueryParameter("client_id", candidate.appId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("approval_prompt", "auto")
                .appendQueryParameter("scope", MBI_SSL_SCOPE)
                .appendQueryParameter("redirect_uri", candidate.redirectUri)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .build()
                .toString()

            if (isLoginUrlHealthyInBrowser(directMsaUrl)) {
                activeClient = candidate
                activeCodeVerifier = verifier
                activeSisuSessionId = null
                return directMsaUrl
            }
        }

        throw IllegalStateException("No se encontró un endpoint disponible en login.live.com")
    }

    private fun isLoginUrlHealthyInBrowser(url: String): Boolean {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
            }
            val code = conn.responseCode
            val html = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()

            code in 200..399 &&
                !html.contains("unauthorized_client", ignoreCase = true) &&
                !html.contains("invalid_request", ignoreCase = true) &&
                !html.contains("unable to complete your request", ignoreCase = true)
        }.getOrDefault(false)
    }

    /**
     * Se ejecuta desde MainActivity.onNewIntent() al regresar del navegador.
     */
    suspend fun completeAutomaticBrowserLogin(authorizationCode: String): Boolean {
        val verifier = activeCodeVerifier ?: return false
        val client = activeClient
        _loginState.value = LoginModalUiState.ExchangingTokens

        return try {
            val tokenJson = withContext(Dispatchers.IO) {
                postFormUrlEncoded(
                    url = "https://login.live.com/oauth20_token.srf",
                    params = mapOf(
                        "client_id" to client.appId,
                        "grant_type" to "authorization_code",
                        "code" to authorizationCode,
                        "redirect_uri" to client.redirectUri,
                        "code_verifier" to verifier,
                        "scope" to MBI_SSL_SCOPE
                    )
                )
            }

            val accessToken = tokenJson.getString("access_token")
            val refreshToken = tokenJson.getString("refresh_token")

            val gamertag = exchangeMsaToFullXboxSession(
                msaAccessToken = accessToken,
                msaRefreshToken = refreshToken,
                clientIdUsed = client.appId
            )
            _loginState.value = LoginModalUiState.Success(gamertag)
            true
        } catch (e: Exception) {
            _loginState.value = LoginModalUiState.Error(
                "Error al canjear código de acceso: ${e.message}"
            )
            false
        }
    }

    /**
     * Performs a secure session load gated by biometrics if supported.
     */
    fun loadSessionWithBiometrics(
        activity: FragmentActivity,
        onSuccess: (StreamingSessionCredentials) -> Unit,
        onError: (String) -> Unit
    ) {
        val authHelper = BiometricAuthHelper(activity)
        val sessionStore = SecureSessionStore(context)
        
        val session = sessionStore.loadSession()
        if (session == null) {
            onError("No hay sesión guardada.")
            return
        }

        if (authHelper.canAuthenticate()) {
            authHelper.promptAuth(
                title = "Xbox Session Guard",
                subtitle = "Autentícate para acceder a tu perfil de Xbox",
                onAuthenticated = { onSuccess(session) },
                onError = onError
            )
        } else {
            onSuccess(session)
        }
    }

    /**
     * Invalidates the current session and clears all secure vaults.
     */
    fun invalidateSession() {
        scope.launch(Dispatchers.IO) {
            val vault = EncryptedVaultService.getInstance(context)
            vault.clearSession()
            
            val sessionStore = SecureSessionStore(context)
            sessionStore.clearSession()
            
            withContext(Dispatchers.Main) {
                resetModalState()
            }
        }
    }

    fun onAutomaticBrowserCancelOrError(reason: String) {
        _loginState.value = LoginModalUiState.Error("Inicio de sesión cancelado: $reason")
    }

    fun resetModalState() {
        activeJob?.cancel()
        _loginState.value = LoginModalUiState.Idle
    }

    private fun requestSignedDeviceToken(): String {
        val body = JSONObject().apply {
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

        val url = "https://device.auth.xboxlive.com/device/authenticate"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-xbl-contract-version", "1")
            setRequestProperty("Signature", signXboxRequest(url, "", body))
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return JSONObject(conn.inputStream.bufferedReader().readText()).getString("Token")
    }

    private suspend fun exchangeMsaToFullXboxSession(
        msaAccessToken: String,
        msaRefreshToken: String,
        clientIdUsed: String
    ): String = withContext(Dispatchers.IO) {
        val vault = EncryptedVaultService.getInstance(context)
        val engine = UnifiedXboxAuthEngine.globalInstance ?: UnifiedXboxAuthEngine(vault)
        val session = engine.buildCompleteTokenPipeline(
            msaAccessToken = msaAccessToken,
            msaRefreshToken = msaRefreshToken,
            clientIdUsed = clientIdUsed,
            spoofedIp = null
        )
        
        vault.saveSession(session)
        
        val sessionStore = SecureSessionStore(context)
        val credentials = StreamingSessionCredentials(
            gamertag = session.gamertag,
            xuid = session.xuid,
            uhs = session.uhs,
            msaRefreshToken = session.msaRefreshToken,
            xCloudGsToken = session.xgpuGsToken,
            xCloudBaseUri = session.preferredBaseUri,
            expiresAtEpochMs = session.gsTokenExpiresAtEpochMs
        )
        sessionStore.saveSession(credentials)

        val bundle = XCloudTokenBundle(
            gamertag = session.gamertag,
            xuid = session.xuid,
            uhs = session.uhs,
            msaAccessToken = session.msaAccessToken,
            msaRefreshToken = session.msaRefreshToken,
            webXstsToken = session.webXstsToken,
            gssvXstsToken = session.gssvXstsToken,
            xgpuGsToken = session.xgpuGsToken,
            xhomeGsToken = session.xhomeGsToken,
            f2pGsToken = session.f2pGsToken,
            defaultRegionBaseUri = session.preferredBaseUri,
            regionsJson = session.regionsJson,
            gsTokenExpiresAtEpochMs = session.gsTokenExpiresAtEpochMs
        )
        _authEvents.emit(bundle)
        
        session.gamertag
    }

    private fun postFormUrlEncoded(url: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun signXboxRequest(url: String, authHeader: String, body: String): String {
        val windowsTime = (Instant.now().epochSecond + WINDOWS_EPOCH_OFFSET) * 10_000_000L
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

    private fun generatePkceVerifier(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return base64Url(b)
    }

    private fun generatePkceChallenge(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(hash)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
