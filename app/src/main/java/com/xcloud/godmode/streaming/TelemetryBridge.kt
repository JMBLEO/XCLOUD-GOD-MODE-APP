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
    val bitrateKbps: Int = 0
)

/**
 * TelemetryBridge: Zero-Copy Direct Memory Telemetry Bridge between C++ NDK and Kotlin Compose.
 * Leverages a shared Direct ByteBuffer [16 bytes] to eliminate JNI call overhead per frame.
 * Layout: [0..3: RTT ms], [4..7: FPS], [8..11: Packet Loss % x 100], [12..15: Bitrate Kbps]
 */
object TelemetryBridge {
    private val directBuffer: ByteBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)

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

            val packetLossPct = (packetLossInt / 100f).coerceIn(0f, 100f)

            emit(
                LiveTelemetryMetrics(
                    rttMs = rtt,
                    fps = fps,
                    packetLossPct = packetLossPct,
                    bitrateKbps = bitrate
                )
            )
            delay(16) // ~60 Hz update rate for Jetpack Compose UI
        }
    }.flowOn(Dispatchers.Default)

    fun updateMetricsLocally(rttMs: Int, fps: Int, packetLossPct: Float, bitrateKbps: Int) {
        directBuffer.position(0)
        directBuffer.putInt(rttMs)
        directBuffer.putInt(fps)
        directBuffer.putInt((packetLossPct * 100).toInt())
        directBuffer.putInt(bitrateKbps)
    }
}
