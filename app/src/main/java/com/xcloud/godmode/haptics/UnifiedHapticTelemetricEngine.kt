package com.xcloud.godmode.haptics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UnifiedHapticTelemetricEngine: 4-Channel normalized haptic and rumble engine.
 *
 * Routing logic:
 * - 4-channel telemetry: Left Grip, Right Grip, Left Trigger (Impulse), Right Trigger (Impulse).
 * - Automatic downmix to 2 channels if gamepad lacks independent trigger motors.
 * - Fallback to local device VibratorManager if touch screen / handheld.
 * - Safe inhibition on Android TV if no haptic actuators are connected.
 */

data class HapticTelemetryPacket(
    val leftGripMotor: Float = 0f,
    val rightGripMotor: Float = 0f,
    val leftTriggerMotor: Float = 0f,
    val rightTriggerMotor: Float = 0f,
    val durationMs: Int = 50,
    val frequencyHz: Int = 120
)

enum class HapticTargetRoute {
    FOUR_MOTOR_ADVANCED_GAMEPAD,
    DUAL_RUMBLE_STANDARD_GAMEPAD,
    LOCAL_DEVICE_VIBRATOR,
    MUTED_TV_NO_ACTUATOR
}

class UnifiedHapticTelemetricEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val isAndroidTv: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    private val telemetryQueue = MutableSharedFlow<HapticTelemetryPacket>(replay = 0, extraBufferCapacity = 32)
    private val isEngineActive = AtomicBoolean(true)

    private val localVibratorManager: VibratorManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
    } else null

    @Suppress("DEPRECATION")
    private val localLegacyVibrator: Vibrator? = if (localVibratorManager == null) {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } else null

    private val connectedGamepads = ConcurrentHashMap<Int, CachedGamepadHaptics>()

    init {
        refreshConnectedInputDevices()
        startTelemetryConsumerLoop()
    }

    fun emitHapticEvent(packet: HapticTelemetryPacket) {
        if (!isEngineActive.get()) return
        telemetryQueue.tryEmit(packet)
    }

    private fun startTelemetryConsumerLoop() {
        scope.launch {
            telemetryQueue.collectLatest { packet ->
                val activeGamepad = getPrimaryActiveGamepad()
                when (resolveOptimalRoute(activeGamepad)) {
                    HapticTargetRoute.FOUR_MOTOR_ADVANCED_GAMEPAD -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            dispatchFourChannelRumble(activeGamepad!!, packet)
                        } else {
                            dispatchDualRumbleWithDownmix(activeGamepad!!, packet)
                        }
                    }
                    HapticTargetRoute.DUAL_RUMBLE_STANDARD_GAMEPAD -> {
                        dispatchDualRumbleWithDownmix(activeGamepad!!, packet)
                    }
                    HapticTargetRoute.LOCAL_DEVICE_VIBRATOR -> {
                        dispatchLocalDeviceHaptics(packet)
                    }
                    HapticTargetRoute.MUTED_TV_NO_ACTUATOR -> {
                        // Safe discard on TV without actuators
                    }
                }
            }
        }
    }

    private fun resolveOptimalRoute(activeGamepad: CachedGamepadHaptics?): HapticTargetRoute {
        if (activeGamepad != null) {
            return if (activeGamepad.hasImpulseTriggers && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                HapticTargetRoute.FOUR_MOTOR_ADVANCED_GAMEPAD
            } else {
                HapticTargetRoute.DUAL_RUMBLE_STANDARD_GAMEPAD
            }
        }

        return if (isAndroidTv) {
            HapticTargetRoute.MUTED_TV_NO_ACTUATOR
        } else {
            HapticTargetRoute.LOCAL_DEVICE_VIBRATOR
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dispatchFourChannelRumble(gamepad: CachedGamepadHaptics, packet: HapticTelemetryPacket) {
        val vm = gamepad.inputDevice.vibratorManager
        val vibratorIds = vm.vibratorIds

        if (vibratorIds.size >= 4) {
            val combinedBuilder = CombinedVibration.startParallel()

            val leftGripAmp = (packet.leftGripMotor.coerceIn(0f, 1f) * 255).toInt()
            if (leftGripAmp > 0) {
                combinedBuilder.addVibrator(vibratorIds[0], VibrationEffect.createOneShot(packet.durationMs.toLong(), leftGripAmp))
            }

            val rightGripAmp = (packet.rightGripMotor.coerceIn(0f, 1f) * 255).toInt()
            if (rightGripAmp > 0) {
                combinedBuilder.addVibrator(vibratorIds[1], VibrationEffect.createOneShot(packet.durationMs.toLong(), rightGripAmp))
            }

            val leftTriggerAmp = (packet.leftTriggerMotor.coerceIn(0f, 1f) * 255).toInt()
            if (leftTriggerAmp > 0) {
                combinedBuilder.addVibrator(vibratorIds[2], VibrationEffect.createOneShot(packet.durationMs.toLong(), leftTriggerAmp))
            }

            val rightTriggerAmp = (packet.rightTriggerMotor.coerceIn(0f, 1f) * 255).toInt()
            if (rightTriggerAmp > 0) {
                combinedBuilder.addVibrator(vibratorIds[3], VibrationEffect.createOneShot(packet.durationMs.toLong(), rightTriggerAmp))
            }

            try {
                vm.vibrate(combinedBuilder.combine())
            } catch (_: Exception) {
                dispatchDualRumbleWithDownmix(gamepad, packet)
            }
        } else {
            dispatchDualRumbleWithDownmix(gamepad, packet)
        }
    }

    private fun dispatchDualRumbleWithDownmix(gamepad: CachedGamepadHaptics, packet: HapticTelemetryPacket) {
        val mixedLeft = (packet.leftGripMotor * 0.7f + packet.leftTriggerMotor * 0.3f).coerceIn(0f, 1f)
        val mixedRight = (packet.rightGripMotor * 0.7f + packet.rightTriggerMotor * 0.3f).coerceIn(0f, 1f)

        val leftAmp = (mixedLeft * 255).toInt()
        val rightAmp = (mixedRight * 255).toInt()
        val maxAmp = maxOf(leftAmp, rightAmp)

        if (maxAmp <= 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(packet.durationMs.toLong(), maxAmp)
            gamepad.inputDevice.vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            gamepad.inputDevice.vibrator.vibrate(packet.durationMs.toLong())
        }
    }

    private fun dispatchLocalDeviceHaptics(packet: HapticTelemetryPacket) {
        val totalMagnitude = maxOf(
            packet.leftGripMotor, packet.rightGripMotor, packet.leftTriggerMotor, packet.rightTriggerMotor
        ).coerceIn(0f, 1f)

        val amplitude = (totalMagnitude * 255).toInt()
        if (amplitude <= 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && localVibratorManager != null) {
            val effect = VibrationEffect.createOneShot(packet.durationMs.toLong(), amplitude)
            localVibratorManager.vibrate(CombinedVibration.createParallel(effect))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && localLegacyVibrator != null) {
            val effect = VibrationEffect.createOneShot(packet.durationMs.toLong(), amplitude)
            localLegacyVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            localLegacyVibrator?.vibrate(packet.durationMs.toLong())
        }
    }

    fun refreshConnectedInputDevices() {
        connectedGamepads.clear()
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            val sources = device.sources

            if ((sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
                (sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            ) {
                val hasVibrator = device.vibrator.hasVibrator()
                var hasImpulse = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hasImpulse = device.vibratorManager.vibratorIds.size >= 4
                }

                if (hasVibrator || hasImpulse) {
                    connectedGamepads[id] = CachedGamepadHaptics(device, hasImpulse)
                }
            }
        }
    }

    private fun getPrimaryActiveGamepad(): CachedGamepadHaptics? {
        return connectedGamepads.values.firstOrNull()
    }

    fun stopAllVibrations() {
        connectedGamepads.values.forEach { gamepad ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                gamepad.inputDevice.vibratorManager.cancel()
            }
            gamepad.inputDevice.vibrator.cancel()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            localVibratorManager?.cancel()
        } else {
            localLegacyVibrator?.cancel()
        }
    }

    fun release() {
        isEngineActive.set(false)
        stopAllVibrations()
        scope.cancel()
    }

    data class CachedGamepadHaptics(
        val inputDevice: InputDevice,
        val hasImpulseTriggers: Boolean
    )
}
