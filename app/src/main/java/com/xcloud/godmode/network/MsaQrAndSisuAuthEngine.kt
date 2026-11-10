package com.xcloud.godmode.network

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
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
import kotlin.coroutines.coroutineContext

data class MsaQrSessionTicket(
    val clientIdUsed: String,
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
    val sisuTicket: SisuBrowserRedirectTicket? = null
)

data class SisuBrowserRedirectTicket(
    val appIdUsed: String,
    val titleIdUsed: String,
    val redirectUri: String,
    val msaOauthRedirectUrl: String,
    val codeVerifier: String,
    val deviceToken: String,
    val sisuSessionId: String
)

/**
 * MsaQrAndSisuAuthEngine: Unified engine for MSA QR Login and SISU 1-Click flows.
 * Redesigned to eliminate "404 Not Found" and "invalid_request" by using official SISU
 * pre-authorized URLs for both flows.
 */
class MsaQrAndSisuAuthEngine(
    private val vaultStorage: SessionVaultPersistence
) {

    companion object {
        // Scope oficial preautorizado de Xbox Live en login.live.com
        const val MBI_SSL_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        
        // Cascada de clientes XAL para la pestaña "Inicio Rápido" y "Vincular QR / TV"
        val SISU_ONE_CLICK_CLIENTS = listOf(
            Triple("00000000402b5328", "328178078", "ms-xal-00000000402b5328://auth"),
            Triple("000000004c20a908", "1016898439", "ms-xal-000000004c20a908://auth"),
            Triple("000000004827c78e", "328178078", "ms-xal-000000004827c78e://auth")
        )

        private const val WINDOWS_EPOCH_OFFSET = 11644473600L
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
     * =========================================================================
     * PESTAÑA "VINCULAR QR / TV": GENERADOR QR BASADO EN AZURE AD (Sustituye 404)
     * =========================================================================
     */
    suspend fun requestMsaQrCodeTicket(): MsaQrSessionTicket = withContext(Dispatchers.IO) {
        // Client ID público universal de Microsoft (Azure CLI / Device Flow) autorizado para /devicecode
        val publicClientId = "04b07795-8ddb-461a-bbee-02f9e1bf7b46"
        val scope = "xboxlive.signin offline_access openid profile"

        val json = postFormUrlEncoded(
            url = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
            params = mapOf(
                "client_id" to publicClientId,
                "scope" to scope
            )
        )

        val userCode = json.getString("user_code")       // Ej: WXYZ7890
        val deviceCode = json.getString("device_code")
        val verificationUri = json.optString("verification_uri", "https://microsoft.com/link")
        val completeUri = json.optString("verification_uri_complete", "$verificationUri?otc=$userCode")

        MsaQrSessionTicket(
            clientIdUsed = publicClientId,
            userCode = userCode,
            deviceCode = deviceCode,
            // verificationUri almacena la URL completa que incluye el código otc para el QR
            verificationUri = completeUri,
            expiresInSeconds = json.optInt("expires_in", 900),
            intervalSeconds = json.optInt("interval", 5).coerceAtLeast(5),
            sisuTicket = null
        )
    }

    suspend fun pollMsaQrTicketUntilApproved(
        ticket: MsaQrSessionTicket,
        spoofedForwardedForIp: String? = null,
        onTickRemainingSeconds: (Int) -> Unit
    ): CompleteXboxSession = withContext(Dispatchers.IO) {
        val deadlineMs = System.currentTimeMillis() + (ticket.expiresInSeconds * 1000L)
        var intervalMs = ticket.intervalSeconds * 1000L

        while (isActive && System.currentTimeMillis() < deadlineMs) {
            val remainingSec = ((deadlineMs - System.currentTimeMillis()) / 1000L).toInt()
            onTickRemainingSeconds(remainingSec)

            delay(intervalMs)

            val conn = (URL("https://login.microsoftonline.com/consumers/oauth2/v2.0/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }

            val body = mapOf(
                "client_id" to ticket.clientIdUsed,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to ticket.deviceCode
            ).entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()

            if (code in 200..299) {
                val json = JSONObject(text)
                val access = json.getString("access_token")
                val refresh = json.getString("refresh_token")

                val fullSession = buildCompleteTokenPipeline(
                    msaAccessToken = access,
                    msaRefreshToken = refresh,
                    clientIdUsed = ticket.clientIdUsed,
                    spoofedIp = spoofedForwardedForIp
                )
                vaultStorage.saveSession(fullSession)
                return@withContext fullSession
            }

            val errJson = runCatching { JSONObject(text) }.getOrNull()
            val errCode = errJson?.optString("error").orEmpty()

            when (errCode) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs += 5_000L
                "expired_token", "code_expired" -> throw IllegalStateException("El código QR ha expirado.")
                "access_denied" -> throw IllegalStateException("Autorización denegada.")
                else -> {
                    if (code == 400 && !errCode.contains("authorization_pending")) {
                        // Continuar sondeando si es error de pendiente
                    }
                }
            }
        }
        throw IllegalStateException("Tiempo de espera agotado para el código QR.")
    }

    /**
     * =========================================================================
     * PESTAÑA "INICIO RÁPIDO": FLUJO OFICIAL SISU 1-CLIC (sisu.xboxlive.com)
     * =========================================================================
     */
    suspend fun prepareSisuOneClickTicket(): SisuBrowserRedirectTicket = withContext(Dispatchers.IO) {
        val deviceToken = obtainSignedDeviceToken()
        var lastError: Throwable? = null

        for ((appId, titleId, redirectUri) in SISU_ONE_CLICK_CLIENTS) {
            try {
                val verifier = generatePkceVerifier()
                val challenge = generatePkceChallenge(verifier)
                val state = base64Url(UUID.randomUUID().toString().toByteArray())

                val sisuPayload = JSONObject().apply {
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
                    })
                }.toString()

                val url = "https://sisu.xboxlive.com/authenticate"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-xbl-contract-version", "1")
                    setRequestProperty("Signature", signRequest(url, "", sisuPayload))
                }
                conn.outputStream.use { it.write(sisuPayload.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()

                if (code in 200..299) {
                    val sessionId = conn.getHeaderField("X-SessionId").orEmpty()
                    val msaUrl = JSONObject(text).getString("MsaOauthRedirect")
                    return@withContext SisuBrowserRedirectTicket(
                        appIdUsed = appId,
                        titleIdUsed = titleId,
                        redirectUri = redirectUri,
                        msaOauthRedirectUrl = msaUrl,
                        codeVerifier = verifier,
                        deviceToken = deviceToken,
                        sisuSessionId = sessionId
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("No se pudo iniciar sesión SISU: ${lastError?.message}")
    }

    suspend fun completeSisuOneClickExchange(
        authorizationCode: String,
        ticket: SisuBrowserRedirectTicket,
        spoofedForwardedForIp: String? = null
    ): CompleteXboxSession = withContext(Dispatchers.IO) {
        val tokenForm = postFormUrlEncoded(
            url = "https://login.live.com/oauth20_token.srf",
            params = mapOf(
                "client_id" to ticket.appIdUsed,
                "grant_type" to "authorization_code",
                "code" to authorizationCode,
                "redirect_uri" to ticket.redirectUri,
                "code_verifier" to ticket.codeVerifier,
                "scope" to MBI_SSL_SCOPE
            )
        )

        val msaAccess = tokenForm.getString("access_token")
        val msaRefresh = tokenForm.getString("refresh_token")

        val session = buildCompleteTokenPipeline(
            msaAccessToken = msaAccess,
            msaRefreshToken = msaRefresh,
            clientIdUsed = ticket.appIdUsed,
            spoofedIp = spoofedForwardedForIp
        )
        vaultStorage.saveSession(session)
        return@withContext session
    }

    fun buildCompleteTokenPipeline(
        msaAccessToken: String,
        msaRefreshToken: String,
        clientIdUsed: String,
        spoofedIp: String?
    ): CompleteXboxSession {
        val deviceToken = runCatching { obtainSignedDeviceToken() }.getOrDefault("")

        val rpsTicket = if (msaAccessToken.startsWith("d=") || msaAccessToken.startsWith("t=")) {
            msaAccessToken
        } else {
            "d=$msaAccessToken"
        }

        val rpsPayload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", rpsTicket)
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

        val webXsts = obtainXstsToken(userToken, deviceToken, "http://xboxlive.com")
        val webToken = webXsts.getString("Token")
        val xui = webXsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)
        val gamertag = xui.optString("gtg", "Xbox Player")
        val xuid = xui.optString("xid", "")

        val gssvXsts = obtainXstsToken(userToken, deviceToken, "http://gssv.xboxlive.com/")
        val gssvToken = gssvXsts.getString("Token")

        val extraHeaders = mutableMapOf("x-gssv-client" to "XboxComBrowser")
        if (!spoofedIp.isNullOrBlank()) {
            extraHeaders["X-Forwarded-For"] = spoofedIp
        }

        val xgpuResp = postJsonSigned(
            url = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
            body = JSONObject().put("token", gssvToken).put("offeringId", "xgpuweb").toString(),
            extraHeaders = extraHeaders
        )
        val xgpuGsToken = xgpuResp.getString("gsToken")
        val durationSec = xgpuResp.optLong("durationInSeconds", 14_400L)
        val regions = xgpuResp.getJSONObject("offeringSettings").getJSONArray("regions")

        var baseUri = ""
        for (i in 0 until regions.length()) {
            val r = regions.getJSONObject(i)
            if (r.optBoolean("isDefault", false) || baseUri.isEmpty()) {
                baseUri = r.getString("baseUri")
            }
        }

        val f2pGsToken = runCatching {
            postJsonSigned(
                url = "https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user",
                body = JSONObject().put("token", gssvToken).put("offeringId", "xgpuwebf2p").toString(),
                extraHeaders = extraHeaders
            ).optString("gsToken").takeIf { it.isNotBlank() }
        }.getOrNull()

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
            f2pGsToken = f2pGsToken,
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
                put("Version", "10.0")
                put("ProofKey", publicKeyJwk)
            })
        }.toString()
        return postJsonSigned("https://device.auth.xboxlive.com/device/authenticate", payload)
            .getString("Token")
    }

    private fun obtainXstsToken(userToken: String, deviceToken: String, relyingParty: String): JSONObject {
        val props = JSONObject().apply {
            put("SandboxId", "RETAIL")
            put("UserTokens", JSONArray().put(userToken))
            if (deviceToken.isNotBlank()) put("DeviceToken", deviceToken)
        }
        val payload = JSONObject().apply {
            put("Properties", props)
            put("RelyingParty", relyingParty)
            put("TokenType", "JWT")
        }.toString()
        return postJsonSigned("https://xsts.auth.xboxlive.com/xsts/authorize", payload)
    }

    private fun postFormUrlEncoded(url: String, params: Map<String, String>): JSONObject {
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
        }
        conn.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun postJsonSigned(
        url: String,
        body: String,
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
            setRequestProperty("Signature", signRequest(url, "", body))
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun signRequest(url: String, authHeader: String, body: String): String {
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
