package com.xcloud.godmode.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LiveTelemetryMetrics(
    val rttMs: Int = 0,
    val fps: Int = 0,
    val packetLossPct: Float = 0f,
    val bitrateKbps: Int = 0,
    val activeEngineId: Int = 3,
    val stunKeepAlives: Int = 0,
    val hwFencesProcessed: Int = 0,
    val aiUpscalingActive: Boolean = true,
    val thermalStatus: Int = 0,
    val clockDriftUs: Long = 0L,
    val lastFrameTimestampNano: Long = 0L,
    val inputToDisplayMs: Int = 0
)

/**
 * TelemetryBridge: Zero-Copy Direct Memory Telemetry Bridge between C++ NDK and Kotlin Compose.
 * Leverages a shared Direct ByteBuffer [64 bytes] to eliminate JNI call overhead per frame.
 * Layout:
 * [0..31: Legacy/Core metrics], [32..35: Thermal], [36..43: Clock Drift], [44..51: Last Frame Presentation Nano], [52..55: Input-to-Display Ms]
 */
object TelemetryBridge {
    private val directBuffer: ByteBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)

    init {
        try {
            NativeStreamBridge.bindTelemetryBuffer(directBuffer)
        } catch (_: Throwable) {}
    }

    val telemetryFlow: Flow<LiveTelemetryMetrics> = flow {
        while (currentCoroutineContext().isActive) {
            directBuffer.position(0)
            val rtt = directBuffer.getInt()
            val fps = directBuffer.getInt()
            val packetLossInt = directBuffer.getInt()
            val bitrate = directBuffer.getInt()
            val activeEngine = directBuffer.getInt().let { if (it in 1..6) it else 3 }
            val stunKeepAlives = directBuffer.getInt()
            val hwFences = directBuffer.getInt()
            val aiActive = directBuffer.getInt() != 0
            val thermal = directBuffer.getInt()
            val drift = directBuffer.getLong()
            val frameTimestamp = directBuffer.getLong()
            val inputToDisplay = directBuffer.getInt()

            val packetLossPct = (packetLossInt / 100f).coerceIn(0f, 100f)

            emit(
                LiveTelemetryMetrics(
                    rttMs = rtt,
                    fps = fps,
                    packetLossPct = packetLossPct,
                    bitrateKbps = bitrate,
                    activeEngineId = activeEngine,
                    stunKeepAlives = stunKeepAlives,
                    hwFencesProcessed = hwFences,
                    aiUpscalingActive = aiActive,
                    thermalStatus = thermal,
                    clockDriftUs = drift,
                    lastFrameTimestampNano = frameTimestamp,
                    inputToDisplayMs = inputToDisplay
                )
            )
            delay(16) // ~60 Hz update rate for Jetpack Compose UI
        }
    }.flowOn(Dispatchers.Default)

    fun updateCoreStats(
        rttMs: Int,
        fps: Int,
        packetLossPct: Float,
        bitrateKbps: Int,
        activeEngineId: Int = 3
    ) {
        directBuffer.position(0)
        directBuffer.putInt(rttMs)
        directBuffer.putInt(fps)
        directBuffer.putInt((packetLossPct * 100).toInt())
        directBuffer.putInt(bitrateKbps)
        directBuffer.putInt(activeEngineId)
    }

    fun updateMetricsLocally(
        rttMs: Int,
        fps: Int,
        packetLossPct: Float,
        bitrateKbps: Int,
        activeEngineId: Int = 3,
        stunKeepAlives: Int = 0,
        hwFencesProcessed: Int = 0,
        aiUpscalingActive: Boolean = true,
        thermalStatus: Int = 0,
        clockDriftUs: Long = 0L,
        lastFrameTimestampNano: Long = 0L,
        inputToDisplayMs: Int = 0
    ) {
        directBuffer.position(0)
        directBuffer.putInt(rttMs)
        directBuffer.putInt(fps)
        directBuffer.putInt((packetLossPct * 100).toInt())
        directBuffer.putInt(bitrateKbps)
        directBuffer.putInt(activeEngineId)
        directBuffer.putInt(stunKeepAlives)
        directBuffer.putInt(hwFencesProcessed)
        directBuffer.putInt(if (aiUpscalingActive) 1 else 0)
        directBuffer.putInt(thermalStatus)
        directBuffer.putLong(clockDriftUs)
        directBuffer.putLong(lastFrameTimestampNano)
        directBuffer.putInt(inputToDisplayMs)
    }
}

