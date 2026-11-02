package com.xcloud.godmode.audio

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class HeadTrackingManager(private val sensorManager: SensorManager) : SensorEventListener {
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    fun start() {
        // Fast polling at 100Hz (10,000 microseconds)
        rotationSensor?.let {
            sensorManager.registerListener(this, it, 10_000)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val qx = event.values[0]
            val qy = event.values[1]
            val qz = event.values[2]
            val qw = if (event.values.size > 3) event.values[3] else 1.0f
            
            // Inject quaternion to native audio engine for HRTF spatial audio recalibration
            NativeAudioEngine.updateHeadPosition(qx, qy, qz, qw)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

object NativeAudioEngine {
    init {
        try {
            System.loadLibrary("godmode_ndk_engine")
        } catch (e: UnsatisfiedLinkError) {
            // Fallback or handle missing library gracefully
        }
    }
    external fun updateHeadPosition(qx: Float, qy: Float, qz: Float, qw: Float)
}
