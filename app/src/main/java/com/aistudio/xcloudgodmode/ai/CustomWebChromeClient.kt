package com.aistudio.xcloudgodmode.ai

import android.app.Activity
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
 * Custom WebChromeClient implementation designed to manage hardware acceleration flags,
 * specifically forcing setLayerType(View.LAYER_TYPE_HARDWARE, null) and handling EGL configurations
 * to resolve 'No se ha podido abrir el rendernode' errors in Android WebView and WebRTC streaming.
 */
open class CustomWebChromeClient(
    private val activity: Activity,
    private val webView: WebView
) : WebChromeClient() {

    private var customVideoView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    // 1x1 transparent poster bitmap to avoid allocating heavy EGL textures for default video posters
    private val defaultPosterBitmap: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    init {
        applyHardwareAccelerationAndEglConfig()
    }

    /**
     * Specifically forces LAYER_TYPE_HARDWARE on the WebView and configures standard EGL surface
     * parameters (PixelFormat.RGBA_8888 and COLOR_MODE_DEFAULT) to prevent the Mesa GPU driver
     * from probing unbacked DRM rendernodes or 10-bit color formats (RGBA_1010102).
     */
    fun applyHardwareAccelerationAndEglConfig() {
        try {
            // Force hardware acceleration layer specifically on the WebView instance
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Ensure window surface format is standard 32-bit RGBA_8888
            activity.window.setFormat(PixelFormat.RGBA_8888)

            // Force hardware acceleration flags on the activity window
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )

            // Constrain color mode to standard sRGB (COLOR_MODE_DEFAULT) on Android 8.0+
            // to avoid probing 10-bit HDR format (101010-2) and missing DRM rendernodes
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

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            if (customVideoView != null) {
                callback?.onCustomViewHidden()
                return
            }

            customVideoView = view
            customViewCallback = callback

            // Re-apply EGL configurations and hardware acceleration for the full-screen video layer
            applyHardwareAccelerationAndEglConfig()

            if (view != null) {
                view.keepScreenOn = true
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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

            // Re-apply and maintain standard hardware acceleration and EGL config
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
