package com.xcloud.godmode.services

import android.content.Context
import android.os.Build
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.xcloud.godmode.network.CompleteXboxSession
import com.xcloud.godmode.network.SessionVaultPersistence
import com.xcloud.godmode.network.SessionVaultStorage
import com.xcloud.godmode.network.StreamingSessionCredentials
import com.xcloud.godmode.network.XCloudTokenBundle
import com.xcloud.godmode.network.XCloudTokenStorage
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * EncryptedVaultService: Hardware-isolated session credential vault.
 * Secures Microsoft, XSTS, and GSSV streaming credentials using Android KeyStore
 * backed by hardware AES-256-GCM encryption in noBackupFilesDir.
 * Implements SessionVaultStorage, XCloudTokenStorage and SessionVaultPersistence.
 */
class EncryptedVaultService private constructor(private val context: Context) : SessionVaultStorage, XCloudTokenStorage, SessionVaultPersistence {

    companion object {
        private const val TAG = "EncryptedVaultService"
        private const val VAULT_FILE_NAME = "secure_session_vault.dat"
        private const val KEY_ALIAS = "XCloudGodModeMasterKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        @Volatile
        private var instance: EncryptedVaultService? = null

        fun getInstance(context: Context): EncryptedVaultService {
            return instance ?: synchronized(this) {
                instance ?: EncryptedVaultService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val secureFile: File = File(context.noBackupFilesDir, VAULT_FILE_NAME)
    private var fallbackJvmSecretKey: SecretKey? = null

    init {
        try {
            initMasterKey()
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore initialization failed (${e.message}). Initializing test fallback key.")
            initJvmFallbackKey()
        }
    }

    private fun initMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(parameterSpec)
            keyGenerator.generateKey()
            Log.i(TAG, "Created hardware AES-256-GCM master key in Android KeyStore")
        }
    }

    private fun initJvmFallbackKey() {
        if (fallbackJvmSecretKey == null) {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            fallbackJvmSecretKey = keyGen.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(KEY_ALIAS, null) as SecretKey
        } catch (e: Exception) {
            if (fallbackJvmSecretKey == null) {
                initJvmFallbackKey()
            }
            fallbackJvmSecretKey!!
        }
    }

    fun encryptAndSave(plainText: String) {
        try {
            writeEncrypted(plainText)
        } catch (e: Exception) {
            Log.w(TAG, "Encryption failed (${e.javaClass.simpleName}). Regenerating KeyStore alias and retrying...")
            runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) {
                    ks.deleteEntry(KEY_ALIAS)
                }
            }
            clearVault()
            runCatching { initMasterKey() }
            runCatching { writeEncrypted(plainText) }
        }
    }

    private fun writeEncrypted(plainText: String) {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getSecretKey())
        }
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Storage format: [IV Size (1 byte)][IV bytes][Ciphertext]
        secureFile.outputStream().use { fos ->
            fos.write(iv.size)
            fos.write(iv)
            fos.write(cipherText)
        }
    }

    fun decryptAndRead(): String? {
        if (!secureFile.exists() || secureFile.length() < 14) return null
        return try {
            secureFile.inputStream().use { fis ->
                val ivSize = fis.read()
                if (ivSize <= 0 || ivSize > 32) return null
                val iv = ByteArray(ivSize)
                val ivRead = fis.read(iv)
                if (ivRead != ivSize) return null
                val cipherText = fis.readBytes()

                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                }
                String(cipher.doFinal(cipherText), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error (tampered or corrupted vault: ${e.javaClass.simpleName}). Purging and resetting alias: ${e.message}")
            runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) {
                    ks.deleteEntry(KEY_ALIAS)
                }
            }
            clearVault()
            runCatching { initMasterKey() }
            null
        }
    }

    fun clearVault() {
        if (secureFile.exists()) {
            secureFile.delete()
            Log.d(TAG, "Purged secure session vault file")
        }
    }

    // --- XCloudTokenStorage Implementation ---

    override suspend fun loadTokenBundle(): XCloudTokenBundle? {
        val jsonString = decryptAndRead() ?: return null
        return try {
            val json = JSONObject(jsonString)
            val msaRefreshToken = json.optString("msaRefreshToken")
            if (msaRefreshToken.isBlank()) return null

            XCloudTokenBundle(
                gamertag = json.optString("gamertag", "Xbox Player"),
                xuid = json.optString("xuid"),
                uhs = json.optString("uhs"),
                msaAccessToken = json.optString("msaAccessToken"),
                msaRefreshToken = msaRefreshToken,
                webXstsToken = json.optString("webXstsToken"),
                gssvXstsToken = json.optString("gssvXstsToken"),
                xgpuGsToken = json.optString("xgpuGsToken").ifBlank { json.optString("xCloudGsToken") },
                f2pGsToken = json.optString("f2pGsToken").takeIf { it.isNotBlank() },
                defaultRegionBaseUri = json.optString("defaultRegionBaseUri").ifBlank { json.optString("xCloudBaseUri") },
                regionsJson = json.optString("regionsJson", "[]"),
                gsTokenExpiresAtEpochMs = json.optLong("gsTokenExpiresAtEpochMs", json.optLong("expiresAtEpochMs", 0L))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse token bundle from vault: ${e.message}")
            null
        }
    }

    override suspend fun saveTokenBundle(bundle: XCloudTokenBundle) {
        val currentJson = runCatching {
            decryptAndRead()?.let { JSONObject(it) }
        }.getOrNull() ?: JSONObject()

        currentJson.apply {
            put("gamertag", bundle.gamertag)
            put("xuid", bundle.xuid)
            put("uhs", bundle.uhs)
            put("msaAccessToken", bundle.msaAccessToken)
            put("msaRefreshToken", bundle.msaRefreshToken)
            put("webXstsToken", bundle.webXstsToken)
            put("gssvXstsToken", bundle.gssvXstsToken)
            put("xgpuGsToken", bundle.xgpuGsToken)
            put("f2pGsToken", bundle.f2pGsToken ?: "")
            put("defaultRegionBaseUri", bundle.defaultRegionBaseUri)
            put("regionsJson", bundle.regionsJson)
            put("gsTokenExpiresAtEpochMs", bundle.gsTokenExpiresAtEpochMs)
            // Aliases for StreamingSessionCredentials compatibility:
            put("xCloudGsToken", bundle.xgpuGsToken)
            put("xCloudBaseUri", bundle.defaultRegionBaseUri)
            put("expiresAtEpochMs", bundle.gsTokenExpiresAtEpochMs)
        }
        encryptAndSave(currentJson.toString())

        mirrorToUnifiedVault(
            gamertag = bundle.gamertag,
            uhs = bundle.uhs,
            msaRefreshToken = bundle.msaRefreshToken,
            gsToken = bundle.xgpuGsToken,
            regionUri = bundle.defaultRegionBaseUri,
            expiresAtMs = bundle.gsTokenExpiresAtEpochMs,
            lptToken = currentJson.optString("longPlayToken")
        )
    }

    override suspend fun clearTokenBundle() {
        clearCredentials()
    }

    // --- SessionVaultStorage implementation ---

    override suspend fun loadCredentials(): StreamingSessionCredentials? {
        val jsonString = decryptAndRead()
        if (jsonString != null) {
            try {
                val json = JSONObject(jsonString)
                val msaRefreshToken = json.optString("msaRefreshToken")
                if (msaRefreshToken.isNotBlank()) {
                    return StreamingSessionCredentials(
                        gamertag = json.optString("gamertag", "Xbox Player"),
                        xuid = json.optString("xuid"),
                        uhs = json.optString("uhs"),
                        msaRefreshToken = msaRefreshToken,
                        xCloudGsToken = json.optString("xCloudGsToken").ifBlank { json.optString("xgpuGsToken") },
                        xCloudBaseUri = json.optString("xCloudBaseUri").ifBlank { json.optString("defaultRegionBaseUri") },
                        expiresAtEpochMs = json.optLong("expiresAtEpochMs", json.optLong("gsTokenExpiresAtEpochMs", 0L)),
                        longPlayToken = json.optString("longPlayToken")
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse credentials JSON: ${e.message}")
            }
        }

        // Fallback migration from legacy UnifiedPersistentAuthVault if present
        return try {
            val unifiedVault = UnifiedPersistentAuthVault.getInstance(context)
            val envelope = unifiedVault.loadEnvelope()
            if (envelope != null && envelope.msaRefreshToken.isNotEmpty()) {
                val creds = StreamingSessionCredentials(
                    gamertag = "Xbox Gamer",
                    xuid = "",
                    uhs = String(envelope.userHashUhs),
                    msaRefreshToken = String(envelope.msaRefreshToken),
                    xCloudGsToken = String(envelope.gsStreamToken),
                    xCloudBaseUri = envelope.discoveredBaseRegionUri,
                    expiresAtEpochMs = System.currentTimeMillis() + 14_400_000L,
                    longPlayToken = String(envelope.lptTransferToken)
                )
                saveCredentials(creds)
                creds
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveCredentials(credentials: StreamingSessionCredentials) {
        val currentJson = runCatching {
            decryptAndRead()?.let { JSONObject(it) }
        }.getOrNull() ?: JSONObject()

        currentJson.apply {
            put("gamertag", credentials.gamertag)
            put("xuid", credentials.xuid)
            put("uhs", credentials.uhs)
            put("msaRefreshToken", credentials.msaRefreshToken)
            put("xCloudGsToken", credentials.xCloudGsToken)
            put("xgpuGsToken", credentials.xCloudGsToken)
            put("xCloudBaseUri", credentials.xCloudBaseUri)
            put("defaultRegionBaseUri", credentials.xCloudBaseUri)
            put("expiresAtEpochMs", credentials.expiresAtEpochMs)
            put("gsTokenExpiresAtEpochMs", credentials.expiresAtEpochMs)
            if (credentials.longPlayToken.isNotBlank()) {
                put("longPlayToken", credentials.longPlayToken)
            }
        }
        encryptAndSave(currentJson.toString())

        mirrorToUnifiedVault(
            gamertag = credentials.gamertag,
            uhs = credentials.uhs,
            msaRefreshToken = credentials.msaRefreshToken,
            gsToken = credentials.xCloudGsToken,
            regionUri = credentials.xCloudBaseUri,
            expiresAtMs = credentials.expiresAtEpochMs,
            lptToken = credentials.longPlayToken
        )
    }

    override suspend fun clearCredentials() {
        clearVault()
        try {
            val unifiedVault = UnifiedPersistentAuthVault.getInstance(context)
            unifiedVault.clearSession()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear UnifiedVault: ${e.message}")
        }
    }

    private fun mirrorToUnifiedVault(
        gamertag: String,
        uhs: String,
        msaRefreshToken: String,
        gsToken: String,
        regionUri: String,
        expiresAtMs: Long,
        lptToken: String = ""
    ) {
        try {
            val unifiedVault = UnifiedPersistentAuthVault.getInstance(context)
            val envelope = com.xcloud.godmode.network.SecureTokenEnvelope(
                msaAccessToken = msaRefreshToken.toCharArray(),
                msaRefreshToken = msaRefreshToken.toCharArray(),
                msaExpiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() + 14_400_000L,
                lptTransferToken = lptToken.toCharArray(),
                xblUserToken = gsToken.toCharArray(),
                userHashUhs = uhs.toCharArray(),
                xstsWebToken = CharArray(0),
                xstsGssvToken = gsToken.toCharArray(),
                xstsExpiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() + 14_400_000L,
                gsStreamToken = gsToken.toCharArray(),
                gsExpiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() + 14_400_000L,
                discoveredBaseRegionUri = regionUri,
                market = "CL"
            )
            CoroutineScope(Dispatchers.IO).launch {
                unifiedVault.saveSession(gamertag, "Game Pass Ultimate", envelope)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not mirror session to UnifiedVault: ${e.message}")
        }
    }

    // --- SessionVaultPersistence Implementation ---

    override suspend fun loadSession(): CompleteXboxSession? {
        val jsonString = decryptAndRead() ?: return null
        return try {
            val json = JSONObject(jsonString)
            val msaRefresh = json.optString("msaRefreshToken")
            if (msaRefresh.isBlank()) return null

            CompleteXboxSession(
                gamertag = json.optString("gamertag", "Xbox Player"),
                xuid = json.optString("xuid"),
                uhs = json.optString("uhs"),
                clientIdUsed = json.optString("clientIdUsed", "000000004827c78e"),
                msaAccessToken = json.optString("msaAccessToken"),
                msaRefreshToken = msaRefresh,
                deviceToken = json.optString("deviceToken"),
                webXstsToken = json.optString("webXstsToken"),
                gssvXstsToken = json.optString("gssvXstsToken"),
                xgpuGsToken = json.optString("xgpuGsToken").ifBlank { json.optString("xCloudGsToken") },
                f2pGsToken = json.optString("f2pGsToken").takeIf { it.isNotBlank() },
                preferredBaseUri = json.optString("preferredBaseUri").ifBlank { json.optString("defaultRegionBaseUri").ifBlank { json.optString("xCloudBaseUri") } },
                regionsJson = json.optString("regionsJson", "[]"),
                gsTokenExpiresAtEpochMs = json.optLong("gsTokenExpiresAtEpochMs", json.optLong("expiresAtEpochMs", 0L))
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to parse CompleteXboxSession: ${e.message}")
            null
        }
    }

    override suspend fun saveSession(session: CompleteXboxSession) {
        val currentJson = runCatching {
            decryptAndRead()?.let { JSONObject(it) }
        }.getOrNull() ?: JSONObject()

        currentJson.apply {
            put("gamertag", session.gamertag)
            put("xuid", session.xuid)
            put("uhs", session.uhs)
            put("clientIdUsed", session.clientIdUsed)
            put("msaAccessToken", session.msaAccessToken)
            put("msaRefreshToken", session.msaRefreshToken)
            put("deviceToken", session.deviceToken)
            put("webXstsToken", session.webXstsToken)
            put("gssvXstsToken", session.gssvXstsToken)
            put("xgpuGsToken", session.xgpuGsToken)
            put("f2pGsToken", session.f2pGsToken ?: "")
            put("preferredBaseUri", session.preferredBaseUri)
            put("defaultRegionBaseUri", session.preferredBaseUri)
            put("regionsJson", session.regionsJson)
            put("gsTokenExpiresAtEpochMs", session.gsTokenExpiresAtEpochMs)
            // Aliases for compatibility
            put("xCloudGsToken", session.xgpuGsToken)
            put("xCloudBaseUri", session.preferredBaseUri)
            put("expiresAtEpochMs", session.gsTokenExpiresAtEpochMs)
        }
        encryptAndSave(currentJson.toString())

        mirrorToUnifiedVault(
            gamertag = session.gamertag,
            uhs = session.uhs,
            msaRefreshToken = session.msaRefreshToken,
            gsToken = session.xgpuGsToken,
            regionUri = session.preferredBaseUri,
            expiresAtMs = session.gsTokenExpiresAtEpochMs,
            lptToken = currentJson.optString("longPlayToken")
        )
    }

    override suspend fun clearSession() {
        clearCredentials()
    }
}
