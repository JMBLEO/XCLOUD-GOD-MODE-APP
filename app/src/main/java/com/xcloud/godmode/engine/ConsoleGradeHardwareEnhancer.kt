package com.xcloud.godmode.engine

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.Window
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ConsoleGradeHardwareEnhancer(
    private val context: Context,
    private val window: Window,
    private val surfaceView: SurfaceView,
    private val hwProfile: DeviceHardwareProfile
) : Choreographer.FrameCallback {

    init {
        try {
            System.loadLibrary("godmode_native")
        } catch (_: Throwable) {
        }
    }

    private var currentTargetFps: Float = 60.0f

    fun activateAllmVrrAndHdrWindow(enableHdr10: Boolean, initialStreamFps: Float) {
        currentTargetFps = initialStreamFps

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val gameManager = context.getSystemService(GameManager::class.java)
            gameManager?.setGameState(GameState(false, GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE))
        }

        if (enableHdr10 && hwProfile.supportsHdr10Display && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }

        updateSeamlessVrrRefreshRate(initialStreamFps)
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun updateSeamlessVrrRefreshRate(streamFps: Float) {
        currentTargetFps = streamFps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sc = surfaceView.surfaceControl ?: return
            if (!sc.isValid) return

            val tx = SurfaceControl.Transaction()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tx.setFrameRate(
                    sc,
                    streamFps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                tx.setFrameRate(sc, streamFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            tx.apply()
        }
    }

    fun buildHgigHdrStaticMetadataBuffer(): ByteBuffer {
        val maxCll = hwProfile.hgigMaxLuminanceNits.toInt().coerceIn(400, 10000)
        val maxFall = (maxCll * 0.5f).toInt().coerceIn(200, 4000)
        val minLum = (hwProfile.hgigMinLuminanceNits * 10000f).toInt().coerceIn(1, 5000)

        val buffer = ByteBuffer.allocateDirect(25).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0.toByte()) 
        buffer.putShort(8500.toShort());  buffer.putShort(39850.toShort()) 
        buffer.putShort(6550.toShort());  buffer.putShort(2300.toShort())  
        buffer.putShort(35400.toShort()); buffer.putShort(14600.toShort()) 
        buffer.putShort(15635.toShort()); buffer.putShort(16450.toShort()) 
        buffer.putShort(maxCll.toShort())  
        buffer.putShort(minLum.toShort())  
        buffer.putShort(maxCll.toShort())  
        buffer.putShort(maxFall.toShort()) 
        buffer.flip()
        return buffer
    }

    fun onTouchUpdateRoiRegion(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
            val normX = (event.x / surfaceView.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            val normY = (event.y / surfaceView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            try {
                nativeUpdateFoveatedRoiCenter(normX, normY)
            } catch (_: Throwable) {
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        val frameIntervalNs = (1_000_000_000.0f / currentTargetFps).toLong()
        val nextVsyncTimeNs = frameTimeNanos + frameIntervalNs
        try {
            nativeNotifyNextVsyncQft(nextVsyncTimeNs)
        } catch (_: Throwable) {
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun release() {
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private external fun nativeNotifyNextVsyncQft(nextVsyncTimeNs: Long)
    private external fun nativeUpdateFoveatedRoiCenter(normX: Float, normY: Float)
}
