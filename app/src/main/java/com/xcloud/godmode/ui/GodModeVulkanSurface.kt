package com.xcloud.godmode.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun GodModeVulkanSurface(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (SurfaceView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceCreated(this@apply)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        // Notificar redimensionamiento al motor C++ nativo
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // Liberar contexto de renderizado de forma segura
                    }
                })
            }
        }
    )
}
