package com.xcloud.godmode.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xcloud.godmode.network.PrimaryAppIdAuthEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GodModeAppCoreRoot: Root orchestrator for the "God Mode" Xbox Cloud Gaming client.
 * Enforces the 100% native architecture, tech policy, and hierarchical App ID cascade.
 *
 * Tech Policy Enforcement:
 * - 0% WebView
 * - 0% WebRTC Google
 * - 0% FFmpeg
 * - 0% H.264 (Strictly HEVC/AV1)
 */
class GodModeAppCoreRoot(private val context: Context) {

    companion object {
        // Tech Policy Constants
        const val ENGINE_VERSION = "10.0"
        const val ALLOW_WEBVIEW = false
        const val ALLOW_FFMPEG = false
        const val ALLOW_WEBRTC_GOOGLE = false
        const val STRICT_CODECS = true // Strictly HEVC and AV1

        @Volatile
        private var instance: GodModeAppCoreRoot? = null

        fun getInstance(context: Context): GodModeAppCoreRoot {
            return instance ?: synchronized(this) {
                instance ?: GodModeAppCoreRoot(context.applicationContext).also { instance = it }
            }
        }
    }

    private var secureVault: android.content.SharedPreferences? = null
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (secureVault != null) return@withContext
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            secureVault = EncryptedSharedPreferences.create(
                context,
                "god_mode_root_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("AppCoreRoot", "Failed to init secure vault: ${e.message}")
        }
    }

    /**
     * Returns the currently active and working Xbox App ID from the priority hierarchy.
     */
    suspend fun getActiveAppId(): String {
        return secureVault?.getString("ACTIVE_APP_ID", PrimaryAppIdAuthEngine.PRIMARY_APP_ID)
            ?: PrimaryAppIdAuthEngine.getActiveAppId()
    }

    /**
     * Persists the App ID that successfully completed an authentication link.
     */
    suspend fun saveActiveAppId(appId: String) {
        secureVault?.edit()?.putString("ACTIVE_APP_ID", appId)?.apply()
        // Synchronize with the network engine
        PrimaryAppIdAuthEngine.setActiveAppId(appId)
    }

    /**
     * Executes a network operation (SISU, MSA, Token Refresh) with automatic App ID cascade fallback.
     * If a server returns 400/401 Client/Request errors, it automatically retries with the next ID in hierarchy.
     */
    suspend fun <T> executeWithAppIdCascade(block: suspend (appId: String) -> T): T = withContext(Dispatchers.IO) {
        val active = getActiveAppId()
        val cascade = listOf(active) + PrimaryAppIdAuthEngine.HIERARCHY_LIST.filter { it != active }

        var lastError: Throwable? = null
        for (appId in cascade) {
            try {
                android.util.Log.d("AppCoreRoot", "Executing operation with App ID: $appId")
                val res = block(appId)
                saveActiveAppId(appId)
                return@withContext res
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty().lowercase()
                
                // Only cascade on client/authorization rejection errors
                val shouldCascade = msg.contains("400") || 
                                   msg.contains("401") || 
                                   msg.contains("unauthorized") || 
                                   msg.contains("invalid_client") ||
                                   msg.contains("invalid_request") ||
                                   msg.contains("aadsts") ||
                                   msg.contains("first-party") ||
                                   msg.contains("primera parte")

                if (!shouldCascade) {
                    // Fatal network or logic error, do not retry hierarchy
                    throw e
                }
                android.util.Log.w("AppCoreRoot", "App ID $appId rejected. Cascading hierarchy...")
            }
        }
        throw lastError ?: IllegalStateException("Fallo crítico: Ningún App ID de la jerarquía pudo autenticar.")
    }
}
