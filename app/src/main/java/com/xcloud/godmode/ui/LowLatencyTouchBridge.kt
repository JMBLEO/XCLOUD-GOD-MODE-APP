package com.xcloud.godmode.ui

import android.view.Choreographer
import android.view.MotionEvent

class LowLatencyTouchBridge : Choreographer.FrameCallback {
    private var lastX = 0f
    private var lastY = 0f
    private var isPressed = false

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                isPressed = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (isPressed) {
            // Despachar al motor C++ sincronizado con VSYNC
            // NativeInputBridge.dispatchRawTouch(lastX, lastY)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
