package com.xcloud.godmode.streaming

import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * SafeHardwareBufferHandle: Manages AHardwareBuffer lifecycle using AutoCloseable
 * to prevent GPU memory leaks in JNI/Native engine transitions.
 */
@RequiresApi(Build.VERSION_CODES.O)
class SafeHardwareBufferHandle(
    private val buffer: HardwareBuffer
) : AutoCloseable {
    private var isClosed = false

    val hardwareBuffer: HardwareBuffer?
        get() = if (!isClosed) buffer else null

    override fun close() {
        if (!isClosed) {
            isClosed = true
            buffer.close()
        }
    }
}
