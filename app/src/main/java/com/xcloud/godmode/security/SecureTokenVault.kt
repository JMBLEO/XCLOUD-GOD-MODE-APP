package com.xcloud.godmode.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecureTokenVault: AES-256 GCM encrypted storage for sensitive XSTS tokens and session credentials.
 * Leverages Android Keystore System and Jetpack Security for sandbox isolation.
 */
class SecureTokenVault(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "godmode_secure_xsts_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun storeAuthToken(key: String, tokenValue: String) {
        encryptedPrefs.edit().putString(key, tokenValue).apply()
    }

    fun retrieveAuthToken(key: String): String? {
        return encryptedPrefs.getString(key, null)
    }

    fun clearVault() {
        encryptedPrefs.edit().clear().apply()
    }

    companion object {
        private var instance: SecureTokenVault? = null
        fun getInstance(context: Context): SecureTokenVault {
            return instance ?: synchronized(this) {
                instance ?: SecureTokenVault(context).also { instance = it }
            }
        }
    }
}
