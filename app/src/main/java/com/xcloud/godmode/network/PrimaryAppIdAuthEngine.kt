package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PrimaryAppIdAuthEngine: Consolidates the hierarchical Xbox Application ID prioritization
 * and automatic cascade execution upon client rejection or corporate restrictions.
 *
 * Priority Hierarchy:
 * 1. PRIMARY_APP_ID: "00000000402b5328" (Xbox Cloud Companion - #1 Principal).
 * 2. FALLBACK_1_APP_ID: "000000004c20a908" (Cliente XAL Android - Fallback 1).
 * 3. FALLBACK_2_APP_ID: "000000004827c78e" (Xbox App Unificada - Fallback 2).
 *
 * Remote Connect & Device Code:
 * Strictly routes through Microsoft Consumer portal (https://login.live.com/oauth20_remoteconnect.srf)
 * completely eliminating Azure AD enterprise tenant and first-party consent errors (invalid_request).
 */
object PrimaryAppIdAuthEngine {
    private const val TAG = "PrimaryAppIdAuth"

    // Hierarchical App IDs (Modernized for Consumer MSA stability)
    const val PRIMARY_APP_ID = "00000000402b5328"       // #1 Principal: Xbox Cloud Companion
    const val FALLBACK_1_APP_ID = "000000004c20a908"    // #2 Sub-login: Cliente XAL Android (XStreaming)
    const val FALLBACK_2_APP_ID = "000000004827c78e"    // #3 Terciaria: Xbox App Unificada

    // Title IDs
    const val TITLE_ID_CLOUD_COMPANION = "328178078"
    const val TITLE_ID_XSTREAMING = "1016898439"
    const val TITLE_ID_UNIFIED = "328178078"

    // Standard Consumer Endpoints & Scopes
    const val CONSUMER_REMOTE_CONNECT_URL = "https://login.live.com/oauth20_remoteconnect.srf"
    const val MBI_SSL_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
    const val LPT_TRANSFER_SCOPE =
        "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"

    val HIERARCHY_LIST: List<String> = listOf(
        PRIMARY_APP_ID,
        FALLBACK_1_APP_ID,
        FALLBACK_2_APP_ID
    )

    @Volatile
    private var inMemoryActiveAppId: String = PRIMARY_APP_ID

    fun getRedirectScheme(appId: String = inMemoryActiveAppId): String = "ms-xal-$appId"

    fun getRedirectUri(appId: String = inMemoryActiveAppId): String = "ms-xal-$appId://auth"

    fun getTitleId(appId: String = inMemoryActiveAppId): String = when (appId) {
        PRIMARY_APP_ID -> TITLE_ID_CLOUD_COMPANION
        FALLBACK_1_APP_ID -> TITLE_ID_XSTREAMING
        else -> TITLE_ID_UNIFIED
    }

    fun getRemoteConnectCompleteUrl(userCode: String): String {
        return "$CONSUMER_REMOTE_CONNECT_URL?otc=$userCode"
    }

    suspend fun getActiveAppId(vault: SessionVaultPersistence? = null): String {
        val savedSession = vault?.loadSession()
        if (savedSession != null && savedSession.clientIdUsed.isNotBlank()) {
            inMemoryActiveAppId = savedSession.clientIdUsed
            return savedSession.clientIdUsed
        }
        return inMemoryActiveAppId
    }

    suspend fun setActiveAppId(appId: String, vault: SessionVaultPersistence? = null) {
        inMemoryActiveAppId = appId
        Log.i(TAG, "Active Xbox App ID updated to: $appId (Redirect: ${getRedirectUri(appId)})")
        val savedSession = vault?.loadSession()
        if (savedSession != null && savedSession.clientIdUsed != appId) {
            val updated = savedSession.copy(clientIdUsed = appId)
            vault.saveSession(updated)
        }
    }

    /**
     * Extracts the App ID from a deep link URI string (e.g. "ms-xal-00000000402b5328://auth?code=...").
     */
    fun extractAppIdFromUri(uriString: String): String? {
        val normalizedUri = uriString.lowercase()
        if (!normalizedUri.startsWith("ms-xal-")) return null
        val afterPrefix = normalizedUri.substring("ms-xal-".length)
        val schemeDelimiter = afterPrefix.indexOf("://")
        val appIdCandidate = if (schemeDelimiter != -1) {
            afterPrefix.substring(0, schemeDelimiter)
        } else {
            afterPrefix.take(16)
        }
        return if (HIERARCHY_LIST.contains(appIdCandidate)) {
            appIdCandidate
        } else {
            appIdCandidate.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Executes an authentication or token action across the hierarchy.
     * If the current App ID receives an error that indicates client/scope rejection,
     * it automatically cascades to the next ID in the hierarchy.
     */
    suspend fun <T> executeWithHierarchyFallback(
        vault: SessionVaultPersistence? = null,
        forcedAppId: String? = null,
        action: suspend (appId: String, redirectUri: String, titleId: String) -> T
    ): T = withContext(Dispatchers.IO) {
        val startId = forcedAppId ?: getActiveAppId(vault)
        val candidateOrder = buildList {
            add(startId)
            HIERARCHY_LIST.forEach { if (it != startId) add(it) }
        }

        var lastException: Throwable? = null
        for (appId in candidateOrder) {
            val redirectUri = getRedirectUri(appId)
            val titleId = getTitleId(appId)
            try {
                Log.d(TAG, "Attempting Xbox Auth with App ID: $appId (Redirect: $redirectUri)")
                val result = action(appId, redirectUri, titleId)
                // If successful, persist this App ID as the active working one
                setActiveAppId(appId, vault)
                return@withContext result
            } catch (e: Throwable) {
                lastException = e
                val message = e.message.orEmpty()
                Log.w(TAG, "Auth attempt with App ID $appId failed: $message")
                val isClientRejection = message.contains("invalid_client") ||
                    message.contains("unauthorized_client") ||
                    message.contains("invalid_request") ||
                    message.contains("AADSTS") ||
                    message.contains("400") ||
                    message.contains("401") ||
                    message.contains("first-party") ||
                    message.contains("primera parte")

                if (!isClientRejection) {
                    // Non-client rejection (e.g. network disconnect, user cancellation) -> do not cascade
                    throw e
                }
                Log.i(TAG, "Cascading to next App ID in hierarchy...")
            }
        }
        throw lastException ?: IllegalStateException("All App IDs in hierarchy failed to authenticate.")
    }
}
