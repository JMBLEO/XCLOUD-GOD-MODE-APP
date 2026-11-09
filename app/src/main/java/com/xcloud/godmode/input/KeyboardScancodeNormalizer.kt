package com.xcloud.godmode.input

import android.view.KeyEvent

/**
 * Phase 96: Physical Keyboard Scancode Normalizer.
 * Intercepts physical hardware scancodes (Linux EV_KEY scancodes via KeyEvent.scanCode)
 * to guarantee positional WASD layout regardless of regional layout (QWERTY, AZERTY, QWERTZ).
 */
class KeyboardScancodeNormalizer {

    companion object {
        // Standard Linux Hardware Scancodes
        const val SCANCODE_KEY_W = 17
        const val SCANCODE_KEY_A = 30
        const val SCANCODE_KEY_S = 31
        const val SCANCODE_KEY_D = 32
        const val SCANCODE_KEY_SPACE = 57
        const val SCANCODE_KEY_LEFTSHIFT = 42
        const val SCANCODE_KEY_LEFTCTRL = 29
        const val SCANCODE_KEY_E = 18
        const val SCANCODE_KEY_R = 19
        const val SCANCODE_KEY_TAB = 15
        const val SCANCODE_KEY_ESC = 1
    }

    enum class VirtualKeyAction {
        MOVE_FORWARD,
        MOVE_BACKWARD,
        MOVE_LEFT,
        MOVE_RIGHT,
        JUMP_BUTTON_A,
        CROUCH_BUTTON_B,
        RELOAD_BUTTON_X,
        INTERACT_BUTTON_Y,
        SPRINT_STICK_CLICK_L3,
        VIEW_SCOREBOARD,
        PAUSE_MENU,
        UNMAPPED
    }

    /**
     * Maps physical scancode to standardized action.
     */
    fun mapScancode(scanCode: Int): VirtualKeyAction {
        return when (scanCode) {
            SCANCODE_KEY_W -> VirtualKeyAction.MOVE_FORWARD
            SCANCODE_KEY_S -> VirtualKeyAction.MOVE_BACKWARD
            SCANCODE_KEY_A -> VirtualKeyAction.MOVE_LEFT
            SCANCODE_KEY_D -> VirtualKeyAction.MOVE_RIGHT
            SCANCODE_KEY_SPACE -> VirtualKeyAction.JUMP_BUTTON_A
            SCANCODE_KEY_LEFTCTRL -> VirtualKeyAction.CROUCH_BUTTON_B
            SCANCODE_KEY_R -> VirtualKeyAction.RELOAD_BUTTON_X
            SCANCODE_KEY_E -> VirtualKeyAction.INTERACT_BUTTON_Y
            SCANCODE_KEY_LEFTSHIFT -> VirtualKeyAction.SPRINT_STICK_CLICK_L3
            SCANCODE_KEY_TAB -> VirtualKeyAction.VIEW_SCOREBOARD
            SCANCODE_KEY_ESC -> VirtualKeyAction.PAUSE_MENU
            else -> VirtualKeyAction.UNMAPPED
        }
    }

    fun isPositionalMovement(scanCode: Int): Boolean {
        return scanCode == SCANCODE_KEY_W ||
                scanCode == SCANCODE_KEY_A ||
                scanCode == SCANCODE_KEY_S ||
                scanCode == SCANCODE_KEY_D
    }
}
