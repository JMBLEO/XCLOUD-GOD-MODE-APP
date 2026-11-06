package com.xcloud.godmode.ui.surface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup

/**
 * Fábrica y limpiador de SurfaceView que impide que SurfaceFlinger deje un lienzo negro
 * fantasma encima del God Mode Hub al abortar o salir de un stream.
 */
object HardwareSurfaceCleaner {

    fun createManagedStreamSurfaceView(context: Context): SurfaceView {
        return SurfaceView(context).apply {
            // PROHIBIDO usar setZOrderOnTop(true): bloquearía los paneles de error y el Hub.
            setZOrderOnTop(false)
            setZOrderMediaOverlay(false)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            visibility = View.VISIBLE
        }
    }

    /**
     * Debe invocarse ANTES de pasar la UI a IDLE o desmontar el nodo en Compose.
     * Limpia el buffer de hardware, oculta la capa en SurfaceFlinger y la desacopla del ViewGroup.
     */
    fun destroyAndDetachSurfaceView(surfaceView: SurfaceView?) {
        if (surfaceView == null) return
        surfaceView.post {
            runCatching {
                val holder = surfaceView.holder
                if (holder.surface.isValid) {
                    val canvas: Canvas? = holder.lockCanvas()
                    if (canvas != null) {
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }
            // Forzar GONE obliga a WindowManager a retirar inmediatamente el agujero de hardware
            surfaceView.visibility = View.GONE
            surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)
            (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
        }
    }
}
