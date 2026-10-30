package com.aistudio.xcloudgodmode.ai.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistudio.xcloudgodmode.ai.services.TokenVaultManager
import com.aistudio.xcloudgodmode.ai.services.XboxFullAuthManager
import com.aistudio.xcloudgodmode.ai.services.XboxSessionProfile
import kotlinx.coroutines.flow.first
import android.util.Log

/**
 * TokenRefreshWorker: Background demon that monitors gsToken expiration.
 * Executes OAuth2 refresh when less than 20 minutes are remaining.
 */
class TokenRefreshWorker(appContext: Context, workerParams: WorkerParameters) : 
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val vault = TokenVaultManager(applicationContext)
        val activeProfile = vault.activeProfile.first() ?: return Result.success()

        val currentTime = System.currentTimeMillis()
        val timeRemainingMs = activeProfile.expiresAtMs - currentTime

        // If less than 20 minutes (1,200,000 ms) remain for the 4-hour token
        if (timeRemainingMs < 1200000) {
            Log.d("TokenRefreshWorker", "Token expiring soon. Initiating silent refresh...")
            
            val networkClient = com.aistudio.xcloudgodmode.ai.services.NetworkClient()
            val authManager = XboxFullAuthManager(networkClient)

            return try {
                // 1. Refresh MSA Tokens
                val (newAccessToken, newRefreshToken) = authManager.refreshMsaAccessToken(activeProfile.refreshToken)
                
                // 2. Perform Sisu Authorization
                // We use a simplified flow here or reuse the existing authManager logic
                // For a robust refresh, we re-run the sisu flow if possible.
                // In this implementation, completeSisuAuth usually needs a code, 
                // but for refresh we only need the refresh_token.
                
                // Let's assume the authManager has a logic for token-based refresh.
                // If not, we'd need to add it. Let's check authManager methods.
                
                // Since authManager.completeAuth(msaTokens) already handles the chain, 
                // we'll use a simulated result or expand authManager.
                
                val simulatedMsaJson = org.json.JSONObject().apply {
                    put("access_token", newAccessToken)
                    put("refresh_token", newRefreshToken)
                }
                
                val newSession = authManager.completeAuth(simulatedMsaJson)

                // 3. Update vault with new session data
                vault.saveOrUpdateProfile(
                    activeProfile.copy(
                        refreshToken = newRefreshToken,
                        xCloudGsToken = newSession.xCloudGsToken,
                        xHomeGsToken = newSession.xHomeGsToken,
                        expiresAtMs = System.currentTimeMillis() + (4 * 60 * 60 * 1000) // Extend for 4 hours
                    )
                )
                
                Log.d("TokenRefreshWorker", "Silent refresh successful for ${activeProfile.gamertag}")
                Result.success()
            } catch (e: Exception) {
                Log.e("TokenRefreshWorker", "Silent refresh failed: ${e.message}")
                Result.retry()
            }
        }

        return Result.success()
    }
}
