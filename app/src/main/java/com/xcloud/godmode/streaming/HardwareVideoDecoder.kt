package com.xcloud.godmode.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.util.Log

/**
 * HardwareVideoDecoder: High-performance H.265 (HEVC) hardware decoder.
 * 
 * Optimized for Xbox Cloud Gaming:
 * - Asynchronous Buffer Pipeline: Moves decoding logic away from the main thread.
 * - Zero-Copy Rendering: Frames are sent directly to the hardware Surface plane.
 * - Low-Latency Configuration: Enforces real-time priority and Android 11+ low-latency flags.
 */
class HardwareVideoDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var isRunning = false
    private val TAG = "HardwareVideoDecoder"

    // High performance configuration for HEVC
    private val HEVC_MIMETYPE = "video/hevc"

    /**
     * Initializes the decoder with HEVC (H.265) parameters for ultra-low latency.
     */
    fun start(width: Int, height: Int) {
        if (isRunning) return
        
        try {
            val format = MediaFormat.createVideoFormat(HEVC_MIMETYPE, width, height)
            
            // 1. Force hardware acceleration priority (Real-time)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            
            // 2. Set low-latency mode (Android 11+ / API 30)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            
            // 3. Performance optimizations: Unlocked operating rate for maximum throughput
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            
            codec = MediaCodec.createDecoderByType(HEVC_MIMETYPE)
            
            // 4. Configure asynchronous callbacks to prevent blocking the data source
            codec?.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Buffers are filled via pushEncodedData
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    // ZERO-COPY: Render the buffer immediately to the hardware surface.
                    // This bypasses any CPU-side memory copying.
                    codec.releaseOutputBuffer(index, true)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "NATIVE_DECODER_ERROR: ${e.message} (Recoverable: ${e.isRecoverable})")
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "DECODER_FORMAT_SYNC: $format")
                }
            })

            // 5. Direct Surface binding (Zero-Copy sink)
            codec?.configure(format, surface, null, 0)
            codec?.start()
            
            isRunning = true
            Log.i(TAG, "HEVC_DECODER_READY: ${width}x${height} @ Hardware Plane")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL_DECODER_INIT_FAILURE: ${e.message}", e)
        }
    }

    /**
     * Pushes an encoded HEVC packet (NAL/AU) into the hardware pipeline.
     */
    fun pushEncodedData(data: ByteArray, length: Int, presentationTimeUs: Long) {
        if (!isRunning) return
        
        try {
            // Using a short timeout to prevent caller hang if codec is overloaded
            val inputBufferIndex = codec?.dequeueInputBuffer(1000) ?: -1
            if (inputBufferIndex >= 0) {
                val buffer = codec?.getInputBuffer(inputBufferIndex) ?: return
                buffer.clear()
                buffer.put(data, 0, length)
                codec?.queueInputBuffer(inputBufferIndex, 0, length, presentationTimeUs, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ENCODED_DATA_PUSH_ERROR: ${e.message}")
        }
    }

    /**
     * Resets and releases the hardware resources.
     */
    fun stop() {
        isRunning = false
        try {
            codec?.flush()
            codec?.stop()
            codec?.release()
            codec = null
            Log.i(TAG, "HEVC_DECODER_RELEASED")
        } catch (e: Exception) {
            Log.e(TAG, "DECODER_RELEASE_ERROR: ${e.message}")
        }
    }
}
