package com.xcloud.godmode.streaming

import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * StreamSurfaceAdapter: Bridge between the Android SurfaceView and the Native/Hardware engine.
 * Ensures the hardware surface is ready and configured with the correct Z-Order before 
 * injecting decoder buffers.
 */
class StreamSurfaceAdapter(private val surfaceView: SurfaceView) {

    private var surfaceListener: ((android.view.Surface) -> Unit)? = null
    private var decoder: HardwareVideoDecoder? = null

    init {
        // ZERO-OVERDRAW: Placed behind the normal window layers to bypass SurfaceFlinger compositing costs.
        surfaceView.setZOrderMediaOverlay(false)
        
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                android.util.Log.i("StreamSurfaceAdapter", "SURFACE_READY: Initializing hardware video sink.")
                
                // Signal that the hardware plane is ready for zero-copy buffer injection
                surfaceListener?.invoke(holder.surface)
                
                // Start the hardware decoder once the sink is ready
                decoder = HardwareVideoDecoder(holder.surface)
                decoder?.start(1920, 1080) // Default target for cloud streaming
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                android.util.Log.i("StreamSurfaceAdapter", "SURFACE_RESIZED: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                android.util.Log.w("StreamSurfaceAdapter", "SURFACE_LOST: Releasing hardware decoder.")
                decoder?.stop()
                decoder = null
            }
        })
    }

    fun setOnSurfaceReadyListener(listener: (android.view.Surface) -> Unit) {
        this.surfaceListener = listener
    }
}
