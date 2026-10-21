package com.aistudio.xcloudgodmode.ai.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.TimeUnit

class XboxFullAuthManager(private val networkClient: NetworkClient) {

    private val TAG = "XboxFullAuthManager"
    private val CLIENT_ID = "000000004c20a908"
    private val TITLE_ID = "1016898439"
    private val REDIRECT_URI = "ms-xal-000000004c20a908://auth"
    private val SCOPES = "service::user.auth.xboxlive.com::MBI_SSL"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class PendingAuthContext(
        val authUrl: String = "",
        val userCode: String = "",
        val deviceCode: String = "",
        val verificationUri: String = "",
        val codeVerifier: String = "",
        val state: String = "",
        val isDirectRps: Boolean = true,
        val isDeviceCodeFlow: Boolean = false
    )

    data class XboxReadySession(
        val gamertag: String,
        val uhs: String,
        val msaRefreshToken: String,
        val webXstsToken: String,
        val xHomeGsToken: String,
        val xHomeRegionUri: String,
        val xCloudGsToken: String,
        val xCloudRegionUri: String,
        val gamerpic: String = ""
    )

    /**
     * Inicia flujo de Device Code (Microsoft OAuth2 Connect)
     * No requiere DeviceToken ni SISU (Evita el 403 Forbidden).
     */
    suspend fun startDeviceCodeFlow(): PendingAuthContext = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", "service::user.auth.xboxlive.com::MBI_SSL offline_access")
            .add("response_type", "device_code")
            .build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_connect.srf")
            .post(formBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Device Code Init HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "")
            return@withContext PendingAuthContext(
                userCode = json.getString("user_code"),
                deviceCode = json.getString("device_code"),
                verificationUri = json.getString("verification_uri"),
                isDeviceCodeFlow = true,
                isDirectRps = true
            )
        }
    }

    /**
     * Poll por el token del flujo Device Code
     */
    suspend fun pollDeviceCode(deviceCode: String): JSONObject? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(formBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) return@withContext JSONObject(body)
            
            val json = try { JSONObject(body) } catch(_: Exception) { null }
            val error = json?.optString("error")
            if (error == "authorization_pending") return@withContext null
            
            throw Exception("Error polling device code: $error")
        }
    }

    /**
     * Flujo PKCE Directo (Alternative)
     */
    suspend fun startPkceFlow(): PendingAuthContext = withContext(Dispatchers.IO) {
        val verifierBytes = ByteArray(32)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = base64UrlEncode(verifierBytes)

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val challengeHash = digest.digest(codeVerifier.toByteArray(Charsets.UTF_8))
        val codeChallenge = base64UrlEncode(challengeHash)

        val state = base64UrlEncode(UUID.randomUUID().toString().toByteArray().sliceArray(0..15))

        val directOAuthUrl = "https://login.live.com/oauth20_authorize.srf?" +
                "client_id=$CLIENT_ID" +
                "&response_type=code" +
                "&approval_prompt=auto" +
                "&scope=service::user.auth.xboxlive.com::MBI_SSL%20offline_access" +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
                "&code_challenge=$codeChallenge" +
                "&code_challenge_method=S256" +
                "&state=$state" +
                "&display=android_phone"

        return@withContext PendingAuthContext(
            authUrl = directOAuthUrl,
            codeVerifier = codeVerifier,
            state = state,
            isDirectRps = true,
            isDeviceCodeFlow = false
        )
    }

    /**
     * Finaliza el flujo de gaming canjeando los tokens de MSA por tokens de Xbox.
     */
    suspend fun finalizeGamingSession(msaTokens: JSONObject): XboxReadySession = withContext(Dispatchers.IO) {
        val msaAccessToken = msaTokens.getString("access_token")
        val msaRefreshToken = msaTokens.optString("refresh_token", "")

        // 1. Canjear token MSA por UserToken de Xbox Live (RPS Directo)
        val userToken = exchangeMsaForXboxUserToken(msaAccessToken)

        // 2. Obtener Perfil y Gamertag (Requerido para handshake)
        val webXstsData = authorizeXsts(userToken, "http://xboxlive.com")
        val webXstsToken = webXstsData.getString("Token")
        val xui = webXstsData.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)
        val uhs = xui?.optString("uhs", "") ?: ""
        val gamertag = xui?.optString("gtg", "Spartan") ?: "Spartan"

        // 4. Obtener Perfil (Gamerpic)
        val gamerpic = try {
            fetchGamerpic(uhs, webXstsToken)
        } catch (_: Exception) { "" }

        // 5. Obtener token para Streaming (GSSV)
        val gssvToken = authorizeXsts(userToken, "http://gssv.xboxlive.com/").getString("Token")

        // 6. Iniciar sesión en Servidores de Streaming
        val xCloudLogin = loginToGssv(
            "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user",
            "xgpuweb",
            gssvToken
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
            gamerpic = gamerpic
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
     * Canjea el código PKCE obtenido en el WebView
     */
    suspend fun exchangePkceCode(code: String, verifier: String): JSONObject = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", "service::user.auth.xboxlive.com::MBI_SSL offline_access")
            .build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(formBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("Error exchange PKCE code: $body")
            return@withContext JSONObject(body)
        }
    }

    private suspend fun exchangeMsaForXboxUserToken(msaAccessToken: String): String = withContext(Dispatchers.IO) {
        val endpoint = "https://user.auth.xboxlive.com/user/authenticate"
        val mediaType = "application/json".toMediaType()

        // Probamos con t= como prefijo para RPS ticket
        val payload = JSONObject().apply {
            val props = JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "t=$msaAccessToken")
            }
            put("Properties", props)
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-xbl-contract-version", "1")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("Xbox User Auth Failed: $body")
            return@withContext JSONObject(body).getString("Token")
        }
    }

    private suspend fun authorizeXsts(userToken: String, relyingParty: String): JSONObject = withContext(Dispatchers.IO) {
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
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        okHttpClient.newCall(req).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("XSTS Authorize ($relyingParty) Failed: $body")
            return@withContext JSONObject(body)
        }
    }

    private suspend fun loginToGssv(endpoint: String, offeringId: String, xstsToken: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val mediaType = "application/json".toMediaType()
        val payload = JSONObject().apply {
            put("token", xstsToken)
            put("offeringId", offeringId)
        }

        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-gssv-client", "XboxComBrowser")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        okHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw Exception("GSSV Login Failed HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "{}")
            val gsToken = json.getString("gsToken")
            val regions = json.optJSONObject("offeringSettings")?.optJSONArray("regions")
            var regionUri = "https://uks.core.gssv-play-prod.xboxlive.com"
            
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
            return@withContext Pair(gsToken, regionUri)
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
            .add("scope", "service::user.auth.xboxlive.com::MBI_SSL offline_access")
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
        val match = Regex("""[?&]code=([^&#\s]+)""").find(input)
        return if (match != null) URLDecoder.decode(match.groupValues[1], "UTF-8") else input.trim()
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
