package com.xcloud.godmode.streaming

import android.content.Context
import android.util.Log
import android.view.Surface
import com.xcloud.godmode.haptics.HapticTelemetryPacket
import com.xcloud.godmode.haptics.UnifiedHapticTelemetricEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * NativeStreamer: High-level Kotlin wrapper binding the C++ native socket, AChoreographer
 * VSYNC frame pacing, Oboe audio pipeline, 250Hz input ring buffer, and live telemetry to GodModeHub.
 */
class NativeStreamer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val TAG = "NativeStreamer"
    private val hapticEngine = UnifiedHapticTelemetricEngine(context, scope)

    val telemetryFlow: Flow<LiveTelemetryMetrics> = TelemetryBridge.telemetryFlow

    fun attachSurface(surface: Surface, codecProfile: Int = 1, width: Int = 1920, height: Int = 1080): Boolean {
        Log.d(TAG, "Attaching Zero-Copy Surface (${width}x${height}, Profile: $codecProfile)")
        return NativeStreamBridge.attachSurface(surface, codecProfile, width, height)
    }

    fun detachSurface() {
        Log.d(TAG, "Detaching Zero-Copy Surface")
        NativeStreamBridge.detachSurface()
    }

    fun startAudioEngine(): Boolean {
        Log.d(TAG, "Starting Oboe Native Audio Engine")
        return NativeStreamBridge.startAudio()
    }

    fun stopAudioEngine() {
        Log.d(TAG, "Stopping Oboe Native Audio Engine")
        NativeStreamBridge.stopAudio()
    }

    fun injectPcmAudio(pcmFrames: ShortArray) {
        NativeStreamBridge.injectAudio(pcmFrames, pcmFrames.size / 2)
    }

    fun injectFloatAudio(pcmFrames: FloatArray) {
        NativeStreamBridge.injectAudioFloat(pcmFrames, pcmFrames.size / 2)
    }

    fun syncAudioClock(audioClockUs: Long, videoPtsUs: Long) {
        NativeStreamBridge.syncAudioClock(audioClockUs, videoPtsUs)
    }

    fun recordAudioPacketArrival(sendTimeUs: Long, arrivalTimeUs: Long) {
        NativeStreamBridge.recordAudioPacketArrival(sendTimeUs, arrivalTimeUs)
    }

    fun getAudioLatencyMs(): Float = NativeStreamBridge.getAudioLatencyMs()

    fun getAudioJitterMs(): Float = NativeStreamBridge.getAudioJitterMs()

    fun getAudioBufferDepthMs(): Float = NativeStreamBridge.getAudioBufferDepthMs()

    fun pushRawControllerInput(data: ByteArray): Boolean {
        return NativeStreamBridge.pushRawInput(data, data.size)
    }

    fun triggerQuadHaptics(leftGrip: Float, rightGrip: Float, leftTrigger: Float, rightTrigger: Float, durationMs: Int = 50) {
        hapticEngine.emitHapticEvent(
            HapticTelemetryPacket(
                leftGripMotor = leftGrip,
                rightGripMotor = rightGrip,
                leftTriggerMotor = leftTrigger,
                rightTriggerMotor = rightTrigger,
                durationMs = durationMs
            )
        )
    }

    fun zeroTraceShutdown() {
        Log.d(TAG, "Executing Zero-Trace Native Memory Sanitization")
        stopAudioEngine()
        detachSurface()
        hapticEngine.release()
        NativeStreamBridge.zeroTraceShutdown()
    }

    external fun bindNativeSocketAndTelemetry(buffer: java.nio.ByteBuffer): Boolean
    external fun updateNativeTelemetry(rtt: Int, fps: Int, packetLoss: Float, bitrate: Int)
    external fun handleAsyncOutputFormatChanged(newWidth: Int, newHeight: Int)

    companion object {
        private var currentBitrate: Int = 15_000_000 // 15 Mbps default

        fun getCurrentTargetBitrate(): Int = currentBitrate

        fun requestBitrateChange(newBitrate: Int) {
            currentBitrate = newBitrate.coerceIn(1_000_000, 50_000_000)
            Log.d("NativeStreamer", "Bitrate adjusted to: $currentBitrate bps")
        }

        fun requestBitrateIncrease(delta: Int) {
            currentBitrate = (currentBitrate + delta).coerceIn(1_000_000, 50_000_000)
            Log.d("NativeStreamer", "Bitrate increased to: $currentBitrate bps")
        }
    }
}
