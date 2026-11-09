package com.xcloud.godmode.streaming

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject

/**
 * CustomEdidManager: Extracts physical screen geometry (aspect ratio, exact pixels, DPI)
 * and formats the virtual EDID payload injected into GSSV play sessions (POST /v5/sessions/cloud/play)
 * to unlock anamorphic rendering and suppress black bars on mobile displays.
 */
class CustomEdidManager(private val context: Context) {

    fun buildDisplayInfoPayload(): JSONObject {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)

        val width = if (metrics.widthPixels > 0) metrics.widthPixels else 1920
        val height = if (metrics.heightPixels > 0) metrics.heightPixels else 1080
        val dpiX = if (metrics.xdpi > 0) metrics.xdpi.toInt() else 395
        val dpiY = if (metrics.ydpi > 0) metrics.ydpi.toInt() else 395

        return JSONObject().apply {
            put("dimensions", JSONObject().apply {
                put("widthInPixels", maxOf(width, height))
                put("heightInPixels", minOf(width, height))
            })
            put("pixelDensity", JSONObject().apply {
                put("dpiX", dpiX)
                put("dpiY", dpiY)
            })
            put("aspectRatio", String.format("%.2f:1", maxOf(width, height).toFloat() / minOf(width, height)))
        }
    }
}
