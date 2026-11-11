package com.xcloud.godmode.network

import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class MsaDirectTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val userId: String
)

/**
 * MsaLiveDirectAuthenticator: Native Microsoft Account (MSA) Live OAuth 2.0 authenticator
 * via login.live.com (bypassing corporate Azure AD directory validation entirely).
 * Uses official active Xbox Main App ID ("000000004827c78e") and MBI_SSL scope.
 */
class MsaLiveDirectAuthenticator(
    private val tokenStorage: XCloudTokenStorage? = null
) {
    companion object {
        private const val TAG = "MsaLiveDirectAuth"

        // Official active App ID of Xbox Live in login.live.com
        val LIVE_CLIENT_ID: String
            get() = PrimaryAppIdAuthEngine.PRIMARY_APP_ID
        val LIVE_REDIRECT_URI: String
            get() = "ms-xal-$LIVE_CLIENT_ID://auth"
        const val LIVE_SCOPE = PrimaryAppIdAuthEngine.MBI_SSL_SCOPE

        @Volatile
        var activeCodeVerifier: String? = null

        private val _authEvents = MutableSharedFlow<XCloudTokenBundle>(extraBufferCapacity = 1)
        val authEvents: SharedFlow<XCloudTokenBundle> = _authEvents.asSharedFlow()
    }

    /**
     * Genera la URL limpia de login.live.com (inmune a errores de Azure AD / Primera Parte).
     */
    suspend fun buildAuthorizeUrl(codeChallengeS256: String, state: String = UUID.randomUUID().toString()): String {
        val clientId = PrimaryAppIdAuthEngine.getActiveAppId()
        val redirectUri = PrimaryAppIdAuthEngine.getRedirectUri(clientId)
        return Uri.parse("https://login.live.com/oauth20_authorize.srf")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", LIVE_SCOPE)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge", codeChallengeS256)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    /**
     * Canjea el código recibido por el access_token y refresh_token de larga duración en login.live.com
     */
    suspend fun exchangeCodeForTokens(
        authorizationCode: String,
        codeVerifier: String = activeCodeVerifier ?: throw IllegalStateException("Missing PKCE code verifier")
    ): MsaDirectTokenPair = withContext(Dispatchers.IO) {
        val cleanCode = authorizationCode.trim()
        val pair = PrimaryAppIdAuthEngine.executeWithHierarchyFallback { appId, redirectUri, _ ->
            val json = postLiveTokenRequest(
                mapOf(
                    "client_id" to appId,
                    "grant_type" to "authorization_code",
                    "code" to cleanCode,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to codeVerifier,
                    "scope" to LIVE_SCOPE
                )
            )
            MsaDirectTokenPair(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresInSeconds = json.optLong("expires_in", 3600L),
                userId = json.optString("user_id", "")
            )
        }
        activeCodeVerifier = null
        pair
    }

    /**
     * Refresca silenciosamente el token antes de cada partida ("JUGAR AHORA")
     */
    suspend fun refreshMsaTokens(currentRefreshToken: String): MsaDirectTokenPair =
        withContext(Dispatchers.IO) {
            val activeId = PrimaryAppIdAuthEngine.getActiveAppId()
            val json = postLiveTokenRequest(
                mapOf(
                    "client_id" to activeId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to currentRefreshToken,
                    "scope" to LIVE_SCOPE
                )
            )
            MsaDirectTokenPair(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifBlank { currentRefreshToken },
                expiresInSeconds = json.optLong("expires_in", 3600L),
                userId = json.optString("user_id", "")
            )
        }

    /**
     * Ejecuta el pipeline completo de Xbox Live (RPS -> Dual XSTS -> Dual GSSV gsToken).
     */
    suspend fun completeXboxAndGssvChain(
        tokenPair: MsaDirectTokenPair,
        spoofedIp: String? = null
    ): XCloudTokenBundle = withContext(Dispatchers.IO) {
        // Step 1: RPS User Token with "d=" prefix
        val rpsTicket = if (tokenPair.accessToken.startsWith("d=")) tokenPair.accessToken else "d=${tokenPair.accessToken}"
        val rpsPayload = JSONObject().apply {
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", rpsTicket)
            })
        }
        val rpsResp = postJson("https://user.auth.xboxlive.com/user/authenticate", rpsPayload)
        val userToken = rpsResp.getString("Token")
        val uhs = rpsResp.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
            .getString("uhs")

        // Step 2: XSTS Web API Token (http://xboxlive.com)
        val webXstsResp = requestXstsToken(userToken, "http://xboxlive.com")
        val webXstsToken = webXstsResp.getString("Token")
        val xui = webXstsResp.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
        val gamertag = xui.optString("gtg", "Xbox Player")
        val xuid = xui.optString("xid", "")

        // Step 3: XSTS GSSV Streaming Token (http://gssv.xboxlive.com/)
        val gssvXstsResp = requestXstsToken(userToken, "http://gssv.xboxlive.com/")
        val gssvXstsToken = gssvXstsResp.getString("Token")

        // Step 4: GSSV v2 login for xgpuweb
        val headers = mutableMapOf("x-gssv-client" to "XboxComBrowser")
        if (!spoofedIp.isNullOrBlank()) {
            headers["X-Forwarded-For"] = spoofedIp
        }

        val xgpuResp = postJson(
            url = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
            body = JSONObject().apply {
                put("token", gssvXstsToken)
                put("offeringId", "xgpuweb")
            },
            extraHeaders = headers
        )
        val xgpuGsToken = xgpuResp.getString("gsToken")
        val durationSeconds = xgpuResp.optLong("durationInSeconds", 14_400L)
        val regions = xgpuResp.getJSONObject("offeringSettings").getJSONArray("regions")

        var defaultUri = ""
        for (i in 0 until regions.length()) {
            val reg = regions.getJSONObject(i)
            if (reg.optBoolean("isDefault", false) || defaultUri.isEmpty()) {
                defaultUri = reg.getString("baseUri")
            }
        }

        // Optional F2P token
        val f2pGsToken = runCatching {
            val f2pResp = postJson(
                url = "https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user",
                body = JSONObject().apply {
                    put("token", gssvXstsToken)
                    put("offeringId", "xgpuwebf2p")
                },
                extraHeaders = headers
            )
            f2pResp.optString("gsToken").takeIf { it.isNotBlank() }
        }.getOrNull()

        val bundle = XCloudTokenBundle(
            gamertag = gamertag,
            xuid = xuid,
            uhs = uhs,
            msaAccessToken = tokenPair.accessToken,
            msaRefreshToken = tokenPair.refreshToken,
            webXstsToken = webXstsToken,
            gssvXstsToken = gssvXstsToken,
            xgpuGsToken = xgpuGsToken,
            xhomeGsToken = null,
            f2pGsToken = f2pGsToken,
            defaultRegionBaseUri = defaultUri,
            regionsJson = regions.toString(),
            gsTokenExpiresAtEpochMs = System.currentTimeMillis() + (durationSeconds * 1000L)
        )

        tokenStorage?.saveTokenBundle(bundle)
        _authEvents.tryEmit(bundle)
        bundle
    }

    private fun requestXstsToken(userToken: String, relyingParty: String): JSONObject {
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().put(userToken))
            })
            put("RelyingParty", relyingParty)
            put("TokenType", "JWT")
        }
        return postJson("https://xsts.auth.xboxlive.com/xsts/authorize", payload)
    }

    private fun postLiveTokenRequest(params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = (URL("https://login.live.com/oauth20_token.srf").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Cache-Control", "no-store, must-revalidate, no-cache")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()

        if (code !in 200..299) {
            throw IllegalStateException("login.live.com HTTP $code: $responseText")
        }
        return JSONObject(responseText)
    }

    private fun postJson(
        url: String,
        body: JSONObject,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-xbl-contract-version", "1")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $text")
        }
        return JSONObject(text)
    }

    fun generatePkceVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    fun generatePkceChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncode(digest)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
