package com.aistudio.xcloudgodmode.ai.audio

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build

/**
 * BassBoostHaptics: Analyzes low-frequency audio energy and translates it to device vibration.
 * Used for "God Mode" immersion on mobile devices.
 */
class BassBoostHaptics(context: Context) {

    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Called when a low-frequency peak is detected in the stream audio.
     * @param intensity 0.0 to 1.0
     */
    fun onBassPeak(intensity: Float) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        
        val duration = (30 + (intensity * 70)).toLong()
        val amplitude = (intensity * 255).toInt().coerceIn(1, 255)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}
