package com.jmbleo.xcloudgodmode

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import com.jmbleo.xcloudgodmode.config.GodModeConfigManager
import com.jmbleo.xcloudgodmode.input.InputMath

/**
 * Gestor avanzado de periféricos y controladores físicos (Bluetooth / USB OTG).
 * Implementa filtrado de zonas muertas radiales, remapeo de botones (A/B, X/Y),
 * y detección del combo 'View + Menu' con cerrojo (latching) para invocar el menú rápido.
 */
class ControllerInputManager(
    private val webView: WebView,
    private val configManager: GodModeConfigManager = GodModeConfigManager.getInstance(webView.context)
) {

    // Callbacks para eventos de interfaz
    var onComboTriggered: (() -> Unit)? = null
    var onStickMovedListener: ((x: Float, y: Float) -> Unit)? = null

    // Estado de botones para detección de combo simultáneo
    private var isViewPressed = false
    private var isMenuPressed = false
    private var isComboLatched = false

    init {
        // Capturar movimientos relativos de ratón físico en Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setOnCapturedPointerListener(View.OnCapturedPointerListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE) {
                    val dx = event.getAxisValue(MotionEvent.AXIS_X)
                    val dy = event.getAxisValue(MotionEvent.AXIS_Y)
                    if (dx != 0f || dy != 0f) {
                        webView.evaluateJavascript(
                            "if(window.__injectNativeMouseDelta) window.__injectNativeMouseDelta($dx, $dy);",
                            null
                        )
                    }
                    return@OnCapturedPointerListener true
                }
                false
            })
        }
    }

    /**
     * Procesa eventos de ejes analógicos de joysticks y gatillos con calibración radial.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        ) {
            val rawX = event.getAxisValue(MotionEvent.AXIS_X)
            val rawY = event.getAxisValue(MotionEvent.AXIS_Y)

            val config = configManager.getConfig()
            val (calibratedX, calibratedY) = InputMath.applyRadialDeadzone(
                x = rawX,
                y = rawY,
                innerDeadzone = config.innerDeadzone,
                outerDeadzone = config.outerDeadzone,
                curve = config.responseCurve
            )

            // Notificar al visualizador de calibración en tiempo real
            onStickMovedListener?.invoke(calibratedX, calibratedY)

            // Inyectar stick calibrado al emulador de Gamepad de God Mode en WebView
            if (rawX != 0f || rawY != 0f) {
                webView.evaluateJavascript(
                    "if(window.__injectNativeStick) window.__injectNativeStick(0, $calibratedX, $calibratedY);",
                    null
                )
            }
            return true
        }
        return false
    }

    /**
     * Intercepta y remapa botones del mando físico, detectando el combo View + Menu.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isFromGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        val keyCode = event.keyCode
        val action = event.action

        // 1. Detección del combo View + Menu (Select + Start)
        val config = configManager.getConfig()
        if (config.comboMenuEnabled) {
            val isViewKey = (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || keyCode == KeyEvent.KEYCODE_BACK)
            val isMenuKey = (keyCode == KeyEvent.KEYCODE_BUTTON_START || keyCode == KeyEvent.KEYCODE_MENU)

            if (isViewKey) {
                isViewPressed = (action == KeyEvent.ACTION_DOWN)
            }
            if (isMenuKey) {
                isMenuPressed = (action == KeyEvent.ACTION_DOWN)
            }

            if (isViewPressed && isMenuPressed) {
                if (!isComboLatched) {
                    isComboLatched = true
                    onComboTriggered?.invoke()
                }
                return true
            } else if (!isViewPressed && !isMenuPressed) {
                isComboLatched = false
            }
        }

        // 2. Botón Central Guía Xbox / PS (KEYCODE_BUTTON_MODE)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onComboTriggered?.invoke()
            }
            return true
        }

        // 3. Remapeo de botones físicos (A/B y X/Y)
        val remappedCode = InputMath.applyButtonRemap(keyCode, config.swapAB, config.swapXY)
        if (remappedCode != keyCode) {
            // Re-inyectar código mapeado al WebView
            val jsAction = if (action == KeyEvent.ACTION_DOWN) "down" else "up"
            webView.evaluateJavascript(
                "if(window.__injectNativeButton) window.__injectNativeButton($remappedCode, '$jsAction');",
                null
            )
            return true
        }

        // 4. Botón Atrás: si no es mando físico, evitar salir de la actividad
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (isFromGamepad) {
                    return false
                }
                onComboTriggered?.invoke()
            }
            return true
        }

        return false
    }
}
