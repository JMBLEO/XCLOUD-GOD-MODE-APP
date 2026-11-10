package com.xcloud.godmode.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log
import com.xcloud.godmode.network.CredentialResolutionResult
import com.xcloud.godmode.network.StreamingCredentialOrchestrator
import com.xcloud.godmode.services.EncryptedVaultService

/**
 * TokenRefreshWorker: Background daemon that monitors gsToken expiration.
 * Executes silent 4-step token chain using StreamingCredentialOrchestrator and EncryptedVaultService.
 */
class TokenRefreshWorker(appContext: Context, workerParams: WorkerParameters) : 
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val vault = EncryptedVaultService.getInstance(applicationContext)
        val orchestrator = StreamingCredentialOrchestrator(vault)

        Log.d("TokenRefreshWorker", "Checking streaming credential health in background...")
        return try {
            when (val result = orchestrator.ensureValidStreamingToken()) {
                is CredentialResolutionResult.Ready -> {
                    Log.d("TokenRefreshWorker", "Streaming credentials valid/refreshed for ${result.credentials.gamertag}")
                    Result.success()
                }
                is CredentialResolutionResult.RequiresInteractiveLogin -> {
                    Log.w("TokenRefreshWorker", "Session expired or revoked: ${result.reason}")
                    Result.success()
                }
                is CredentialResolutionResult.NetworkFailure -> {
                    Log.e("TokenRefreshWorker", "Network failure during token refresh: ${result.reason}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e("TokenRefreshWorker", "Background token refresh error: ${e.message}")
            Result.retry()
        }
    }
}
