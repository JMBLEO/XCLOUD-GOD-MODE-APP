package com.xcloud.godmode.streaming

import android.util.Log

object EdgeAiQoS {
    private const val TAG = "EdgeAiQoS"

    fun predictAndMitigateCongestion(recentPings: List<Int>) {
        if (recentPings.size < 10) return
        
        val average = recentPings.average()
        val variance = recentPings.map { (it - average) * (it - average) }.average()
        
        // If variance is high (ping spiking, e.g. 20ms -> 45ms -> 80ms)
        if (variance > 150.0 && average > 40.0) {
            Log.w(TAG, "Warning: Imminent congestion predicted. Reducing bitrate proactively (Multiplicative Decrease).")
            
            val currentBitrate = NativeStreamer.getCurrentTargetBitrate()
            if (currentBitrate > 0) {
                val newBitrate = (currentBitrate * 0.7).toInt()
                NativeStreamer.requestBitrateChange(newBitrate)
            }
        } 
        // If network is rock-solid stable, increase aggressively (Additive Increase)
        else if (variance < 10.0 && average < 25.0) {
            Log.d(TAG, "Network stable. Increasing bitrate (Additive Increase).")
            NativeStreamer.requestBitrateIncrease(500) // Add 500 kbps
        }
    }
}
