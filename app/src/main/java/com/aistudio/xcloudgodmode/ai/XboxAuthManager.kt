package com.aistudio.xcloudgodmode.ai

import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.util.*
import java.util.concurrent.TimeUnit

class XboxAuthManager {

    private val CLIENT_ID = "000000004c20a908" // ID Oficial Xbox Game Pass
    private val TITLE_ID = "1016898439"
    private val REDIRECT_URI = "ms-xal-000000004c20a908://auth"
    private val SCOPES = "service::user.auth.xboxlive.com::MBI_SSL"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Xbox/2402.1001.20.0 (Windows NT 10.0; Win64; x64)")
                .build()
            chain.proceed(request)
        }
        .build()

    private val keyPair: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }

    private val privateKey: ECPrivateKey get() = keyPair.private as ECPrivateKey
    private val publicKey: ECPublicKey get() = keyPair.public as ECPublicKey

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

    // =========================================================================
    // MOTOR CRIPTOGRÁFICO XAL
    // =========================================================================

    private fun getPublicKeyJwk(): JSONObject {
        val point: ECPoint = publicKey.w
        val xBytes = point.affineX.toByteArray().stripLeadingZero()
        val yBytes = point.affineY.toByteArray().stripLeadingZero()

        // Asegurar 32 bytes para las coordenadas x e y
        val xFixed = ByteArray(32)
        val yFixed = ByteArray(32)
        System.arraycopy(xBytes, 0, xFixed, 32 - xBytes.size, xBytes.size)
        System.arraycopy(yBytes, 0, yFixed, 32 - yBytes.size, yBytes.size)

        return JSONObject().apply {
            put("kty", "EC")
            put("x", base64UrlEncode(xFixed))
            put("y", base64UrlEncode(yFixed))
            put("crv", "P-256")
            put("alg", "ES256")
            put("use", "sig")
        }
    }

    private fun signXboxRequest(url: String, method: String, bodyJsonString: String, authHeader: String = ""): String {
        val uri = java.net.URI(url)
        val pathAndQuery = uri.path + (if (uri.query != null) "?" + uri.query else "")

        // Windows FileTime (100ns intervals since 1601-01-01)
        val windowsTicks = (System.currentTimeMillis() * 10000) + 116444736000000000L

        val timestampBuffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        timestampBuffer.putLong(windowsTicks)
        val timestampBytes = timestampBuffer.array()

        val policyBytes = byteArrayOf(0, 0, 0, 1)

        val signatureBase = mutableListOf<Byte>()

        fun appendWithNull(bytes: ByteArray) {
            signatureBase.addAll(bytes.toList())
            signatureBase.add(0)
        }

        // SignatureBase = Policy + \0 + Timestamp + \0 + Method + \0 + URI + \0 + Authorization + \0 + Body + \0
        appendWithNull(policyBytes)
        appendWithNull(timestampBytes)
        appendWithNull(method.uppercase().toByteArray(Charsets.UTF_8))
        appendWithNull(pathAndQuery.toByteArray(Charsets.UTF_8))
        appendWithNull(authHeader.toByteArray(Charsets.UTF_8))
        appendWithNull(bodyJsonString.toByteArray(Charsets.UTF_8))

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(signatureBase.toByteArray())
        val derSignature = signature.sign()

        val ieeeP1363Sig = convertDerToP1363(derSignature)

        // Header format: Binary(Policy(4) + Timestamp(8) + Signature(64)) -> Base64
        val finalHeaderBytes = ByteBuffer.allocate(76).order(ByteOrder.BIG_ENDIAN)
        finalHeaderBytes.put(policyBytes)
        finalHeaderBytes.put(timestampBytes)
        finalHeaderBytes.put(ieeeP1363Sig)

        return Base64.encodeToString(finalHeaderBytes.array(), Base64.NO_WRAP)
    }

    private fun convertDerToP1363(der: ByteArray): ByteArray {
        // DER format: 0x30 [len] 0x02 [rLen] [r] 0x02 [sLen] [s]
        var offset = 2 // Skip 0x30 and len
        
        // Extract R
        offset++ // Skip 0x02
        val rLen = der[offset++].toInt()
        val r = der.copyOfRange(offset, offset + rLen).stripLeadingZero()
        offset += rLen
        
        // Extract S
        offset++ // Skip 0x02
        val sLen = der[offset++].toInt()
        val s = der.copyOfRange(offset, offset + sLen).stripLeadingZero()

        val p1363 = ByteArray(64)
        System.arraycopy(r, 0, p1363, 32 - r.size, r.size)
        System.arraycopy(s, 0, p1363, 64 - s.size, s.size)
        return p1363
    }

    private fun ByteArray.stripLeadingZero(): ByteArray {
        return if (this.size > 1 && this[0].toInt() == 0) {
            this.copyOfRange(1, this.size)
        } else {
            this
        }
    }

    // =========================================================================
    // ETAPAS DE AUTENTICACIÓN
    // =========================================================================

    suspend fun getDeviceToken(): String {
        val endpoint = "https://device.auth.xboxlive.com/device/authenticate"
        val payload = JSONObject().apply {
            val props = JSONObject()
            props.put("AuthMethod", "ProofOfPossession")
            props.put("Id", "{${UUID.randomUUID()}}")
            props.put("DeviceType", "Android")
            props.put("SerialNumber", "{${UUID.randomUUID()}}")
            props.put("Version", "15.0")
            props.put("ProofKey", getPublicKeyJwk())
            put("Properties", props)
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }

        val bodyString = payload.toString()
        val signature = signXboxRequest(endpoint, "POST", bodyString)

        val request = Request.Builder()
            .url(endpoint)
            .post(bodyString.toRequestBody("application/json".toMediaType()))
            .header("x-xbl-contract-version", "1")
            .header("Signature", signature)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Device Auth failed: ${response.code} ${response.body?.string()}")
            val json = JSONObject(response.body?.string() ?: "")
            return json.getString("Token")
        }
    }

    suspend fun createLoginSession(): PendingAuthContext {
        val deviceToken = getDeviceToken()
        val verifierBytes = ByteArray(32)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = base64UrlEncode(verifierBytes)
        
        val digest = MessageDigest.getInstance("SHA-256")
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

        val bodyString = payload.toString()
        val signature = signXboxRequest(endpoint, "POST", bodyString)

        val request = Request.Builder()
            .url(endpoint)
            .post(bodyString.toRequestBody("application/json".toMediaType()))
            .header("x-xbl-contract-version", "1")
            .header("Signature", signature)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("SISU Auth failed: ${response.code} ${response.body?.string()}")
            val sessionId = response.header("x-sessionid") ?: ""
            val json = JSONObject(response.body?.string() ?: "")
            return PendingAuthContext(
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
    ): XboxReadySession {
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

        return XboxReadySession(
            gamertag = gamertag,
            uhs = uhs,
            msaRefreshToken = msaTokens.getString("refresh_token"),
            webXstsToken = webXstsToken,
            gssvXstsToken = gssvXstsToken,
            xCloudGsToken = xCloudLogin.first,
            xCloudRegionUri = xCloudLogin.second
        )
    }

    private suspend fun exchangeCodeForMsaToken(code: String, codeVerifier: String): JSONObject {
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", SCOPES)
            .build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MSA Token Exchange failed: ${response.code} ${response.body?.string()}")
            return JSONObject(response.body?.string() ?: "")
        }
    }

    private suspend fun authorizeWithSisu(msaAccessToken: String, deviceToken: String, sessionId: String): JSONObject {
        val endpoint = "https://sisu.xboxlive.com/authorize"
        val payload = JSONObject().apply {
            put("AccessToken", "t=$msaAccessToken")
            put("AppId", CLIENT_ID)
            put("DeviceToken", deviceToken)
            put("Sandbox", "RETAIL")
            put("SiteName", "user.auth.xboxlive.com")
            put("UseModernGamertag", true)
            put("ProofKey", getPublicKeyJwk())
            put("SessionId", sessionId)
        }

        val bodyString = payload.toString()
        val signature = signXboxRequest(endpoint, "POST", bodyString)

        val request = Request.Builder()
            .url(endpoint)
            .post(bodyString.toRequestBody("application/json".toMediaType()))
            .header("x-xbl-contract-version", "1")
            .header("Signature", signature)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("SISU Authorize failed: ${response.code} ${response.body?.string()}")
            return JSONObject(response.body?.string() ?: "")
        }
    }

    private suspend fun getGssvXstsToken(userToken: String): String {
        val endpoint = "https://xsts.auth.xboxlive.com/xsts/authorize"
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().apply { put(userToken) })
            })
            put("RelyingParty", "http://gssv.xboxlive.com/")
            put("TokenType", "JWT")
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("x-xbl-contract-version", "1")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("GSSV XSTS failed: ${response.code} ${response.body?.string()}")
            val json = JSONObject(response.body?.string() ?: "")
            return json.getString("Token")
        }
    }

    private suspend fun loginToGssvStreamingServer(
        loginEndpoint: String,
        offeringId: String,
        gssvXstsToken: String
    ): Pair<String, String> {
        val payload = JSONObject().apply {
            put("token", gssvXstsToken)
            put("offeringId", offeringId)
        }

        val request = Request.Builder()
            .url(loginEndpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("x-gssv-client", "XboxComBrowser")
            .build()

        httpClient.newCall(request).execute().use { response ->
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
            
            return Pair(gsToken, regionUri)
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
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
