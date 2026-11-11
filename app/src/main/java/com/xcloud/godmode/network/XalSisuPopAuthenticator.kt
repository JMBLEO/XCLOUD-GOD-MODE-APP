package com.xcloud.godmode.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

data class SisuAuthSession(
    val msaOauthRedirectUrl: String,
    val sisuSessionId: String,
    val deviceToken: String,
    val codeVerifier: String,
    val state: String,
    val appId: String = PrimaryAppIdAuthEngine.PRIMARY_APP_ID
)

data class SisuTokensResponse(
    val userToken: String,
    val titleToken: String,
    val xstsAuthorizationToken: String,
    val uhs: String,
    val gamertag: String,
    val xuid: String
)

/**
 * XalSisuPopAuthenticator: Native implementation of the official Xbox Authentication Library (XAL)
 * with Proof-of-Possession (PoP) EC P-256 signatures and SISU preauthorization.
 * 
 * Uses the official active Xbox Main App ID hierarchy and captures system browser redirects
 * via ms-xal-<AppId>://auth without WebView.
 */
class XalSisuPopAuthenticator(
    private val tokenStorage: XCloudTokenStorage? = null
) {
    companion object {
        private const val TAG = "XalSisuPopAuth"

        // Official active App ID hierarchy
        const val XBOX_MAIN_APP_ID = PrimaryAppIdAuthEngine.PRIMARY_APP_ID
        const val XBOX_REDIRECT_URI = "ms-xal-$XBOX_MAIN_APP_ID://auth"
        const val TITLE_ID_XBOX_MOBILE = PrimaryAppIdAuthEngine.TITLE_ID_CLOUD_COMPANION

        // Seconds between 1601-01-01 (Windows FileTime) and 1970-01-01 (Unix Epoch)
        private const val WINDOWS_EPOCH_OFFSET_SECONDS = 11644473600L

        // Global shared state for active auth sessions caught via deep link
        @Volatile
        var currentActiveSession: SisuAuthSession? = null

        private val _authEvents = MutableSharedFlow<XCloudTokenBundle>(extraBufferCapacity = 1)
        val authEvents: SharedFlow<XCloudTokenBundle> = _authEvents.asSharedFlow()
    }

    private val ecKeyPair: KeyPair by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        generator.generateKeyPair()
    }

    val publicKeyJwk: JSONObject by lazy {
        val pub = ecKeyPair.public as ECPublicKey
        val xBytes = toFixedLength32Bytes(pub.w.affineX)
        val yBytes = toFixedLength32Bytes(pub.w.affineY)
        JSONObject().apply {
            put("kty", "EC")
            put("x", base64UrlEncode(xBytes))
            put("y", base64UrlEncode(yBytes))
            put("crv", "P-256")
            put("alg", "ES256")
            put("use", "sig")
        }
    }

    /**
     * PASO 1 y 2: Obtiene el DeviceToken firmado con PoP y solicita a SISU la URL preautorizada.
     */
    suspend fun createSisuAuthSession(deviceUuid: UUID = UUID.randomUUID()): SisuAuthSession =
        withContext(Dispatchers.IO) {
            val deviceToken = requestXboxDeviceToken(deviceUuid)
            val codeVerifier = generatePkceVerifier()
            val codeChallenge = generatePkceChallengeS256(codeVerifier)
            val state = base64UrlEncode(UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))

            val session = PrimaryAppIdAuthEngine.executeWithHierarchyFallback { appId, redirectUri, titleId ->
                val sisuBody = JSONObject().apply {
                    put("AppId", appId)
                    put("TitleId", titleId)
                    put("RedirectUri", redirectUri)
                    put("DeviceToken", deviceToken)
                    put("Sandbox", "RETAIL")
                    put("TokenType", "code")
                    put("Offers", JSONArray().put(PrimaryAppIdAuthEngine.MBI_SSL_SCOPE))
                    put("Query", JSONObject().apply {
                        put("display", "android_phone")
                        put("code_challenge", codeChallenge)
                        put("code_challenge_method", "S256")
                        put("state", state)
                    })
                }.toString()

                val url = "https://sisu.xboxlive.com/authenticate"
                val signature = signXboxRequest(url = url, authHeader = "", body = sisuBody)

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("x-xbl-contract-version", "1")
                    setRequestProperty("Signature", signature)
                    setRequestProperty("Cache-Control", "no-store, no-cache")
                }
                conn.outputStream.use { it.write(sisuBody.toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                val responseText = (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()

                if (responseCode !in 200..299) {
                    throw IllegalStateException("SISU Authenticate HTTP $responseCode: $responseText")
                }

                val sessionId = conn.getHeaderField("X-SessionId").orEmpty()
                val json = JSONObject(responseText)

                SisuAuthSession(
                    msaOauthRedirectUrl = json.getString("MsaOauthRedirect"),
                    sisuSessionId = sessionId,
                    deviceToken = deviceToken,
                    codeVerifier = codeVerifier,
                    state = state,
                    appId = appId
                )
            }
            currentActiveSession = session
            session
        }

    /**
     * PASO 3: Cuando el navegador redirige a ms-xal-<AppId>://auth/?code=...,
     * canjea el código directamente en SISU Authorize obteniendo todos los tokens de Xbox.
     */
    suspend fun authorizeWithSisuCode(
        authCode: String,
        session: SisuAuthSession = currentActiveSession ?: throw IllegalStateException("No active SISU session found")
    ): SisuTokensResponse = withContext(Dispatchers.IO) {
        val cleanCode = authCode.trim()
        val body = JSONObject().apply {
            put("AccessToken", "t=$cleanCode")
            put("AppId", session.appId)
            put("DeviceToken", session.deviceToken)
            put("Sandbox", "RETAIL")
            put("SiteName", "user.auth.xboxlive.com")
            put("SessionId", session.sisuSessionId)
            put("UseModernGamertag", true)
            put("ProofKey", publicKeyJwk)
            put("code_verifier", session.codeVerifier)
        }.toString()

        val url = "https://sisu.xboxlive.com/authorize"
        val signature = signXboxRequest(url = url, authHeader = "", body = body)

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-xbl-contract-version", "1")
            setRequestProperty("Signature", signature)
            setRequestProperty("Cache-Control", "no-store, no-cache")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()

        if (code !in 200..299) {
            throw IllegalStateException("SISU Authorize HTTP $code: $text")
        }

        val json = JSONObject(text)
        val userToken = json.getJSONObject("UserToken").getString("Token")
        val titleToken = json.optJSONObject("TitleToken")?.optString("Token").orEmpty()
        val authObj = json.getJSONObject("AuthorizationToken")
        val xstsToken = authObj.getString("Token")
        val xui = authObj.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)

        SisuTokensResponse(
            userToken = userToken,
            titleToken = titleToken,
            xstsAuthorizationToken = xstsToken,
            uhs = xui.getString("uhs"),
            gamertag = xui.optString("gtg", "Xbox Player"),
            xuid = xui.optString("xid", "")
        )
    }

    /**
     * Completa el pipeline conectando el UserToken de SISU con los gsTokens de Azure (xgpuweb y xgpuwebf2p).
     */
    suspend fun completeSisuTokenChain(
        tokens: SisuTokensResponse,
        spoofedIp: String? = null
    ): XCloudTokenBundle = withContext(Dispatchers.IO) {
        // Request dedicated XSTS streaming token for http://gssv.xboxlive.com/
        val gssvPayload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().put(tokens.userToken))
            })
            put("RelyingParty", "http://gssv.xboxlive.com/")
            put("TokenType", "JWT")
        }

        val gssvUrl = "https://xsts.auth.xboxlive.com/xsts/authorize"
        val gssvSig = signXboxRequest(url = gssvUrl, authHeader = "", body = gssvPayload.toString())
        val gssvConn = (URL(gssvUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Signature", gssvSig)
        }
        gssvConn.outputStream.use { it.write(gssvPayload.toString().toByteArray(Charsets.UTF_8)) }
        val gssvCode = gssvConn.responseCode
        val gssvText = (if (gssvCode in 200..299) gssvConn.inputStream else gssvConn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (gssvCode !in 200..299) {
            throw IllegalStateException("XSTS Streaming HTTP $gssvCode: $gssvText")
        }
        val gssvXstsToken = JSONObject(gssvText).getString("Token")

        // Exchange for Game Pass Ultimate gsToken
        val headers = mutableMapOf("x-gssv-client" to "XboxComBrowser")
        if (!spoofedIp.isNullOrBlank()) {
            headers["X-Forwarded-For"] = spoofedIp
        }

        val xgpuConn = (URL("https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val xgpuBody = JSONObject().apply {
            put("token", gssvXstsToken)
            put("offeringId", "xgpuweb")
        }.toString()
        xgpuConn.outputStream.use { it.write(xgpuBody.toByteArray(Charsets.UTF_8)) }
        val xgpuText = xgpuConn.inputStream.bufferedReader().readText()
        val xgpuJson = JSONObject(xgpuText)
        val xgpuGsToken = xgpuJson.getString("gsToken")
        val durationSeconds = xgpuJson.optLong("durationInSeconds", 14_400L)
        val regions = xgpuJson.getJSONObject("offeringSettings").getJSONArray("regions")

        var defaultUri = ""
        for (i in 0 until regions.length()) {
            val reg = regions.getJSONObject(i)
            if (reg.optBoolean("isDefault", false) || defaultUri.isEmpty()) {
                defaultUri = reg.getString("baseUri")
            }
        }

        // Optional F2P token
        val f2pGsToken = runCatching {
            val f2pConn = (URL("https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val f2pBody = JSONObject().apply {
                put("token", gssvXstsToken)
                put("offeringId", "xgpuwebf2p")
            }.toString()
            f2pConn.outputStream.use { it.write(f2pBody.toByteArray(Charsets.UTF_8)) }
            val f2pText = f2pConn.inputStream.bufferedReader().readText()
            JSONObject(f2pText).optString("gsToken").takeIf { it.isNotBlank() }
        }.getOrNull()

        val bundle = XCloudTokenBundle(
            gamertag = tokens.gamertag,
            xuid = tokens.xuid,
            uhs = tokens.uhs,
            msaAccessToken = tokens.userToken,
            msaRefreshToken = "",
            webXstsToken = tokens.xstsAuthorizationToken,
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

    private fun requestXboxDeviceToken(deviceUuid: UUID): String {
        val body = JSONObject().apply {
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("AuthMethod", "ProofOfPossession")
                put("Id", "{${deviceUuid}}")
                put("DeviceType", "Android")
                put("SerialNumber", "{${UUID.randomUUID()}}")
                put("Version", "15.0")
                put("ProofKey", publicKeyJwk)
            })
        }.toString()

        val url = "https://device.auth.xboxlive.com/device/authenticate"
        val signature = signXboxRequest(url = url, authHeader = "", body = body)

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-xbl-contract-version", "1")
            setRequestProperty("Signature", signature)
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()

        if (code !in 200..299) {
            throw IllegalStateException("Device Authenticate HTTP $code: $text")
        }
        return JSONObject(text).getString("Token")
    }

    /**
     * Firma binaria Windows FileTime + SHA-256 + ECDSA IEEE P1363 exigida por Xbox Live (JoseStuff).
     */
    fun signXboxRequest(url: String, authHeader: String, body: String): String {
        val windowsTimestamp = (Instant.now().epochSecond + WINDOWS_EPOCH_OFFSET_SECONDS) * 10_000_000L
        val pathAndQuery = URL(url).let { u ->
            if (u.query.isNullOrEmpty()) u.path else "${u.path}?${u.query}"
        }

        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { dos ->
            dos.writeInt(1) // Policy Version = 1 (Big Endian)
            dos.writeByte(0)
            dos.writeLong(windowsTimestamp)
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

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(ecKeyPair.private)
        signer.update(buffer.toByteArray())
        val derSignature = signer.sign()
        val p1363Signature = derToP1363(derSignature)

        val finalPacket = ByteArrayOutputStream()
        DataOutputStream(finalPacket).use { dos ->
            dos.writeInt(1)
            dos.writeLong(windowsTimestamp)
            dos.write(p1363Signature)
        }
        return Base64.encodeToString(finalPacket.toByteArray(), Base64.NO_WRAP)
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2
        if (der[1].toInt() and 0x80 != 0) offset += (der[1].toInt() and 0x7F)
        offset++ // 0x02 tag
        val rLen = der[offset++].toInt()
        val rBytes = der.copyOfRange(offset, offset + rLen)
        offset += rLen
        offset++ // 0x02 tag
        val sLen = der[offset++].toInt()
        val sBytes = der.copyOfRange(offset, offset + sLen)

        val result = ByteArray(64)
        copyBigIntBytes(rBytes, result, 0)
        copyBigIntBytes(sBytes, result, 32)
        return result
    }

    private fun copyBigIntBytes(src: ByteArray, dst: ByteArray, dstOffset: Int) {
        val clean = if (src.size > 32 && src[0] == 0.toByte()) src.copyOfRange(1, src.size) else src
        val start = dstOffset + (32 - clean.size).coerceAtLeast(0)
        System.arraycopy(clean, 0, dst, start, clean.size.coerceAtMost(32))
    }

    private fun toFixedLength32Bytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val out = ByteArray(32)
        copyBigIntBytes(bytes, out, 0)
        return out
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
