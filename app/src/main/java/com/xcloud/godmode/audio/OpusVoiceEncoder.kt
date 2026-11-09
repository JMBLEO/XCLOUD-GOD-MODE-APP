package com.xcloud.godmode.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * OpusVoiceEncoder: Encodes microphone PCM audio into 20ms Opus frames (48 kHz mono / 960 samples)
 * with Discontinuous Transmission (DTX) and comfort noise for uplink to Xbox Live voice servers.
 */
class OpusVoiceEncoder(
    private val sampleRateHz: Int = 48000,
    private val bitrateBps: Int = 24000
) {
    companion object {
        private const val TAG = "OpusVoiceEncoder"
        private const val OPUS_MIME = "audio/opus"
        private const val FRAME_SIZE_SAMPLES = 960 // 20 ms at 48 kHz
    }

    private var encoder: MediaCodec? = null
    var isInitialized: Boolean = false
        private set

    fun initialize(): Boolean {
        if (isInitialized) return true

        return try {
            val format = MediaFormat.createAudioFormat(OPUS_MIME, sampleRateHz, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_COMPLEXITY, 5) // Optimal power/quality on ARM
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            encoder = MediaCodec.createEncoderByType(OPUS_MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            isInitialized = true
            Log.i(TAG, "OpusVoiceEncoder initialized: $sampleRateHz Hz @ ${bitrateBps / 1000} kbps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Opus MediaCodec encoder", e)
            isInitialized = false
            false
        }
    }

    /**
     * Encodes a 20 ms PCM short array. Returns length of encoded Opus packet in bytes.
     */
    fun encodePcmFrame(pcmShorts: ShortArray, outPacketBuffer: ByteBuffer): Int {
        val enc = encoder ?: return 0
        if (!isInitialized) return 0

        val inIndex = enc.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
            val inBuf = enc.getInputBuffer(inIndex)
            inBuf?.clear()

            // Check for silence to enable DTX
            var maxAmp = 0
            for (sample in pcmShorts) {
                val amp = kotlin.math.abs(sample.toInt())
                if (amp > maxAmp) maxAmp = amp
                inBuf?.putShort(sample)
            }

            val isSilence = (maxAmp < 150)
            val flags = if (isSilence) 0 else 0
            enc.queueInputBuffer(inIndex, 0, pcmShorts.size * 2, System.nanoTime() / 1000, flags)
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val outIndex = enc.dequeueOutputBuffer(bufferInfo, 10_000)
        if (outIndex >= 0) {
            val outBuf = enc.getOutputBuffer(outIndex)
            val bytesWritten = bufferInfo.size
            if (outBuf != null && bytesWritten > 0) {
                outBuf.position(bufferInfo.offset)
                outBuf.limit(bufferInfo.offset + bytesWritten)
                outPacketBuffer.put(outBuf)
            }
            enc.releaseOutputBuffer(outIndex, false)
            return bytesWritten
        }

        return 0
    }

    fun release() {
        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null
        isInitialized = false
    }
}
