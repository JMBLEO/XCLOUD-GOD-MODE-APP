package com.xcloud.godmode.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.xcloud.godmode.MainActivity

/**
 * ShortcutLauncherService: Generates pinned home-screen launcher shortcuts for games (API 26+),
 * allowing one-touch cold boot directly into game streaming.
 */
object ShortcutLauncherService {
    private const val TAG = "ShortcutLauncher"

    const val EXTRA_TITLE_ID = "com.xcloud.godmode.EXTRA_TITLE_ID"
    const val EXTRA_AUTO_START_STREAM = "com.xcloud.godmode.EXTRA_AUTO_START_STREAM"

    fun canPinShortcuts(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            return shortcutManager?.isRequestPinShortcutSupported == true
        }
        return false
    }

    fun pinGameShortcut(
        context: Context,
        titleId: String,
        titleName: String,
        iconBitmap: Bitmap? = null
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!shortcutManager.isRequestPinShortcutSupported) {
            return false
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_TITLE_ID, titleId)
            putExtra(EXTRA_AUTO_START_STREAM, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val icon = if (iconBitmap != null) {
            Icon.createWithAdaptiveBitmap(iconBitmap)
        } else {
            Icon.createWithResource(context, android.R.drawable.ic_media_play)
        }

        val pinShortcutInfo = ShortcutInfo.Builder(context, "shortcut_game_$titleId")
            .setShortLabel(titleName.take(20))
            .setLongLabel("God Mode: $titleName")
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        val callbackIntent = shortcutManager.createShortcutResultIntent(pinShortcutInfo)
        val successPendingIntent = PendingIntent.getBroadcast(
            context,
            titleId.hashCode(),
            callbackIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        Log.i(TAG, "Requesting pin shortcut for $titleName ($titleId)")
        return shortcutManager.requestPinShortcut(pinShortcutInfo, successPendingIntent.intentSender)
    }
}
