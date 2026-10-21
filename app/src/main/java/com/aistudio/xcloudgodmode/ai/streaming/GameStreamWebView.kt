package com.aistudio.xcloudgodmode.ai.streaming

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Native Bridge for God Mode Script.
 * Allows JavaScript to trigger native Android features like Haptics and System Bar color changes.
 */
class GodModeAndroidBridge(private val activity: Activity, private val webView: WebView? = null) {

    private var currentStatusColor: Int = Color.parseColor("#08090C")
    private var currentNavColor: Int = Color.parseColor("#050608")

    @JavascriptInterface
    fun triggerNativeHaptics(durationMs: Int, amplitudePercent: Int) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = activity.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            val amplitude = (255 * (amplitudePercent / 100f)).toInt().coerceIn(1, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs.toLong())
            }
        } catch (e: Exception) {
            android.util.Log.w("GodModeBridge", "Haptics error: ${e.message}")
        }
    }

    /**
     * Invocado desde TypeScript dentro del Stream Overlay para usar el sintetizador nativo.
     */
    @JavascriptInterface
    fun playNativeUiSound(eventTypeStr: String) {
        val event = try {
            com.aistudio.xcloudgodmode.ai.audio.SoundEventType.valueOf(eventTypeStr.uppercase())
        } catch (_: Exception) {
            com.aistudio.xcloudgodmode.ai.audio.SoundEventType.SELECT
        }
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(event, fromWebView = true)
    }

    /**
     * Sincroniza el estado del Stream para silenciar o permitir sonidos.
     */
    @JavascriptInterface
    fun setStreamLayerState(layerState: String) {
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.currentInputLayer = when (layerState.lowercase()) {
            "stream_active" -> com.aistudio.xcloudgodmode.ai.audio.ActiveInputLayer.STREAM_ACTIVE
            "stream_overlay" -> com.aistudio.xcloudgodmode.ai.audio.ActiveInputLayer.STREAM_OVERLAY
            else -> com.aistudio.xcloudgodmode.ai.audio.ActiveInputLayer.COMPOSE_HOME
        }
    }

    @JavascriptInterface
    fun setChameleonSystemBars(r: Int, g: Int, b: Int) {
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.updateChameleonPitch(r, g, b)

        val targetStatusColor = Color.rgb(
            (r * 0.16).toInt().coerceIn(6, 255),
            (g * 0.16).toInt().coerceIn(6, 255),
            (b * 0.16).toInt().coerceIn(8, 255)
        )
        val targetNavColor = Color.rgb(
            (r * 0.10).toInt().coerceIn(4, 255),
            (g * 0.10).toInt().coerceIn(4, 255),
            (b * 0.10).toInt().coerceIn(6, 255)
        )

        activity.runOnUiThread {
            try {
                val window = activity.window ?: return@runOnUiThread
                
                android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), currentStatusColor, targetStatusColor).apply {
                    duration = 350
                    addUpdateListener { animator ->
                        val color = animator.animatedValue as Int
                        window.statusBarColor = color
                        currentStatusColor = color
                    }
                    start()
                }

                android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), currentNavColor, targetNavColor).apply {
                    duration = 350
                    addUpdateListener { animator ->
                        val color = animator.animatedValue as Int
                        window.navigationBarColor = color
                        currentNavColor = color
                    }
                    start()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val controller = window.decorView.windowInsetsController
                    controller?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                }
            } catch (e: Exception) {
                android.util.Log.w("GodModeBridge", "SystemBar error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun clearNativeWebViewCache() {
        activity.runOnUiThread {
            try {
                webView?.clearCache(true)
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                android.util.Log.d("GodModeBridge", "Auto-Detox: Web cache cleared.")
            } catch (e: Exception) {}
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GameStreamWebView(
    launchUrl: String,
    modifier: Modifier = Modifier,
    appSettings: com.aistudio.xcloudgodmode.ai.models.SettingsModel = com.aistudio.xcloudgodmode.ai.models.SettingsModel(),
    scriptInjector: ScriptInjector? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val effectiveInjector = remember(context) {
        scriptInjector ?: ScriptInjector(context)
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
                setBackgroundColor(Color.BLACK)

                // Native Bridge Injection
                if (activity != null) {
                    addJavascriptInterface(GodModeAndroidBridge(activity, this), "GodModeAndroidBridge")
                }

                // Apply Engine Flags (GPU, Dark Mode, TLS 1.3)
                com.aistudio.xcloudgodmode.ai.engine.GodModeEngineUtils.applyWebViewFlags(
                    webView = this,
                    enableGpu = appSettings.enableGpuRasterization,
                    enableDark = appSettings.autoDarkModeWebContents,
                    enforceTls13 = appSettings.enforceTls13Strict
                )

                this.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = true
                    allowContentAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    
                    // User Agent Profile Selection
                    userAgentString = when (appSettings.userAgentProfile) {
                        "Xbox Windows 11 App" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
                        "Samsung Tizen TV" -> "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/131.0.0.0 TV Safari/537.36"
                        else -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (view != null) {
                            effectiveInjector.injectGodModeScript(view, appSettings)
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }
                    
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }
                }

                loadUrl(launchUrl)
                requestFocus()
                onWebViewCreated?.invoke(this)
            }
        },
        update = { wv ->
            // Only reload if the URL changes significantly to avoid losing session state during on-the-fly engine switching (#303)
            if (wv.url.isNullOrBlank()) {
                wv.loadUrl(launchUrl)
            }
        }
    )
}
