package com.xcloud.godmode.network

import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * SessionKeepaliveHeartbeat: Sends periodic signals to Azure to prevent unilateral session closure.
 */
class SessionKeepaliveHeartbeat(
    private val scope: CoroutineScope,
    private val sendControlPing: suspend () -> Unit
) {
    private var heartbeatJob: Job? = null

    fun start() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(15_000L) // 15s interval as per cloud session keepalive specification
                runCatching {
                    sendControlPing()
                }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
