package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DeviceCodeFlowState(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

data class XCloudTokenBundle(
    val gamertag: String,
    val xuid: String,
    val uhs: String,
    val msaAccessToken: String,
    val msaRefreshToken: String,
    val webXstsToken: String,
    val gssvXstsToken: String,
    val xgpuGsToken: String,
    val f2pGsToken: String?,
    val defaultRegionBaseUri: String,
    val regionsJson: String,
    val gsTokenExpiresAtEpochMs: Long
) {
    fun isGsTokenValid(safetyWindowMs: Long = 300_000L): Boolean {
        return xgpuGsToken.isNotBlank() &&
            defaultRegionBaseUri.isNotBlank() &&
            (System.currentTimeMillis() + safetyWindowMs) < gsTokenExpiresAtEpochMs
    }
}

sealed interface AuthTokenResolution {
    data class Valid(
        val bundle: XCloudTokenBundle,
        val longPlayTokenMsal: String
    ) : AuthTokenResolution

    data class NeedsDeviceCodeLogin(val reason: String) : AuthTokenResolution
    data class TransientNetworkError(val reason: String) : AuthTokenResolution
}

class OAuthRejectedException(message: String) : Exception(message)

interface XCloudTokenStorage {
    suspend fun loadTokenBundle(): XCloudTokenBundle?
    suspend fun saveTokenBundle(bundle: XCloudTokenBundle)
    suspend fun clearTokenBundle()
}

/**
 * XCloudAuthProtocolService: Implements the exact 5-step Xbox Cloud Gaming authentication chain
 * from reference implementations (XStreamingDesktop, greenlight, stratix).
 *
 * 1 & 2: Device Code Flow RFC 8628 via login.live.com/oauth20_device.srf & /oauth20_token.srf
 * 3a: User Token via user.auth.xboxlive.com/user/authenticate ("RpsTicket": "d=...")
 * 3b: Dual XSTS tokens for http://xboxlive.com (Web API) & http://gssv.xboxlive.com/ (Streaming)
 * 4: Dual gsToken for xgpuweb (Game Pass) & xgpuwebf2p (Free-to-Play) via GSSV v2/login/user
 * 5: Long Play Token (LPT / msalToken) for /connect via login.live.com/oauth20_token.srf
 */
class XCloudAuthProtocolService(
    private val tokenStorage: XCloudTokenStorage
) {
    private val authMutex = Mutex()

    companion object {
        private const val TAG = "XCloudAuthService"

        // Official Client ID for Consumer Xbox OAuth (login.live.com)
        const val DEVICE_CODE_CLIENT_ID = PrimaryAppIdAuthEngine.PRIMARY_APP_ID

        // Scope oficial para login.live.com inmune a restricciones de primera parte de Azure AD
        const val DEVICE_CODE_SCOPE = PrimaryAppIdAuthEngine.MBI_SSL_SCOPE

        // Scope requerido en el Paso 5 para el Long Play Token (LPT / msalToken) en /connect
        const val LPT_TRANSFER_SCOPE = PrimaryAppIdAuthEngine.LPT_TRANSFER_SCOPE
    }

    /**
     * PASO 1: Solicita el código de dispositivo en login.live.com sin error de Primera Parte ni AADSTS700016.
     */
    suspend fun startDeviceCodeFlow(): DeviceCodeFlowState = withContext(Dispatchers.IO) {
        val response = postForm(
            url = "https://login.live.com/oauth20_device.srf",
            params = mapOf(
                "client_id" to DEVICE_CODE_CLIENT_ID,
                "scope" to DEVICE_CODE_SCOPE,
                "response_type" to "device_code"
            )
        )
        val userCode = response.getString("user_code")
        val verificationUri = response.optString("verification_uri", PrimaryAppIdAuthEngine.CONSUMER_REMOTE_CONNECT_URL)
            .let { if (it.contains("microsoft.com/link")) PrimaryAppIdAuthEngine.CONSUMER_REMOTE_CONNECT_URL else it }
        val completeUri = PrimaryAppIdAuthEngine.getRemoteConnectCompleteUrl(userCode)

        DeviceCodeFlowState(
            deviceCode = response.getString("device_code"),
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = completeUri,
            expiresInSeconds = response.optInt("expires_in", 900),
            intervalSeconds = response.optInt("interval", 5).coerceAtLeast(3)
        )
    }

    /**
     * PASO 2: Sondea el token MSA y, al aprobarse, ejecuta los Pasos 3a, 3b y 4 completos.
     */
    suspend fun pollDeviceCodeAndExchange(
        deviceCode: String,
        spoofedForwardedForIp: String? = null
    ): XCloudTokenBundle = withContext(Dispatchers.IO) {
        val tokenJson = postForm(
            url = "https://login.live.com/oauth20_token.srf",
            params = mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to DEVICE_CODE_CLIENT_ID,
                "device_code" to deviceCode
            )
        )

        val accessToken = tokenJson.getString("access_token")
        val refreshToken = tokenJson.getString("refresh_token")

        val bundle = buildFullXboxAndGssvChain(
            msaAccessToken = accessToken,
            msaRefreshToken = refreshToken,
            spoofedIp = spoofedForwardedForIp
        )
        tokenStorage.saveTokenBundle(bundle)
        bundle
    }

    /**
     * Se ejecuta antes de iniciar cualquier stream ("JUGAR AHORA"):
     * - Si el gsToken expiró o fue borrado en caché, rota el refresh_token y regenera la cadena.
     * - Obtiene el Long Play Token (LPT) de 5 minutos para la llamada a /connect.
     */
    suspend fun resolveTokensForPlay(
        spoofedForwardedForIp: String? = null
    ): AuthTokenResolution = authMutex.withLock {
        withContext(Dispatchers.IO) {
            val stored = tokenStorage.loadTokenBundle()
                ?: return@withContext AuthTokenResolution.NeedsDeviceCodeLogin(
                    "No hay sesión activa. Vincula tu cuenta con el código de dispositivo."
                )

            try {
                val activeBundle = if (stored.isGsTokenValid()) {
                    stored
                } else {
                    if (stored.msaRefreshToken.isBlank()) {
                        tokenStorage.clearTokenBundle()
                        return@withContext AuthTokenResolution.NeedsDeviceCodeLogin(
                            "Tu sesión de streaming expiró. Inicia sesión nuevamente."
                        )
                    }
                    // Refrescar MSA Token (Importante: el refresh_token rota en cada uso)
                    Log.d(TAG, "Refrescando MSA token de forma silenciosa...")
                    val refreshedMsa = postForm(
                        url = "https://login.live.com/oauth20_token.srf",
                        params = mapOf(
                            "grant_type" to "refresh_token",
                            "client_id" to DEVICE_CODE_CLIENT_ID,
                            "refresh_token" to stored.msaRefreshToken,
                            "scope" to DEVICE_CODE_SCOPE
                        )
                    )
                    val newAccess = refreshedMsa.getString("access_token")
                    val rotatedRefresh = refreshedMsa.optString("refresh_token")
                        .ifBlank { stored.msaRefreshToken }

                    val updatedBundle = buildFullXboxAndGssvChain(
                        msaAccessToken = newAccess,
                        msaRefreshToken = rotatedRefresh,
                        spoofedIp = spoofedForwardedForIp
                    )
                    // Persistir de inmediato el nuevo refresh_token rotado
                    tokenStorage.saveTokenBundle(updatedBundle)
                    Log.i(TAG, "Cadena de streaming renovada exitosamente para ${updatedBundle.gamertag}")
                    updatedBundle
                }

                // PASO 5: Obtener Long Play Token (LPT / msalToken) requerido para /connect
                val lptToken = fetchLongPlayTransferToken(activeBundle.msaRefreshToken)

                AuthTokenResolution.Valid(
                    bundle = activeBundle,
                    longPlayTokenMsal = lptToken
                )
            } catch (e: OAuthRejectedException) {
                Log.w(TAG, "OAuth rechazado durante resolución de tokens: ${e.message}")
                tokenStorage.clearTokenBundle()
                AuthTokenResolution.NeedsDeviceCodeLogin(
                    "La sesión de Xbox caducó o fue revocada. Inicia sesión de nuevo."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error de red durante resolución de tokens: ${e.message}")
                AuthTokenResolution.TransientNetworkError(
                    "No se pudo conectar con los servidores de autenticación de Xbox: ${e.message}"
                )
            }
        }
    }

    fun buildFullXboxAndGssvChain(
        msaAccessToken: String,
        msaRefreshToken: String,
        spoofedIp: String?
    ): XCloudTokenBundle {
        // PASO 3a: RPS User Authenticate (Prefijo obligatorio "d=")
        val rpsBody = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=$msaAccessToken")
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }
        val rpsResp = postJson("https://user.auth.xboxlive.com/user/authenticate", rpsBody)
        val userToken = rpsResp.getString("Token")
        val uhs = rpsResp.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
            .getString("uhs")

        // PASO 3b-1: XSTS Token para Xbox Live Web API (Perfil, Amigos, Logros)
        val webXstsResp = requestXstsToken(userToken, "http://xboxlive.com")
        val webXstsToken = webXstsResp.getString("Token")
        val xuiClaim = webXstsResp.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
        val gamertag = xuiClaim.optString("gtg", "Xbox Player")
        val xuid = xuiClaim.optString("xid", "")

        // PASO 3b-2: XSTS Token específico para GSSV Streaming
        val gssvXstsResp = requestXstsToken(userToken, "http://gssv.xboxlive.com/")
        val gssvXstsToken = gssvXstsResp.getString("Token")

        // PASO 4a: Token de Streaming para Game Pass Ultimate (xgpuweb)
        val gssvHeaders = mutableMapOf("x-gssv-client" to "XboxComBrowser")
        if (!spoofedIp.isNullOrBlank()) {
            gssvHeaders["X-Forwarded-For"] = spoofedIp
        }

        val xgpuResp = postJson(
            url = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
            body = JSONObject().apply {
                put("token", gssvXstsToken)
                put("offeringId", "xgpuweb")
            },
            extraHeaders = gssvHeaders
        )
        val xgpuGsToken = xgpuResp.getString("gsToken")
        val durationSeconds = xgpuResp.optLong("durationInSeconds", 14_400L)
        val regionsArray = xgpuResp.getJSONObject("offeringSettings").getJSONArray("regions")

        var defaultUri = ""
        for (i in 0 until regionsArray.length()) {
            val reg = regionsArray.getJSONObject(i)
            if (reg.optBoolean("isDefault", false) || defaultUri.isEmpty()) {
                defaultUri = reg.getString("baseUri")
            }
        }

        // PASO 4b: Token de Streaming para Juegos Free-to-Play (xgpuwebf2p)
        val f2pGsToken = runCatching {
            val f2pResp = postJson(
                url = "https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user",
                body = JSONObject().apply {
                    put("token", gssvXstsToken)
                    put("offeringId", "xgpuwebf2p")
                },
                extraHeaders = gssvHeaders
            )
            f2pResp.optString("gsToken").takeIf { it.isNotBlank() }
        }.getOrNull()

        return XCloudTokenBundle(
            gamertag = gamertag,
            xuid = xuid,
            uhs = uhs,
            msaAccessToken = msaAccessToken,
            msaRefreshToken = msaRefreshToken,
            webXstsToken = webXstsToken,
            gssvXstsToken = gssvXstsToken,
            xgpuGsToken = xgpuGsToken,
            f2pGsToken = f2pGsToken,
            defaultRegionBaseUri = defaultUri,
            regionsJson = regionsArray.toString(),
            gsTokenExpiresAtEpochMs = System.currentTimeMillis() + (durationSeconds * 1000L)
        )
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

    /**
     * PASO 5: Obtiene el Long Play Token (LPT) requerido en POST /v5/sessions/cloud/{id}/connect
     */
    fun fetchLongPlayTransferToken(refreshToken: String): String {
        return try {
            val resp = postForm(
                url = "https://login.live.com/oauth20_token.srf",
                params = mapOf(
                    "client_id" to DEVICE_CODE_CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "scope" to LPT_TRANSFER_SCOPE
                )
            )
            resp.optString("access_token")
        } catch (e: Exception) {
            Log.w(TAG, "LPT transfer token request returned: ${e.message}")
            ""
        }
    }

    private fun postForm(url: String, params: Map<String, String>): JSONObject {
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
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code == 400 || code == 401) {
            throw OAuthRejectedException("OAuth HTTP $code: $text")
        }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $text")
        }
        return JSONObject(text)
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
            setRequestProperty("Cache-Control", "no-store, must-revalidate, no-cache")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code == 401 || code == 403) {
            throw OAuthRejectedException("XSTS/GSSV HTTP $code: $text")
        }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $text")
        }
        return JSONObject(text)
    }
}
