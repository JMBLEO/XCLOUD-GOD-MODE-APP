package com.aistudio.xcloudgodmode.ai.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.*

class XboxFullAuthManager(private val networkClient: NetworkClient) {

    private val CLIENT_ID = "000000004c20a908"
    private val TITLE_ID = "1016898439"
    private val REDIRECT_URI = "ms-xal-000000004c20a908://auth"
    private val SCOPES = "service::user.auth.xboxlive.com::MBI_SSL"

    data class PendingAuthContext(
        val authUrl: String,
        val codeVerifier: String,
        val state: String,
        val sessionId: String,
        val deviceToken: String
    )

    data class XboxReadySession(
        val gamertag: String,
        val uhs: String,
        val msaRefreshToken: String,
        val webXstsToken: String,
        val gssvXstsToken: String,
        val xCloudGsToken: String,
        val xCloudRegionUri: String
    )

    suspend fun getDeviceToken(): String = withContext(Dispatchers.IO) {
        val endpoint = "https://device.auth.xboxlive.com/device/authenticate"
        val payload = JSONObject().apply {
            val props = JSONObject()
            props.put("AuthMethod", "ProofOfPossession")
            props.put("Id", "{${UUID.randomUUID()}}")
            props.put("DeviceType", "Android")
            props.put("SerialNumber", "{${UUID.randomUUID()}}")
            props.put("Version", "15.0")
            props.put("ProofKey", networkClient.getPublicKeyJwk())
            put("Properties", props)
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }

        networkClient.post(endpoint, payload).use { response ->
            if (!response.isSuccessful) throw Exception("Device Auth failed: ${response.code} ${response.body?.string()}")
            val json = JSONObject(response.body?.string() ?: "")
            return@withContext json.getString("Token")
        }
    }

    suspend fun createLoginSession(): PendingAuthContext = withContext(Dispatchers.IO) {
        val deviceToken = getDeviceToken()
        val verifierBytes = ByteArray(32)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = base64UrlEncode(verifierBytes)
        
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val challengeHash = digest.digest(codeVerifier.toByteArray(Charsets.UTF_8))
        val codeChallenge = base64UrlEncode(challengeHash)
        
        val state = base64UrlEncode(UUID.randomUUID().toString().toByteArray().sliceArray(0..15))

        val endpoint = "https://sisu.xboxlive.com/authenticate"
        val payload = JSONObject().apply {
            put("AppId", CLIENT_ID)
            put("TitleId", TITLE_ID)
            put("RedirectUri", REDIRECT_URI)
            put("DeviceToken", deviceToken)
            put("Sandbox", "RETAIL")
            put("TokenType", "code")
            put("Offers", JSONArray().apply { put(SCOPES) })
            put("Query", JSONObject().apply {
                put("display", "android_phone")
                put("code_challenge", codeChallenge)
                put("code_challenge_method", "S256")
                put("state", state)
            })
        }

        networkClient.post(endpoint, payload).use { response ->
            if (!response.isSuccessful) throw Exception("SISU Auth failed: ${response.code} ${response.body?.string()}")
            val sessionId = response.header("x-sessionid") ?: ""
            val json = JSONObject(response.body?.string() ?: "")
            return@withContext PendingAuthContext(
                authUrl = json.getString("MsaOauthRedirect"),
                codeVerifier = codeVerifier,
                state = state,
                sessionId = sessionId,
                deviceToken = deviceToken
            )
        }
    }

    suspend fun finishLoginAndPrepareGaming(
        rawRedirectUrlOrCode: String,
        context: PendingAuthContext
    ): XboxReadySession = withContext(Dispatchers.IO) {
        val code = extractAuthCode(rawRedirectUrlOrCode)
        if (code.isBlank()) throw Exception("No valid code found")

        val msaTokens = exchangeCodeForMsaToken(code, context.codeVerifier)
        val sisuData = authorizeWithSisu(msaTokens.getString("access_token"), context.deviceToken, context.sessionId)

        val userToken = sisuData.getJSONObject("UserToken").getString("Token")
        val authData = sisuData.getJSONObject("AuthorizationToken")
        val webXstsToken = authData.getString("Token")
        val xui = authData.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)
        val uhs = xui.getString("uhs")
        val gamertag = xui.optString("gtg", "Player")

        val gssvXstsToken = getGssvXstsToken(userToken)
        val xCloudLogin = loginToGssvStreamingServer(
            "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user",
            "xgpuweb",
            gssvXstsToken
        )

        return@withContext XboxReadySession(
            gamertag = gamertag,
            uhs = uhs,
            msaRefreshToken = msaTokens.getString("refresh_token"),
            webXstsToken = webXstsToken,
            gssvXstsToken = gssvXstsToken,
            xCloudGsToken = xCloudLogin.first,
            xCloudRegionUri = xCloudLogin.second
        )
    }

    private suspend fun exchangeCodeForMsaToken(code: String, codeVerifier: String): JSONObject = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", SCOPES)
            .build()

        // MSA Token exchange doesn't use XAL signature, just standard OAuth2
        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(formBody)
            .build()

        // We use the internal okHttpClient because this endpoint is not XAL-protected
        val client = OkHttpClient() 
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MSA Token Exchange failed: ${response.code} ${response.body?.string()}")
            return@withContext JSONObject(response.body?.string() ?: "")
        }
    }

    private suspend fun authorizeWithSisu(msaAccessToken: String, deviceToken: String, sessionId: String): JSONObject = withContext(Dispatchers.IO) {
        val endpoint = "https://sisu.xboxlive.com/authorize"
        val payload = JSONObject().apply {
            put("AccessToken", "t=$msaAccessToken")
            put("AppId", CLIENT_ID)
            put("DeviceToken", deviceToken)
            put("Sandbox", "RETAIL")
            put("SiteName", "user.auth.xboxlive.com")
            put("UseModernGamertag", true)
            put("ProofKey", networkClient.getPublicKeyJwk())
            put("SessionId", sessionId)
        }

        networkClient.post(endpoint, payload).use { response ->
            if (!response.isSuccessful) throw Exception("SISU Authorize failed: ${response.code} ${response.body?.string()}")
            return@withContext JSONObject(response.body?.string() ?: "")
        }
    }

    private suspend fun getGssvXstsToken(userToken: String): String = withContext(Dispatchers.IO) {
        val endpoint = "https://xsts.auth.xboxlive.com/xsts/authorize"
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().apply { put(userToken) })
            })
            put("RelyingParty", "http://gssv.xboxlive.com/")
            put("TokenType", "JWT")
        }

        networkClient.post(endpoint, payload).use { response ->
            if (!response.isSuccessful) throw Exception("GSSV XSTS failed: ${response.code} ${response.body?.string()}")
            val json = JSONObject(response.body?.string() ?: "")
            return@withContext json.getString("Token")
        }
    }

    private suspend fun loginToGssvStreamingServer(
        loginEndpoint: String,
        offeringId: String,
        gssvXstsToken: String
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("token", gssvXstsToken)
            put("offeringId", offeringId)
        }

        networkClient.post(loginEndpoint, payload, headers = mapOf("x-gssv-client" to "XboxComBrowser")).use { response ->
            if (!response.isSuccessful) throw Exception("GSSV Login failed: ${response.code} ${response.body?.string()}")
            val json = JSONObject(response.body?.string() ?: "")
            val gsToken = json.getString("gsToken")
            
            val regions = json.optJSONObject("offeringSettings")?.optJSONArray("regions")
            var regionUri = "https://brs.core.gssv-play-prod.xboxlive.com" // Fallback
            
            if (regions != null && regions.length() > 0) {
                for (i in 0 until regions.length()) {
                    val region = regions.getJSONObject(i)
                    if (region.optBoolean("isDefault", false)) {
                        regionUri = region.getString("baseUri")
                        break
                    }
                    if (i == 0) regionUri = region.getString("baseUri")
                }
            }
            
            return@withContext Pair(gsToken, regionUri)
        }
    }

    private fun extractAuthCode(input: String): String {
        val clean = input.trim()
        if (clean.contains("code=")) {
            val uri = android.net.Uri.parse(clean)
            return uri.getQueryParameter("code") ?: ""
        }
        return clean
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
