package com.jmbleo.xcloudgodmode

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Cliente WebChrome personalizado para XCloud God Mode AI.
 * Gestiona de forma estricta las banderas de aceleración por hardware, formatos de superficie EGL,
 * pantallas completas para el stream de video de WebRTC y permisos de audio.
 */
class GodModeWebChromeClient(
    private val activity: MainActivity,
    private val webView: WebView
) : WebChromeClient() {

    private var customVideoView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    // Poster 1x1 transparente predeterminado para evitar texturas intermedias pesadas de EGL
    private val defaultPosterBitmap: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    init {
        // Usa aceleración directa sobre la ventana sin capas intermedias que fallen en Mesa
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        applyHardwareAccelerationAndEglConfig()
    }

    /**
     * Aplica aceleración por hardware y formato de superficie estándar RGBA_8888 en la ventana,
     * fijando COLOR_MODE_DEFAULT para evitar que Mesa y HWUI intenten negociar formatos HDR de 10 bits (RGBA_1010102)
     * que provocan el error 'No se ha podido abrir el rendernode'.
     */
    fun applyHardwareAccelerationAndEglConfig() {
        try {
            webView.setLayerType(View.LAYER_TYPE_NONE, null)
            activity.window.setFormat(PixelFormat.RGBA_8888)
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getDefaultVideoPoster(): Bitmap {
        return defaultPosterBitmap
    }

    override fun getVideoLoadingProgressView(): View? {
        return null
    }

    /**
     * Configura y estabiliza las banderas de hardware acceleration de la ventana al entrar a pantalla completa.
     */
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            if (customVideoView != null) {
                callback?.onCustomViewHidden()
                return
            }

            customVideoView = view
            customViewCallback = callback

            // Blindar aceleración por hardware con formato estándar RGBA_8888 para evitar fallos de rendernode EGL
            applyHardwareAccelerationAndEglConfig()

            if (view != null) {
                view.keepScreenOn = true
                view.setLayerType(View.LAYER_TYPE_NONE, null)
                (activity.window.decorView as? ViewGroup)?.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onHideCustomView() {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            if (customVideoView != null) {
                (activity.window.decorView as? ViewGroup)?.removeView(customVideoView)
                customVideoView = null
            }
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null

            // Re-aplicar configuración de hardware y EGL al salir de pantalla completa
            applyHardwareAccelerationAndEglConfig()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    if (ContextCompat.checkSelfPermission(
                            activity,
                            android.Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(android.Manifest.permission.RECORD_AUDIO),
                            101
                        )
                    }
                }
                request.grant(request.resources)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        return super.onConsoleMessage(consoleMessage)
    }
}
