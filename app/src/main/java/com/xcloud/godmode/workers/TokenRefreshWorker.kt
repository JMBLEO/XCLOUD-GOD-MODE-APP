package com.xcloud.godmode.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import android.util.Log

/**
 * TokenRefreshWorker: Background demon that monitors gsToken expiration.
 * Executes OAuth2 refresh when less than 20 minutes are remaining.
 */
class TokenRefreshWorker(appContext: Context, workerParams: WorkerParameters) : 
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val vault = com.xcloud.godmode.services.UnifiedPersistentAuthVault.getInstance(applicationContext)
        val envelope = vault.loadEnvelope() ?: return Result.success()
        val sessionState = vault.sessionState.first()

        val currentTime = android.os.SystemClock.elapsedRealtime()
        val timeRemainingMs = envelope.gsExpiresAtElapsedMs - currentTime

        // If less than 20 minutes (1,200,000 ms) remain
        if (timeRemainingMs < 1200000) {
            Log.d("TokenRefreshWorker", "Token expiring soon. Initiating silent refresh...")
            
            val networkClient = com.xcloud.godmode.network.NetworkClient()
            val authManager = com.xcloud.godmode.network.XboxFullAuthManager(networkClient)

            return try {
                val (newAccessToken, newRefreshToken) = authManager.refreshMsaAccessToken(String(envelope.msaRefreshToken))
                
                val simulatedMsaJson = org.json.JSONObject().apply {
                    put("access_token", newAccessToken)
                    put("refresh_token", newRefreshToken)
                }
                
                val newSession = authManager.completeAuth(simulatedMsaJson)

                val newEnvelope = com.xcloud.godmode.network.SecureTokenEnvelope(
                    msaAccessToken = newAccessToken.toCharArray(),
                    msaRefreshToken = newRefreshToken.toCharArray(),
                    msaExpiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() + 14_400_000L,
                    lptTransferToken = CharArray(0),
                    xblUserToken = newSession.webXstsToken.toCharArray(),
                    userHashUhs = newSession.uhs.toCharArray(),
                    xstsWebToken = CharArray(0),
                    xstsGssvToken = newSession.webXstsToken.toCharArray(),
                    xstsExpiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() + 14_400_000L,
                    gsStreamToken = newSession.xCloudGsToken.toCharArray(),
                    gsExpiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() + 14_400_000L,
                    discoveredBaseRegionUri = newSession.xCloudRegionUri,
                    market = "CL"
                )
                
                vault.saveSession(sessionState.gamertag, sessionState.subscriptionTier, newEnvelope)
                
                Log.d("TokenRefreshWorker", "Silent refresh successful for ${sessionState.gamertag}")
                Result.success()
            } catch (e: Exception) {
                Log.e("TokenRefreshWorker", "Silent refresh failed: ${e.message}")
                Result.retry()
            }
        }

        return Result.success()
    }
}
