package com.xcloud.godmode.ui.components

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * GodModeSurfaceView: Specialized high-performance hardware surface for Xbox streaming.
 * 
 * Features:
 * - Zero-Overdraw architecture: Placed behind the window to avoid Android View hierarchy blending.
 * - Native PixelFormat: Uses RGB_565 or OPAQUE to bypass alpha-blending costs.
 * - Async Lifecycle: Integrates with NativeStreamBridge on surface changes.
 */
class GodModeSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    interface SurfaceListener {
        fun onSurfaceCreated(surface: Surface)
        fun onSurfaceDestroyed()
        fun onSurfaceChanged(width: Int, height: Int)
    }

    private var listener: SurfaceListener? = null

    init {
        // Core "Zero-Overdraw" setting:
        // Placed behind the normal UI layers. This allows the GPU to render the 1440p
        // video stream directly to the hardware plane without CPU involvement in compositing.
        setZOrderMediaOverlay(false)
        
        // Ensure the surface is opaque to optimize hardware-level blending
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
    }

    fun setSurfaceListener(listener: SurfaceListener) {
        this.listener = listener
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        android.util.Log.i("GodModeSurfaceView", "Hardware surface created: ${holder.surface}")
        listener?.onSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        android.util.Log.i("GodModeSurfaceView", "Hardware surface resized: ${width}x$height")
        listener?.onSurfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        android.util.Log.i("GodModeSurfaceView", "Hardware surface destroyed")
        listener?.onSurfaceDestroyed()
    }
}
