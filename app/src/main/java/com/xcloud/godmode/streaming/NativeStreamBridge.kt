package com.xcloud.godmode.streaming

import android.view.Surface

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
}

