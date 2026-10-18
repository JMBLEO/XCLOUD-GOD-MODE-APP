package com.jmbleo.xcloudgodmode.stream

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.jmbleo.xcloudgodmode.GodModeNativeBridge
import com.jmbleo.xcloudgodmode.GodModeWebChromeClient
import com.jmbleo.xcloudgodmode.GodModeWebViewClient
import com.jmbleo.xcloudgodmode.MainActivity

/**
 * Componente Jetpack Compose para streaming de baja latencia en Xbox Cloud Gaming.
 * Configura el motor WebView con aceleración por GPU, cookies de sesión,
 * inyección de scripts JavaScript mediante ScriptInjector y enlace bidireccional nativo.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GameStreamWebView(
    launchUrl: String,
    modifier: Modifier = Modifier,
    scriptInjector: ScriptInjector? = null,
    nativeBridge: GodModeNativeBridge? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? MainActivity

    val effectiveInjector = remember(context) {
        scriptInjector ?: ScriptInjector(context)
    }

    var webViewInstance: WebView? = null

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewInstance = this
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
                setBackgroundColor(Color.BLACK)
                setLayerType(View.LAYER_TYPE_NONE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER

                // Configuración de motor Web & Storage (JavaScript y DOM Storage obligatorios para WebRTC)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    allowFileAccess = true
                    allowContentAccess = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // User-Agent de Microsoft Edge en Windows 11 para desbloquear 1080p HQ / 1440p y HEVC
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
                }

                // 2. Gestión de cookies de sesión Xbox Live
                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 3. Enlace nativo JNI si está disponible
                val bridge = nativeBridge ?: if (activity != null) GodModeNativeBridge(activity, this) else null
                bridge?.let {
                    addJavascriptInterface(it, "GodModeAndroid")
                }

                // 4. Clientes WebChrome y WebView con aceleración EGL y soporte WebRTC
                if (activity != null && bridge != null) {
                    webViewClient = object : GodModeWebViewClient(activity, bridge) {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (view != null && !effectiveInjector.isDocumentStartSupported) {
                                effectiveInjector.injectOnPageFinished(view)
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            handler?.proceed()
                        }
                    }
                    webChromeClient = GodModeWebChromeClient(activity, this)
                } else {
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (view != null && !effectiveInjector.isDocumentStartSupported) {
                                effectiveInjector.injectOnPageFinished(view)
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            handler?.proceed()
                        }
                    }
                    webChromeClient = android.webkit.WebChromeClient()
                }

                // 5. Inyección temprana de JavaScript (@run-at document-start)
                effectiveInjector.setupDocumentStartInjection(this)

                // 6. Cargar la URL de streaming directo
                loadUrl(launchUrl)
                requestFocus()

                onWebViewCreated?.invoke(this)
            }
        },
        update = { wv ->
            if (wv.url.isNullOrBlank() || wv.url != launchUrl) {
                wv.loadUrl(launchUrl)
            }
        }
    )

    // Limpieza segura del WebView para evitar fugas de memoria al desmontar
    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewInstance?.let { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    wv.webChromeClient = null
                    wv.removeJavascriptInterface("GodModeAndroid")
                    wv.destroy()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/**
 * Sobrecarga de conveniencia para invocación directa con parámetro 'url'.
 */
@Composable
fun GameStreamWebView(
    url: String,
    modifier: Modifier = Modifier
) {
    GameStreamWebView(launchUrl = url, modifier = modifier)
}
