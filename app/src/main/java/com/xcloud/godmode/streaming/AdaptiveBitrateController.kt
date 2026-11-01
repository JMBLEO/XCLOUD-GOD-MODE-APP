package com.xcloud.godmode.streaming

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AdaptiveBitrateController: Industrial PD (Proportional-Derivative) Congestion Controller
 * and Hardware Thermal Throttling Guard for the God Mode streaming core.
 *
 * Reacts in <200ms to packet loss spikes (>3.5%) by lowering bitrate and dropping SVC layers,
 * and monitors device thermal status to avoid SoC throttling.
 */
class AdaptiveBitrateController(
    private val context: Context,
    private val baseMaxBitrateKbps: Int = 35000, // 35 Mbps "God Mode"
    private val minBitrateKbps: Int = 6000       // 6 Mbps minimum floor
) {
    private val TAG = "AdaptiveBitrateCtrl"

    // Proportional & Derivative gains
    private val kp = 1800f // kbps reduction per 1% loss
    private val kd = 900f  // kbps reduction per 1% loss/sec rate of change

    private var previousLossPct = 0f
    private var previousRttMs = 0
    private var lastEvaluationTimeMs = System.currentTimeMillis()

    private val _currentBitrateKbps = MutableStateFlow(baseMaxBitrateKbps)
    val currentBitrateKbps: StateFlow<Int> = _currentBitrateKbps.asStateFlow()

    private val _shouldDropEnhancementLayers = MutableStateFlow(false)
    val shouldDropEnhancementLayers: StateFlow<Boolean> = _shouldDropEnhancementLayers.asStateFlow()

    private val _shouldOffloadNeuralScaler = MutableStateFlow(false)
    val shouldOffloadNeuralScaler: StateFlow<Boolean> = _shouldOffloadNeuralScaler.asStateFlow()

    private var thermalCapKbps = baseMaxBitrateKbps
    private var thermalListener: Any? = null

    init {
        setupThermalGuard()
    }

    /**
     * Ingests real-time RTCP feedback, packet loss telemetry, and RTT.
     * Evaluates in <200ms intervals with Buffer Bloat anticipation (dRTT/dt).
     */
    fun onTelemetryTick(packetLossPct: Float, rttMs: Int) {
        val now = System.currentTimeMillis()
        val dtMs = (now - lastEvaluationTimeMs).coerceAtLeast(1L)
        lastEvaluationTimeMs = now

        val dtSec = dtMs / 1000f
        val lossDelta = packetLossPct - previousLossPct
        val dLossDt = if (dtSec > 0.001f) lossDelta / dtSec else 0f
        previousLossPct = packetLossPct

        // Extensión 1: Buffer Bloat Mitigation & Packet Burst Tracing
        val rttDelta = rttMs - previousRttMs
        val dRttDt = if (dtSec > 0.001f && previousRttMs > 0) rttDelta / dtSec else 0f
        previousRttMs = rttMs

        var newBitrate = _currentBitrateKbps.value

        // Preemptive Buffer Bloat trigger: dRTT/dt > 30 ms/s indicates queue growth in intermediate hops
        val isBufferBloat = dRttDt > 30f && rttMs > 60

        if (packetLossPct > 3.5f) {
            // High loss detected: activate SVC drop and PD bitrate reduction
            _shouldDropEnhancementLayers.value = true

            val reduction = (kp * packetLossPct + kd * dLossDt).toInt().coerceAtLeast(1000)
            newBitrate = (newBitrate - reduction).coerceIn(minBitrateKbps, thermalCapKbps)
            Log.d(TAG, "Congestion spike: Loss=${packetLossPct}%, dLoss/dt=${dLossDt}. Bitrate reduced to ${newBitrate} kbps")
        } else if (isBufferBloat) {
            // Anticipatory Buffer Bloat mitigation: reduce bitrate 50ms early before packet loss occurs
            val preemptiveCut = (dRttDt * 25f).toInt().coerceIn(1000, 4000)
            newBitrate = (newBitrate - preemptiveCut).coerceIn(minBitrateKbps, thermalCapKbps)
            Log.i(TAG, "Buffer Bloat detected (dRTT/dt=${dRttDt} ms/s). Preemptively reducing bitrate to ${newBitrate} kbps")
        } else if (packetLossPct < 1.0f && rttMs < 80) {
            // Network clean: disable SVC drop and additive bitrate recovery
            _shouldDropEnhancementLayers.value = false
            if (newBitrate < thermalCapKbps) {
                // Additive increase: +500 kbps per tick
                newBitrate = (newBitrate + 500).coerceAtMost(thermalCapKbps)
            }
        } else {
            // Neutral zone: 1.0% - 3.5% loss
            _shouldDropEnhancementLayers.value = false
        }

        _currentBitrateKbps.value = newBitrate
    }

    /**
     * Configures the Android PowerManager Thermal Status Listener (API 29+).
     * Automatically scales bitrate and offloads neural scaler (Extensión 6) when SoC temperatures escalate.
     */
    private fun setupThermalGuard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null) {
                    val listener = PowerManager.OnThermalStatusChangedListener { status ->
                        when (status) {
                            PowerManager.THERMAL_STATUS_SEVERE,
                            PowerManager.THERMAL_STATUS_CRITICAL,
                            PowerManager.THERMAL_STATUS_EMERGENCY -> {
                                Log.w(TAG, "Thermal Guard ALERT: Severe/Critical thermal status ($status). Throttling to 18 Mbps & offloading NN scaler")
                                thermalCapKbps = 18000
                                _shouldOffloadNeuralScaler.value = true
                                if (_currentBitrateKbps.value > thermalCapKbps) {
                                    _currentBitrateKbps.value = thermalCapKbps
                                }
                            }
                            PowerManager.THERMAL_STATUS_MODERATE -> {
                                Log.i(TAG, "Thermal Guard: Moderate heat ($status). Throttling cap to 24 Mbps & enabling NN Lite")
                                thermalCapKbps = 24000
                                _shouldOffloadNeuralScaler.value = true
                                if (_currentBitrateKbps.value > thermalCapKbps) {
                                    _currentBitrateKbps.value = thermalCapKbps
                                }
                            }
                            else -> {
                                // Normal / Light status
                                thermalCapKbps = baseMaxBitrateKbps
                                _shouldOffloadNeuralScaler.value = false
                            }
                        }
                    }
                    powerManager.addThermalStatusListener(listener)
                    thermalListener = listener
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to register thermal status listener: ${e.message}")
            }
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener
                if (powerManager != null && listener != null) {
                    powerManager.removeThermalStatusListener(listener)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing thermal listener: ${e.message}")
            }
        }
    }
}
