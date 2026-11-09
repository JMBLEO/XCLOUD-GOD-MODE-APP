package com.xcloud.godmode.streaming

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NativeInputBridge: High-performance 250Hz Controller and MKB Hardware Bridge.
 *
 * Direct Native Pipeline:
 * 1. Reads raw MotionEvent & KeyEvent from Bluetooth/USB input devices.
 * 2. Processes mathematical radial deadzones and smooth acceleration curves.
 * 3. Serializes packets directly into the native C++ SPSC lock-free ring buffer at 250Hz (4ms).
 * 4. Supports Local Co-Op (Slots 0 to 7) and MKB (Mouse & Keyboard) emulation.
 */
class NativeInputBridge(
    private val inputPacketSink: ((ByteArray) -> Unit)? = null
) {

    private val TAG = "NativeInputBridge"
    private val bridgeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null

    // Controller State for up to 8 players
    data class ControllerState(
        var buttons: Int = 0,
        var leftStickX: Float = 0f,
        var leftStickY: Float = 0f,
        var rightStickX: Float = 0f,
        var rightStickY: Float = 0f,
        var leftTrigger: Float = 0f,
        var rightTrigger: Float = 0f,
        var deadzone: Float = 0.08f,
        var lastUpdateNano: Long = 0L
    )

    val controllers = Array(8) { ControllerState() }
    var lastInputProcessingLatencyMs: Long = 0L
        private set

    private var isMkbEnabled = false
    private var mouseSensitivity = 1.0f
    private var mouseSmoothX = 0f
    private var mouseSmoothY = 0f

    // New Touch Customization fields
    var touchSensitivity = 1.0f
    var touchDeadzone = 0.1f

    // Xbox Button bitmasks (Standard XInput / xCloud report)
    companion object {
        const val BTN_DPAD_UP = 0x0001
        const val BTN_DPAD_DOWN = 0x0002
        const val BTN_DPAD_LEFT = 0x0004
        const val BTN_DPAD_RIGHT = 0x0008
        const val BTN_START = 0x0010
        const val BTN_BACK = 0x0020
        const val BTN_LEFT_THUMB = 0x0040
        const val BTN_RIGHT_THUMB = 0x0080
        const val BTN_LEFT_SHOULDER = 0x0100
        const val BTN_RIGHT_SHOULDER = 0x0200
        const val BTN_GUIDE = 0x0400
        const val BTN_A = 0x1000
        const val BTN_B = 0x2000
        const val BTN_X = 0x4000
        const val BTN_Y = 0x8000

        @Volatile
        var activeInstance: NativeInputBridge? = null
        var isRawTouchEnabled: Boolean = false

        fun injectRawTouchEvent(pointerId: Int, x: Float, y: Float, action: Int, screenWidth: Int, screenHeight: Int) {
            val bridge = activeInstance ?: return
            if (!isRawTouchEnabled) return
            bridge.processRawTouch(pointerId, x, y, action, screenWidth, screenHeight)
        }

        fun dispatchButton(slot: Int = 0, buttonMask: Int, isPressed: Boolean) {
            activeInstance?.setButtonState(slot, buttonMask, isPressed)
        }

        fun dispatchStick(slot: Int = 0, isLeft: Boolean, x: Float, y: Float) {
            activeInstance?.setThumbstick(slot, isLeft, x, y)
        }

        fun dispatchTrigger(slot: Int = 0, isLeft: Boolean, value: Float) {
            activeInstance?.setTrigger(slot, isLeft, value)
        }
    }

    init {
        activeInstance = this
    }

    fun setButtonState(slot: Int = 0, buttonMask: Int, isPressed: Boolean) {
        if (slot !in 0..7) return
        val state = controllers[slot]
        state.buttons = if (isPressed) state.buttons or buttonMask else state.buttons and buttonMask.inv()
        state.lastUpdateNano = System.nanoTime()
    }

    fun setThumbstick(slot: Int = 0, isLeft: Boolean, x: Float, y: Float) {
        if (slot !in 0..7) return
        val state = controllers[slot]
        val (normX, normY) = applyRadialDeadzone(x, y, state.deadzone)
        if (isLeft) {
            state.leftStickX = normX
            state.leftStickY = normY
        } else {
            state.rightStickX = normX
            state.rightStickY = normY
        }
        state.lastUpdateNano = System.nanoTime()
    }

    fun setTrigger(slot: Int = 0, isLeft: Boolean, value: Float) {
        if (slot !in 0..7) return
        val state = controllers[slot]
        if (isLeft) {
            state.leftTrigger = value.coerceIn(0f, 1f)
        } else {
            state.rightTrigger = value.coerceIn(0f, 1f)
        }
        state.lastUpdateNano = System.nanoTime()
    }

    private var touchOriginX = 0f
    private var touchOriginY = 0f
    private var touchSmoothedX = 0f
    private var touchSmoothedY = 0f

    fun processRawTouch(pointerId: Int, x: Float, y: Float, action: Int, screenWidth: Int, screenHeight: Int) {
        val state = controllers[0]
        state.lastUpdateNano = System.nanoTime()

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (x < screenWidth * 0.5f) {
                touchSmoothedX = 0f
                touchSmoothedY = 0f
                state.leftStickX = 0f
                state.leftStickY = 0f
            } else {
                state.buttons = state.buttons and (BTN_A or BTN_B or BTN_X or BTN_Y or BTN_LEFT_SHOULDER or BTN_RIGHT_SHOULDER).inv()
                state.leftTrigger = 0f
                state.rightTrigger = 0f
            }
            return
        }

        // Left half: Virtual thumbstick with Elliptical Deadzone and EMA De-jitter
        if (x < screenWidth * 0.45f) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                touchOriginX = x
                touchOriginY = y
                touchSmoothedX = 0f
                touchSmoothedY = 0f
            }
            val radius = 120f
            val dx = ((x - touchOriginX).coerceIn(-radius, radius) / radius) * touchSensitivity
            val dy = ((y - touchOriginY).coerceIn(-radius, radius) / radius) * touchSensitivity
            val (normX, normY) = applyEllipticalDeadzone(dx, dy, touchDeadzone, touchDeadzone * 0.9f)
            // EMA Trajectory smoothing (Extensión 8: Touch De-jitter)
            touchSmoothedX = (touchSmoothedX * 0.3f) + (normX * 0.7f)
            touchSmoothedY = (touchSmoothedY * 0.3f) + (normY * 0.7f)
            state.leftStickX = touchSmoothedX.coerceIn(-1f, 1f)
            state.leftStickY = touchSmoothedY.coerceIn(-1f, 1f)
        } else if (y < screenHeight * 0.3f) {
            // Upper area: Triggers & Bumpers
            if (x < screenWidth * 0.25f) {
                state.leftTrigger = 1.0f
            } else if (x < screenWidth * 0.5f) {
                state.buttons = state.buttons or BTN_LEFT_SHOULDER
            } else if (x > screenWidth * 0.75f) {
                state.rightTrigger = 1.0f
            } else {
                state.buttons = state.buttons or BTN_RIGHT_SHOULDER
            }
        } else if (x > screenWidth * 0.55f && y > screenHeight * 0.4f) {
            // Right lower quadrant: Face buttons ABXY
            val centerX = screenWidth * 0.82f
            val centerY = screenHeight * 0.72f
            val deltaX = x - centerX
            val deltaY = y - centerY

            if (deltaY > 40f) {
                state.buttons = state.buttons or BTN_A
            } else if (deltaX > 40f) {
                state.buttons = state.buttons or BTN_B
            } else if (deltaX < -40f) {
                state.buttons = state.buttons or BTN_X
            } else if (deltaY < -40f) {
                state.buttons = state.buttons or BTN_Y
            }
        }
    }

    private val lastSentPacketHash = IntArray(8)
    private var idleCount = 0

    /**
     * Inicia el bucle de sondeo a alta frecuencia con Idle Input Suppression.
     * En reposo desciende a 15Hz de keepalive, y despierta instantáneamente a 250Hz al detectar deltas.
     */
    fun startPolling(rateHz: Int = 250) {
        stopPolling()
        val activeIntervalMs = (1000.0 / rateHz).toLong().coerceAtLeast(4L)
        val idleIntervalMs = 66L // 15Hz keepalive
        Log.d(TAG, "Iniciando sondeo nativo de mandos a ${rateHz}Hz (${activeIntervalMs}ms) con Supresión Idle...")

        pollingJob = bridgeScope.launch {
            while (isActive) {
                var hasActiveChange = false
                for (slot in 0 until 4) { // Active players
                    val state = controllers[slot]
                    val stateHash = state.hashCode()
                    val isNonZero = state.buttons != 0 || state.leftStickX != 0f || state.leftStickY != 0f ||
                                   state.rightStickX != 0f || state.rightStickY != 0f ||
                                   state.leftTrigger != 0f || state.rightTrigger != 0f

                    if (stateHash != lastSentPacketHash[slot] || isNonZero) {
                        hasActiveChange = true
                        lastSentPacketHash[slot] = stateHash
                        val packet = buildGamepadPacket(slot, state)
                        val bytes = ByteArray(packet.remaining())
                        packet.get(bytes)
                        
                        // Use 16-byte packet for DataChannel sink if it exists
                        val bytes16 = buildGamepadPacket16(slot, state)
                        inputPacketSink?.invoke(bytes16)
                        
                        try {
                            NativeStreamBridge.pushRawInput(bytes, bytes.size)
                            if (state.lastUpdateNano > 0) {
                                lastInputProcessingLatencyMs = (System.nanoTime() - state.lastUpdateNano) / 1_000_000L
                            }
                        } catch (ignored: Throwable) {}
                    } else if (idleCount % 15 == 0) {
                        // Enviar keepalive a 15Hz para mantener el watchdog de xCloud activo
                        val packet = buildGamepadPacket(slot, state)
                        val bytes = ByteArray(packet.remaining())
                        packet.get(bytes)
                        inputPacketSink?.invoke(bytes)
                        try {
                            NativeStreamBridge.pushRawInput(bytes, bytes.size)
                        } catch (ignored: Throwable) {}
                    }
                }
                if (hasActiveChange) {
                    idleCount = 0
                    delay(activeIntervalMs)
                } else {
                    idleCount++
                    delay(if (idleCount > 10) idleIntervalMs else activeIntervalMs)
                }
            }
        }
    }

    /**
     * Input Wedge Recovery: Restablece controles a posición neutra si el parser de xCloud se traba
     */
    fun sendInputWedgeRecovery(slot: Int = 0) {
        if (slot in 0..7) {
            controllers[slot] = ControllerState()
            lastSentPacketHash[slot] = 0
        }
        val packet = buildGamepadPacket(slot, controllers[slot])
        val bytes = ByteArray(packet.remaining())
        packet.get(bytes)
        inputPacketSink?.invoke(bytes)
        try {
            NativeStreamBridge.pushRawInput(bytes, bytes.size)
        } catch (ignored: Throwable) {}
        Log.d(TAG, "Input Wedge Recovery: Mando $slot reseteado a neutro.")
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun setMkbMode(enabled: Boolean, sensitivity: Float = 1.0f) {
        isMkbEnabled = enabled
        mouseSensitivity = sensitivity
    }

    fun setDeadzone(slot: Int, deadzone: Float) {
        if (slot in 0..7) {
            controllers[slot].deadzone = deadzone.coerceIn(0f, 0.4f)
        }
    }

    /**
     * Procesa MotionEvent nativo de Android (Joysticks analógicos y gatillos L2/R2)
     */
    fun onMotionEvent(event: MotionEvent, slot: Int = 0): Boolean {
        if (slot !in 0..7) return false
        val state = controllers[slot]
        state.lastUpdateNano = System.nanoTime()

        // Ejes Izquierdos (LX, LY) con Deadzone Radial Matemática
        val rawLx = event.getAxisValue(MotionEvent.AXIS_X)
        val rawLy = event.getAxisValue(MotionEvent.AXIS_Y)
        val (lx, ly) = applyRadialDeadzone(rawLx, rawLy, state.deadzone)
        state.leftStickX = lx
        state.leftStickY = ly

        // Ejes Derechos (RX, RY)
        val rawRx = event.getAxisValue(MotionEvent.AXIS_Z)
        val rawRy = event.getAxisValue(MotionEvent.AXIS_RZ)
        val (rx, ry) = applyRadialDeadzone(rawRx, rawRy, state.deadzone)
        state.rightStickX = rx
        state.rightStickY = ry

        // Gatillos analógicos independientes (L2 / R2)
        val rawL2 = event.getAxisValue(MotionEvent.AXIS_BRAKE).coerceAtLeast(event.getAxisValue(MotionEvent.AXIS_LTRIGGER))
        val rawR2 = event.getAxisValue(MotionEvent.AXIS_GAS).coerceAtLeast(event.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        state.leftTrigger = rawL2.coerceIn(0f, 1f)
        state.rightTrigger = rawR2.coerceIn(0f, 1f)

        // D-Pad vía ejes analógicos (HAT_X / HAT_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX < -0.5f) state.buttons = state.buttons or BTN_DPAD_LEFT else state.buttons = state.buttons and BTN_DPAD_LEFT.inv()
        if (hatX > 0.5f) state.buttons = state.buttons or BTN_DPAD_RIGHT else state.buttons = state.buttons and BTN_DPAD_RIGHT.inv()
        if (hatY < -0.5f) state.buttons = state.buttons or BTN_DPAD_UP else state.buttons = state.buttons and BTN_DPAD_UP.inv()
        if (hatY > 0.5f) state.buttons = state.buttons or BTN_DPAD_DOWN else state.buttons = state.buttons and BTN_DPAD_DOWN.inv()

        return true
    }

    /**
     * Procesa KeyEvent nativo de Android (Botones físicos A, B, X, Y, Bumpers, Menú, Guía)
     * Implementa Normalización HID (Layer 2) para detectar Sony DualSense, Nintendo Switch Pro y Xbox.
     */
    fun onKeyEvent(event: KeyEvent, slot: Int = 0): Boolean {
        if (slot !in 0..7) return false
        val state = controllers[slot]
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val device = event.device ?: return false
        
        // Layer 2: HID Normalization Mapping
        val mask = when {
            // Xbox / Standard Android Mapping
            device.name.contains("Xbox", ignoreCase = true) || device.name.contains("Standard", ignoreCase = true) -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> BTN_A
                    KeyEvent.KEYCODE_BUTTON_B -> BTN_B
                    KeyEvent.KEYCODE_BUTTON_X -> BTN_X
                    KeyEvent.KEYCODE_BUTTON_Y -> BTN_Y
                    KeyEvent.KEYCODE_BUTTON_L1 -> BTN_LEFT_SHOULDER
                    KeyEvent.KEYCODE_BUTTON_R1 -> BTN_RIGHT_SHOULDER
                    KeyEvent.KEYCODE_BUTTON_THUMBL -> BTN_LEFT_THUMB
                    KeyEvent.KEYCODE_BUTTON_THUMBR -> BTN_RIGHT_THUMB
                    KeyEvent.KEYCODE_BUTTON_START -> BTN_START
                    KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_BACK
                    KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_HOME -> BTN_GUIDE
                    KeyEvent.KEYCODE_DPAD_UP -> BTN_DPAD_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> BTN_DPAD_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> BTN_DPAD_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> BTN_DPAD_RIGHT
                    else -> 0
                }
            }
            // Sony PlayStation (DualSense / DualShock 4)
            device.name.contains("DualSense", ignoreCase = true) || device.name.contains("Wireless Controller", ignoreCase = true) -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> BTN_A // Cross
                    KeyEvent.KEYCODE_BUTTON_B -> BTN_B // Circle
                    KeyEvent.KEYCODE_BUTTON_X -> BTN_X // Square
                    KeyEvent.KEYCODE_BUTTON_Y -> BTN_Y // Triangle
                    KeyEvent.KEYCODE_BUTTON_L1 -> BTN_LEFT_SHOULDER
                    KeyEvent.KEYCODE_BUTTON_R1 -> BTN_RIGHT_SHOULDER
                    KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_BACK // Create/Share
                    KeyEvent.KEYCODE_BUTTON_START -> BTN_START // Options
                    KeyEvent.KEYCODE_BUTTON_MODE -> BTN_GUIDE // PS Button
                    else -> 0
                }
            }
            // Nintendo Switch Pro / Joy-Con
            device.name.contains("Pro Controller", ignoreCase = true) || device.name.contains("Joy-Con", ignoreCase = true) -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> BTN_B // A maps to B (Xbox layout)
                    KeyEvent.KEYCODE_BUTTON_B -> BTN_A // B maps to A
                    KeyEvent.KEYCODE_BUTTON_X -> BTN_Y // X maps to Y
                    KeyEvent.KEYCODE_BUTTON_Y -> BTN_X // Y maps to X
                    else -> 0
                }
            }
            else -> {
                // Fallback standard
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> BTN_A
                    KeyEvent.KEYCODE_BUTTON_B -> BTN_B
                    KeyEvent.KEYCODE_BUTTON_X -> BTN_X
                    KeyEvent.KEYCODE_BUTTON_Y -> BTN_Y
                    KeyEvent.KEYCODE_BUTTON_L1 -> BTN_LEFT_SHOULDER
                    KeyEvent.KEYCODE_BUTTON_R1 -> BTN_RIGHT_SHOULDER
                    KeyEvent.KEYCODE_BUTTON_START -> BTN_START
                    KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_BACK
                    KeyEvent.KEYCODE_DPAD_UP -> BTN_DPAD_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> BTN_DPAD_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> BTN_DPAD_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> BTN_DPAD_RIGHT
                    else -> 0
                }
            }
        }

        if (mask != 0) {
            state.buttons = if (isDown) state.buttons or mask else state.buttons and mask.inv()
            state.lastUpdateNano = System.nanoTime()
            return true
        }
        return false
    }

    /**
     * Emulación MKB: Traduce deltas de ratón (movementX, movementY) a stick derecho con suavizado Kalman
     */
    fun onMouseDelta(deltaX: Float, deltaY: Float, slot: Int = 0) {
        if (!isMkbEnabled || slot !in 0..7) return
        val state = controllers[slot]

        try {
            val smoothed = NativeStreamBridge.smoothMouseDelta(deltaX * mouseSensitivity * 0.05f, deltaY * mouseSensitivity * 0.05f)
            mouseSmoothX = smoothed[0]
            mouseSmoothY = smoothed[1]
        } catch (e: Throwable) {
            // Suavizado exponencial fallback (EMA smoothing)
            mouseSmoothX = (mouseSmoothX * 0.4f) + (deltaX * mouseSensitivity * 0.05f * 0.6f)
            mouseSmoothY = (mouseSmoothY * 0.4f) + (deltaY * mouseSensitivity * 0.05f * 0.6f)
        }

        state.rightStickX = mouseSmoothX.coerceIn(-1f, 1f)
        state.rightStickY = mouseSmoothY.coerceIn(-1f, 1f)
    }

    /**
     * Algoritmo de Zona Muerta Concéntrica y Elíptica (Extensión 8)
     * Permite umbrales asimétricos X/Y para evitar derivas diagonales involuntarias.
     */
    private fun applyEllipticalDeadzone(x: Float, y: Float, deadzoneX: Float, deadzoneY: Float): Pair<Float, Float> {
        val normalizedX = if (deadzoneX > 0f) x / deadzoneX else x
        val normalizedY = if (deadzoneY > 0f) y / deadzoneY else y
        val distance = sqrt((normalizedX * normalizedX) + (normalizedY * normalizedY))

        if (distance < 1.0f) return Pair(0f, 0f)

        // Rescaling radial
        val rawMagnitude = sqrt((x * x) + (y * y)).coerceAtMost(1.0f)
        val avgDeadzone = (deadzoneX + deadzoneY) * 0.5f
        val scaledMagnitude = ((rawMagnitude - avgDeadzone) / (1f - avgDeadzone)).coerceIn(0f, 1f)
        val angle = Math.atan2(y.toDouble(), x.toDouble())

        val finalX = (cos(angle) * scaledMagnitude).toFloat()
        val finalY = (sin(angle) * scaledMagnitude).toFloat()
        return Pair(finalX, finalY)
    }

    /**
     * Algoritmo de Zona Muerta Radial Matemática
     */
    private fun applyRadialDeadzone(x: Float, y: Float, deadzone: Float): Pair<Float, Float> {
        val rawMagnitude = sqrt((x * x) + (y * y))
        // Square-to-circle clamping (Radial Normalization) para evitar diagonales mayores a 1.0f
        val clampedX = if (rawMagnitude > 1.0f) x / rawMagnitude else x
        val clampedY = if (rawMagnitude > 1.0f) y / rawMagnitude else y
        val magnitude = sqrt((clampedX * clampedX) + (clampedY * clampedY)).coerceAtMost(1.0f)

        if (magnitude < deadzone) return Pair(0f, 0f)

        val normalizedMagnitude = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val angle = Math.atan2(clampedY.toDouble(), clampedX.toDouble())

        val finalX = (cos(angle) * normalizedMagnitude).toFloat()
        val finalY = (sin(angle) * normalizedMagnitude).toFloat()
        return Pair(finalX, finalY)
    }

    /**
     * Serializa estado del mando en trama binaria XInput de 16 bytes para el RTCDataChannel 'input'.
     * Formato compatible con el cliente web oficial (Little-Endian).
     */
    private fun buildGamepadPacket16(slot: Int, state: ControllerState): ByteArray {
        val buttonsMask = state.buttons
        val leftThumbX = (state.leftStickX * 32767).toInt().toShort()
        val leftThumbY = (-state.leftStickY * 32767).toInt().toShort()
        val rightThumbX = (state.rightStickX * 32767).toInt().toShort()
        val rightThumbY = (-state.rightStickY * 32767).toInt().toShort()
        val leftTrigger = (state.leftTrigger * 1023).toInt().toShort()
        val rightTrigger = (state.rightTrigger * 1023).toInt().toShort()

        return com.xcloud.godmode.bridge.XCloudInputPacketBuilder.buildGamepadStartPacket(
            buttonsMask, leftThumbX, leftThumbY, rightThumbX, rightThumbY, leftTrigger, rightTrigger
        )
    }

    /**
     * Serializa estado del mando en trama binaria XInput de 24 bytes para el RTCDataChannel
     */
    private fun buildGamepadPacket(slot: Int, state: ControllerState): ByteBuffer {
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x00.toByte())          // Packet type: Gamepad Input
        buffer.put(slot.toByte())          // Player Index (0..7)
        buffer.putShort(state.buttons.toShort())

        // Gatillos analógicos (0..255 o 0..1023)
        buffer.put((state.leftTrigger * 255).toInt().toByte())
        buffer.put((state.rightTrigger * 255).toInt().toByte())

        // Joysticks analógicos (-32768..32767)
        val sLx = (state.leftStickX * 32767).toInt().coerceIn(-32768, 32767).toShort()
        val sLy = (-state.leftStickY * 32767).toInt().coerceIn(-32768, 32767).toShort() // Invert Y for XInput
        val sRx = (state.rightStickX * 32767).toInt().coerceIn(-32768, 32767).toShort()
        val sRy = (-state.rightStickY * 32767).toInt().coerceIn(-32768, 32767).toShort()

        buffer.putShort(sLx)
        buffer.putShort(sLy)
        buffer.putShort(sRx)
        buffer.putShort(sRy)
        buffer.putLong(SystemClock.uptimeMillis()) // Timestamp

        buffer.flip()
        return buffer
    }
}
