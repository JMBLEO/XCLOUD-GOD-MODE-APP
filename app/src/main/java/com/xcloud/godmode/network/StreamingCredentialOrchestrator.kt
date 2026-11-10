package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class StreamingSessionCredentials(
    val gamertag: String,
    val xuid: String,
    val uhs: String,
    val msaRefreshToken: String,
    val xCloudGsToken: String,
    val xCloudBaseUri: String,
    val expiresAtEpochMs: Long,
    val longPlayToken: String = ""
) {
    fun isStreamingTokenValid(safetyMarginMs: Long = 300_000L): Boolean {
        return xCloudGsToken.isNotBlank() &&
                xCloudBaseUri.isNotBlank() &&
                (System.currentTimeMillis() + safetyMarginMs) < expiresAtEpochMs
    }
}

sealed interface CredentialResolutionResult {
    data class Ready(val credentials: StreamingSessionCredentials) : CredentialResolutionResult
    data class RequiresInteractiveLogin(val reason: String) : CredentialResolutionResult
    data class NetworkFailure(val reason: String) : CredentialResolutionResult
}

class InvalidGrantException(message: String) : Exception(message)

interface SessionVaultStorage {
    suspend fun loadCredentials(): StreamingSessionCredentials?
    suspend fun saveCredentials(credentials: StreamingSessionCredentials)
    suspend fun clearCredentials()
}

/**
 * StreamingCredentialOrchestrator: Lifecycle and silent auto-refresh manager for Xbox streaming credentials.
 * Implements the full 5-step token chain from XStreaming docs (1.8 to 1.11):
 * MSA Refresh -> RPS UserToken -> XSTS Streaming Token -> GSSV v2/login/user -> Long Play Token (LPT)
 * Supports both Game Pass Ultimate (xgpuweb) and Free-to-Play (xgpuwebf2p) offerings.
 */
class StreamingCredentialOrchestrator(
    private val vaultStorage: SessionVaultStorage,
    private val clientId: String = XBOX_PUBLIC_CLIENT_ID
) {
    companion object {
        private const val TAG = "CredentialOrchestrator"
        // Official verified Xbox Client ID for consumer OAuth (login.live.com)
        const val PUBLIC_CLIENT_ID = PrimaryAppIdAuthEngine.PRIMARY_APP_ID
        const val XBOX_PUBLIC_CLIENT_ID = PrimaryAppIdAuthEngine.PRIMARY_APP_ID
        const val SCOPE_MSAL = PrimaryAppIdAuthEngine.MBI_SSL_SCOPE
        const val LPT_TRANSFER_SCOPE = PrimaryAppIdAuthEngine.LPT_TRANSFER_SCOPE
        const val OFFERING_GAME_PASS = "xgpuweb"
        const val OFFERING_FREE_TO_PLAY = "xgpuwebf2p"
    }

    private val refreshMutex = Mutex()

    /**
     * Ensures the session has a valid, unexpired xCloud streaming token and fresh LPT.
     * Invoked on app startup and in the onClick handler of 'JUGAR AHORA'.
     *
     * @param isFreeToPlay True for games that run on Free-to-Play infrastructure (e.g. Fortnite)
     * @param spoofedForwardedForIp Optional geographic bypass IP header
     */
    suspend fun ensureValidStreamingToken(
        isFreeToPlay: Boolean = false,
        spoofedForwardedForIp: String? = null
    ): CredentialResolutionResult = refreshMutex.withLock {
        val current = vaultStorage.loadCredentials()
            ?: return CredentialResolutionResult.RequiresInteractiveLogin(
                reason = "No existe una sesión activa. Inicia sesión con tu cuenta de Xbox."
            )

        // If the gsToken is still valid (>5 min remaining), check if longPlayToken is present
        if (current.isStreamingTokenValid()) {
            val validCreds = if (current.longPlayToken.isBlank() && current.msaRefreshToken.isNotBlank()) {
                val lpt = fetchLongPlayTransferToken(current.msaRefreshToken)
                val updated = current.copy(longPlayToken = lpt)
                vaultStorage.saveCredentials(updated)
                updated
            } else {
                current
            }
            return CredentialResolutionResult.Ready(validCreds)
        }

        // If gsToken expired or cache was cleared, but no refresh_token exists, purge zombie state
        if (current.msaRefreshToken.isBlank()) {
            vaultStorage.clearCredentials()
            return CredentialResolutionResult.RequiresInteractiveLogin(
                reason = "La credencial de streaming expiró y no hay token de refresco. Inicia sesión nuevamente."
            )
        }

        return try {
            val offering = if (isFreeToPlay) OFFERING_FREE_TO_PLAY else OFFERING_GAME_PASS
            Log.d(TAG, "Silently renewing streaming credentials for offering: $offering...")

            val refreshed = executeSilentTokenChain(
                refreshToken = current.msaRefreshToken,
                fallbackGamertag = current.gamertag,
                offeringId = offering,
                spoofedIp = spoofedForwardedForIp
            )
            val lpt = fetchLongPlayTransferToken(refreshed.msaRefreshToken)
            val finalCreds = refreshed.copy(longPlayToken = lpt)
            vaultStorage.saveCredentials(finalCreds)
            Log.i(TAG, "Streaming credentials successfully renewed for ${finalCreds.gamertag}")
            CredentialResolutionResult.Ready(finalCreds)
        } catch (e: InvalidGrantException) {
            Log.w(TAG, "Refresh token expired or revoked in Microsoft: ${e.message}")
            vaultStorage.clearCredentials()
            CredentialResolutionResult.RequiresInteractiveLogin(
                reason = "Tu sesión de Xbox ha caducado. Por favor, vuelve a iniciar sesión."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Network failure while renewing streaming credentials: ${e.message}")
            CredentialResolutionResult.NetworkFailure(
                reason = "Error de red al renovar credenciales de streaming: ${e.message}"
            )
        }
    }

    private fun executeSilentTokenChain(
        refreshToken: String,
        fallbackGamertag: String,
        offeringId: String,
        spoofedIp: String?
    ): StreamingSessionCredentials {
        // STEP 1: Refresh MSA Access Token via login.live.com with official Xbox client ID and MBI_SSL
        val msaJson = postFormUrlEncoded(
            url = "https://login.live.com/oauth20_token.srf",
            params = mapOf(
                "client_id" to XBOX_PUBLIC_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "scope" to SCOPE_MSAL
            )
        )
        val newAccessToken = msaJson.optString("access_token")
        val newRefreshToken = msaJson.optString("refresh_token").ifBlank { refreshToken }
        if (newAccessToken.isBlank()) {
            throw InvalidGrantException("MSA refresh devolvió un access_token vacío")
        }

        // STEP 2: Obtain Xbox Live User Token (RPS ticket with "d=" prefix)
        val rpsPayload = JSONObject().apply {
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", if (newAccessToken.startsWith("d=") || newAccessToken.startsWith("t=")) newAccessToken else "d=$newAccessToken")
            })
        }
        val rpsJson = try {
            postJson(
                url = "https://user.auth.xboxlive.com/user/authenticate",
                body = rpsPayload.toString()
            )
        } catch (e: Exception) {
            rpsPayload.getJSONObject("Properties").put("RpsTicket", "t=$newAccessToken")
            postJson(
                url = "https://user.auth.xboxlive.com/user/authenticate",
                body = rpsPayload.toString()
            )
        }
        val userToken = rpsJson.getString("Token")
        val uhs = rpsJson.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
            .getString("uhs")

        // STEP 3: Obtain XSTS Streaming Token for http://gssv.xboxlive.com/
        val xstsPayload = JSONObject().apply {
            put("RelyingParty", "http://gssv.xboxlive.com/")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().put(userToken))
            })
        }
        val xstsJson = postJson(
            url = "https://xsts.auth.xboxlive.com/xsts/authorize",
            body = xstsPayload.toString()
        )
        val xstsToken = xstsJson.getString("Token")
        val xuiObj = xstsJson.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
        val gamertag = xuiObj.optString("gtg").ifBlank { fallbackGamertag }
        val xuid = xuiObj.optString("xid")

        // STEP 4: Exchange XSTS for xCloud gsToken (xgpuweb or xgpuwebf2p)
        val gssvPayload = JSONObject().apply {
            put("token", xstsToken)
            put("offeringId", offeringId)
        }
        val extraHeaders = mutableMapOf(
            "x-gssv-client" to "XboxComBrowser"
        )
        if (!spoofedIp.isNullOrBlank()) {
            extraHeaders["X-Forwarded-For"] = spoofedIp
        }

        val gssvUrl = if (offeringId == OFFERING_FREE_TO_PLAY) {
            "https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user"
        } else {
            "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user"
        }

        val gssvJson = postJson(
            url = gssvUrl,
            body = gssvPayload.toString(),
            extraHeaders = extraHeaders
        )

        val gsToken = gssvJson.getString("gsToken")
        val durationSeconds = gssvJson.optLong("durationInSeconds", 14_400L) // ~4 hours
        val regions = gssvJson.getJSONObject("offeringSettings").getJSONArray("regions")

        var defaultBaseUri = ""
        for (i in 0 until regions.length()) {
            val reg = regions.getJSONObject(i)
            if (reg.optBoolean("isDefault", false) || defaultBaseUri.isEmpty()) {
                defaultBaseUri = reg.getString("baseUri")
            }
        }

        return StreamingSessionCredentials(
            gamertag = gamertag,
            xuid = xuid,
            uhs = uhs,
            msaRefreshToken = newRefreshToken,
            xCloudGsToken = gsToken,
            xCloudBaseUri = defaultBaseUri,
            expiresAtEpochMs = System.currentTimeMillis() + (durationSeconds * 1000L)
        )
    }

    /**
     * STEP 5: Obtains the Long Play Token (LPT / msalToken) for /connect calls.
     */
    fun fetchLongPlayTransferToken(refreshToken: String): String {
        return try {
            val resp = postFormUrlEncoded(
                url = "https://login.live.com/oauth20_token.srf",
                params = mapOf(
                    "client_id" to XBOX_PUBLIC_CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "scope" to LPT_TRANSFER_SCOPE
                )
            )
            resp.optString("access_token")
        } catch (e: Exception) {
            Log.w(TAG, "LPT fetch failed: ${e.message}")
            ""
        }
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
            setRequestProperty("Cache-Control", "no-store, must-revalidate, no-cache")
        }
        conn.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code == 400 || code == 401) {
            throw InvalidGrantException(responseText)
        }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $responseText")
        }
        return JSONObject(responseText)
    }

    private fun postJson(
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
            setRequestProperty("Cache-Control", "no-store, must-revalidate, no-cache")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code == 401 || code == 403) {
            throw InvalidGrantException("XSTS/GSSV Auth Rejected ($code): $responseText")
        }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $responseText")
        }
        return JSONObject(responseText)
    }
}
