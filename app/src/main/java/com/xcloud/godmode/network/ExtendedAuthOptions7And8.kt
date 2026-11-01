package com.xcloud.godmode.network

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Cross-Platform First-Party Xbox Authentication Client.
 * Implements native proof-of-possession signatures with JoseCripto
 * for cross-platform device profiles.
 */
object CrossPlatformFirstPartyAuth {
    private const val TAG = "CrossPlatformAuth"
    private const val SISU_AUTHENTICATE_URL = "https://sisu.xboxlive.com/authenticate"
    private const val DEVICE_AUTH_URL = "https://device.auth.xboxlive.com/device/authenticate"

    private val httpClient = OkHttpClient.Builder().build()

    enum class TargetPlatformProfile(
        val appId: String,
        val titleId: String,
        val deviceType: String,
        val redirectUri: String
    ) {
        IOS_XBOX_APP(
            appId = "0000000044154202",
            titleId = "1016898439",
            deviceType = "iOS",
            redirectUri = "ms-xal-0000000044154202://auth"
        ),
        NINTENDO_SWITCH_XBOX(
            appId = "0000000044154202",
            titleId = "1751147076",
            deviceType = "Nintendo",
            redirectUri = "ms-xal-0000000044154202://auth"
        ),
        WINDOWS_DESKTOP_APP(
            appId = "000000004C12AE6F",
            titleId = "328178078",
            deviceType = "Win32",
            redirectUri = "https://login.live.com/oauth20_desktop.srf"
        )
    }

    /**
     * Authenticates the device identity with device.auth.xboxlive.com using JoseCripto ECDSA signature.
     */
    suspend fun registerDeviceToken(
        profile: TargetPlatformProfile = TargetPlatformProfile.WINDOWS_DESKTOP_APP
    ): String = withContext(Dispatchers.IO) {
        val jwk = JoseCripto.getPublicJwk()
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "ProofOfPossession")
                put("Id", "{${java.util.UUID.randomUUID()}}")
                put("DeviceType", profile.deviceType)
                put("SerialNumber", "{${java.util.UUID.randomUUID()}}")
                put("Version", "15.0.0")
                put("ProofKey", jwk)
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }

        val bodyJson = payload.toString()
        val signature = JoseCripto.generateSignatureHeader("POST", DEVICE_AUTH_URL, bodyJson, "")

        val req = Request.Builder()
            .url(DEVICE_AUTH_URL)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Signature", signature)
            .header("x-xbl-contract-version", "1")
            .build()

        val res = httpClient.newCall(req).execute()
        val respStr = res.body?.string() ?: "{}"
        if (!res.isSuccessful) {
            throw IllegalStateException("Fallo en registro de dispositivo (${res.code}): $respStr")
        }

        val json = JSONObject(respStr)
        json.getString("Token")
    }
}

/**
 * LAN Companion Relay Server and Local Cookie Hydrator.
 * Allows pairing across local Wi-Fi devices or rehydrating sessions from system WebView cookies.
 */
object LanCompanionAndCookieHydrator {
    private const val TAG = "LanCompanionHydrator"

    /**
     * Extracts existing session tokens from system CookieManager.
     */
    fun tryHydrateFromSystemCookies(): Map<String, String> {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://login.live.com") ?: ""
        val result = mutableMapOf<String, String>()

        cookies.split(";").forEach { item ->
            val parts = item.trim().split("=", limit = 2)
            if (parts.size == 2) {
                result[parts[0].trim()] = parts[1].trim()
            }
        }
        return result
    }

    /**
     * Starts a lightweight LAN companion server on Wi-Fi (port 49216)
     * so another device (e.g. phone/tablet) can complete login and transmit the token locally.
     */
    suspend fun awaitCompanionTokenPush(
        port: Int = 49216,
        timeoutMs: Long = 180000L
    ): String = withContext(Dispatchers.IO) {
        var server: ServerSocket? = null
        try {
            server = ServerSocket(port)
            server.soTimeout = timeoutMs.toInt()
            Log.d(TAG, "Companion LAN Relay escuchando en puerto $port...")

            val socket: Socket = server.accept()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: ""

            var token: String? = null
            if (firstLine.contains("token=")) {
                token = firstLine.substringAfter("token=").substringBefore(" ")
            }

            val html = """
                <!DOCTYPE html>
                <html>
                <body style="background:#0c1017;color:#4ade80;font-family:sans-serif;text-align:center;padding:40px;">
                    <h2>¡Dispositivo Vinculado!</h2>
                    <p style="color:#cbd5e1;">La sesión ha sido transferida a God Mode exitosamente.</p>
                </body>
                </html>
            """.trimIndent()

            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html"
            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.close()

            token ?: throw IllegalStateException("No se recibió token en el payload LAN")
        } finally {
            runCatching { server?.close() }
        }
    }
}
