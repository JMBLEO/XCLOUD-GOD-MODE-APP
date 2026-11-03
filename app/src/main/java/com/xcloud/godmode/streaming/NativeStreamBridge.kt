package com.xcloud.godmode.streaming

import android.view.Surface

enum class StreamEngineType(val id: Int, val title: String, val subtitle: String) {
    ENGINE_1_RUST_SECURE_VULKAN(1, "Motor 1: Rust Secure + Vulkan", "Rust Zero-Trust + Decodificación Multi-hilo + Vulkan Zero-Copy"),
    ENGINE_2_VULKAN_VIDEO_ASIC(2, "Motor 2: Vulkan Video ASIC Direct", "VK_KHR_video_decode_queue directo en silicio (0ms auto-fallback)"),
    ENGINE_3_MEDIACODEC_AI_NPU(3, "Motor 3: AMediaCodec + NPU AI", "AMediaCodec Asíncrono + AHardwareBuffer + Super-Resolución AI"),
    ENGINE_4_HWC2_SURFACE_TRANSACTION_AI(4, "Motor 4: HWC2 Direct Overlay AI", "ASurfaceTransaction + HWC Direct Plane + DPU AISR (Snapdragon/MiraVision)"),
    ENGINE_5_MEDIACODEC_NPU_NNAPI_AI(5, "Motor 5: AMediaCodec + NNAPI AI", "HardwareBuffer + NPU Tensor Cores + Reconstrucción Neuronal"),
    ENGINE_6_MEDIACODEC_EGLIMAGE_OES_AI(6, "Motor 6: EGLImage Zero-Copy OES AI", "EGLImageKHR Direct + GLES 3.2 OES + Shader AI Laplaciano");

    companion object {
        fun fromId(id: Int): StreamEngineType = entries.find { it.id == id } ?: ENGINE_3_MEDIACODEC_AI_NPU
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
}

