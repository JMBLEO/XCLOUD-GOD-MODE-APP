package com.xcloud.godmode.haptics

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

class GyroAimController : SensorEventListener {
    private var isAiming = false
    private val sensitivity = 0.25f

    fun setAimTriggerState(pressed: Boolean) {
        isAiming = pressed
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isAiming || event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val yawRate = event.values[1]   // Yaw (horizontal)
        val pitchRate = event.values[0] // Pitch (vertical)

        val gyroDeltaX = (yawRate * sensitivity).coerceIn(-1.0f, 1.0f)
        val gyroDeltaY = (pitchRate * sensitivity).coerceIn(-1.0f, 1.0f)

        // NativeInputBridge.injectAimDeltas(gyroDeltaX, gyroDeltaY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
