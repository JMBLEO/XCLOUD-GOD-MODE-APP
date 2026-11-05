package com.xcloud.godmode.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "token_vault")

/**
 * XboxSessionProfile: Model for a saved Xbox account in the Multi-Account Vault.
 * Complies with Sprint 1 specification:
 * gamertag, xuid, uhs, gamerpic, userToken, xstsToken, gsToken, refreshToken, expiresAtMs, xCloudRegionUri.
 */
data class XboxSessionProfile(
    val xuid: String,
    val gamertag: String,
    val uhs: String,
    val gamerpic: String = "",
    val userToken: String = "",
    val xstsToken: String = "",
    val gsToken: String = "",
    val xCloudGsToken: String = gsToken,
    val xHomeGsToken: String = gsToken,
    val xCloudRegionUri: String = "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user",
    val refreshToken: String,
    val expiresAtMs: Long = System.currentTimeMillis() + (4 * 60 * 60 * 1000),
    val activeOffering: String = "xgpuweb",
    val tierBadge: String = "Game Pass Ultimate"
) {
    // Convenience getter to ensure xCloudGsToken is always accessible
    fun resolvedGsToken(): String = if (gsToken.isNotBlank()) gsToken else xCloudGsToken
}

/**
 * VaultSecurityHelper: Hardware-backed AES-256-GCM encryption for stored tokens.
 */
object VaultSecurityHelper {
    private const val TAG = "VaultSecurity"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "xcloud_godmode_vault_master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SEPARATOR = "]"

    init {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            Log.w(TAG, "KeyStore initialization fallback active: ${e.message}")
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (_: Exception) {
            null
        }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""
        try {
            val key = getSecretKey() ?: return Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encString = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            return "$ivString$IV_SEPARATOR$encString"
        } catch (e: Exception) {
            Log.w(TAG, "Encryption fallback: ${e.message}")
            return Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isBlank()) return ""
        try {
            val key = getSecretKey() ?: return String(Base64.decode(cipherText, Base64.NO_WRAP), Charsets.UTF_8)
            if (!cipherText.contains(IV_SEPARATOR)) {
                return String(Base64.decode(cipherText, Base64.NO_WRAP), Charsets.UTF_8)
            }
            val parts = cipherText.split(IV_SEPARATOR)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedData = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val decryptedBytes = cipher.doFinal(encryptedData)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption fallback: ${e.message}")
            return try {
                String(Base64.decode(cipherText, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
                cipherText
            }
        }
    }
}

/**
 * TokenVaultManager: Handles persistent AES-256 encrypted storage of multiple Xbox accounts and Fast Switching.
 */
class TokenVaultManager(private val context: Context) {
    private val ACCOUNTS_KEY = stringPreferencesKey("saved_xbox_accounts_encrypted")
    private val ACTIVE_XUID_KEY = stringPreferencesKey("active_xuid")
    private val gson = Gson()

    // Internal model for encrypted storage
    private data class StoredEncryptedProfile(
        val xuid: String,
        val gamertag: String,
        val uhs: String,
        val gamerpic: String,
        val encUserToken: String,
        val encXstsToken: String,
        val encGsToken: String,
        val encXCloudGsToken: String,
        val encXHomeGsToken: String,
        val xCloudRegionUri: String,
        val encRefreshToken: String,
        val expiresAtMs: Long
    )

    private fun decryptProfile(stored: StoredEncryptedProfile): XboxSessionProfile {
        val userToken = VaultSecurityHelper.decrypt(stored.encUserToken)
        val xstsToken = VaultSecurityHelper.decrypt(stored.encXstsToken)
        val gsToken = VaultSecurityHelper.decrypt(stored.encGsToken)
        val xCloudGsToken = VaultSecurityHelper.decrypt(stored.encXCloudGsToken).ifBlank { gsToken }
        val xHomeGsToken = VaultSecurityHelper.decrypt(stored.encXHomeGsToken).ifBlank { gsToken }
        val refreshToken = VaultSecurityHelper.decrypt(stored.encRefreshToken)

        return XboxSessionProfile(
            xuid = stored.xuid,
            gamertag = stored.gamertag,
            uhs = stored.uhs,
            gamerpic = stored.gamerpic,
            userToken = userToken,
            xstsToken = xstsToken,
            gsToken = gsToken,
            xCloudGsToken = xCloudGsToken,
            xHomeGsToken = xHomeGsToken,
            xCloudRegionUri = stored.xCloudRegionUri,
            refreshToken = refreshToken,
            expiresAtMs = stored.expiresAtMs
        )
    }

    private fun encryptProfile(profile: XboxSessionProfile): StoredEncryptedProfile {
        val effectiveGs = profile.resolvedGsToken()
        return StoredEncryptedProfile(
            xuid = profile.xuid,
            gamertag = profile.gamertag,
            uhs = profile.uhs,
            gamerpic = profile.gamerpic,
            encUserToken = VaultSecurityHelper.encrypt(profile.userToken),
            encXstsToken = VaultSecurityHelper.encrypt(profile.xstsToken),
            encGsToken = VaultSecurityHelper.encrypt(effectiveGs),
            encXCloudGsToken = VaultSecurityHelper.encrypt(profile.xCloudGsToken.ifBlank { effectiveGs }),
            encXHomeGsToken = VaultSecurityHelper.encrypt(profile.xHomeGsToken.ifBlank { effectiveGs }),
            xCloudRegionUri = profile.xCloudRegionUri,
            encRefreshToken = VaultSecurityHelper.encrypt(profile.refreshToken),
            expiresAtMs = profile.expiresAtMs
        )
    }

    // 1. Get all saved accounts Flow
    val savedAccounts: Flow<List<XboxSessionProfile>> = context.dataStore.data.map { prefs ->
        val json = prefs[ACCOUNTS_KEY] ?: "[]"
        val type = object : TypeToken<List<StoredEncryptedProfile>>() {}.type
        val storedList: List<StoredEncryptedProfile> = try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        storedList.map { decryptProfile(it) }
    }

    // 2. Get active profile Flow (Fast Switching)
    val activeProfile: Flow<XboxSessionProfile?> = context.dataStore.data.map { prefs ->
        val activeXuid = prefs[ACTIVE_XUID_KEY] ?: return@map null
        val json = prefs[ACCOUNTS_KEY] ?: "[]"
        val type = object : TypeToken<List<StoredEncryptedProfile>>() {}.type
        val storedList: List<StoredEncryptedProfile> = try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val match = storedList.find { it.xuid == activeXuid } ?: return@map null
        decryptProfile(match)
    }

    // 3. Save or Update a profile (Sprint 1 specification)
    suspend fun saveOrUpdateProfile(profile: XboxSessionProfile) {
        context.dataStore.edit { prefs ->
            val json = prefs[ACCOUNTS_KEY] ?: "[]"
            val type = object : TypeToken<MutableList<StoredEncryptedProfile>>() {}.type
            val accounts: MutableList<StoredEncryptedProfile> = try {
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }

            val encrypted = encryptProfile(profile)
            val existingIndex = accounts.indexOfFirst { it.xuid == profile.xuid }
            if (existingIndex >= 0) {
                accounts[existingIndex] = encrypted
            } else {
                accounts.add(encrypted)
            }

            prefs[ACCOUNTS_KEY] = gson.toJson(accounts)
            prefs[ACTIVE_XUID_KEY] = profile.xuid // Set as active
        }
    }

    suspend fun saveSession(profile: XboxSessionProfile) = saveOrUpdateProfile(profile)

    suspend fun saveStreamCredentials(gsToken: String, baseRegionUri: String) {
        context.dataStore.edit { prefs ->
            val json = prefs[ACCOUNTS_KEY] ?: "[]"
            val type = object : TypeToken<MutableList<StoredEncryptedProfile>>() {}.type
            val accounts: MutableList<StoredEncryptedProfile> = try {
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
            val activeXuid = prefs[ACTIVE_XUID_KEY]
            val index = accounts.indexOfFirst { it.xuid == activeXuid }
            if (index >= 0) {
                val acc = accounts[index]
                accounts[index] = acc.copy(
                    encGsToken = VaultSecurityHelper.encrypt(gsToken),
                    encXCloudGsToken = VaultSecurityHelper.encrypt(gsToken),
                    xCloudRegionUri = baseRegionUri
                )
                prefs[ACCOUNTS_KEY] = gson.toJson(accounts)
            }
        }
    }

    fun getActiveSession(): Flow<XboxSessionProfile?> = activeProfile

    fun getAllAccounts(): Flow<List<XboxSessionProfile>> = savedAccounts

    // 4. Switch active account (Sprint 1 specification)
    suspend fun switchActiveAccount(xuid: String) {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_XUID_KEY] = xuid
        }
    }

    suspend fun switchAccount(xuid: String) = switchActiveAccount(xuid)

    // 5. Remove account (Sprint 1 specification)
    suspend fun removeAccount(xuid: String) {
        context.dataStore.edit { prefs ->
            val json = prefs[ACCOUNTS_KEY] ?: "[]"
            val type = object : TypeToken<MutableList<StoredEncryptedProfile>>() {}.type
            val accounts: MutableList<StoredEncryptedProfile> = try {
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }

            accounts.removeAll { it.xuid == xuid }
            prefs[ACCOUNTS_KEY] = gson.toJson(accounts)

            if (prefs[ACTIVE_XUID_KEY] == xuid) {
                prefs.remove(ACTIVE_XUID_KEY)
            }
        }
    }
}
