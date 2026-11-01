package com.xcloud.godmode.network

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DiscordRPCManager: Broadcasts live streaming metadata to local Discord clients.
 * Shows: "Jugando a [Title] en God Mode Hub - [Resolution]/120FPS" with live artwork.
 */
object DiscordRPCManager {

    private const val TAG = "DiscordRPC"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class PresenceData(
        val title: String = "",
        val titleId: String = "",
        val iconUrl: String = "",
        val details: String = "",
        val state: String = "Jugando en la Nube",
        val startTimestamp: Long = 0L,
        val isStreaming: Boolean = false
    )

    private val _presence = MutableStateFlow(PresenceData())
    val presence: StateFlow<PresenceData> = _presence.asStateFlow()

    fun updatePresence(
        context: Context,
        gameTitle: String,
        titleId: String,
        iconUrl: String,
        resProfile: String = "1440p",
        fps: Int = 120
    ) {
        scope.launch {
            val data = PresenceData(
                title = gameTitle,
                titleId = titleId,
                iconUrl = iconUrl,
                details = "God Mode Hub • $resProfile @ ${fps}FPS",
                state = "Xbox Cloud Gaming (Bypass Ultra)",
                startTimestamp = System.currentTimeMillis(),
                isStreaming = true
            )
            _presence.value = data
            Log.d(TAG, "Discord Presence emitida: $gameTitle ($resProfile/${fps}FPS)")

            // Emite Intent broadcast de Discord IPC estándar para clientes móviles compatibles
            try {
                val intent = Intent("com.discord.ACTION_UPDATE_PRESENCE").apply {
                    putExtra("game", gameTitle)
                    putExtra("details", data.details)
                    putExtra("state", data.state)
                    putExtra("large_image", iconUrl)
                    putExtra("start_time", data.startTimestamp)
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.d(TAG, "Discord broadcast notification: ${e.message}")
            }
        }
    }

    fun clearPresence(context: Context) {
        scope.launch {
            _presence.value = PresenceData()
            try {
                val intent = Intent("com.discord.ACTION_CLEAR_PRESENCE")
                context.sendBroadcast(intent)
            } catch (_: Exception) {}
        }
    }
}
