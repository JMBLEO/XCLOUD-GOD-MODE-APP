package com.xcloud.godmode.bridge

import android.os.Build
import android.os.Trace

/**
 * Phase 83: Perfetto & Systrace Telemetry Bridge.
 * Emits nanosecond-precision trace sections and counters for Perfetto,
 * capturing UDP packet transit, MediaCodec decode pipeline latency, and input dispatch times.
 */
object PerfettoTraceBridge {

    const val SECTION_UDP_RECV = "GM_UdpRecv"
    const val SECTION_DECODE_FRAME = "GM_DecodeFrame"
    const val SECTION_FSR_PASS = "GM_FsrPass"
    const val SECTION_INPUT_DISPATCH = "GM_InputDispatch"

    inline fun <T> traceSection(name: String, block: () -> T): T {
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    fun setCounter(counterName: String, value: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.setCounter(counterName, value)
        }
    }

    fun recordRtt(rttMs: Long) {
        setCounter("GM_NetworkRttMs", rttMs)
    }

    fun recordDecodeTimeUs(decodeUs: Long) {
        setCounter("GM_DecodeLatencyUs", decodeUs)
    }

    fun recordFps(fps: Long) {
        setCounter("GM_StreamFps", fps)
    }
}
