package com.xcloud.godmode.streaming

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * StreamHandshakeWatchdog: Monitor de inicio y de silencio UDP en caliente (Fase 2).
 *
 * Funciones clave:
 * 1. Detección de arranque: Si no llega el primer frame en 450-850ms, dispara PLI/Keyframe inicial.
 * 2. Reconexión en caliente (Hot Reconnect): Si hay silencio UDP > 1500ms durante la partida,
 *    mantiene intacto el sessionId en Azure y dispara inmediatamente una ráfaga de PLI
 *    y re-sincronización de transporte sin desconectar al usuario ni destruir la sesión.
 */
class StreamHandshakeWatchdog(
    private val onTriggerPli: () -> Unit,
    private val onTriggerKeyframe: () -> Unit,
    private val onHotReconnectSilenceDetected: (() -> Unit)? = null
) {
    private val TAG = "StreamWatchdog"
    private val handler = Handler(Looper.getMainLooper())
    private var isFrameReceived = false
    private var watchdogActive = false

    @Volatile
    private var lastPacketTimestampMs = 0L
    private var silenceMonitorActive = false

    companion object {
        private const val UDP_SILENCE_THRESHOLD_MS = 1500L
        private const val SILENCE_POLL_INTERVAL_MS = 300L
    }

    fun start(timeoutMs: Long = 850L) {
        if (watchdogActive) return
        watchdogActive = true
        isFrameReceived = false
        lastPacketTimestampMs = System.currentTimeMillis()
        Log.d(TAG, "Iniciando watchdog de arranque (timeout: $timeoutMs ms)")

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isFrameReceived && watchdogActive) {
                    Log.w(TAG, "Watchdog activado: Sin primer frame. Solicitando PLI/Keyframe de arranque...")
                    onTriggerPli()
                    onTriggerKeyframe()
                    handler.postDelayed(this, timeoutMs)
                }
            }
        }, timeoutMs)
    }

    fun notifyFirstFrame() {
        if (!isFrameReceived) {
            Log.d(TAG, "Primer fotograma renderizado con éxito.")
        }
        isFrameReceived = true
        watchdogActive = false
        handler.removeCallbacksAndMessages(null)
        startContinuousSilenceMonitor()
    }

    fun notifyPacketReceived() {
        lastPacketTimestampMs = System.currentTimeMillis()
    }

    /**
     * Monitor de Silencio UDP continuo (>1500ms).
     * Si la red se congela momentáneamente, rescata el stream con PLI inmediato
     * conservando exactamente el mismo sessionId.
     */
    private fun startContinuousSilenceMonitor() {
        if (silenceMonitorActive) return
        silenceMonitorActive = true
        lastPacketTimestampMs = System.currentTimeMillis()

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!silenceMonitorActive) return

                val now = System.currentTimeMillis()
                val elapsedSinceLastPacket = now - lastPacketTimestampMs

                if (elapsedSinceLastPacket > UDP_SILENCE_THRESHOLD_MS) {
                    Log.w(TAG, "Silencio UDP detectado (${elapsedSinceLastPacket}ms > 1500ms). Ejecutando reconexión en caliente...")
                    onTriggerPli()
                    onTriggerKeyframe()
                    onHotReconnectSilenceDetected?.invoke()
                    // Resetear para no spamear continuamente en el mismo segundo
                    lastPacketTimestampMs = now - 500L
                }

                handler.postDelayed(this, SILENCE_POLL_INTERVAL_MS)
            }
        }, SILENCE_POLL_INTERVAL_MS)
    }

    fun stop() {
        watchdogActive = false
        silenceMonitorActive = false
        handler.removeCallbacksAndMessages(null)
    }
}
