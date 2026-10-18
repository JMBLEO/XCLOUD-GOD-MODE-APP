package com.aistudio.xcloudgodmode.ai.streaming

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GameStreamWebView(
    launchUrl: String,
    modifier: Modifier = Modifier,
    scriptInjector: ScriptInjector? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null
) {
    val context = LocalContext.current
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

                // El renderizado acelerado por GPU se realiza directamente sobre la ventana de la Activity.
                // Evitamos capas de hardware intermedias que provocan el error de rendernode en Mesa.
                setLayerType(View.LAYER_TYPE_NONE, null)

                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = true
                    allowContentAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                cookieManager.flush()

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (view != null) {
                            // Adjuntar el inyector automáticamente al terminar cada carga de página para limpiar la UI
                            effectiveInjector.injectGodModeScript(view)
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        return false // Permitir la carga fluida de los recursos internos de Azure/Xbox
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: android.webkit.SslErrorHandler?,
                        error: android.net.http.SslError?
                    ) {
                        android.util.Log.w("GameStreamWebView", "SSL Handshake warning (${error?.primaryError}) en ${error?.url} - Procediendo")
                        handler?.proceed()
                    }
                }

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
}
