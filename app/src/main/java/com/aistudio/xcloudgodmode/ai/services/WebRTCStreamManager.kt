package com.aistudio.xcloudgodmode.ai.services

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
     * Motor de reescritura de SDP (inspirado en better-xcloud sdp.ts).
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

        return modifiedLines.joinToString("\r\n")
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
            createPeerConnection()
            // In a real implementation, we would now negotiate SDP with the sessionPath
            // 1. Create Offer
            // 2. Send Offer to sessionPath -> receive Answer
            // 3. Set Remote Description
            // 4. Handle ICE candidates
            Log.d(TAG, "Starting stream with token: $gsToken at $sessionPath")
        }
    }

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
                // Send to server
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
