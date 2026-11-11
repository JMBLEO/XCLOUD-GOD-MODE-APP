package com.xcloud.godmode.streaming

import android.view.Surface

enum class StreamEngineType(val id: Int, val title: String, val subtitle: String) {
    MOTOR_1_NANO_RUST_VK_IMMEDIATE(1, "Motor 1: NANO Rust + Vulkan Immediate", "Rust Core + VK_PRESENT_MODE_IMMEDIATE_KHR"),
    MOTOR_2_NANO_VK_VIDEO_ASIC(2, "Motor 2: NANO Vulkan Video ASIC", "VK_KHR_video_decode_queue (H265/AV1)"),
    MOTOR_3_NANO_C2_VK_COMPUTE_FSR(3, "Motor 3: NANO Codec2 + Vulkan FSR", "AMediaCodec + AHardwareBuffer + AMD FSR 1.0"),
    MOTOR_4_NANO_C2_HWC2_DIRECT(4, "Motor 4: NANO Codec2 + HWC2 Direct", "HWC2 Direct Overlay Bypass (0ms SurfaceFlinger)"),
    MOTOR_5_NANO_C2_NPU_TENSOR(5, "Motor 5: NANO Codec2 + NPU Tensor", "Qualcomm Hexagon / Google Edge TPU / MediaTek APU"),
    MOTOR_6_NANO_C2_EGL_OES_ZEROCOPY(6, "Motor 6: NANO Codec2 + EGL OES", "EGLImageKHR + Zero-Copy OES + High Priority Context"),
    MOTOR_7_PROJECT_NANO_V3_RAW_UDP(7, "Motor 7: Project NANO V3 Raw UDP", "Native Binary Protocol V3 (Raw UDP Socket)"),
    MOTOR_8_PROJECT_NANO_V4_CLOUD_DIRECT(8, "Motor 8: Project NANO V4 Cloud Direct", "Optimized NAL/OBU alignment for Azure Xbox Series X"),
    MOTOR_9_SMARTGLASS_GAMESTREAM_CORE(9, "Motor 9: SmartGlass Gamestream Core", "Xbox SmartGlass Binary Transport + AES-128-GCM"),
    MOTOR_10_AZURE_MOQ_QUIC_NATIVE(10, "Motor 10: Azure MoQ QUIC Native", "Media-over-QUIC (RFC 9000) + 0-RTT Reconnection"),
    MOTOR_11_GSSV_NATIVE_SRTP_RINGBUFFER(11, "Motor 11: GSSV Native SRTP RingBuffer", "Direct SRTP Unpack + Vyukov Lock-Free C++20 RingBuffer"),
    MOTOR_12_XHOME_NANO_CONSOLE_BRIDGE(12, "Motor 12: XHome NANO Console Bridge", "Unified Remote Play + Cloud Gaming Signature"),
    MOTOR_13_DIRECT_GPU_DMA_STREAMER(13, "Motor 13: Direct GPU DMA Streamer", "DMA compressed payloads to VK_MEMORY_PROPERTY_DEVICE_LOCAL"),
    MOTOR_14_AZURE_ANYCAST_MMSG_SOCKET(14, "Motor 14: Azure Anycast MMSG Socket", "Linux recvmmsg + SO_BUSY_POLL + DSCP EF tagging"),
    MOTOR_15_FAST_NANO_PACKETIZER(15, "Motor 15: Fast NANO Packetizer", "Real-time SCHED_FIFO frame assembly (Ultra-Low Latency Core)"),
    MOTOR_16_HIGH_BITRATE_TUNNEL(16, "Motor 16: Native High Bitrate Tunnel", "High Performance Profile (150 Mbps / 4K Support)"),
    MOTOR_17_SECURE_NATIVE_HANDSHAKE(17, "Motor 17: Secure Native Handshake", "TLS 1.3 session negotiation (Native Cloud Stream)"),
    MOTOR_18_LIBRENANO_4CHANNEL_MUX(18, "Motor 18: LibreNANO 4-Channel Mux", "Lock-free determinism for Video/Audio/Input/QoS"),
    MOTOR_19_AMD_FSR_1_EASU_RCAS_VULKAN(19, "Motor 19: AMD FSR 1.0 (EASU + RCAS)", "Official FidelityFX Spatial Upscaling in Vulkan Compute"),
    MOTOR_20_AMD_FSR_3_TEMPORAL_COMPUTE(20, "Motor 20: AMD FSR 3.0 (Temporal)", "Temporal compute reconstruction via Block Motion Vectors"),
    MOTOR_21_QUALCOMM_SNAPDRAGON_GSR_V2(21, "Motor 21: Qualcomm Snapdragon GSR v2", "Single-pass 12-Tap Lanczos fp16 (Adreno Optimized)"),
    MOTOR_22_ARM_ASR_IMMORTALIS_ENGINE(22, "Motor 22: ARM ASR Immortalis Engine", "Accuracy Super Resolution + asimdhp vector instructions"),
    MOTOR_23_ANDROID_TV_TUNNELED_HDR10PLUS(23, "Motor 23: Android TV Tunneled HDR10+", "FEATURE_TunneledPlayback + HDR10+/DV Passthrough"),
    MOTOR_24_AAUDIO_MMAP_CLOCK_LOCKED(24, "Motor 24: AAudio MMAP Clock-Locked", "Shared Memory Audio + Microsecond Video Sync");

    companion object {
        fun fromId(id: Int): StreamEngineType = entries.find { it.id == id } ?: MOTOR_3_NANO_C2_VK_COMPUTE_FSR
    }
}

/**
 * NativeStreamBridge: Interface to the high-performance C++20/Rust streaming core.
 * Handles Zero-Copy video injection, SDP rewriting, and real-time telemetry.
 */
object NativeStreamBridge {
    init {
        System.loadLibrary("godmode_native")
        initPipeline()
    }

    private external fun initPipeline(): Boolean

    /**
     * Initializes the specified native engine (1, 2, or 3) with direct zero-copy surface presentation.
     */
    external fun initStreamPipeline(
        surface: Surface,
        engineType: Int,
        width: Int,
        height: Int,
        enableAiUpscale: Boolean
    ): Boolean

    external fun onSurfaceResized(width: Int, height: Int)

    external fun shutdownStreamPipeline()

    /**
     * Retrieves the currently active native engine ID (1..6) after fail-safe cascade resolution.
     */
    external fun getActiveEngineId(): Int

    /**
     * Dispatches an incoming RTP video packet to the native CompleteAccessUnitAssembler.
     */
    external fun dispatchRtpPacket(data: ByteArray, len: Int, timestamp: Long, marker: Boolean): Boolean

    /**
     * Attaches a native surface to the streaming pipeline.
     * @param surface The Android Surface from a SurfaceView or TextureView.
     * @param profile The codec profile (0=H264_SVC, 1=HEVC, 2=AV1).
     * @param width Native width.
     * @param height Native height.
     */
    external fun attachSurface(surface: Surface, profile: Int, width: Int, height: Int): Boolean

    /**
     * Detaches the surface and shuts down the hardware decoder.
     */
    external fun detachSurface()

    /**
     * Injects an encoded video frame (Access Unit) directly into the hardware decoder.
     * @param data The raw bitstream data.
     * @param size Size of the data.
     * @param serverDataKey Telemetry key from Microsoft extension.
     * @param packetLoss Current network packet loss percentage.
     */
    external fun injectAccessUnit(data: ByteArray, size: Int, serverDataKey: Int, packetLoss: Float): Boolean

    /**
     * Rewrites the SDP offer to force high bitrate and prioritize codecs.
     */
    external fun rewriteSdp(rawSdp: String, maxBitrateKbps: Int): String

    /**
     * Cleans up EGL surface bindings to prevent "ANativeWindow already connected to another API".
     */
    external fun cleanupEgl()

    /**
     * Smooths mouse aiming deltas using native 1D linear Kalman filters.
     */
    external fun smoothMouseDelta(deltaX: Float, deltaY: Float): FloatArray

    /**
     * Pushes a raw controller/input packet to the native C++20 lock-free SPSC ring buffer.
     */
    external fun pushRawInput(data: ByteArray, size: Int): Boolean

    /**
     * Pops a raw controller/input packet from the native lock-free SPSC ring buffer.
     */
    external fun popRawInput(): ByteArray?

    /**
     * Zero-Trace Execution Audit: Overwrites native memory buffers, decoders, and ring buffers with zeros.
     */
    external fun zeroTraceShutdown()

    /**
     * Sets a presentation time stamp offset in microseconds to compensate for Bluetooth audio latency.
     */
    external fun setPtsOffsetUs(offsetUs: Long)

    /**
     * Stores a sensitive session token in the native volatile C++ memory vault.
     */
    external fun storeSecureToken(data: ByteArray)

    /**
     * Purges the native secure memory vault with explicit zeroing.
     */
    external fun purgeSecureVault()

    /**
     * Starts the high-performance C++ Oboe audio pipeline.
     */
    external fun startAudio(): Boolean

    /**
     * Stops the Oboe audio pipeline.
     */
    external fun stopAudio()

    /**
     * Injects decoded PCM audio frames (48kHz Stereo) into the Oboe pipeline.
     */
    external fun injectAudio(data: ShortArray, numFrames: Int)

    /**
     * Injects decoded float PCM audio frames (48kHz Stereo) into the Oboe pipeline.
     */
    external fun injectAudioFloat(data: FloatArray, numFrames: Int)

    /**
     * Synchronizes audio presentation clock with video PTS to maintain zero-click A/V sync.
     */
    external fun syncAudioClock(audioClockUs: Long, videoPtsUs: Long)

    /**
     * Starts the ultra-low latency AAudio engine (direct C++ JNI) or Oboe hybrid.
     * @param framesPerBuffer Target buffer size (e.g. 128)
     */
    external fun startUltraLowLatencyAudio(framesPerBuffer: Int): Boolean

    /**
     * Injects PCM frames with precise timestamps for AV Sync.
     */
    external fun injectAudioWithTimestamp(data: ShortArray, numFrames: Int, presentationTimeNs: Long)

    /**
     * Records audio UDP packet transmission and arrival timestamps for RFC 3550 jitter calculation.
     */
    external fun recordAudioPacketArrival(sendTimeUs: Long, arrivalTimeUs: Long)

    /**
     * Retrieves current end-to-end audio output latency in milliseconds from Oboe.
     */
    external fun getAudioLatencyMs(): Float

    /**
     * Retrieves current calculated audio network jitter in milliseconds.
     */
    external fun getAudioJitterMs(): Float

    /**
     * Retrieves current audio jitter buffer queue depth in milliseconds.
     */
    external fun getAudioBufferDepthMs(): Float

    /**
     * Binds a 48-byte Direct ByteBuffer for zero-copy JNI telemetry transfer:
     * [0..31: Legacy/Core metrics], [32..35: Thermal], [36..43: Clock Drift]
     */
    external fun bindTelemetryBuffer(buffer: java.nio.ByteBuffer)

    /**
     * Reports a packet acknowledgment to the native BBR pacing controller.
     */
    external fun reportPacketAck(bytes: Int, rttUs: Double)

    /**
     * Gets the target pacing rate in bits per second from the native BBR controller.
     */
    external fun getTargetPacingRateBps(ultra: Boolean): Double

    /**
     * Debug: Simulates a Vulkan Device Lost event to trigger the fail-safe cascade.
     */
    external fun simulateDeviceLost()

    /**
     * Debug: Simulates a Thermal Critical state to test QoS adaptation.
     */
    external fun forceThermalCritical()

    /**
     * Starts the native C++ stream reactor (ICE + DTLS + SRTP + SCTP) for zero-latency 120FPS throughput.
     */
    external fun startNativeReactor(
        remoteIp: String,
        remotePort: Int,
        remoteUfrag: String,
        remotePwd: String,
        localUfrag: String,
        enableIPv6: Boolean
    ): Boolean

    /**
     * Stops the native C++ stream reactor.
     */
    external fun stopNativeReactor()

    /**
     * Inyecta directamente las llaves maestras SRTP desde /configuration para decodificación inmediata sin esperar DTLS.
     */
    external fun injectDirectSrtpKeys(keyBase64: String, saltBase64: String): Boolean

    /**
     * Retrieves the measured STUN round-trip time metrics from NativeIceDtlsSrtpBridge:
     * [0: currentRttMs, 1: minRttMs, 2: maxRttMs, 3: smoothedRttMs, 4: jitterMs, 5: totalSuccessReceived]
     */
    external fun getStunLatencyMetrics(): FloatArray?

    /**
     * Enforces a hard frame rate cap in the native rendering pipeline to mitigate thermal stress.
     */
    external fun setFrameRateCap(fps: Int)

    /**
     * Triggers a Picture Loss Indication (PLI) over RTCP to request a new keyframe from the server.
     */
    external fun requestPli()

    /**
     * Triggers a keyframe request via the native transport layer.
     */
    external fun requestKeyframe()

    /**
     * Starts the native transport pipeline by gathering local ICE candidates.
     */
    external fun startTransportPipeline(localUfrag: String, localPwd: String)

    /**
     * Completes the transport handshake with remote credentials and candidates.
     */
    external fun setRemoteTransportParameters(ufrag: String, pwd: String, candidates: Array<String>)

    /**
     * Pins the current thread to high-performance cores and elevates priority to SCHED_FIFO.
     */
    external fun pinCurrentThreadToPerformanceTier(coreMask: Int)
}


