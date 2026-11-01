package com.xcloud.godmode.network

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * Independent Xbox Live Client Profiles that bypass intent hijacking
 * caused by official mobile apps intercepting custom URI schemes.
 */
enum class IndependentClientProfile(
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val displayName: String
) {
    // Primary Official Desktop Core App Client (100% immune to intent hijacking)
    XBOX_CORE_DESKTOP(
        clientId = "000000004C12AE6F",
        redirectUri = "https://login.live.com/oauth20_desktop.srf",
        scope = "service::user.auth.xboxlive.com::MBI_SSL",
        displayName = "Xbox Core Desktop Flow (oauth20_desktop.srf)"
    ),
    // Secondary Official PC / Console Companion Client
    MY_XBOX_LIVE(
        clientId = "0000000048093EE3",
        redirectUri = "https://login.live.com/oauth20_desktop.srf",
        scope = "service::user.auth.xboxlive.com::MBI_SSL",
        displayName = "My Xbox Live Companion Profile"
    ),
    // Local loopback server for 1-click browser callback
    GODMODE_LOCAL_LOOPBACK(
        clientId = "000000004C12AE6F",
        redirectUri = "http://127.0.0.1:49215/auth/callback",
        scope = "service::user.auth.xboxlive.com::MBI_SSL",
        displayName = "Local Loopback Interceptor (127.0.0.1:49215)"
    )
}

data class IndependentAuthResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val userXuid: String?
)

/**
 * Manages independent Xbox Live authentication flows that do not collide
 * with device deep links or installed official apps.
 */
object IndependentXboxAuthManager {
    private const val TAG = "IndependentXboxAuth"
    private const val LIVE_AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf"
    private const val LIVE_TOKEN_URL = "https://login.live.com/oauth20_token.srf"

    private val httpClient = OkHttpClient.Builder().build()

    /**
     * Generates standard authorization URL for the selected independent profile.
     */
    fun buildAuthorizeUrl(
        profile: IndependentClientProfile = IndependentClientProfile.XBOX_CORE_DESKTOP,
        prompt: String = "select_account"
    ): String {
        return Uri.parse(LIVE_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", profile.clientId)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("scope", profile.scope)
            .appendQueryParameter("redirect_uri", profile.redirectUri)
            .appendQueryParameter("display", "touch")
            .appendQueryParameter("prompt", prompt)
            .build()
            .toString()
    }

    /**
     * Extracts token or authorization code from redirect URL.
     * Supports both query parameters (?code=...) and URL fragments (#access_token=...).
     */
    fun extractTokenOrCodeFromUrl(url: String): Pair<String?, String?> {
        val trimmed = url.trim()
        val queryUri = Uri.parse(trimmed)

        // 1. Check URL fragment for implicit grant (#access_token=...&refresh_token=...)
        val fragment = queryUri.fragment
        if (!fragment.isNullOrBlank()) {
            val params = fragment.split("&").associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }
            val accessToken = params["access_token"]
            val refreshToken = params["refresh_token"]
            if (!accessToken.isNullOrBlank()) {
                return Pair(accessToken, refreshToken)
            }
        }

        // 2. Check query parameters for code grant (?code=...)
        val code = queryUri.getQueryParameter("code")
        if (!code.isNullOrBlank()) {
            return Pair(code, null)
        }

        // 3. Fallback regex for raw strings
        val tokenRegex = Regex("[?&#]access_token=([^&#\\s]+)")
        val match = tokenRegex.find(trimmed)
        if (match != null) {
            val token = match.groupValues[1]
            return Pair(token, null)
        }

        return Pair(null, null)
    }

    /**
     * Exchanges authorization code for access and refresh tokens.
     */
    suspend fun exchangeCode(
        code: String,
        profile: IndependentClientProfile = IndependentClientProfile.XBOX_CORE_DESKTOP
    ): IndependentAuthResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", profile.clientId)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", profile.redirectUri)
            .add("scope", profile.scope)
            .build()

        val req = Request.Builder()
            .url(LIVE_TOKEN_URL)
            .post(form)
            .header("Accept", "application/json")
            .build()

        val res = httpClient.newCall(req).execute()
        val body = res.body?.string() ?: "{}"
        if (!res.isSuccessful) {
            throw IllegalStateException("Fallo al canjear código (${res.code}): $body")
        }

        val json = JSONObject(body)
        IndependentAuthResult(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresIn = json.optLong("expires_in", 86400L),
            userXuid = json.optString("user_id").takeIf { it.isNotBlank() }
        )
    }

    /**
     * Refreshes an expired session using the refresh token (up to 90 days validity).
     */
    suspend fun refreshSession(
        refreshToken: String,
        profile: IndependentClientProfile = IndependentClientProfile.XBOX_CORE_DESKTOP
    ): IndependentAuthResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", profile.clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", profile.scope)
            .build()

        val req = Request.Builder()
            .url(LIVE_TOKEN_URL)
            .post(form)
            .header("Accept", "application/json")
            .build()

        val res = httpClient.newCall(req).execute()
        val body = res.body?.string() ?: "{}"
        if (!res.isSuccessful) {
            throw IllegalStateException("Fallo al renovar token (${res.code}): $body")
        }

        val json = JSONObject(body)
        IndependentAuthResult(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken,
            expiresIn = json.optLong("expires_in", 86400L),
            userXuid = json.optString("user_id").takeIf { it.isNotBlank() }
        )
    }

    /**
     * Starts an embedded local HTTP loopback server on port 49215 to intercept
     * the browser OAuth redirect automatically without user copy-pasting.
     */
    suspend fun awaitLoopbackRedirect(
        port: Int = 49215,
        timeoutMs: Long = 180000L
    ): String = withContext(Dispatchers.IO) {
        var server: ServerSocket? = null
        try {
            server = ServerSocket(port)
            server.soTimeout = timeoutMs.toInt()
            Log.d(TAG, "Servidor loopback escuchando en 127.0.0.1:$port...")

            val socket: Socket = server.accept()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: ""
            Log.d(TAG, "Petición HTTP interceptada en loopback: $firstLine")

            // HTTP Response to show user in browser
            val responseHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>God Mode - Autenticación Exitosa</title>
                    <style>
                        body { background: #0c1017; color: #fff; font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 90vh; margin: 0; }
                        .card { background: #141a24; border: 1px solid #107c10; padding: 32px; border-radius: 16px; text-align: center; max-width: 420px; }
                        h2 { color: #4ade80; margin-top: 0; }
                        p { color: #cbd5e1; font-size: 14px; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h2>¡Autenticación Completada!</h2>
                        <p>God Mode ha recibido tus credenciales de forma segura. Puedes regresar a la aplicación ahora.</p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            val httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: ${responseHtml.toByteArray(Charsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n" + responseHtml

            socket.getOutputStream().write(httpResponse.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.close()

            // Extract the path from "GET /auth/callback?code=... HTTP/1.1"
            val parts = firstLine.split(" ")
            if (parts.size >= 2) {
                return@withContext parts[1]
            } else {
                throw IllegalStateException("Cabecera HTTP inválida en loopback: $firstLine")
            }
        } finally {
            runCatching { server?.close() }
        }
    }
}
