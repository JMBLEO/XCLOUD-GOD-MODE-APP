package com.xcloud.godmode.streaming

import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * AdaptiveVrrController: Matches display hardware refresh rate directly to video stream frame rate
 * via Surface.setFrameRate (Android 11+ API 30+), eliminating 3:2 asymmetric pulldown judder on 90Hz/120Hz LTPO panels.
 */
class AdaptiveVrrController {
    companion object {
        private const val TAG = "AdaptiveVrr"
    }

    var isVrrSupported: Boolean = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        private set

    var currentTargetFps: Float = 60.0f
        private set

    var isFrameRateLocked: Boolean = false
        private set

    /**
     * Locks the hardware display surface refresh rate to match the active stream FPS.
     */
    fun matchDisplayToStream(surface: Surface, targetFps: Float): Boolean {
        currentTargetFps = targetFps

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                surface.setFrameRate(
                    targetFps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
                isFrameRateLocked = true
                Log.i(TAG, "Hardware display refresh rate locked to ${targetFps}Hz via Surface.setFrameRate")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Surface.setFrameRate", e)
                isFrameRateLocked = false
                false
            }
        } else {
            Log.d(TAG, "Surface.setFrameRate requires Android 11+ (API 30+). Running in default refresh mode.")
            return false
        }
    }

    /**
     * Clears frame rate lock, restoring OS default variable refresh rate behavior.
     */
    fun clearFrameRateLock(surface: Surface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isFrameRateLocked) {
            try {
                surface.clearFrameRate()
            } catch (_: Exception) {}
            isFrameRateLocked = false
            Log.i(TAG, "Restored default display refresh rate policy.")
        }
    }
}
