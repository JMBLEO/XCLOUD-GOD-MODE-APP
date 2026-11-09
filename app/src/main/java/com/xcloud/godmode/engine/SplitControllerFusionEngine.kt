package com.xcloud.godmode.engine

import android.view.InputDevice

/**
 * Phase 81: Split Controller Fusion Engine (Dual-IMU Joy-Con / Dual Gamepads).
 * Merges two distinct physical Bluetooth devices into a single virtual Xbox controller.
 * Dispatches Left controller (D-Pad, Left Stick, LB/LT, Left IMU) and Right controller
 * (Face buttons, Right Stick, RB/RT, Right IMU for camera aiming) concurrently.
 */
class SplitControllerFusionEngine {

    data class CombinedControllerState(
        val buttonsMask: Int = 0,
        val leftStickX: Float = 0f,
        val leftStickY: Float = 0f,
        val rightStickX: Float = 0f,
        val rightStickY: Float = 0f,
        val leftTrigger: Float = 0f,
        val rightTrigger: Float = 0f,
        val gyroAimYaw: Float = 0f,
        val gyroAimPitch: Float = 0f
    )

    private var leftDeviceId: Int = -1
    private var rightDeviceId: Int = -1

    private var leftButtons: Int = 0
    private var rightButtons: Int = 0
    private var lx: Float = 0f
    private var ly: Float = 0f
    private var rx: Float = 0f
    private var ry: Float = 0f
    private var lt: Float = 0f
    private var rt: Float = 0f
    private var aimYaw: Float = 0f
    private var aimPitch: Float = 0f

    fun assignDevices(leftId: Int, rightId: Int) {
        leftDeviceId = leftId
        rightDeviceId = rightId
    }

    fun updateLeftPad(buttons: Int, stickX: Float, stickY: Float, trigger: Float) {
        leftButtons = buttons
        lx = stickX
        ly = stickY
        lt = trigger
    }

    fun updateRightPad(buttons: Int, stickX: Float, stickY: Float, trigger: Float) {
        rightButtons = buttons
        rx = stickX
        ry = stickY
        rt = trigger
    }

    fun updateRightGyro(yaw: Float, pitch: Float) {
        aimYaw = yaw
        aimPitch = pitch
    }

    /**
     * Synthesizes unified Xbox controller frame.
     */
    fun getFusedState(): CombinedControllerState {
        val mergedMask = leftButtons or rightButtons
        return CombinedControllerState(
            buttonsMask = mergedMask,
            leftStickX = lx,
            leftStickY = ly,
            rightStickX = rx,
            rightStickY = ry,
            leftTrigger = lt,
            rightTrigger = rt,
            gyroAimYaw = aimYaw,
            gyroAimPitch = aimPitch
        )
    }

    fun isSplitDevice(deviceId: Int): Boolean {
        return deviceId == leftDeviceId || deviceId == rightDeviceId
    }
}
