package com.xcloud.godmode.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xcloud.godmode.R

/**
 * StreamingService is a Foreground Service that keeps the native stream alive
 * even when the app is in the background or in Picture-in-Picture mode.
 */
class StreamingService : Service() {

    private val CHANNEL_ID = "xcloud_stream_channel"
    private val NOTIFICATION_ID = 1337
    
    private val binder = StreamingBinder()

    inner class StreamingBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Sesión God Mode Activa")
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = Intent(this, com.xcloud.godmode.MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("God Mode Hub")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play) // Use a better icon later
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "xCloud Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
    
    fun updateStatus(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(status))
    }
}
