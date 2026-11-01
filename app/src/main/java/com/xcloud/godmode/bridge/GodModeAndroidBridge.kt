package com.xcloud.godmode.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GodModeAndroidBridge: Bidirectional Tri-Layer Bridge Architecture.
 *
 * Rules:
 * 1. UI is 100% Jetpack Compose and video stream is hardware-accelerated.
 * 2. Fast-Path: Direct pure Kotlin + OkHttp resolution without touching WebView.
 * 3. Headless Fallback: If dynamic JS evaluation or WebRTC signaling is required,
 *    an ephemeral off-screen WebView is initialized, intercepts the media/SDP payload,
 *    and is immediately destroyed to prevent memory leaks on Android TV.
 */

data class ExtractedMediaPayload(
    val streamUrl: String,
    val streamType: StreamProtocolType,
    val headers: Map<String, String> = emptyMap(),
    val cookies: String? = null,
    val drmLicenseUrl: String? = null,
    val webrtcSdpOffer: String? = null,
    val subtitles: List<ExtractedSubtitle> = emptyList(),
    val resolvedBy: ResolutionEngine
)

data class ExtractedSubtitle(
    val lang: String,
    val label: String,
    val url: String
)

enum class StreamProtocolType {
    HLS_M3U8,
    DASH_MPD,
    DIRECT_MP4,
    WEBRTC_NATIVE
}

enum class ResolutionEngine {
    NATIVE_KOTLIN_FASTPATH,
    HEADLESS_WEBVIEW_BRIDGE,
    NATIVE_HARDWARE_CORE
}

sealed class BridgeState {
    data object Idle : BridgeState()
    data class ResolvingNative(val targetUrl: String) : BridgeState()
    data class EscalatedToHeadlessWebView(val reason: String) : BridgeState()
    data class StreamReadyForNativePlayer(val payload: ExtractedMediaPayload) : BridgeState()
    data class Error(val code: Int, val message: String) : BridgeState()
}

class GodModeAndroidBridge(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // Reactive State for ViewModel and Media Player pipeline
    private val _bridgeState = MutableStateFlow<BridgeState>(BridgeState.Idle)
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

    // Shared Flow for telemetry and bidirectional commands
    private val _bridgeEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bridgeEvents: SharedFlow<String> = _bridgeEvents.asSharedFlow()

    private var headlessWebView: WebView? = null
    private var activeExtractionJob: Job? = null

    /**
     * Resolves cloud stream with Fast-Path first, falling back to ephemeral Headless WebView if required.
     */
    fun resolveCloudStream(
        rawPageOrManifestUrl: String,
        customHeaders: Map<String, String> = emptyMap(),
        forceHeadlessSolver: Boolean = false
    ) {
        activeExtractionJob?.cancel()
        activeExtractionJob = externalScope.launch {
            _bridgeState.value = BridgeState.ResolvingNative(rawPageOrManifestUrl)

            if (!forceHeadlessSolver) {
                // Step 1: Pure Kotlin Fast-Path
                val nativeResult = tryNativeKotlinFastPath(rawPageOrManifestUrl, customHeaders)
                if (nativeResult != null) {
                    _bridgeState.value = BridgeState.StreamReadyForNativePlayer(nativeResult)
                    return@launch
                }
            }

            // Step 2: Ephemeral Headless Extractor
            _bridgeState.value = BridgeState.EscalatedToHeadlessWebView(
                "Iniciando extractor efimero de baja latencia..."
            )
            val headlessResult = executeEphemeralHeadlessExtraction(
                targetUrl = rawPageOrManifestUrl,
                customHeaders = customHeaders,
                timeoutMs = 12_000L
            )

            if (headlessResult != null) {
                _bridgeState.value = BridgeState.StreamReadyForNativePlayer(headlessResult)
            } else {
                _bridgeState.value = BridgeState.Error(
                    code = 408,
                    message = "No se pudo extraer un flujo nativo valido tras agotar ambas vias."
                )
            }
        }
    }

    /**
     * Layer 1: Fast-Path in pure Kotlin
     */
    private suspend fun tryNativeKotlinFastPath(
        url: String,
        headers: Map<String, String>
    ): ExtractedMediaPayload? = withContext(Dispatchers.IO) {
        try {
            if (url.contains(".m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true)) {
                return@withContext ExtractedMediaPayload(
                    streamUrl = url,
                    streamType = if (url.contains(".mpd", ignoreCase = true)) StreamProtocolType.DASH_MPD else StreamProtocolType.HLS_M3U8,
                    headers = headers,
                    resolvedBy = ResolutionEngine.NATIVE_KOTLIN_FASTPATH
                )
            }

            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 403 || response.code == 503) return@withContext null

                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.contains("mpegurl", true) || contentType.contains("dash+xml", true)) {
                    return@withContext ExtractedMediaPayload(
                        streamUrl = response.request.url.toString(),
                        streamType = if (contentType.contains("dash", true)) StreamProtocolType.DASH_MPD else StreamProtocolType.HLS_M3U8,
                        headers = headers,
                        resolvedBy = ResolutionEngine.NATIVE_KOTLIN_FASTPATH
                    )
                }

                val bodyHtml = response.body?.string().orEmpty()
                val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                val match = m3u8Regex.find(bodyHtml)
                if (match != null) {
                    return@withContext ExtractedMediaPayload(
                        streamUrl = match.value.replace("\\/", "/"),
                        streamType = StreamProtocolType.HLS_M3U8,
                        headers = headers + mapOf("Referer" to url),
                        resolvedBy = ResolutionEngine.NATIVE_KOTLIN_FASTPATH
                    )
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Layer 2: Ephemeral Headless Extractor with zero-RAM-leak immediate autodestruction
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun executeEphemeralHeadlessExtraction(
        targetUrl: String,
        customHeaders: Map<String, String>,
        timeoutMs: Long
    ): ExtractedMediaPayload? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val isResolved = AtomicBoolean(false)
            val capturedHeaders = ConcurrentHashMap<String, String>(customHeaders)

            fun completeAndDestroy(payload: ExtractedMediaPayload?) {
                if (isResolved.compareAndSet(false, true)) {
                    destroyHeadlessWebView()
                    if (continuation.isActive) {
                        continuation.resume(payload, onCancellation = null)
                    }
                }
            }

            mainHandler.post {
                try {
                    destroyHeadlessWebView()

                    val webView = WebView(appContext).apply {
                        setLayerType(WebView.LAYER_TYPE_NONE, null)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            blockNetworkImage = true
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            userAgentString = customHeaders["User-Agent"]
                                ?: "Mozilla/5.0 (Linux; Android 14; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                        }
                    }

                    webView.addJavascriptInterface(object : Any() {
                        @JavascriptInterface
                        fun onStreamExtractedFromJs(url: String, type: String) {
                            val cookies = CookieManager.getInstance().getCookie(targetUrl)
                            completeAndDestroy(
                                ExtractedMediaPayload(
                                    streamUrl = url,
                                    streamType = if (type.equals("DASH", true)) StreamProtocolType.DASH_MPD else StreamProtocolType.HLS_M3U8,
                                    headers = capturedHeaders + mapOf("Referer" to targetUrl),
                                    cookies = cookies,
                                    resolvedBy = ResolutionEngine.HEADLESS_WEBVIEW_BRIDGE
                                )
                            )
                        }

                        @JavascriptInterface
                        fun onWebRtcSdpCaptured(sdpOffer: String, signalingUrl: String) {
                            completeAndDestroy(
                                ExtractedMediaPayload(
                                    streamUrl = signalingUrl,
                                    streamType = StreamProtocolType.WEBRTC_NATIVE,
                                    headers = capturedHeaders,
                                    webrtcSdpOffer = sdpOffer,
                                    resolvedBy = ResolutionEngine.HEADLESS_WEBVIEW_BRIDGE
                                )
                            )
                        }

                        @JavascriptInterface
                        fun logBridgeTelemetry(msg: String) {
                            _bridgeEvents.tryEmit(msg)
                        }
                    }, "GodModeBridge")

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(JS_HOOK_SNIFFER_SCRIPT, null)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val reqHeaders = request.requestHeaders ?: emptyMap()

                            if (reqUrl.contains("doubleclick.net") || reqUrl.contains("googlesyndication")) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }

                            if (isVideoManifestUrl(reqUrl)) {
                                capturedHeaders.putAll(reqHeaders)
                                val cookieStr = CookieManager.getInstance().getCookie(targetUrl)

                                completeAndDestroy(
                                    ExtractedMediaPayload(
                                        streamUrl = reqUrl,
                                        streamType = detectProtocolType(reqUrl),
                                        headers = capturedHeaders,
                                        cookies = cookieStr,
                                        resolvedBy = ResolutionEngine.HEADLESS_WEBVIEW_BRIDGE
                                    )
                                )
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }

                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    headlessWebView = webView
                    webView.loadUrl(targetUrl, customHeaders)

                } catch (_: Exception) {
                    completeAndDestroy(null)
                }
            }

            continuation.invokeOnCancellation {
                destroyHeadlessWebView()
            }
        }
    }

    private fun isVideoManifestUrl(url: String): Boolean {
        val clean = url.lowercase()
        return clean.contains(".m3u8") ||
               clean.contains(".mpd") ||
               clean.contains("/manifest") ||
               (clean.contains(".mp4") && !clean.contains("preview"))
    }

    private fun detectProtocolType(url: String): StreamProtocolType {
        return when {
            url.contains(".mpd", true) -> StreamProtocolType.DASH_MPD
            url.contains(".mp4", true) -> StreamProtocolType.DIRECT_MP4
            else -> StreamProtocolType.HLS_M3U8
        }
    }

    /**
     * Destroys the ephemeral WebView on main thread to free all memory.
     */
    fun destroyHeadlessWebView() {
        mainHandler.post {
            headlessWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            headlessWebView = null
        }
    }

    companion object {
        private const val JS_HOOK_SNIFFER_SCRIPT = """
            (function() {
                if (window.__godModeInjected) return;
                window.__godModeInjected = true;

                const origFetch = window.fetch;
                window.fetch = async function(...args) {
                    const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || '');
                    if (url.includes('.m3u8') || url.includes('.mpd')) {
                        if (window.GodModeBridge && window.GodModeBridge.onStreamExtractedFromJs) {
                            window.GodModeBridge.onStreamExtractedFromJs(url, url.includes('.mpd') ? 'DASH' : 'HLS');
                        }
                    }
                    return origFetch.apply(this, args);
                };

                const OrigPeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection;
                if (OrigPeerConnection) {
                    window.RTCPeerConnection = function(...pcArgs) {
                        const pc = new OrigPeerConnection(...pcArgs);
                        const origSetRemote = pc.setRemoteDescription.bind(pc);
                        pc.setRemoteDescription = function(desc) {
                            if (desc && desc.sdp && window.GodModeBridge && window.GodModeBridge.onWebRtcSdpCaptured) {
                                window.GodModeBridge.onWebRtcSdpCaptured(desc.sdp, window.location.href);
                            }
                            return origSetRemote(desc);
                        };
                        return pc;
                    };
                }
            })();
        """
    }
}
