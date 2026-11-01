package com.xcloud.godmode.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI

/**
 * GyroscopeProvider: Translates device rotation into virtual Right Stick axes.
 * Supports sensitivity calibration and axis inversion.
 */
class GyroscopeProvider(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    var isEnabled = false
        set(value) {
            field = value
            if (value) start() else stop()
        }

    var sensitivityX = 1.0f
    var sensitivityY = 1.0f
    var invertX = false
    var invertY = false
    
    private var _onGyroUpdate: ((Float, Float) -> Unit)? = null

    fun setOnGyroUpdate(listener: (Float, Float) -> Unit) {
        _onGyroUpdate = listener
    }

    private fun start() {
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || event == null) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            // event.values[0]: Pitch (X axis) -> Vertical look
            // event.values[1]: Yaw (Y axis) -> Horizontal look
            // event.values[2]: Roll (Z axis)
            
            var dx = event.values[1] * sensitivityX
            var dy = event.values[0] * sensitivityY
            
            if (invertX) dx *= -1
            if (invertY) dy *= -1
            
            // Normalize or pass raw delta depending on what the consumer expects.
            // xCloud usually expects absolute stick positions or relative deltas.
            // Here we provide relative deltas (rad/s).
            _onGyroUpdate?.invoke(dx, dy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
