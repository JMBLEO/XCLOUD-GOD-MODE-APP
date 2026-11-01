package com.xcloud.godmode.services

import com.xcloud.godmode.models.GameModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionTelemetrySummary(
    val gameTitle: String,
    val durationSeconds: Long,
    val avgPingMs: Long,
    val avgJitterMs: Double,
    val packetLossPercent: Double,
    val resolutionUsed: String,
    val motorUsed: String,
    val serverId: String = "ChileCentral",
    val networkType: String = "Wi-Fi 6"
)

object StreamSessionHolder {
    var activeGsToken: String = ""
    var activeSessionPath: String = ""
    var activeGame: GameModel? = null
    var sessionStartTime: Long = 0L

    private val _lastSessionSummary = MutableStateFlow<SessionTelemetrySummary?>(null)
    val lastSessionSummary: StateFlow<SessionTelemetrySummary?> = _lastSessionSummary.asStateFlow()

    fun recordStart(game: GameModel?, gsToken: String, sessionPath: String) {
        activeGame = game
        activeGsToken = gsToken
        activeSessionPath = sessionPath
        sessionStartTime = System.currentTimeMillis()
    }

    fun recordEnd(
        avgPing: Long = 24, 
        avgJitter: Double = 1.1, 
        packetLoss: Double = 0.02, 
        resolution: String = "1080p", 
        motor: String = "Native WebRTC + FSR",
        serverId: String = "ChileCentral",
        networkType: String = "Wi-Fi 6"
    ) {
        val duration = if (sessionStartTime > 0) (System.currentTimeMillis() - sessionStartTime) / 1000 else 45
        _lastSessionSummary.value = SessionTelemetrySummary(
            gameTitle = activeGame?.title ?: "Xbox Cloud Game",
            durationSeconds = duration,
            avgPingMs = avgPing,
            avgJitterMs = avgJitter,
            packetLossPercent = packetLoss,
            resolutionUsed = resolution,
            motorUsed = motor,
            serverId = serverId,
            networkType = networkType
        )
        activeGsToken = ""
        activeSessionPath = ""
    }

    fun clearSummary() {
        _lastSessionSummary.value = null
    }
}
