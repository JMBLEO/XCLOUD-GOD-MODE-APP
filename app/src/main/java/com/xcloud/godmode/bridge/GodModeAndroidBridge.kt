package com.xcloud.godmode.bridge

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GodModeAndroidBridge: Pure Native Media Resolver Architecture.
 *
 * Rules:
 * 1. UI is 100% Jetpack Compose and video stream is hardware-accelerated.
 * 2. 100% Pure Native Kotlin + OkHttp + Sockets resolution with zero web dependencies.
 * 3. Pure Direct Sockets, Zero memory leaks.
 */

data class ExtractedMediaPayload(
    val streamUrl: String,
    val streamType: StreamProtocolType,
    val headers: Map<String, String> = emptyMap(),
    val cookies: String? = null,
    val drmLicenseUrl: String? = null,
    val sdpOffer: String? = null,
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
    NATIVE_UDP_STREAM
}

enum class ResolutionEngine {
    NATIVE_KOTLIN_FASTPATH,
    NATIVE_SOCKET_BRIDGE,
    NATIVE_HARDWARE_CORE
}

sealed class BridgeState {
    data object Idle : BridgeState()
    data class ResolvingNative(val targetUrl: String) : BridgeState()
    data class StreamReadyForNativePlayer(val payload: ExtractedMediaPayload) : BridgeState()
    data class Error(val code: Int, val message: String) : BridgeState()
}

class GodModeAndroidBridge(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
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

    private var activeExtractionJob: Job? = null

    /**
     * Resolves cloud stream with pure native Kotlin Fast-Path.
     */
    fun resolveCloudStream(
        rawPageOrManifestUrl: String,
        customHeaders: Map<String, String> = emptyMap()
    ) {
        activeExtractionJob?.cancel()
        activeExtractionJob = externalScope.launch {
            _bridgeState.value = BridgeState.ResolvingNative(rawPageOrManifestUrl)

            val nativeResult = tryNativeKotlinFastPath(rawPageOrManifestUrl, customHeaders)
            if (nativeResult != null) {
                _bridgeState.value = BridgeState.StreamReadyForNativePlayer(nativeResult)
            } else {
                _bridgeState.value = BridgeState.Error(
                    code = 404,
                    message = "No se pudo extraer un flujo nativo valido."
                )
            }
        }
    }

    /**
     * Fast-Path in pure Kotlin using OkHttp
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
}
