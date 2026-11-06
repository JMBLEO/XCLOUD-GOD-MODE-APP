package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.*
import java.util.concurrent.TimeUnit

class XboxFullAuthManager(private val networkClient: NetworkClient) {

    private val TAG = "XboxFullAuthManager"
    private val CLIENT_ID = "000000004c20a908"
    private val REDIRECT_URI = "ms-xal-000000004c20a908://auth"
    private val SCOPES = "service::user.auth.xboxlive.com::MBI_SSL"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var authKeys: AuthKeys? = null

    data class AuthKeys(
        val keyPair: KeyPair,
        val x: String,
        val y: String
    )

    data class PendingAuthContext(
        val authUrl: String = "",
        val userCode: String = "",
        val deviceCode: String = "",
        val verificationUri: String = "",
        val codeVerifier: String = "",
        val state: String = "",
        val isDirectRps: Boolean = true,
        val isDeviceCodeFlow: Boolean = false,
        val sessionId: String = "",
        val deviceToken: String = "",
        val expiresIn: Int = 900
    ) : java.io.Serializable

    companion object {
        @Volatile
        var latestGssvXstsToken: String = ""
            private set

        @Volatile
        var latestF2pGsToken: String = ""
            private set

        @Volatile
        var latestF2pRegionUri: String = ""
            private set

        fun updateGssvTokens(xstsToken: String, f2pToken: String = "", f2pUri: String = "") {
            if (xstsToken.isNotBlank()) latestGssvXstsToken = xstsToken
            if (f2pToken.isNotBlank()) latestF2pGsToken = f2pToken
            if (f2pUri.isNotBlank()) latestF2pRegionUri = f2pUri
        }
    }

    data class XboxReadySession(
        val gamertag: String,
        val uhs: String,
        val msaRefreshToken: String,
        val msaAccessToken: String = "",
        val msaExpiresIn: Int = 3600,
        val xblUserToken: String = "",
        val webXstsToken: String,
        val xHomeGsToken: String,
        val xHomeRegionUri: String,
        val xCloudGsToken: String,
        val xCloudRegionUri: String,
        val gamerpic: String = "",
        val ageGroup: String = "Adult",
        val webXstsTokenFull: JSONObject? = null,
        val gssvXstsToken: String = "",
        val f2pGsToken: String = "",
        val f2pRegionUri: String = "",
        val lptToken: String = ""
    )

    /**
     * Genera o recupera llaves EC P-256 para firma de peticiones (Proof of Possession)
     */
    private fun getOrCreateKeys(): AuthKeys {
        authKeys?.let { return it }
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        
        val x = base64UrlEncode(publicKey.w.affineX.toByteArray().takeLast(32).toByteArray())
        val y = base64UrlEncode(publicKey.w.affineY.toByteArray().takeLast(32).toByteArray())
        
        return AuthKeys(keyPair, x, y).also { authKeys = it }
    }

    private fun getWindowsTimestamp(): Long {
        return (System.currentTimeMillis() / 1000 + 11644473600L) * 10000000L
    }

    private fun generateSignature(
        url: String,
        authorizationToken: String,
        payload: String,
        privateKey: PrivateKey,
        windowsTimestamp: Long
    ): String {
        val uri = java.net.URI(url)
        val pathAndQuery = uri.path + (if (uri.query != null) "?" + uri.query else "")

        val bos = java.io.ByteArrayOutputStream()
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array())
        bos.write(0)
        bos.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(windowsTimestamp).array())
        bos.write(0)

        bos.write("POST".toByteArray())
        bos.write(0)
        bos.write(pathAndQuery.toByteArray())
        bos.write(0)
        bos.write(authorizationToken.toByteArray())
        bos.write(0)
        bos.write(payload.toByteArray())
        bos.write(0)

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(bos.toByteArray())
        val derSignature = sig.sign()
        
        val rawSignature = derToP1363(derSignature)

        val header = ByteBuffer.allocate(12 + rawSignature.size).order(ByteOrder.BIG_ENDIAN)
        header.putInt(1)
        header.putLong(windowsTimestamp)
        header.put(rawSignature)

        return android.util.Base64.encodeToString(header.array(), android.util.Base64.NO_WRAP)
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 0
        if (der[offset++] != 0x30.toByte()) throw Exception("Invalid DER: not a sequence")
        
        // Skip length
        val len = der[offset++].toInt() and 0xFF
        if (len and 0x80 != 0) {
            offset += len and 0x7F
        }

        fun extractInt(bytes: ByteArray, start: Int): ByteArray {
            var curr = start
            if (bytes[curr++] != 0x02.toByte()) throw Exception("Invalid DER: not an integer")
            var iLen = bytes[curr++].toInt() and 0xFF
            if (iLen and 0x80 != 0) throw Exception("DER integer length too long")
            
            while (iLen > 0 && bytes[curr] == 0.toByte()) {
                curr++
                iLen--
            }
            
            val result = ByteArray(32)
            if (iLen > 32) {
                System.arraycopy(bytes, curr + (iLen - 32), result, 0, 32)
            } else {
                System.arraycopy(bytes, curr, result, 32 - iLen, iLen)
            }
            return result
        }

        val r = extractInt(der, offset)
        // Skip R integer (tag + len + actual_len)
        var rTotalLen = (der[offset + 1].toInt() and 0xFF) + 2
        val s = extractInt(der, offset + rTotalLen)
        
        return r + s
    }

    private suspend fun getDeviceToken(keys: AuthKeys): String = withContext(Dispatchers.IO) {
        val url = "https://device.auth.xboxlive.com/device/authenticate"
        val uuid = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "ProofOfPossession")
                put("Id", "{$uuid}")
                put("DeviceType", "Android")
                put("SerialNumber", "{$uuid}")
                put("Version", "15.0")
                put("ProofKey", JSONObject().apply {
                    put("use", "sig")
                    put("alg", "ES256")
                    put("kty", "EC")
                    put("crv", "P-256")
                    put("x", keys.x)
                    put("y", keys.y)
                })
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }

        val timestamp = getWindowsTimestamp()
        val signature = generateSignature(url, "", payload.toString(), keys.keyPair.private, timestamp)

        val request = Request.Builder()
            .url(url)
            .header("x-xbl-contract-version", "1")
            .header("Cache-Control", "no-store, must-revalidate, no-cache")
            .header("Signature", signature)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("Device Auth Failed: $body")
            return@withContext JSONObject(body).getString("Token")
        }
    }

    /**
     * Inicia flujo de Device Code estándar (OAuth 2.0 v2.0 Console Device Code)
     */
    suspend fun initDeviceLogin(): PendingAuthContext = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", "1f907974-e22b-4810-a9de-d9647380c97e")
            .add("scope", "XboxLive.signin offline_access")
            .build()

        val request = Request.Builder()
            .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode")
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("OAuth Device Code Failed: ${response.code} $body")
            
            val json = JSONObject(body)
            val userCode = json.getString("user_code")
            val deviceCode = json.getString("device_code")
            val interval = json.optInt("interval", 5)
            val expiresIn = json.optInt("expires_in", 900)
            val verificationUri = "https://www.microsoft.com/link?otc=$userCode"
            
            return@withContext PendingAuthContext(
                userCode = userCode,
                deviceCode = deviceCode,
                verificationUri = verificationUri,
                isDeviceCodeFlow = true,
                isDirectRps = true,
                sessionId = interval.toString(),
                expiresIn = expiresIn
            )
        }
    }

    private val pollingMutex = kotlinx.coroutines.sync.Mutex()
    private var lastSuccessfulDeviceTokens: JSONObject? = null
    private val redeemedCodes = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val codeExchangeMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Comprueba el estado del login por código de dispositivo (RFC 8628 v2.0)
     */
    suspend fun checkLoginStatus(deviceCode: String): JSONObject? = withContext(Dispatchers.IO) {
        pollingMutex.withLock {
            val formBody = FormBody.Builder()
                .add("client_id", "1f907974-e22b-4810-a9de-d9647380c97e")
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()

            val request = Request.Builder()
                .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    lastSuccessfulDeviceTokens = json
                    return@withContext json
                }
                
                val json = try { JSONObject(body) } catch(_: Exception) { null }
                val error = json?.optString("error", "") ?: ""
                when (error) {
                    "authorization_pending", "" -> return@withContext null
                    "slow_down" -> {
                        // Microsoft requested slow down: wait and continue polling
                        delay(5000L)
                        return@withContext null
                    }
                    "authorization_declined" -> {
                        throw Exception("El inicio de sesión fue cancelado.")
                    }
                    "expired_token" -> {
                        lastSuccessfulDeviceTokens?.let { return@withContext it }
                        throw Exception("El código ha expirado.")
                    }
                    "invalid_grant" -> {
                        // In RFC 8628 v2, invalid_grant can appear transiently before user approval propagates
                        lastSuccessfulDeviceTokens?.let { return@withContext it }
                        return@withContext null
                    }
                    else -> {
                        return@withContext null
                    }
                }
            }
        }
    }

    /**
     * Inicia flujo Sisu (God Mode robusto)
     */
    suspend fun initWebLogin(): PendingAuthContext = withContext(Dispatchers.IO) {
        val keys = getOrCreateKeys()
        val deviceToken = getDeviceToken(keys)
        
        val verifierBytes = ByteArray(32)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = base64UrlEncode(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        val challengeHash = digest.digest(codeVerifier.toByteArray(Charsets.UTF_8))
        val codeChallenge = base64UrlEncode(challengeHash)
        
        val stateBytes = ByteArray(64)
        SecureRandom().nextBytes(stateBytes)
        val state = base64UrlEncode(stateBytes)
        
        val payload = JSONObject().apply {
            put("AppId", CLIENT_ID)
            put("TitleId", "328178078")
            put("RedirectUri", REDIRECT_URI)
            put("DeviceToken", deviceToken)
            put("Sandbox", "RETAIL")
            put("TokenType", "code")
            put("Offers", JSONArray().apply { put("service::user.auth.xboxlive.com::MBI_SSL") })
            put("Query", JSONObject().apply {
                put("display", "android_phone")
                put("code_challenge", codeChallenge)
                put("code_challenge_method", "S256")
                put("state", state)
            })
        }
        
        val timestamp = getWindowsTimestamp()
        val signature = generateSignature("https://sisu.xboxlive.com/authenticate", "", payload.toString(), keys.keyPair.private, timestamp)
        
        val request = Request.Builder()
            .url("https://sisu.xboxlive.com/authenticate")
            .header("x-xbl-contract-version", "1")
            .header("Cache-Control", "no-store, must-revalidate, no-cache")
            .header("Signature", signature)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
            
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("Sisu Authenticate Failed: $body")
            
            val json = JSONObject(body)
            var authUrl = json.optString("MsaOauthRedirect") ?: json.optJSONObject("data")?.optString("MsaOauthRedirect", "") ?: ""
            if (authUrl.isNotEmpty()) {
                authUrl += if (authUrl.contains("?")) "&prompt=login" else "?prompt=login"
            }
            val sessionId = response.header("x-sessionid", "") ?: ""
            
            return@withContext PendingAuthContext(
                authUrl = authUrl,
                codeVerifier = codeVerifier,
                state = state,
                isDirectRps = true,
                isDeviceCodeFlow = false,
                sessionId = sessionId,
                deviceToken = deviceToken
            )
        }
    }

    /**
     * Finaliza la autenticación Sisu y prepara la sesión de juego
     */
    suspend fun completeSisuAuth(code: String, context: PendingAuthContext): XboxReadySession = withContext(Dispatchers.IO) {
        val msaTokens = tokenExchange(code, context.codeVerifier)
        
        try {
            val accessToken = msaTokens.getString("access_token")
            val keys = getOrCreateKeys()
            val payload = JSONObject().apply {
                put("AccessToken", "t=$accessToken")
                put("AppId", CLIENT_ID)
                put("DeviceToken", context.deviceToken)
                put("Sandbox", "RETAIL")
                put("SiteName", "user.auth.xboxlive.com")
                put("UseModernGamertag", true)
                put("ProofKey", JSONObject().apply {
                    put("use", "sig")
                    put("alg", "ES256")
                    put("kty", "EC")
                    put("crv", "P-256")
                    put("x", keys.x)
                    put("y", keys.y)
                })
            }
            
            val timestamp = getWindowsTimestamp()
            val signature = generateSignature("https://sisu.xboxlive.com/authorize", "", payload.toString(), keys.keyPair.private, timestamp)
            
            val request = Request.Builder()
                .url("https://sisu.xboxlive.com/authorize")
                .header("x-xbl-contract-version", "1")
                .header("Cache-Control", "no-store, must-revalidate, no-cache")
                .header("Signature", signature)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
                
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("XboxAuth", "Sisu authorize failed: $body. Falling back to RPS.")
                    return@withContext completeAuth(msaTokens)
                }
                
                val sisuResult = JSONObject(body)
                
                // Final reliability check for Sisu components
                if (!sisuResult.has("DeviceToken") || !sisuResult.has("UserToken") || !sisuResult.has("AuthorizationToken")) {
                    Log.w("XboxAuth", "Sisu response missing tokens. Falling back to RPS path.")
                    return@withContext completeAuth(msaTokens)
                }

                val authHeaderToken = sisuResult.getJSONObject("AuthorizationToken").getString("Token")
                val xui = sisuResult.getJSONObject("AuthorizationToken").getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)
                val uhs = xui.getString("uhs")
                val gamertag = xui.optString("gtg", "Spartan")
                val ageGroup = xui.optString("agg", "Adult")

                val gamerpic = try { fetchGamerpic(uhs, authHeaderToken) } catch (_: Exception) { "" }
                
                val gssvTokenData = getXstsFromSisu(sisuResult, "http://gssv.xboxlive.com/")
                val gssvToken = gssvTokenData.getString("Token")
                
                val xCloudLogin = gssvLogin(
                    "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user",
                    "xgpuweb",
                    gssvToken
                )

                val f2pLogin = try {
                    loginGssvOffering("xgpuwebf2p", gssvToken)
                } catch (_: Exception) { null }

                updateGssvTokens(
                    xstsToken = gssvToken,
                    f2pToken = f2pLogin?.first.orEmpty(),
                    f2pUri = f2pLogin?.second.orEmpty()
                )
                
                return@withContext XboxReadySession(
                    gamertag = gamertag,
                    uhs = uhs,
                    msaRefreshToken = msaTokens.optString("refresh_token", ""),
                    webXstsToken = authHeaderToken,
                    xHomeGsToken = xCloudLogin.first,
                    xHomeRegionUri = xCloudLogin.second,
                    xCloudGsToken = xCloudLogin.first,
                    xCloudRegionUri = xCloudLogin.second,
                    gamerpic = gamerpic,
                    ageGroup = ageGroup,
                    webXstsTokenFull = sisuResult,
                    gssvXstsToken = gssvToken,
                    f2pGsToken = f2pLogin?.first.orEmpty(),
                    f2pRegionUri = f2pLogin?.second.orEmpty()
                )
            }
        } catch (e: Exception) {
            Log.e("XboxAuth", "God Mode Sisu recovery: ${e.message}. Forcing RPS.")
            return@withContext completeAuth(msaTokens)
        }
    }

    private suspend fun getXstsFromSisu(sisuResult: JSONObject, relyingParty: String): JSONObject = withContext(Dispatchers.IO) {
        val keys = getOrCreateKeys()
        val deviceToken = sisuResult.optString("DeviceToken", "")
        val titleToken = sisuResult.optJSONObject("TitleToken")?.optString("Token", "") ?: ""
        val userToken = sisuResult.optJSONObject("UserToken")?.optString("Token", "") ?: ""
        
        if (deviceToken.isBlank() || titleToken.isBlank() || userToken.isBlank()) {
            throw Exception("Missing required Sisu authorization tokens")
        }
        
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("DeviceToken", deviceToken)
                put("TitleToken", titleToken)
                put("UserTokens", JSONArray().apply { put(userToken) })
            })
            put("RelyingParty", relyingParty)
            put("TokenType", "JWT")
        }
        
        val timestamp = getWindowsTimestamp()
        val signature = generateSignature("https://xsts.auth.xboxlive.com/xsts/authorize", "", payload.toString(), keys.keyPair.private, timestamp)
        
        val request = Request.Builder()
            .url("https://xsts.auth.xboxlive.com/xsts/authorize")
            .header("x-xbl-contract-version", "1")
            .header("Cache-Control", "no-store, must-revalidate, no-cache")
            .header("Signature", signature)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
            
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("XSTS ($relyingParty) Failed: $body")
            return@withContext JSONObject(body)
        }
    }

    /**
     * Mantiene compatibilidad con flujo anterior para Device Code
     */
    suspend fun completeAuth(msaTokens: JSONObject): XboxReadySession = withContext(Dispatchers.IO) {
        val msaAccessToken = msaTokens.getString("access_token")
        val msaRefreshToken = msaTokens.optString("refresh_token", "")

        val userToken = msaToXbox(msaAccessToken)
        val webXstsData = getXsts(userToken, "http://xboxlive.com")
        val webXstsToken = webXstsData.getString("Token")
        val xui = webXstsData.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)
        val uhs = xui?.optString("uhs", "") ?: ""
        val gamertag = xui?.optString("gtg", "Spartan") ?: "Spartan"
        val ageGroup = xui?.optString("agg", "Adult") ?: "Adult"

        val gamerpic = try { fetchGamerpic(uhs, webXstsToken) } catch (_: Exception) { "" }
        val gssvToken = getXsts(userToken, "http://gssv.xboxlive.com/").getString("Token")

        val xCloudLogin = gssvLogin(
            "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user",
            "xgpuweb",
            gssvToken
        )

        val f2pLogin = try {
            loginGssvOffering("xgpuwebf2p", gssvToken)
        } catch (_: Exception) { null }

        updateGssvTokens(
            xstsToken = gssvToken,
            f2pToken = f2pLogin?.first.orEmpty(),
            f2pUri = f2pLogin?.second.orEmpty()
        )

        return@withContext XboxReadySession(
            gamertag = gamertag,
            uhs = uhs,
            msaRefreshToken = msaRefreshToken,
            webXstsToken = webXstsToken,
            xHomeGsToken = xCloudLogin.first,
            xHomeRegionUri = xCloudLogin.second,
            xCloudGsToken = xCloudLogin.first,
            xCloudRegionUri = xCloudLogin.second,
            gamerpic = gamerpic,
            ageGroup = ageGroup,
            webXstsTokenFull = webXstsData,
            gssvXstsToken = gssvToken,
            f2pGsToken = f2pLogin?.first.orEmpty(),
            f2pRegionUri = f2pLogin?.second.orEmpty()
        )
    }

    private suspend fun fetchGamerpic(uhs: String, xstsToken: String): String = withContext(Dispatchers.IO) {
        val url = "https://profile.xboxlive.com/users/batch/profile/settings"
        val payload = JSONObject().apply {
            put("userIds", JSONArray().apply { put(uhs) })
            put("settings", JSONArray().apply { put("GameDisplayPicRaw") })
        }
        val mediaType = "application/json".toMediaType()
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "XBL3.0 x=$uhs;$xstsToken")
            .addHeader("x-xbl-contract-version", "2")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext ""
            val json = JSONObject(resp.body?.string() ?: "{}")
            val profiles = json.optJSONArray("profileUsers")
            if (profiles != null && profiles.length() > 0) {
                val settings = profiles.getJSONObject(0).optJSONArray("settings")
                if (settings != null && settings.length() > 0) {
                    return@withContext settings.getJSONObject(0).optString("value", "")
                }
            }
            return@withContext ""
        }
    }

    /**
     * Genera la URL oficial de Microsoft OAuth para Chrome Custom Tabs o Navegador Externo
     */
    fun generateStandardLoginUrl(): String {
        val encodedScope = URLEncoder.encode("service::user.auth.xboxlive.com::MBI_SSL offline_access", "UTF-8")
        val encodedRedirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        return "https://login.live.com/oauth20_authorize.srf?client_id=$CLIENT_ID&response_type=code&approval_prompt=auto&scope=$encodedScope&redirect_uri=$encodedRedirect&display=android_phone"
    }

    /**
     * Canjea un código de autorización OAuth por tokens de acceso
     */
    suspend fun tokenExchange(code: String, verifier: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val builder = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", "service::user.auth.xboxlive.com::MBI_SSL offline_access")
            
        if (verifier.isNotBlank()) {
            builder.add("code_verifier", verifier)
        }
        val formBody = builder.build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(formBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val json = try { JSONObject(body) } catch(_: Exception) { null }
                val error = json?.optString("error") ?: "unknown"
                if (error == "invalid_grant") {
                    throw Exception("El código o enlace ha expirado. Por favor intenta iniciar sesión de nuevo.")
                }
                throw Exception("Exchange Failed ($error): $body")
            }
            return@withContext JSONObject(body)
        }
    }

    /**
     * Embudo Universal RPS Bypass: Procesa el código o enlace ms-xal:// y finaliza la sesión
     */
    suspend fun processOAuthCode(redirectUrlOrCode: String): XboxReadySession = withContext(Dispatchers.IO) {
        val code = extractAuthCode(redirectUrlOrCode)
        if (code.isBlank()) throw Exception("Código de autorización no encontrado en el enlace.")
        
        codeExchangeMutex.withLock {
            if (redeemedCodes.contains(code)) {
                Log.w(TAG, "Authorization code $code already redeemed. Ignoring duplicate attempt.")
                throw Exception("Este enlace de autorización ya fue procesado o ha expirado.")
            }
            redeemedCodes.add(code)
            val tokenJson = tokenExchange(code)
            return@withContext completeAuth(tokenJson)
        }
    }

    private suspend fun msaToXbox(msaAccessToken: String): String = withContext(Dispatchers.IO) {
        val endpoint = "https://user.auth.xboxlive.com/user/authenticate"
        val mediaType = "application/json".toMediaType()
        
        suspend fun attempt(prefix: String): String? {
            val payload = JSONObject().apply {
                put("Properties", JSONObject().apply {
                    put("AuthMethod", "RPS")
                    put("SiteName", "user.auth.xboxlive.com")
                    put("RpsTicket", "$prefix$msaAccessToken")
                })
                put("RelyingParty", "http://auth.xboxlive.com")
                put("TokenType", "JWT")
            }
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-xbl-contract-version", "1")
                .post(payload.toString().toRequestBody(mediaType))
                .build()
            
            return try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        JSONObject(response.body?.string() ?: "").getString("Token")
                    } else null
                }
            } catch (e: Exception) { null }
        }

        // Try 'd=' first (recommended for God Mode), fallback to 't='
        attempt("d=") ?: attempt("t=") ?: throw Exception("User Auth Failed after dual-ticket attempts")
    }

    private suspend fun getXsts(userToken: String, relyingParty: String): JSONObject = withContext(Dispatchers.IO) {
        val endpoint = "https://xsts.auth.xboxlive.com/xsts/authorize"
        val mediaType = "application/json".toMediaType()
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().apply { put(userToken) })
            })
            put("RelyingParty", relyingParty)
            put("TokenType", "JWT")
        }
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("x-xbl-contract-version", "1")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        okHttpClient.newCall(req).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errJson = try { JSONObject(body) } catch(_:Exception) { null }
                val identity = errJson?.optString("Identity") ?: "Unknown"
                throw Exception("XSTS ($relyingParty) Failed (Identity: $identity): $body")
            }
            return@withContext JSONObject(body)
        }
    }

    private suspend fun gssvLogin(endpoint: String, offeringId: String, xstsToken: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val mediaType = "application/json".toMediaType()
        val bypassIp = "20.143.92.1"
        
        suspend fun attempt(url: String, offId: String, withBypass: Boolean): Pair<String, String>? {
            val payload = JSONObject().apply {
                put("token", xstsToken)
                put("offeringId", offId)
            }
            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("x-gssv-client", "XboxComBrowser")
                .post(payload.toString().toRequestBody(mediaType))

            if (withBypass) {
                reqBuilder.addHeader("X-Forwarded-For", bypassIp)
            }
            
            return try {
                okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code == 403) {
                        Log.w("XboxAuth", "GSSV Login 403 for $offId (bypass=$withBypass). Proceeding to next step...")
                        return null
                    }
                    if (!response.isSuccessful) return null
                    
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val gsToken = json.getString("gsToken")
                    val regions = json.optJSONObject("offeringSettings")?.optJSONArray("regions")
                    var regionUri = url.substringBefore("/v2/login/user")
                    
                    if (regions != null && regions.length() > 0) {
                        regionUri = regions.getJSONObject(0).getString("baseUri")
                        for (i in 0 until regions.length()) {
                            val r = regions.getJSONObject(i)
                            if (r.optBoolean("isDefault", false)) {
                                regionUri = r.getString("baseUri")
                                break
                            }
                        }
                    }
                    Pair(gsToken, regionUri)
                }
            } catch (e: Exception) {
                Log.w("XboxAuth", "GSSV request error for $offId: ${e.message}")
                null
            }
        }

        val primaryUrl = if (endpoint.contains("/v2/login/user")) endpoint else "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user"
        val f2pUrl = "https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user"
        val xhomeUrl = "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user"

        // Intento 1: Servidor Principal Ultimate (xgpuweb) directo
        var result = attempt(primaryUrl, "xgpuweb", withBypass = false)
        if (result != null) return@withContext result

        // Intento 2: Reactivo con Bypass X-Forwarded-For en xgpuweb
        Log.d("XboxAuth", "Activando Bypass reactivo X-Forwarded-For ($bypassIp)...")
        result = attempt(primaryUrl, "xgpuweb", withBypass = true)
        if (result != null) return@withContext result

        // Intento 3: Servidor Free-to-Play (xgpuwebf2p) con Bypass
        Log.d("XboxAuth", "Intentando F2P Offering (xgpuwebf2p)...")
        result = attempt(f2pUrl, "xgpuwebf2p", withBypass = true)
        if (result != null) return@withContext result

        // Intento 4: Consola Remota Local (xhome)
        Log.d("XboxAuth", "Intentando Remote Play offering (xhome)...")
        result = attempt(xhomeUrl, "xhome", withBypass = false)
        if (result != null) return@withContext result

        throw Exception("GSSV Login Failed: La cuenta requiere verificación de región o no tiene suscripción activa.")
    }

    suspend fun loginGssvOffering(
        offeringId: String,
        xstsToken: String = latestGssvXstsToken,
        bypassIp: String? = null
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (xstsToken.isBlank()) return@withContext null
        val mediaType = "application/json".toMediaType()
        val ipToUse = bypassIp?.takeIf { it.isNotBlank() && it != "Desactivado" } ?: "20.143.92.1"
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
            .post(payload.toString().toRequestBody(mediaType))

        if (ipToUse.isNotBlank()) {
            reqBuilder.addHeader("X-Forwarded-For", ipToUse)
        }

        try {
            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("XboxAuth", "loginGssvOffering $offeringId failed with HTTP ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val gsToken = json.optString("gsToken")
                if (gsToken.isBlank()) return@withContext null
                val regions = json.optJSONObject("offeringSettings")?.optJSONArray("regions")
                var regionUri = url.substringBefore("/v2/login/user")
                if (regions != null && regions.length() > 0) {
                    regionUri = regions.getJSONObject(0).optString("baseUri", regionUri)
                    for (i in 0 until regions.length()) {
                        val r = regions.getJSONObject(i)
                        if (r.optBoolean("isDefault", false)) {
                            regionUri = r.optString("baseUri", regionUri)
                            break
                        }
                    }
                }
                Pair(gsToken, regionUri)
            }
        } catch (e: Exception) {
            Log.w("XboxAuth", "Error en loginGssvOffering $offeringId: ${e.message}")
            null
        }
    }

    suspend fun validateActiveSession(webToken: String, uhs: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://peoplehub.xboxlive.com/users/me/people/social")
                .addHeader("Authorization", "XBL3.0 x=$uhs;$webToken")
                .addHeader("x-xbl-contract-version", "5")
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun refreshMsaAccessToken(refreshToken: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .add("scope", SCOPES)
            .build()
        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(formBody)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Refresh failed")
            val json = JSONObject(response.body?.string() ?: "{}")
            return@withContext Pair(json.getString("access_token"), json.optString("refresh_token", refreshToken))
        }
    }

    fun extractAuthCode(input: String): String {
        val match = Regex("""[?&#](?:code|approval_code)=([^&#\s]+)""").find(input)
        return if (match != null) URLDecoder.decode(match.groupValues[1], "UTF-8") else input.trim()
    }

    suspend fun getConsoles(uhs: String, webToken: String): JSONObject = withContext(Dispatchers.IO) {
        val url = "https://xccs.xboxlive.com/lists/devices?queryCurrentDevice=false&includeStorageDevices=true"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "XBL3.0 x=$uhs;$webToken")
            .addHeader("Accept-Language", "en-US")
            .addHeader("x-xbl-contract-version", "2")
            .addHeader("x-xbl-client-name", "XboxApp")
            .addHeader("x-xbl-client-type", "UWA")
            .addHeader("x-xbl-client-version", "39.39.22001.0")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("Get Consoles Failed: $body")
            return@withContext JSONObject(body)
        }
    }

    /**
     * Consulta silenciosa a la API xHome de Microsoft para obtener consolas listas para Remote Play
     */
    suspend fun getXHomeConsoles(uhs: String, webToken: String): JSONObject = withContext(Dispatchers.IO) {
        val url = "https://xhome.xboxlive.com/v8/environments"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "XBL3.0 x=$uhs;$webToken")
            .addHeader("Accept-Language", "en-US")
            .addHeader("x-xbl-contract-version", "2")
            .get()
            .build()
        return@withContext try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful && body.isNotBlank()) {
                    JSONObject(body)
                } else {
                    getConsoles(uhs, webToken)
                }
            }
        } catch (_: Exception) {
            getConsoles(uhs, webToken)
        }
    }

    suspend fun getCloudTitles(gsToken: String, regionUri: String): JSONObject = withContext(Dispatchers.IO) {
        val baseUrl = regionUri.removeSuffix("/")
        val url = "$baseUrl/v2/titles"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $gsToken")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("Get Cloud Titles Failed: $body")
            return@withContext JSONObject(body)
        }
    }

    suspend fun getMruTitles(gsToken: String, regionUri: String): JSONObject = withContext(Dispatchers.IO) {
        val baseUrl = regionUri.removeSuffix("/")
        val url = "$baseUrl/v2/titles/mru?mr=25"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $gsToken")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("Get MRU Titles Failed: $body")
            return@withContext JSONObject(body)
        }
    }

    suspend fun getCloudTitleDetails(productIds: List<String>): JSONObject = withContext(Dispatchers.IO) {
        val url = "https://catalog.gamepass.com/v3/products?market=US&language=en-US&hydration=RemoteHighSapphire0"
        val payload = JSONObject().apply {
            put("Products", JSONArray(productIds))
        }
        val mediaType = "application/json".toMediaType()
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("ms-cv", "0")
            .addHeader("calling-app-name", "Xbox Cloud Gaming Web")
            .addHeader("calling-app-version", "21.0.0")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.trim().startsWith("<") || body.isBlank()) {
                    return@withContext JSONObject()
                }
                return@withContext runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            }
        } catch (_: Exception) {
            return@withContext JSONObject()
        }
    }

    suspend fun getRecentlyAddedTitles(): JSONArray = withContext(Dispatchers.IO) {
        val url = "https://catalog.gamepass.com/sigls/v2?id=f13cf6b4-57e6-4459-89df-6aec18cf0538&market=US&language=en-US"
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "[]"
            if (!response.isSuccessful) return@withContext JSONArray()
            return@withContext JSONArray(body)
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
