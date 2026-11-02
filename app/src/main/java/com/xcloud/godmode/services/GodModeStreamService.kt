package com.xcloud.godmode.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class GodModeStreamService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GodMode::StreamKeepAliveLock"
        ).apply { acquire(4 * 60 * 60 * 1000L) } // 4 hours timeout

        startForeground(1337, createPersistentNotification())
    }

    private fun createPersistentNotification(): Notification {
        val channelId = "god_mode_stream_channel"
        val channel = NotificationChannel(
            channelId,
            "xCloud Stream Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, channelId)
            .setContentTitle("God Mode Hub Activo")
            .setContentText("Transmisión de baja latencia en curso...")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
