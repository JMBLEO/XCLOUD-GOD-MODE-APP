package com.aistudio.xcloudgodmode.ai.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "XboxLogin"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XboxLoginWebView(
    onAuthenticated: () -> Unit,
    onAuthenticationError: (String) -> Unit
) {
    val webViewState = remember {
        mutableStateOf<WebView?>(null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewState.value?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                webChromeClient = null
                webViewClient = WebViewClient()
                removeAllViews()
                destroy()
            }

            webViewState.value = null
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        factory = { context ->
            WebView(context).apply {
                webViewState.value = this

                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                visibility = View.VISIBLE

                setBackgroundColor(
                    android.graphics.Color.WHITE
                )

                setLayerType(
                    View.LAYER_TYPE_NONE,
                    null
                )

                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true

                    cacheMode =
                        WebSettings.LOAD_DEFAULT

                    mediaPlaybackRequiresUserGesture =
                        false

                    mixedContentMode =
                        WebSettings.MIXED_CONTENT_NEVER_ALLOW

                    allowFileAccess = false
                    allowContentAccess = false

                    javaScriptCanOpenWindowsAutomatically =
                        false

                    setSupportMultipleWindows(false)
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false

                    useWideViewPort = true
                    loadWithOverviewMode = false

                    /*
                     * No reemplazar userAgentString.
                     *
                     * Debe conservarse el User-Agent
                     * real del Android System WebView.
                     */
                }

                XboxAuthManager.configureCookies(this)

                webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(
                            consoleMessage:
                                ConsoleMessage
                        ): Boolean {
                            Log.d(
                                TAG,
                                "${consoleMessage.messageLevel()}: " +
                                    consoleMessage.message()
                            )

                            return true
                        }

                        override fun onProgressChanged(
                            view: WebView?,
                            newProgress: Int
                        ) {
                            super.onProgressChanged(
                                view,
                                newProgress
                            )

                            Log.d(
                                TAG,
                                "Progreso: $newProgress"
                            )
                        }
                    }

                webViewClient =
                    XboxLoginWebViewClient(
                        onAuthenticated =
                            onAuthenticated,
                        onAuthenticationError =
                            onAuthenticationError
                    )

                loadUrl(
                    XboxAuthManager.startUrl()
                )
            }
        }
    )
}

private class XboxLoginWebViewClient(
    private val onAuthenticated: () -> Unit,
    private val onAuthenticationError:
        (String) -> Unit
) : WebViewClient() {

    private var completionDelivered = false
    private var callbackObserved = false

    override fun onPageStarted(
        view: WebView,
        url: String,
        favicon: Bitmap?
    ) {
        super.onPageStarted(
            view,
            url,
            favicon
        )

        val uri = runCatching {
            Uri.parse(url)
        }.getOrNull()

        if (
            uri != null &&
            XboxAuthRoutes
                .isXboxOAuthCallback(uri)
        ) {
            /*
             * No cerrar el diálogo.
             *
             * La web de Xbox debe consumir primero
             * el fragmento OAuth mediante MSAL.js.
             */
            callbackObserved = true
        }

        Log.d(
            TAG,
            "Inicio: ${redactUrl(url)}"
        )
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val uri = request.url

        if (
            uri.scheme == "about" ||
            uri.scheme == "data"
        ) {
            return false
        }

        if (!XboxAuthRoutes.isTrusted(uri)) {
            Log.w(
                TAG,
                "Navegación bloqueada: " +
                    "${uri.scheme}://${uri.host}"
            )

            onAuthenticationError(
                "La autenticación intentó abrir " +
                    "un dominio no permitido"
            )

            return true
        }

        return false
    }

    override fun onPageFinished(
        view: WebView,
        url: String
    ) {
        super.onPageFinished(view, url)

        Log.d(
            TAG,
            "Final: ${redactUrl(url)}"
        )

        val uri = runCatching {
            Uri.parse(url)
        }.getOrNull() ?: return

        /*
         * El callback debe permanecer abierto.
         * MSAL.js todavía está procesando PKCE.
         */
        if (
            XboxAuthRoutes
                .isXboxOAuthCallback(uri)
        ) {
            callbackObserved = true

            view.postDelayed(
                {
                    inspectOAuthCallback(view)
                },
                1_000L
            )

            return
        }

        if (!XboxAuthRoutes.isXboxPlay(uri)) {
            return
        }

        view.postDelayed(
            {
                verifyXboxPlayPage(view)
            },
            1_500L
        )
    }

    private fun inspectOAuthCallback(
        view: WebView
    ) {
        view.evaluateJavascript(
            CALLBACK_DIAGNOSTIC
        ) { result ->
            Log.d(
                TAG,
                "Callback preparado: $result"
            )
        }
    }

    private fun verifyXboxPlayPage(
        view: WebView
    ) {
        if (completionDelivered) {
            return
        }

        view.evaluateJavascript(
            AUTHENTICATION_PROBE
        ) { result ->
            val pageReady = result == "true"

            if (
                !pageReady ||
                !XboxAuthManager
                    .hasCandidateSession()
            ) {
                return@evaluateJavascript
            }

            XboxAuthManager.saveSession()

            completionDelivered = true
            onAuthenticated()
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(
            view,
            request,
            error
        )

        if (request.isForMainFrame) {
            val message =
                if (Build.VERSION.SDK_INT >= 23) {
                    error.description.toString()
                } else {
                    "Error de carga del WebView"
                }

            Log.e(
                TAG,
                "Error principal: $message"
            )

            onAuthenticationError(message)
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(
            view,
            request,
            errorResponse
        )

        if (request.isForMainFrame) {
            Log.e(
                TAG,
                "HTTP ${errorResponse.statusCode} en " +
                    redactUrl(
                        request.url.toString()
                    )
            )
        }
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        Log.w(
            TAG,
            "SSL Handshake aviso (${error.primaryError}) en ${error.url} - Autorizando handshake"
        )
        handler.proceed()
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail
    ): Boolean {
        Log.e(
            TAG,
            "Proceso WebView finalizado. " +
                "Crash: ${detail.didCrash()}"
        )

        onAuthenticationError(
            "El proceso de autenticación web " +
                "se cerró inesperadamente"
        )

        return true
    }

    private fun redactUrl(
        rawUrl: String
    ): String {
        return runCatching {
            val uri = Uri.parse(rawUrl)

            buildString {
                append(uri.scheme)
                append("://")
                append(uri.host.orEmpty())
                append(uri.path.orEmpty())

                val action =
                    uri.getQueryParameter("action")

                if (!action.isNullOrBlank()) {
                    append("?action=")
                    append(action)
                }
            }
        }.getOrElse {
            "[URL no disponible]"
        }
    }

    companion object {
        private const val CALLBACK_DIAGNOSTIC = """
            (() => {
              try {
                return Boolean(
                  location.hash.includes('code=') ||
                  location.hash.includes('error=')
                );
              } catch (_) {
                return false;
              }
            })();
        """

        private const val AUTHENTICATION_PROBE = """
            (() => {
              try {
                const host =
                  location.hostname.toLowerCase();

                const xboxHost =
                  host === 'xbox.com' ||
                  host === 'www.xbox.com' ||
                  host.endsWith('.xbox.com');

                const playPath =
                  /(^|\/)play(\/|$)/i.test(
                    location.pathname
                  );

                if (!xboxHost || !playPath) {
                  return false;
                }

                const root =
                  document.querySelector('#root') ||
                  document.querySelector(
                    '[data-reactroot]'
                  ) ||
                  document.body;

                const officialLoginLink =
                  document.querySelector(
                    'a[href*="/auth/msa"]' +
                    '[href*="action=logIn"]'
                  );

                return Boolean(root) &&
                  document.readyState === 'complete' &&
                  !officialLoginLink;
              } catch (_) {
                return false;
              }
            })();
        """
    }
}
