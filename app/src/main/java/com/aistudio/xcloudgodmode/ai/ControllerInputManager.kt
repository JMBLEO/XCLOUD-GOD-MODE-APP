package com.aistudio.xcloudgodmode.ai

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

class ControllerInputManager(private val webView: WebView) {

    init {
        // Capturar movimientos relativos del ratón físico en Android 8.0+ (API 26+)
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
     * Intercepta botones problemáticos en Android (Botón Atrás y Botón Guía Xbox/PlayStation)
     * para que jamás te saquen de la aplicación mientras juegas.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isFromGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        // 1. Botón Central Guía Xbox / PS (KEYCODE_BUTTON_MODE) -> Abre o cierra el panel de God Mode AI
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                webView.evaluateJavascript(
                    "(function(){ const ov = document.getElementById('gm-overlay'); if(ov){ ov.style.display = (ov.style.display === 'flex' ? 'none' : 'flex'); } })();",
                    null
                )
            }
            return true
        }

        // 2. Botón Atrás de Android o Botón Select/View mal mapeado como BACK
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (isFromGamepad) {
                    // En algunos mandos Bluetooth, el botón View/Select envía KEYCODE_BACK: lo dejamos pasar al WebView sin cerrar la Activity
                    return false
                }
                // Si fue el gesto "Atrás" de la pantalla de Android: cerrar el menú #gm-overlay si está abierto
                webView.evaluateJavascript(
                    "(function(){ const ov = document.getElementById('gm-overlay'); if(ov && ov.style.display === 'flex'){ ov.style.display = 'none'; } })();",
                    null
                )
            }
            return true
        }

        return false
    }
}
