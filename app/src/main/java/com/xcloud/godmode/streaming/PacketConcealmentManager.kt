package com.xcloud.godmode.streaming

import android.util.Log

/**
 * Phase 68: Extreme Network Loss Concealment (Freeze-Hold).
 * Detects packet arrival gaps exceeding 300ms, engages visual freeze-hold to prevent macroblock corruption,
 * performs smooth 50ms audio fade-out to prevent speaker pops, and triggers fast IDR keyframe recovery.
 */
class PacketConcealmentManager(
    private val onKeyFrameNeeded: () -> Unit
) {

    companion object {
        private const val TAG = "PacketConcealment"
        const val PACKET_GAP_THRESHOLD_MS = 300L
        const val AUDIO_FADE_STEPS = 10
    }

    enum class ConcealmentState {
        NORMAL,
        FREEZE_HOLD,
        RECOVERING
    }

    private var lastPacketTimestampNs = System.nanoTime()
    private var currentState = ConcealmentState.NORMAL
    private var isAudioFaded = false

    /**
     * Called whenever a valid video packet is received.
     */
    fun onPacketReceived(): Boolean {
        val now = System.nanoTime()
        val gapMs = (now - lastPacketTimestampNs) / 1_000_000L
        lastPacketTimestampNs = now

        if (currentState == ConcealmentState.FREEZE_HOLD) {
            Log.i(TAG, "Network restored after gap of ${gapMs}ms. Requesting immediate IDR keyframe.")
            currentState = ConcealmentState.RECOVERING
            onKeyFrameNeeded()
            isAudioFaded = false
            return true
        }

        currentState = ConcealmentState.NORMAL
        return false
    }

    /**
     * Watchdog tick invoked at 60 Hz to audit network stall.
     */
    fun auditPacketFreshness(): ConcealmentState {
        val now = System.nanoTime()
        val gapMs = (now - lastPacketTimestampNs) / 1_000_000L

        if (gapMs >= PACKET_GAP_THRESHOLD_MS && currentState == ConcealmentState.NORMAL) {
            Log.w(TAG, "Network packet loss gap exceeded ${gapMs}ms. Entering FREEZE_HOLD concealment.")
            currentState = ConcealmentState.FREEZE_HOLD
            isAudioFaded = true
        }

        return currentState
    }

    /**
     * Applies a 50ms smooth volume decay multiplier to prevent pop/click during stalls.
     */
    fun computeAudioAttenuationMultiplier(elapsedStallMs: Long): Float {
        if (currentState == ConcealmentState.NORMAL) return 1.0f
        val fadeProgress = (elapsedStallMs.toFloat() / 50f).coerceIn(0f, 1f)
        return (1.0f - fadeProgress)
    }

    fun getState(): ConcealmentState = currentState
}
