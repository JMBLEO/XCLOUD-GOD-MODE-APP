package com.xcloud.godmode.services

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

interface XboxAuthApi {
    suspend fun refreshXstsToken(): String
}

class TokenWatchdog(private val authApi: XboxAuthApi) {
    val currentXstsToken = AtomicReference<String>()

    fun startSilentRotation(coroutineScope: CoroutineScope, initialToken: String, expiresInMs: Long) {
        currentXstsToken.set(initialToken)
        coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Wake up 15 minutes (900,000 ms) before expiration
                val delayTime = expiresInMs - 900_000L
                if (delayTime > 0) {
                    delay(delayTime)
                }
                
                try {
                    val newToken = authApi.refreshXstsToken()
                    currentXstsToken.set(newToken)
                } catch (e: Exception) {
                    delay(60_000) // Retry in 1 minute if failed
                }
            }
        }
    }
}
