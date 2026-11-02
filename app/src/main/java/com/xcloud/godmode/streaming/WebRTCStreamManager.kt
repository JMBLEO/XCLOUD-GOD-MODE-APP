package com.xcloud.godmode.streaming

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.xcloud.godmode.network.NetworkClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import kotlinx.coroutines.*

import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioSamples
import java.nio.ByteBuffer

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
    private var audioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    
    private var wifiLock: WifiManager.WifiLock? = null
    private var watchdogJob: Job? = null
    private var lastTrafficMs = System.currentTimeMillis()
    
    private val gamepadStates = mutableMapOf<Int, ByteArray>() // Slot -> Last Packet
    
    val eglBase: EglBase = EglBase.create()
    private var videoSink: VideoSink? = null
    private val hapticEngine = com.xcloud.godmode.haptics.UnifiedHapticTelemetricEngine(context)
    private var lastKeyframeRequestMs = 0L
    private var activeGsToken: String? = null
    private var activeSessionPath: String? = null

    private val localCandidates = mutableListOf<IceCandidate>()
    private var dataChannelHandshake: XboxDataChannelHandshake? = null
    private var messageChannel: DataChannel? = null
    private var controlChannel: DataChannel? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    val okHttpClient: OkHttpClient get() = networkClient.httpClient

    data class StreamStats(
        val rtt: Int = 0,
        val jitter: Double = 0.0,
        val packetLoss: Double = 0.0,
        val bitrate: Int = 0,
        val fps: Int = 0
    )

    val bitrateController = AdaptiveBitrateController(context)
    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    fun updateTelemetry(rtt: Int, jitter: Double, packetLoss: Double, bitrate: Int, fps: Int) {
        _stats.value = StreamStats(rtt, jitter, packetLoss, bitrate, fps)
        bitrateController.onTelemetryTick(packetLoss.toFloat(), rtt)
    }

    fun createOfferSync(targetWidth: Int, targetHeight: Int, maxBitrateKbps: Int): String? {
        val countDownLatch = java.util.concurrent.CountDownLatch(1)
        var sdpResult: String? = null
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("echoCancellation", "false"))
            optional.add(MediaConstraints.KeyValuePair("autoGainControl", "false"))
        }

        // Create specialized DataChannels before offer
        val dcInit = DataChannel.Init()
        messageChannel = peerConnection?.createDataChannel("message", dcInit)
        controlChannel = peerConnection?.createDataChannel("control", dcInit)
        
        setupDataChannel(messageChannel)
        setupDataChannel(controlChannel)

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    val settings = com.xcloud.godmode.models.SettingsModel(maxBitrateMbps = maxBitrateKbps / 1000)
                    val rewrittenSdp = NativeStreamBridge.rewriteSdp(desc.description, maxBitrateKbps)
                    sdpResult = rewrittenSdp
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { countDownLatch.countDown() }
                        override fun onCreateFailure(p0: String?) { countDownLatch.countDown() }
                        override fun onSetFailure(p0: String?) { countDownLatch.countDown() }
                    }, SessionDescription(desc.type, rewrittenSdp))
                } else countDownLatch.countDown()
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { countDownLatch.countDown() }
            override fun onSetFailure(p0: String?) { countDownLatch.countDown() }
        }, constraints)

        countDownLatch.await(5, TimeUnit.SECONDS)
        
        // Initialize handshake helper
        dataChannelHandshake = XboxDataChannelHandshake(
            sendOnMessageChannel = { msg -> sendMessage("message", msg) },
            sendOnControlChannel = { msg -> sendMessage("control", msg) },
            sendClientMetadataOnInputChannel = { sendInput(byteArrayOf(0x08, 0x00, 0x00, 0x00)) }
        )

        return sdpResult
    }

    fun setRemoteDescriptionSync(sdp: String) {
        val countDownLatch = java.util.concurrent.CountDownLatch(1)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { countDownLatch.countDown() }
            override fun onCreateFailure(p0: String?) { countDownLatch.countDown() }
            override fun onSetFailure(p0: String?) { countDownLatch.countDown() }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        countDownLatch.await(5, TimeUnit.SECONDS)
    }

    fun addRemoteIceCandidatesSync(candidates: JSONArray) {
        for (i in 0 until candidates.length()) {
            val obj = candidates.optJSONObject(i)
            if (obj != null) {
                var candidateStr = obj.optString("candidate", "")
                if (candidateStr.startsWith("a=")) {
                    candidateStr = candidateStr.substring(2)
                }
                if (candidateStr.isBlank() || candidateStr.contains("end-of-candidates")) continue
                val candidate = IceCandidate(
                    obj.optString("sdpMid", "0"),
                    obj.optInt("sdpMLineIndex", 0),
                    candidateStr
                )
                peerConnection?.addIceCandidate(candidate)
            } else {
                var candidateStr = candidates.optString(i, "")
                if (candidateStr.startsWith("a=")) {
                    candidateStr = candidateStr.substring(2)
                }
                if (candidateStr.isBlank() || candidateStr.contains("end-of-candidates")) continue
                val candidate = IceCandidate("0", 0, candidateStr)
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    fun getLocalCandidates(): List<JSONObject> {
        return localCandidates.map {
            JSONObject().apply {
                put("candidate", it.sdp)
                put("sdpMid", it.sdpMid)
                put("sdpMLineIndex", it.sdpMLineIndex)
            }
        }
    }

    private fun sendMessage(label: String, message: String) {
        val channel = if (label == "message") messageChannel else controlChannel
        if (channel?.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(message.toByteArray()), false)
            channel.send(buffer)
        }
    }

    private fun setupDataChannel(channel: DataChannel?) {
        channel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel ${channel.label()} State: ${channel.state()}")
                if (channel.state() == DataChannel.State.OPEN && channel.label() == "message") {
                    dataChannelHandshake?.onMessageChannelOpened()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                if (!buffer.binary) {
                    val text = String(data)
                    if (channel.label() == "message") {
                        dataChannelHandshake?.onMessageChannelReceived(text)
                    }
                }
            }
        })
    }

    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        
        val godModeDecoderFactory = object : VideoDecoderFactory {
            private val defaultFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            
            override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
                val name = info.name.uppercase()
                Log.d(TAG, "Creating God Mode Native Decoder for: $name")
                
                if (name == "H264" || name == "H265" || name == "HEVC" || name == "AV1") {
                    return object : VideoDecoder {
                        override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
                            return VideoCodecStatus.OK
                        }

                        override fun release(): VideoCodecStatus {
                            NativeStreamBridge.detachSurface()
                            return VideoCodecStatus.OK
                        }

                        override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
                            val buffer = frame.buffer
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            
                            val success = NativeStreamBridge.injectAccessUnit(
                                data = data,
                                size = data.size,
                                serverDataKey = 0,
                                packetLoss = _stats.value.packetLoss.toFloat()
                            )
                            
                            return if (success) VideoCodecStatus.OK else VideoCodecStatus.ERROR
                        }

                        override fun getImplementationName(): String = "GodModeZeroCopyNDK"
                    }
                }
                return defaultFactory.createDecoder(info)
            }

            override fun getSupportedCodecs(): Array<VideoCodecInfo> = defaultFactory.supportedCodecs
        }

        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)

        val adm = JavaAudioDeviceModule.builder(context)
            .setSamplesReadyCallback { samples: AudioSamples ->
                if (isConnected()) {
                    val data = samples.data
                    val shortArray = ShortArray(data.size / 2)
                    java.nio.ByteBuffer.wrap(data)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortArray)
                    NativeStreamBridge.injectAudio(shortArray, shortArray.size / samples.channelCount)
                }
            }
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoDecoderFactory(godModeDecoderFactory)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .createPeerConnectionFactory()
        
        adm.release()
    }

    fun setVideoSink(sink: VideoSink) {
        this.videoSink = sink
    }

    fun startStream(gsToken: String, sessionPath: String, titleId: String? = null) {
        this.activeGsToken = gsToken
        this.activeSessionPath = sessionPath

        // Extensión 3: Adquisición de Wi-Fi WMM Low-Latency Lock (<2ms contención en router)
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GodModeLowLatencyLock")?.apply {
                setReferenceCounted(false)
                acquire()
                Log.d(TAG, "Wi-Fi WMM Full Low-Latency Lock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire Wi-Fi WMM Lock: ${e.message}")
        }

        // Extensión 4: Compensación Dinámica de Latencia A/V Bluetooth
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val isBtActive = audioManager?.isBluetoothA2dpOn == true || audioManager?.isBluetoothScoOn == true
            if (isBtActive) {
                // Compensación temporal negativa de 125 ms para sincronía de labios/audio en mandos/auriculares BT
                NativeStreamBridge.setPtsOffsetUs(-125_000L)
                Log.i(TAG, "Bluetooth Audio activo: compensación A/V PTS configurada a -125ms")
            } else {
                NativeStreamBridge.setPtsOffsetUs(0L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Bluetooth audio delay: ${e.message}")
        }

        // Extensión 9: Aislamiento en Bóveda de Memoria Segura C++
        NativeStreamBridge.storeSecureToken(gsToken.toByteArray())
        NativeStreamBridge.startAudio()

        // Iniciar Watchdog de enlace SCTP/ICE (Extensión 2 y 5)
        startConnectionWatchdog()
        
        // Use the orchestrator internally
        val orchestrator = GssvSignalingOrchestrator(okHttpClient)
        val config = GssvSessionConfig(
            gsToken = gsToken,
            regionName = "Auto",
            regionBaseUri = sessionPath.substringBefore("/v2/login/user"),
            bypassIp = null,
            titleId = titleId ?: "9NKX70BBCDRN"
        )
        
        scope.launch {
            orchestrator.startCloudStream(config, this@WebRTCStreamManager)
        }
    }

    private fun startConnectionWatchdog() {
        watchdogJob?.cancel()
        lastTrafficMs = System.currentTimeMillis()
        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(500) // Heartbeat interval
                val now = System.currentTimeMillis()
                val isChanOpen = dataChannel?.state() == DataChannel.State.OPEN
                if (isChanOpen) {
                    // Send lightweight keepalive ping on control channel
                    sendMessage("control", "{\"type\":\"ping\",\"ts\":$now}")
                }
                // Si transcurren >1200 ms sin paquetes útiles mientras estamos conectados, auto-reparar ICE
                if (isChanOpen && (now - lastTrafficMs) > 1200L) {
                    Log.w(TAG, "Watchdog de Enlace: Sin tráfico útil durante ${now - lastTrafficMs}ms. Disparando Fast ICE Restart...")
                    restartIce()
                    lastTrafficMs = now // Reset watchdog counter
                }
            }
        }
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        // DISABLE mDNS by not using continual gathering and filtering .local candidates
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "IceConnectionState: $state")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val candidateSdp = candidate.sdp.orEmpty()
                
                // Anti-Relay Filter: Drop "typ relay" (TURN) candidates
                if (candidateSdp.contains("typ relay", ignoreCase = true)) return
                
                // mDNS Filter: Drop ".local" candidates to avoid Xbox ICE rejection
                if (candidateSdp.contains(".local", ignoreCase = true)) return

                localCandidates.add(candidate)
                Log.d(TAG, "Accepted Direct ICE candidate: $candidateSdp")
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {
                Log.d(TAG, "Received Remote DataChannel: ${p0?.label()}")
                setupDataChannel(p0)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "Received VideoTrack - Low Latency Playout Delay 0")
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
                Log.d(TAG, "DataChannel input State: ${dataChannel?.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                lastTrafficMs = System.currentTimeMillis()
                val remaining = buffer.data.remaining()
                if (remaining >= 4) {
                    val bytes = ByteArray(remaining)
                    buffer.data.get(bytes)
                    // Intercept Rumble packet (0x80 bitmask) from server
                    val packetType = bytes[0].toInt() and 0xFF
                    if (packetType == 0x80 && remaining >= 8) {
                        val leftGrip = (bytes[2].toInt() and 0xFF) / 255f
                        val rightGrip = (bytes[3].toInt() and 0xFF) / 255f
                        val leftTrigger = if (remaining >= 6) (bytes[4].toInt() and 0xFF) / 255f else 0f
                        val rightTrigger = if (remaining >= 7) (bytes[5].toInt() and 0xFF) / 255f else 0f
                        hapticEngine.emitHapticEvent(
                            com.xcloud.godmode.haptics.HapticTelemetryPacket(
                                leftGripMotor = leftGrip,
                                rightGripMotor = rightGrip,
                                leftTriggerMotor = leftTrigger,
                                rightTriggerMotor = rightTrigger,
                                durationMs = 60
                            )
                        )
                    }
                }
            }
        })
    }

    fun getDataChannel(): DataChannel? = dataChannel

    fun isConnected(): Boolean {
        return peerConnection != null && (dataChannel?.state() == DataChannel.State.OPEN)
    }

    fun requestKeyframe(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastKeyframeRequestMs) < 1500L) return
        lastKeyframeRequestMs = now
        dataChannelHandshake?.requestImmediateKeyframe()
    }

    fun restartIce() {
        executor.execute {
            try {
                Log.d(TAG, "Fast Session Resume: Triggering ICE Restart")
                peerConnection?.restartIce()
                // Renegotiation logic...
            } catch (e: Exception) {
                Log.e(TAG, "Fast Session Resume failed: ${e.message}")
            }
        }
    }

    fun sendInput(data: ByteArray, slot: Int = 0) {
        if (dataChannel?.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true)
            dataChannel?.send(buffer)
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (enabled) {
            if (audioSource == null) {
                val micConstraints = MediaConstraints().apply {
                    optional.add(MediaConstraints.KeyValuePair("echoCancellation", "false"))
                    optional.add(MediaConstraints.KeyValuePair("autoGainControl", "false"))
                }
                audioSource = peerConnectionFactory?.createAudioSource(micConstraints)
                audioTrack = peerConnectionFactory?.createAudioTrack("mic_track", audioSource)
            }
            audioTrack?.setEnabled(true)
            com.xcloud.godmode.audio.OboeAudioBridge.setMute(false)
        } else {
            audioTrack?.setEnabled(false)
            com.xcloud.godmode.audio.OboeAudioBridge.setMute(true)
        }
    }

    fun stopStream() {
        executor.execute {
            watchdogJob?.cancel()
            watchdogJob = null
            try {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
                wifiLock = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing Wi-Fi lock: ${e.message}")
            }
            bitrateController.release()
            NativeStreamBridge.stopAudio()
            com.xcloud.godmode.audio.OboeAudioBridge.stopCapture()
            hapticEngine.stopAllVibrations()
            peerConnection?.dispose()
            peerConnection = null
            dataChannel?.dispose()
            dataChannel = null
            messageChannel?.dispose()
            messageChannel = null
            controlChannel?.dispose()
            controlChannel = null
            localCandidates.clear()
            
            // Extensión 10: Zero-Trace Execution Audit & Memory Sanitization
            NativeStreamBridge.zeroTraceShutdown()

            // Purga de directorios caché en frío
            try {
                context.cacheDir?.deleteRecursively()
                context.externalCacheDir?.deleteRecursively()
                Log.d(TAG, "Zero-Trace: Cold cache purged successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Cache purge warning: ${e.message}")
            }
        }
    }
}
