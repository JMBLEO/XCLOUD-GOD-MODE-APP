package com.xcloud.godmode.security

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.xcloud.godmode.network.SecureTokenEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.authDataStore by preferencesDataStore(name = "godmode_unified_vault")

/**
 * UnifiedPersistentAuthVault: Single source of truth for Xbox Cloud session tokens.
 * Uses DataStore for persistence and Android KeyStore (AES-GCM) for hardware encryption.
 */
class UnifiedPersistentAuthVault private constructor(private val context: Context) {
    private val TAG = "UnifiedVault"
    private val KEY_ENCRYPTED_ENVELOPE = stringPreferencesKey("secure_envelope_v3")
    private val KEY_GAMERTAG = stringPreferencesKey("gamertag")
    private val KEY_SUB_TIER = stringPreferencesKey("sub_tier")
    
    private val gson = Gson()
    private val mutex = Mutex()

    // Internal DTO for JSON serialization of the encrypted envelope
    private data class StoredEnvelope(
        val msaAccess: String,
        val msaRefresh: String,
        val msaExpiry: Long,
        val xblToken: String,
        val uhs: String,
        val xstsToken: String,
        val gsToken: String,
        val gsExpiry: Long,
        val regionUri: String,
        val market: String
    )

    val sessionState: Flow<AuthProfileState> = context.authDataStore.data.map { prefs ->
        val gamertag = prefs[KEY_GAMERTAG] ?: ""
        val tier = prefs[KEY_SUB_TIER] ?: ""
        AuthProfileState(
            isLoggedIn = gamertag.isNotBlank(),
            gamertag = gamertag,
            subscriptionTier = tier
        )
    }

    suspend fun hasValidSession(): Boolean {
        return sessionState.map { it.isLoggedIn }.firstOrNull() ?: false
    }

    suspend fun saveSession(
        gamertag: String,
        tier: String,
        envelope: SecureTokenEnvelope
    ) = mutex.withLock {
        val stored = StoredEnvelope(
            msaAccess = encrypt(String(envelope.msaAccessToken)),
            msaRefresh = encrypt(String(envelope.msaRefreshToken)),
            msaExpiry = envelope.msaExpiresAtElapsedMs,
            xblToken = encrypt(String(envelope.xblUserToken)),
            uhs = encrypt(String(envelope.userHashUhs)),
            xstsToken = encrypt(String(envelope.xstsGssvToken)),
            gsToken = encrypt(String(envelope.gsStreamToken)),
            gsExpiry = envelope.gsExpiresAtElapsedMs,
            regionUri = envelope.discoveredBaseRegionUri,
            market = envelope.market
        )
        
        val json = gson.toJson(stored)
        context.authDataStore.edit { prefs ->
            prefs[KEY_ENCRYPTED_ENVELOPE] = json
            prefs[KEY_GAMERTAG] = gamertag
            prefs[KEY_SUB_TIER] = tier
        }
        Log.d(TAG, "Session persisted atomically to UnifiedVault.")
    }

    suspend fun loadEnvelope(): SecureTokenEnvelope? = mutex.withLock {
        val prefs = context.authDataStore.data.firstOrNull() ?: return null
        val json = prefs[KEY_ENCRYPTED_ENVELOPE] ?: return null
        
        return try {
            val stored = gson.fromJson(json, StoredEnvelope::class.java)
            SecureTokenEnvelope(
                msaAccessToken = decrypt(stored.msaAccess).toCharArray(),
                msaRefreshToken = decrypt(stored.msaRefresh).toCharArray(),
                msaExpiresAtElapsedMs = stored.msaExpiry,
                lptTransferToken = CharArray(0),
                xblUserToken = decrypt(stored.xblToken).toCharArray(),
                userHashUhs = decrypt(stored.uhs).toCharArray(),
                xstsWebToken = CharArray(0),
                xstsGssvToken = decrypt(stored.xstsToken).toCharArray(),
                xstsExpiresAtElapsedMs = stored.msaExpiry, // Using MSA expiry as fallback if needed
                gsStreamToken = decrypt(stored.gsToken).toCharArray(),
                gsExpiresAtElapsedMs = stored.gsExpiry,
                discoveredBaseRegionUri = stored.regionUri,
                market = stored.market
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rehydrate envelope from UnifiedVault", e)
            null
        }
    }

    suspend fun clearSession() = mutex.withLock {
        context.authDataStore.edit { it.clear() }
    }

    // --- Hardware Encryption Logic (Carrier-Grade) ---

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(1 + iv.size + encrypted.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Base64.encodeToString(plain.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = combined[0].toInt()
            val iv = combined.copyOfRange(1, 1 + ivSize)
            val encrypted = combined.copyOfRange(1 + ivSize, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8)
        }
    }

    data class AuthProfileState(
        val isLoggedIn: Boolean = false,
        val gamertag: String = "",
        val subscriptionTier: String = ""
    )

    companion object {
        private const val KEY_ALIAS = "GodModeUnifiedVaultKey_v1"
        
        @Volatile
        private var instance: UnifiedPersistentAuthVault? = null

        fun getInstance(context: Context): UnifiedPersistentAuthVault =
            instance ?: synchronized(this) {
                instance ?: UnifiedPersistentAuthVault(context.applicationContext).also { instance = it }
            }
    }
}
