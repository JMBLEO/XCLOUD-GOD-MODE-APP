package com.xcloud.godmode

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.xcloud.godmode.streaming.NativeStreamPipelineManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ControllerInputManager: Manages multiple gamepads and motion sensors.
 * Translates Android inputs into the binary protocol expected by Xbox Cloud Gaming.
 */
class ControllerInputManager(private val streamManager: NativeStreamPipelineManager) {

    private val activeGamepads = mutableMapOf<Int, Int>() // deviceId -> slot (0-3)
    private var gyroSensitivity = 1.0f

    /**
     * Map an Android MotionEvent from a gamepad to an xCloud input packet.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
        
        val deviceId = event.deviceId
        val slot = getOrAssignSlot(deviceId) ?: return false

        val packet = createGamepadPacket(event, slot)
        streamManager.sendInput(packet, slot)
        return true
    }

    /**
     * Map an Android KeyEvent from a gamepad to an xCloud input packet.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val deviceId = event.deviceId
        val slot = getOrAssignSlot(deviceId) ?: return false
        
        // Handle specific buttons like Xbox/Guide button
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            // Trigger Guide Menu or similar
            return true
        }

        val packet = createKeyEventPacket(event, slot)
        streamManager.sendInput(packet, slot)
        return true
    }

    private var lastRightStickX = 0f
    private var lastRightStickY = 0f

    /**
     * Inject Gyroscope deltas into the right stick of the primary controller (Slot 0).
     */
    fun injectGyro(dx: Float, dy: Float) {
        // dx, dy are deltas. We apply them to the current state of the right stick.
        lastRightStickX = (lastRightStickX + dx).coerceIn(-1f, 1f)
        lastRightStickY = (lastRightStickY + dy).coerceIn(-1f, 1f)
        
        // In a real implementation, we'd send a packet here or wait for the next physical event.
        // For now, let's send a virtual update for Slot 0.
        val packet = createVirtualStickPacket(0, lastRightStickX, lastRightStickY)
        streamManager.sendInput(packet, 0)
    }

    private fun createVirtualStickPacket(slot: Int, rx: Float, ry: Float): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x03) // Packet Type: Virtual Stick
        buffer.put(slot.toByte())
        buffer.putShort((rx * 32767).toInt().toShort())
        buffer.putShort((ry * 32767).toInt().toShort())
        return buffer.array()
    }

    private fun getOrAssignSlot(deviceId: Int): Int? {
        if (activeGamepads.containsKey(deviceId)) return activeGamepads[deviceId]
        
        if (activeGamepads.size < 4) {
            val nextSlot = activeGamepads.size
            activeGamepads[deviceId] = nextSlot
            return nextSlot
        }
        return null
    }

    /**
     * Creates a binary packet for Gamepad Input (Axes + Buttons).
     * The protocol for xCloud is typically a fixed-length buffer.
     */
    private fun createGamepadPacket(event: MotionEvent, slot: Int): ByteArray {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x01) // Packet Type: Gamepad
        buffer.put(slot.toByte())
        
        // Axes (Normalized -32768 to 32767)
        buffer.putShort((event.getAxisValue(MotionEvent.AXIS_X) * 32767).toInt().toShort())
        buffer.putShort((event.getAxisValue(MotionEvent.AXIS_Y) * 32767).toInt().toShort())
        buffer.putShort((event.getAxisValue(MotionEvent.AXIS_Z) * 32767).toInt().toShort())
        buffer.putShort((event.getAxisValue(MotionEvent.AXIS_RZ) * 32767).toInt().toShort())
        
        // Triggers
        buffer.putShort((event.getAxisValue(MotionEvent.AXIS_LTRIGGER) * 1023).toInt().toShort())
        buffer.putShort((event.getAxisValue(MotionEvent.AXIS_RTRIGGER) * 1023).toInt().toShort())
        
        return buffer.array()
    }

    private fun createKeyEventPacket(event: KeyEvent, slot: Int): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x02) // Packet Type: Key
        buffer.put(slot.toByte())
        buffer.putInt(event.keyCode)
        buffer.put(if (event.action == KeyEvent.ACTION_DOWN) 1.toByte() else 0.toByte())
        return buffer.array()
    }
}
