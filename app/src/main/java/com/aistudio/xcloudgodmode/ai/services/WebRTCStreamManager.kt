package com.aistudio.xcloudgodmode.ai.services

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WebRTCStreamManager handles the WebRTC connection to the Xbox Cloud Gaming servers.
 * It manages the PeerConnection, DataChannel for input, and video/audio tracks.
 */
class WebRTCStreamManager(
    private val context: Context,
    private val networkClient: NetworkClient
) {
    private val TAG = "WebRTCStreamManager"
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    
    val eglBase: EglBase = EglBase.create()
    
    private var videoSink: VideoSink? = null

    private val _stats = kotlinx.coroutines.flow.MutableStateFlow(StreamStats())
    val stats: kotlinx.coroutines.flow.StateFlow<StreamStats> = _stats.asStateFlow()

    data class StreamStats(
        val rtt: Long = 0,
        val jitter: Double = 0.0,
        val packetLoss: Int = 0,
        val bitrate: Long = 0
    )

    init {
        initWebRTC()
    }

    fun release() {
        eglBase.release()
    }

    /**
     * Detecta si el hardware del dispositivo (SoC / MediaCodec) soporta decodificación
     * acelerada por GPU para el códec especificado.
     */
    fun isHardwareDecoderSupported(mimeType: String): Boolean {
        return try {
            val codecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { info ->
                if (info.isEncoder) return@any false
                val supportsType = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                if (!supportsType) return@any false
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("omx.google.", ignoreCase = true) &&
                    !info.name.startsWith("c2.android.", ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            true // Fallback permisivo
        }
    }

    /**
     * Motor de reescritura de SDP.
     * Reordena los códecs de video priorizando AV1, HEVC, VP9 o H264,
     * inyecta límites de tasa de bits x-google-max-bitrate y optimiza Opus a 510kbps / minptime=10.
     */
    fun rewriteSdp(originalSdp: String, settings: com.aistudio.xcloudgodmode.ai.models.SettingsModel): String {
        val lines = originalSdp.lines().toMutableList()
        val rtpMap = mutableMapOf<String, String>() // PayloadType -> Codec Name

        for (line in lines) {
            if (line.startsWith("a=rtpmap:")) {
                val parts = line.substring(9).split(" ")
                if (parts.size >= 2) {
                    val pt = parts[0]
                    val codecInfo = parts[1].split("/")[0]
                    rtpMap[pt] = codecInfo.uppercase()
                }
            }
        }

        val targetCodecName = when (settings.videoCodec) {
            "AV1" -> "AV1"
            "HEVC / H.265" -> "H265"
            "VP9" -> "VP9"
            "H.264 Baseline" -> "H264"
            else -> "H264"
        }

        val prioritizedPts = rtpMap.filter { it.value.contains(targetCodecName) }.keys.toList()

        val modifiedLines = mutableListOf<String>()
        val maxBitrateKbps = settings.maxBitrateMbps * 1000

        for (line in lines) {
            if (line.startsWith("m=video ")) {
                val parts = line.split(" ").toMutableList()
                if (parts.size > 3 && prioritizedPts.isNotEmpty()) {
                    val prefix = parts.subList(0, 3)
                    val currentPts = parts.subList(3, parts.size).filter { !prioritizedPts.contains(it) }
                    val reordered = prefix + prioritizedPts + currentPts
                    modifiedLines.add(reordered.joinToString(" "))
                    // Inyectar b=AS de bitrate inmediatamente tras m=video
                    modifiedLines.add("b=AS:$maxBitrateKbps")
                    continue
                }
            }

            // Inyectar / modificar fmtp de Opus (Audio)
            if (line.startsWith("a=fmtp:111 ")) {
                val audioConfig = when (settings.audioCodecProfile) {
                    "Opus Hi-Fi (510 kbps)" -> ";stereo=1;sprop-stereo=1;maxaveragebitrate=510000;cbr=1"
                    "Opus Ultra-Low Latency" -> ";minptime=10;useinbandfec=1;stereo=1"
                    else -> ";stereo=1"
                }
                val channelMode = if (settings.audioChannelMode == "Mono Mix") ";stereo=0;sprop-stereo=0" else ""
                modifiedLines.add(line + audioConfig + channelMode)
                continue
            }

            // Inyectar bitrate máximo en los fmtp del códec de video priorizado
            if (prioritizedPts.any { line.startsWith("a=fmtp:$it ") }) {
                val bitrateFlags = ";x-google-max-bitrate=$maxBitrateKbps;x-google-min-bitrate=5000;x-google-start-bitrate=${maxBitrateKbps * 8 / 10}"
                modifiedLines.add(line + bitrateFlags)
                continue
            }

            modifiedLines.add(line)
        }

        val finalSdp = modifiedLines.joinToString("\r\n")
        
        // Final Sanitization (mDNS Anonymization & Candidate Filtering)
        return com.aistudio.xcloudgodmode.ai.engine.GodModeEngineUtils.sanitizeSdp(
            rawSdp = finalSdp,
            anonymize = settings.anonymizeWebRtcLocalIps,
            filterTeredo = settings.filterTeredoCandidates,
            isLocal = settings.forceXRemoteRes // Approximate check for local
        )
    }

    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .createPeerConnectionFactory()
    }

    fun setVideoSink(sink: VideoSink) {
        this.videoSink = sink
    }

    fun startStream(gsToken: String, sessionPath: String) {
        executor.execute {
            try {
                Log.d(TAG, "Initiating stream session at $sessionPath")
                
                // 1. Create PeerConnection early to be ready for candidates
                createPeerConnection()

                // 2. Poll for Provisioned state if sessionPath is already a session
                // Or start a new session if titleId was passed. 
                // For this implementation, we assume sessionPath is either the login user path or a full session path.
                
                var currentPath = sessionPath
                if (sessionPath.endsWith("/login/user")) {
                    // This is a startup path, we might need to handle titleId here or assume it's handled in callers
                }

                // Wait for provisioned
                var isProvisioned = false
                var retryCount = 0
                while (!isProvisioned && retryCount < 10) {
                    val state = getSessionState(gsToken, currentPath)
                    Log.d(TAG, "Session state: $state")
                    if (state == "Provisioned") {
                        isProvisioned = true
                    } else {
                        Thread.sleep(2000)
                        retryCount++
                    }
                }

                if (!isProvisioned) throw Exception("Session failed to provision")

                // 3. Get Configuration
                val config = getConfiguration(gsToken, currentPath)
                Log.d(TAG, "Configuration received. Server IP: ${config.optJSONObject("serverDetails")?.optString("ipAddress")}")

                // 4. Create and Send Offer
                createOffer { sdpOffer ->
                    executor.execute {
                        try {
                            sendSdpOffer(gsToken, currentPath, sdpOffer)
                            
                            // 5. Query for Answer
                            val sdpAnswer = querySdpAnswer(gsToken, currentPath)
                            peerConnection?.setRemoteDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() { Log.d(TAG, "Remote SDP set successfully") }
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) { Log.e(TAG, "Failed to set remote SDP: $p0") }
                            }, SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer))
                            
                            // 6. Handle ICE exchange would happen via onIceCandidate callback
                        } catch (e: Exception) {
                            Log.e(TAG, "Offer/Answer exchange failed: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Stream start failed: ${e.message}")
            }
        }
    }

    private fun getSessionState(gsToken: String, sessionPath: String): String {
        val url = if (sessionPath.startsWith("http")) "$sessionPath/state" else "https://uks.core.gssv-play-prod.xboxlive.com/$sessionPath/state"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $gsToken")
            .get()
            .build()
        
        okHttpClient.newCall(request).execute().use { resp: Response ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            return json.optString("state", "Unknown")
        }
    }

    private fun getConfiguration(gsToken: String, sessionPath: String): JSONObject {
        val url = if (sessionPath.startsWith("http")) "$sessionPath/configuration" else "https://uks.core.gssv-play-prod.xboxlive.com/$sessionPath/configuration"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $gsToken")
            .get()
            .build()
        
        okHttpClient.newCall(request).execute().use { resp: Response ->
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun sendSdpOffer(gsToken: String, sessionPath: String, sdp: String) {
        val url = if (sessionPath.startsWith("http")) "$sessionPath/sdp" else "https://uks.core.gssv-play-prod.xboxlive.com/$sessionPath/sdp"
        val payload = JSONObject().apply {
            put("messageType", "offer")
            put("sdp", sdp)
            put("configuration", JSONObject().apply {
                put("chatConfiguration", JSONObject().apply {
                    put("bytesPerSample", 2)
                    put("expectedClipDurationMs", 20)
                    put("format", JSONObject().apply { put("codec", "opus"); put("container", "webm") })
                    put("numChannels", 1)
                    put("sampleFrequencyHz", 24000)
                })
                put("chat", JSONObject().apply { put("minVersion", 1); put("maxVersion", 1) })
                put("control", JSONObject().apply { put("minVersion", 1); put("maxVersion", 3) })
                put("input", JSONObject().apply { put("minVersion", 1); put("maxVersion", 8) })
                put("message", JSONObject().apply { put("minVersion", 1); put("maxVersion", 1) })
            })
        }
        val mediaType = "application/json".toMediaType()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $gsToken")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        
        okHttpClient.newCall(request).execute().close()
    }

    private fun querySdpAnswer(gsToken: String, sessionPath: String): String {
        val url = if (sessionPath.startsWith("http")) "$sessionPath/sdp" else "https://uks.core.gssv-play-prod.xboxlive.com/$sessionPath/sdp"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $gsToken")
            .get()
            .build()
        
        okHttpClient.newCall(request).execute().use { resp: Response ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            val exchangeResponse = json.optString("exchangeResponse")
            val innerJson = JSONObject(exchangeResponse)
            return innerJson.optString("sdp")
        }
    }

    private fun createOffer(onSdpReady: (String) -> Unit) {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { onSdpReady(desc.description) }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, desc)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "IceConnectionState: $state")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "New ICE candidate: ${candidate?.sdp}")
                // In a full implementation, we'd batch these and send via POST /ice
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {
                Log.d(TAG, "Received DataChannel: ${p0?.label()}")
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "Received VideoTrack")
                    videoSink?.let { track.addSink(it) }
                }
            }
        })

        // Create DataChannel for input
        val dcInit = DataChannel.Init()
        dcInit.ordered = true
        dataChannel = peerConnection?.createDataChannel("input", dcInit)
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel State: ${dataChannel?.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                Log.d(TAG, "Received message on DataChannel")
            }
        })
    }

    fun sendInput(data: ByteArray) {
        if (dataChannel?.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true)
            dataChannel?.send(buffer)
        }
    }

    fun stopStream() {
        executor.execute {
            peerConnection?.dispose()
            peerConnection = null
            dataChannel?.dispose()
            dataChannel = null
        }
    }
}
