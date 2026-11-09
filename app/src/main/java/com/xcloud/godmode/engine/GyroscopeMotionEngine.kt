package com.xcloud.godmode.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.InputDevice
import kotlin.math.abs

enum class GyroscopeSource {
    DEVICE_INTERNAL,
    GAMEPAD_CONTROLLER,
    AUTO_PREFER_GAMEPAD
}

enum class GyroscopeActivationGate {
    ALWAYS_ON,
    HOLD_LEFT_TRIGGER, // ADS (Aim Down Sights) LT > 0.15f
    HOLD_LEFT_BUMPER,
    HOLD_LT_OR_LB
}

data class GyroscopeSettings(
    val source: GyroscopeSource = GyroscopeSource.AUTO_PREFER_GAMEPAD,
    val activationGate: GyroscopeActivationGate = GyroscopeActivationGate.HOLD_LEFT_TRIGGER,
    val sensitivityX: Float = 1.0f,
    val sensitivityY: Float = 1.0f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val deadzoneThreshold: Float = 0.025f // rad/s to eliminate hand jitter
)

/**
 * GyroscopeMotionEngine: Dual-source IMU motion aiming engine (Phone gyroscope vs Gamepad IMU API 31+)
 * fusing angular velocity directly into Right Stick analog coordinates for precision aiming.
 */
class GyroscopeMotionEngine(
    private val context: Context,
    var settings: GyroscopeSettings = GyroscopeSettings()
) : SensorEventListener {

    private val systemSensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var activeSensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null

    // Current angular velocity in rad/s
    private var lastAngularX = 0f
    private var lastAngularY = 0f
    private var isRegistered = false

    fun startListening(gamepadInputDevice: InputDevice? = null) {
        stopListening()

        // 1. Check for Gamepad IMU on Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && gamepadInputDevice != null &&
            (settings.source == GyroscopeSource.GAMEPAD_CONTROLLER || settings.source == GyroscopeSource.AUTO_PREFER_GAMEPAD)) {
            val gamepadSensors = gamepadInputDevice.sensorManager
            val controllerGyro = gamepadSensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (controllerGyro != null) {
                activeSensorManager = gamepadSensors
                gyroSensor = controllerGyro
            }
        }

        // 2. Fallback to Device Gyroscope
        if (gyroSensor == null && settings.source != GyroscopeSource.GAMEPAD_CONTROLLER) {
            activeSensorManager = systemSensorManager
            gyroSensor = systemSensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        }

        gyroSensor?.let { sensor ->
            isRegistered = activeSensorManager?.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST
            ) == true
        }
    }

    fun stopListening() {
        if (isRegistered) {
            activeSensorManager?.unregisterListener(this)
            isRegistered = false
        }
        activeSensorManager = null
        gyroSensor = null
        lastAngularX = 0f
        lastAngularY = 0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            // event.values: [0] = rotation around X (pitch), [1] = rotation around Y (roll), [2] = rotation around Z (yaw)
            // In landscape handheld gaming: yaw (values[2] or values[1]) rotates camera horizontally, pitch (values[0]) rotates vertically
            val yaw = event.values[1] // Horizontal angular speed
            val pitch = event.values[0] // Vertical angular speed

            lastAngularX = if (abs(yaw) > settings.deadzoneThreshold) yaw else 0f
            lastAngularY = if (abs(pitch) > settings.deadzoneThreshold) pitch else 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Calculates stick deflection deltas (-32768 to 32767) to fuse with Right Stick.
     */
    fun computeStickDeltas(leftTrigger: Float, isLeftBumperPressed: Boolean): Pair<Int, Int> {
        val isGateActive = when (settings.activationGate) {
            GyroscopeActivationGate.ALWAYS_ON -> true
            GyroscopeActivationGate.HOLD_LEFT_TRIGGER -> leftTrigger > 0.15f
            GyroscopeActivationGate.HOLD_LEFT_BUMPER -> isLeftBumperPressed
            GyroscopeActivationGate.HOLD_LT_OR_LB -> (leftTrigger > 0.15f) || isLeftBumperPressed
        }

        if (!isGateActive || (lastAngularX == 0f && lastAngularY == 0f)) {
            return Pair(0, 0)
        }

        // Scale rad/s to stick range (~8000 per rad/s standard sensitivity)
        val baseScale = 8500f
        var deltaX = (lastAngularX * baseScale * settings.sensitivityX).toInt()
        var deltaY = (lastAngularY * baseScale * settings.sensitivityY).toInt()

        if (settings.invertX) deltaX = -deltaX
        if (settings.invertY) deltaY = -deltaY

        return Pair(deltaX.coerceIn(-32767, 32767), deltaY.coerceIn(-32767, 32767))
    }
}
