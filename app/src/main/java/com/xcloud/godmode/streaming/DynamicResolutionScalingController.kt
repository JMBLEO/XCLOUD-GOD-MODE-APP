package com.xcloud.godmode.streaming

import kotlin.math.max
import kotlin.math.min

/**
 * DynamicResolutionScalingController: Evaluates network health and requests resolution adjustments.
 * Pairs with AMD FSR 1.0 (EASU + RCAS) in the native layer to maintain visual quality.
 */
class DynamicResolutionScalingController(
    private val onResolutionChangeRequested: (targetWidth: Int, targetHeight: Int) -> Unit
) {
    private var currentWidth = 1920
    private var currentHeight = 1080
    private var consecutiveHighPacketLoss = 0
    private var consecutiveLowLatency = 0

    fun evaluateNetworkMetrics(packetLossRatio: Float, rttMs: Int) {
        // High loss or high latency: Step down
        if (packetLossRatio > 0.04f || rttMs > 120) {
            consecutiveHighPacketLoss++
            consecutiveLowLatency = 0
            if (consecutiveHighPacketLoss >= 3 && currentWidth > 1280) {
                stepDownResolution()
                consecutiveHighPacketLoss = 0
            }
        } 
        // Stable and low latency: Step up
        else if (packetLossRatio < 0.005f && rttMs < 50) {
            consecutiveLowLatency++
            consecutiveHighPacketLoss = 0
            if (consecutiveLowLatency >= 10 && currentWidth < 3840) {
                stepUpResolution()
                consecutiveLowLatency = 0
            }
        }
    }

    private fun stepDownResolution() {
        when (currentWidth) {
            3840 -> updateRes(2560, 1440)
            2560 -> updateRes(1920, 1080)
            1920 -> updateRes(1280, 720)
        }
    }

    private fun stepUpResolution() {
        when (currentWidth) {
            1280 -> updateRes(1920, 1080)
            1920 -> updateRes(2560, 1440)
            2560 -> updateRes(3840, 2160)
        }
    }

    private fun updateRes(w: Int, h: Int) {
        if (currentWidth == w && currentHeight == h) return
        currentWidth = w
        currentHeight = h
        onResolutionChangeRequested(w, h)
    }
}
