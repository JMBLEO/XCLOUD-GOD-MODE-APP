package com.xcloud.godmode.engine

import android.view.KeyEvent

data class EmulatedControllerState(
    var leftStickX: Int = 0,
    var leftStickY: Int = 0,
    var rightStickX: Int = 0,
    var rightStickY: Int = 0,
    var leftTrigger: Float = 0f,
    var rightTrigger: Float = 0f,
    var buttonA: Boolean = false,
    var buttonB: Boolean = false,
    var buttonX: Boolean = false,
    var buttonY: Boolean = false,
    var buttonLB: Boolean = false,
    var buttonRB: Boolean = false,
    var buttonL3: Boolean = false,
    var buttonR3: Boolean = false,
    var buttonMenu: Boolean = false,
    var buttonView: Boolean = false
)

/**
 * KbmEmulator: Translates physical keyboard and mouse inputs into standard Xbox controller packets.
 * Supports WASD movement, space to jump, shift to sprint, and key bindings.
 */
class KbmEmulator {
    val state = EmulatedControllerState()

    private var keyW = false
    private var keyA = false
    private var keyS = false
    private var keyD = false

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        return handleKey(event.keyCode, isDown)
    }

    fun handleKey(keyCode: Int, isDown: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_W -> { keyW = isDown; updateLeftStick(); return true }
            KeyEvent.KEYCODE_A -> { keyA = isDown; updateLeftStick(); return true }
            KeyEvent.KEYCODE_S -> { keyS = isDown; updateLeftStick(); return true }
            KeyEvent.KEYCODE_D -> { keyD = isDown; updateLeftStick(); return true }
            KeyEvent.KEYCODE_SPACE -> { state.buttonA = isDown; return true }
            KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_CTRL_LEFT -> { state.buttonB = isDown; return true }
            KeyEvent.KEYCODE_R -> { state.buttonX = isDown; return true }
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_F -> { state.buttonY = isDown; return true }
            KeyEvent.KEYCODE_Q -> { state.buttonLB = isDown; return true }
            KeyEvent.KEYCODE_SHIFT_LEFT -> { state.buttonL3 = isDown; return true }
            KeyEvent.KEYCODE_ESCAPE -> { state.buttonMenu = isDown; return true }
            KeyEvent.KEYCODE_TAB -> { state.buttonView = isDown; return true }
        }
        return false
    }

    fun handleMouseButton(buttonIndex: Int, isPressed: Boolean) {
        when (buttonIndex) {
            0 -> state.rightTrigger = if (isPressed) 1.0f else 0.0f // Left click = Fire (RT)
            1 -> state.leftTrigger = if (isPressed) 1.0f else 0.0f  // Right click = Aim (LT)
            2 -> state.buttonRB = isPressed                         // Middle click = RB
        }
    }

    private fun updateLeftStick() {
        var x = 0
        var y = 0
        if (keyD) x += 32767
        if (keyA) x -= 32767
        if (keyW) y += 32767
        if (keyS) y -= 32767
        state.leftStickX = x
        state.leftStickY = y
    }
}
