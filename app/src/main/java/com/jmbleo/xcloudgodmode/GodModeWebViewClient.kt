package com.jmbleo.xcloudgodmode

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

open class GodModeWebViewClient(
    val activity: MainActivity,
    val bridge: GodModeNativeBridge
) : WebViewClient() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null
        val url = request.url?.toString() ?: return null

        try {
            // 1. Bloqueo de Telemetría y Feedback a nivel de Socket Nativo (Cero uso de CPU en JS)
            if (bridge.blockAnalytics && (url.contains("qos.gameservices") || url.contains("browser.events.data.msn.com"))) {
                return createEmptyJsonOkResponse()
            }
            if (bridge.skipFeedback && url.contains("/feedback")) {
                return createEmptyJsonOkResponse()
            }

            // 2. Intercepción nativa segura de login para inyección de X-Forwarded-For a nivel de Socket
            if (bridge.enableNetworkBypass && bridge.activeBypassIp.isNotBlank() && url.endsWith("/v2/login/user")) {
                val sanitizedIp = sanitizeHeaderValue(bridge.activeBypassIp)
                if (sanitizedIp.isNotEmpty()) {
                    val method = request.method ?: "GET"
                    val builder = Request.Builder().url(url)

                    // Copiar y sanitizar cabeceras de la petición original
                    request.requestHeaders?.forEach { (k, v) ->
                        val sanitizedKey = sanitizeHeaderName(k)
                        val sanitizedVal = sanitizeHeaderValue(v)
                        if (sanitizedKey.isNotEmpty() && sanitizedVal.isNotEmpty()) {
                            try {
                                builder.header(sanitizedKey, sanitizedVal)
                            } catch (ignored: Exception) {}
                        }
                    }

                    // Inyectar X-Forwarded-For y User-Agent nativo
                    builder.header("X-Forwarded-For", sanitizedIp)
                    builder.header("User-Agent", "Xbox/2402.1001.20.0 (Windows NT 10.0; Win64; x64)")

                    // Inyectar cookies de la sesión
                    try {
                        val cookieStr = CookieManager.getInstance().getCookie(url)
                        if (!cookieStr.isNullOrBlank()) {
                            builder.header("Cookie", sanitizeHeaderValue(cookieStr))
                        }
                    } catch (ignored: Exception) {}

                    if (method.equals("POST", ignoreCase = true) || method.equals("PUT", ignoreCase = true)) {
                        builder.method(method, ByteArray(0).toRequestBody(null))
                    } else {
                        builder.method(method, null)
                    }

                    try {
                        val response = httpClient.newCall(builder.build()).execute()
                        val responseStream = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        val responseHeaders = mutableMapOf<String, String>()
                        response.headers.forEach { pair ->
                            responseHeaders[pair.first] = pair.second
                        }

                        // Guardar cookies resultantes
                        val setCookieList = response.headers("Set-Cookie")
                        for (c in setCookieList) {
                            try {
                                CookieManager.getInstance().setCookie(url, c)
                            } catch (ignored: Exception) {}
                        }

                        val contentType = response.header("Content-Type", "application/json; charset=utf-8") ?: "application/json"
                        val mimeType = contentType.substringBefore(";").trim()
                        val encoding = if (contentType.contains("charset=")) {
                            contentType.substringAfter("charset=").substringBefore(";").trim()
                        } else {
                            "UTF-8"
                        }

                        return WebResourceResponse(
                            mimeType,
                            encoding,
                            response.code,
                            response.message.ifBlank { "OK" },
                            responseHeaders,
                            responseStream
                        )
                    } catch (e: Exception) {
                        when (e) {
                            is SocketTimeoutException, is IOException -> {
                                // En caso de fallo de red en el proxy, permitir fallback al flujo estándar de WebView
                            }
                            else -> e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return handleUrlNavigation(url)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        return handleUrlNavigation(url)
    }

    /**
     * Filtra la navegación de URLs para eliminar por completo la interfaz web matriz de Xbox.
     * Solo permite autenticación y streams directos; las redirecciones al portal web retornan al launcher nativo.
     */
    private fun handleUrlNavigation(url: String): Boolean {
        // 1. Si durante una sesión activa de stream se detecta redirección a login o credencial expirada,
        // salir de inmediato del stream y advertir al usuario en el Home nativo pidiendo iniciar sesión
        val isAuthRedirect = url.contains("login.live.com") ||
                url.contains("login.microsoftonline.com") ||
                url.contains("account.live.com") ||
                url.contains("/v2/login/user")

        if (isAuthRedirect && activity.streamContainer.visibility == android.view.View.VISIBLE) {
            activity.runOnUiThread {
                activity.notifySessionRequired()
            }
            return true
        }

        // Permitir siempre flujos de autenticación oficial si ocurren fuera del stream (ej. diálogo de login)
        if (isAuthRedirect || url.contains("oauth")) {
            return false
        }

        // 2. Permitir URLs de streaming directo (/play/launch/{titleId})
        if (url.contains("/play/launch/")) {
            return false
        }

        // 3. Interceptar si intenta cargar el dashboard web de Xbox (/play raíz o catálogo general)
        val isWebMatrixDashboard = url.equals("https://www.xbox.com/play", ignoreCase = true) ||
                url.equals("https://www.xbox.com/play/", ignoreCase = true) ||
                url.matches(Regex("https://www\\.xbox\\.com/[a-zA-Z\\-]+/play/?$")) ||
                url.startsWith("https://www.xbox.com/games", ignoreCase = true)

        if (isWebMatrixDashboard && activity.streamContainer.visibility == android.view.View.VISIBLE) {
            activity.runOnUiThread {
                activity.exitStreamSession()
            }
            return true
        }

        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (view != null) {
            // Inyectar inmediatamente el CSS Hiding Payload al comenzar la carga del DOM
            com.jmbleo.xcloudgodmode.stream.ScriptInjector.injectCssHidingPayload(view)

            // Si el WebView del dispositivo no soporta DOCUMENT_START_SCRIPT nativo de Jetpack, inyectar en onPageStarted como seguro de vida
            if (!activity.isDocumentStartSupported) {
                try {
                    view.evaluateJavascript(activity.godModeUserscriptCode, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view != null) {
            // Reaplicar CSS Hiding Payload tras el render inicial para neutralizar cualquier elemento DOM tardío
            com.jmbleo.xcloudgodmode.stream.ScriptInjector.injectCssHidingPayload(view)
            if (!activity.isDocumentStartSupported && activity.godModeUserscriptCode.isNotBlank()) {
                try {
                    view.evaluateJavascript(activity.godModeUserscriptCode, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: android.webkit.RenderProcessGoneDetail?
    ): Boolean {
        // Blindaje contra terminación del proceso host de Android si el renderer de Chromium sufre un crash o recycle
        try {
            if (view != null) {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            }
        } catch (ignored: Exception) {}
        return true
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: android.webkit.SslErrorHandler?,
        error: android.net.http.SslError?
    ) {
        android.util.Log.w("GodModeWebViewClient", "SSL Handshake warning (${error?.primaryError}) en ${error?.url} - Procediendo")
        handler?.proceed()
    }

    private fun sanitizeHeaderValue(value: String?): String {
        if (value == null) return ""
        // Eliminar saltos de línea (CRLF) y caracteres de control que rompen OkHttp
        return value.replace("[\r\n\u0000-\u001F]".toRegex(), "").trim()
    }

    private fun sanitizeHeaderName(name: String?): String {
        if (name == null) return ""
        return name.replace("[\r\n\u0000-\u0020:]".toRegex(), "").trim()
    }

    private fun createEmptyJsonOkResponse(): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Headers" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Cache-Control" to "no-store"
        )
        return WebResourceResponse(
            "application/json",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8))
        )
    }
}
