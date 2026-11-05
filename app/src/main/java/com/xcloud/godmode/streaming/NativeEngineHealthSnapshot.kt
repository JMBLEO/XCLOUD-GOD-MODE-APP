package com.xcloud.godmode.streaming

/**
 * NativeEngineHealthSnapshot: Compact typed representation of native engine health
 * for real-time telemetry, HUD rendering, and supervisor monitoring.
 */
data class NativeEngineHealthSnapshot(
    val framesReceived: Long,
    val framesPresented: Long,
    val frameDrops: Long,
    val decoderResets: Long,
    val rendererResets: Long,
    val gpuUsage: Float,
    val memoryPressure: Float,
    val thermalState: Float,
    val decoderHealthy: Boolean,
    val rendererHealthy: Boolean,
    // HealthScore compacto (0 a 100)
    val scoreNetwork: Byte,
    val scoreDecoder: Byte,
    val scoreRenderer: Byte,
    val scoreThermal: Byte,
    val scoreMemory: Byte,
    val scoreTotal: Byte
)
