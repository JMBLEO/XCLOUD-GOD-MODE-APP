package com.xcloud.godmode.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * MsaDeviceCodeAuthEngine: Implementación del flujo OAuth2 Device Code para cuentas MSA.
 * Utiliza el Client ID proporcionado por el usuario para evitar errores de cliente no autorizado.
 */
class MsaDeviceCodeAuthEngine(
    private val http: OkHttpClient
) {
    companion object {
        private const val CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"
        private const val DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        private const val TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val SCOPE = "xboxlive.signin openid profile offline_access"
    }

    data class DeviceCodeTicket(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int
    )

    /**
     * Solicita un nuevo código de dispositivo al servidor de Microsoft.
     */
    suspend fun requestDeviceCode(): DeviceCodeTicket = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()

        val response = http.newCall(
            Request.Builder()
                .url(DEVICE_CODE_URL)
                .post(form)
                .build()
        ).execute()

        val body = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            throw IllegalStateException("Error al solicitar código: ${response.code} - $body")
        }

        val json = JSONObject(body)
        DeviceCodeTicket(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            interval = json.optInt("interval", 5),
            expiresIn = json.optInt("expires_in", 900)
        )
    }

    /**
     * Polling para obtener los tokens una vez que el usuario ha ingresado el código.
     */
    suspend fun pollForTokens(
        ticket: DeviceCodeTicket,
        onTick: (Int) -> Unit = {}
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + (ticket.expiresIn * 1000L)
        
        while (System.currentTimeMillis() < deadline) {
            val remaining = ((deadline - System.currentTimeMillis()) / 1000L).toInt()
            onTick(remaining)

            delay(ticket.interval * 1000L)

            val tokenResponse = http.newCall(
                Request.Builder()
                    .url(TOKEN_URL)
                    .post(
                        FormBody.Builder()
                            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                            .add("client_id", CLIENT_ID)
                            .add("device_code", ticket.deviceCode)
                            .build()
                    )
                    .build()
            ).execute()

            val jsonStr = tokenResponse.body?.string() ?: "{}"
            val json = JSONObject(jsonStr)

            if (json.has("error")) {
                val error = json.getString("error")
                if (error == "authorization_pending") continue
                if (error == "slow_down") {
                    delay(5000)
                    continue
                }
                throw IllegalStateException("Error de OAuth: $error - ${json.optString("error_description")}")
            }

            return@withContext Pair(
                json.getString("access_token"),
                json.optString("refresh_token", "")
            )
        }
        throw IllegalStateException("El tiempo de espera para el inicio de sesión ha expirado.")
    }
}
