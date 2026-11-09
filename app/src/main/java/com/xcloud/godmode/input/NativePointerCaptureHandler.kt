package com.xcloud.godmode.input

import android.os.Build
import android.view.MotionEvent
import android.view.View

enum class PointerCaptureMode {
    DISABLED,
    RELATIVE_MOUSE_CAMERA, // For FPS shooters
    ABSOLUTE_CURSOR        // For menus / strategy games
}

/**
 * NativePointerCaptureHandler: Captures hardware mouse input using Android Pointer Capture (API 26+)
 * for zero-latency camera aiming and menu navigation.
 */
class NativePointerCaptureHandler(
    private val onRelativeDelta: (dx: Float, dy: Float) -> Unit,
    private val onAbsolutePosition: (x: Float, y: Float) -> Unit
) {
    var captureMode: PointerCaptureMode = PointerCaptureMode.RELATIVE_MOUSE_CAMERA
    var sensitivity: Float = 1.0f // 0.1 to 2.0
    var isPointerCaptured: Boolean = false
        private set

    fun attachToView(targetView: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetView.setOnCapturedPointerListener { _, event ->
                handleCapturedPointerEvent(event)
            }
        }
    }

    fun requestCapture(targetView: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetView.requestPointerCapture()
            isPointerCaptured = true
        }
    }

    fun releaseCapture(targetView: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetView.releasePointerCapture()
            isPointerCaptured = false
        }
    }

    fun handleCapturedPointerEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false

        when (captureMode) {
            PointerCaptureMode.DISABLED -> return false
            PointerCaptureMode.RELATIVE_MOUSE_CAMERA -> {
                val dx = event.x * sensitivity
                val dy = event.y * sensitivity
                onRelativeDelta(dx, dy)
                return true
            }
            PointerCaptureMode.ABSOLUTE_CURSOR -> {
                onAbsolutePosition(event.x, event.y)
                return true
            }
        }
    }
}
