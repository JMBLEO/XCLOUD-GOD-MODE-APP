package com.xcloud.godmode.engine

import android.view.KeyEvent

/**
 * Phase 61: Rear Paddles (M1/M2/M3/M4) & Vendor HID Slices Remapper.
 * Extracts auxiliary paddle inputs from vendor-specific HID packets
 * for mobile controllers (Razer Kishi, Backbone One, GameSir X2/G8, Xbox Elite Series 2)
 * and maps them to customizable digital actions (e.g., L3/R3, A/B/X/Y, Macro Triggers).
 */
class VendorPaddleRemapper {

    enum class ControllerVendor {
        GENERIC_XINPUT,
        RAZER_KISHI,
        BACKBONE_ONE,
        GAMESIR_G8_X2,
        XBOX_ELITE_SERIES_2
    }

    enum class RemappedAction {
        NONE,
        BUTTON_A,
        BUTTON_B,
        BUTTON_X,
        BUTTON_Y,
        LEFT_STICK_CLICK_L3,
        RIGHT_STICK_CLICK_R3,
        LEFT_BUMPER_LB,
        RIGHT_BUMPER_RB,
        D_PAD_UP,
        D_PAD_DOWN,
        MACRO_1,
        MACRO_2
    }

    data class PaddleMappingConfig(
        var m1Action: RemappedAction = RemappedAction.LEFT_STICK_CLICK_L3,
        var m2Action: RemappedAction = RemappedAction.RIGHT_STICK_CLICK_R3,
        var m3Action: RemappedAction = RemappedAction.BUTTON_A,
        var m4Action: RemappedAction = RemappedAction.BUTTON_B
    )

    data class PaddleState(
        val m1Pressed: Boolean = false,
        val m2Pressed: Boolean = false,
        val m3Pressed: Boolean = false,
        val m4Pressed: Boolean = false
    )

    private var currentConfig = PaddleMappingConfig()

    fun updateConfig(config: PaddleMappingConfig) {
        currentConfig = config
    }

    /**
     * Detects controller vendor from Android InputDevice VID/PID.
     */
    fun detectVendor(vendorId: Int, productId: Int): ControllerVendor {
        return when {
            vendorId == 0x1532 -> ControllerVendor.RAZER_KISHI        // Razer
            vendorId == 0x358A -> ControllerVendor.BACKBONE_ONE       // Backbone
            vendorId == 0x045E && (productId == 0x0B00 || productId == 0x0B05) -> ControllerVendor.XBOX_ELITE_SERIES_2
            vendorId == 0x2E2C || vendorId == 0x05ac -> ControllerVendor.GAMESIR_G8_X2
            else -> ControllerVendor.GENERIC_XINPUT
        }
    }

    /**
     * Parses raw vendor HID buffer slice to extract paddle bit states.
     */
    fun parseVendorBuffer(vendor: ControllerVendor, rawBytes: ByteArray): PaddleState {
        if (rawBytes.isEmpty()) return PaddleState()

        return when (vendor) {
            ControllerVendor.RAZER_KISHI -> {
                // Razer Kishi vendor byte (usually at index 24 or 26)
                if (rawBytes.size > 24) {
                    val slice = rawBytes[24].toInt() and 0xFF
                    PaddleState(
                        m1Pressed = (slice and 0x01) != 0,
                        m2Pressed = (slice and 0x02) != 0
                    )
                } else PaddleState()
            }
            ControllerVendor.BACKBONE_ONE -> {
                // Backbone capture byte
                if (rawBytes.size > 18) {
                    val slice = rawBytes[18].toInt() and 0xFF
                    PaddleState(
                        m1Pressed = (slice and 0x04) != 0,
                        m2Pressed = (slice and 0x08) != 0
                    )
                } else PaddleState()
            }
            ControllerVendor.XBOX_ELITE_SERIES_2 -> {
                // Microsoft Elite report byte 18: bits 0-3 correspond to P1, P2, P3, P4
                if (rawBytes.size > 18) {
                    val b = rawBytes[18].toInt() and 0xFF
                    PaddleState(
                        m1Pressed = (b and 0x01) != 0,
                        m2Pressed = (b and 0x02) != 0,
                        m3Pressed = (b and 0x04) != 0,
                        m4Pressed = (b and 0x08) != 0
                    )
                } else PaddleState()
            }
            ControllerVendor.GAMESIR_G8_X2 -> {
                // GameSir M1/M2 at offset 16
                if (rawBytes.size > 16) {
                    val slice = rawBytes[16].toInt() and 0xFF
                    PaddleState(
                        m1Pressed = (slice and 0x10) != 0,
                        m2Pressed = (slice and 0x20) != 0
                    )
                } else PaddleState()
            }
            ControllerVendor.GENERIC_XINPUT -> {
                PaddleState()
            }
        }
    }

    /**
     * Merges paddle states into Xbox controller button bitmask (16-bit word).
     * Standard Xbox Bitmask:
     * 0x0001: D-pad UP
     * 0x0002: D-pad DOWN
     * 0x0004: D-pad LEFT
     * 0x0008: D-pad RIGHT
     * 0x0010: Start / Menu
     * 0x0020: Back / View
     * 0x0040: LS (L3)
     * 0x0080: RS (R3)
     * 0x0100: LB
     * 0x0200: RB
     * 0x1000: A
     * 0x2000: B
     * 0x4000: X
     * 0x8000: Y
     */
    fun applyPaddlesToButtonMask(currentMask: Int, state: PaddleState): Int {
        var mask = currentMask
        if (state.m1Pressed) mask = mask or actionToMask(currentConfig.m1Action)
        if (state.m2Pressed) mask = mask or actionToMask(currentConfig.m2Action)
        if (state.m3Pressed) mask = mask or actionToMask(currentConfig.m3Action)
        if (state.m4Pressed) mask = mask or actionToMask(currentConfig.m4Action)
        return mask
    }

    private fun actionToMask(action: RemappedAction): Int {
        return when (action) {
            RemappedAction.BUTTON_A -> 0x1000
            RemappedAction.BUTTON_B -> 0x2000
            RemappedAction.BUTTON_X -> 0x4000
            RemappedAction.BUTTON_Y -> 0x8000
            RemappedAction.LEFT_STICK_CLICK_L3 -> 0x0040
            RemappedAction.RIGHT_STICK_CLICK_R3 -> 0x0080
            RemappedAction.LEFT_BUMPER_LB -> 0x0100
            RemappedAction.RIGHT_BUMPER_RB -> 0x0200
            RemappedAction.D_PAD_UP -> 0x0001
            RemappedAction.D_PAD_DOWN -> 0x0002
            else -> 0x0000
        }
    }
}
