package com.xcloud.godmode.services

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xcloud.godmode.network.StreamingSessionCredentials
import org.json.JSONObject

/**
 * SecureSessionStore: Implementation of Xbox session vault using EncryptedSharedPreferences.
 */
class SecureSessionStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_xbox_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_SESSION_DATA = "xbox_session_json"
        private const val KEY_GAMERTAG = "gamertag"
        private const val KEY_EXPIRY = "expires_at_ms"
    }

    fun saveSession(credentials: StreamingSessionCredentials) {
        val json = JSONObject().apply {
            put("gamertag", credentials.gamertag)
            put("xuid", credentials.xuid)
            put("uhs", credentials.uhs)
            put("msaRefreshToken", credentials.msaRefreshToken)
            put("xCloudGsToken", credentials.xCloudGsToken)
            put("xCloudBaseUri", credentials.xCloudBaseUri)
            put("expiresAtEpochMs", credentials.expiresAtEpochMs)
        }
        sharedPreferences.edit().apply {
            putString(KEY_SESSION_DATA, json.toString())
            putString(KEY_GAMERTAG, credentials.gamertag)
            putLong(KEY_EXPIRY, credentials.expiresAtEpochMs)
            apply()
        }
    }

    fun loadSession(): StreamingSessionCredentials? {
        val jsonString = sharedPreferences.getString(KEY_SESSION_DATA, null) ?: return null
        return try {
            val json = JSONObject(jsonString)
            StreamingSessionCredentials(
                gamertag = json.getString("gamertag"),
                xuid = json.getString("xuid"),
                uhs = json.getString("uhs"),
                msaRefreshToken = json.getString("msaRefreshToken"),
                xCloudGsToken = json.getString("xCloudGsToken"),
                xCloudBaseUri = json.getString("xCloudBaseUri"),
                expiresAtEpochMs = json.getLong("expiresAtEpochMs")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearSession() {
        sharedPreferences.edit().clear().apply()
    }

    fun isSessionExpired(): Boolean {
        val expiry = sharedPreferences.getLong(KEY_EXPIRY, 0L)
        return System.currentTimeMillis() > expiry
    }

    fun getGamertag(): String? = sharedPreferences.getString(KEY_GAMERTAG, null)
}
