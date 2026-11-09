package com.xcloud.godmode.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * LowLatencyAudioTrack: Direct PCM 16-bit 48kHz audio sink achieving sub-10ms output latency
 * with PERFORMANCE_MODE_LOW_LATENCY and dynamic volume gain boost.
 */
class LowLatencyAudioTrack(
    private val sampleRateHz: Int = 48000,
    private val channelCount: Int = 2
) {
    companion object {
        private const val TAG = "LowLatencyAudioTrack"
    }

    private var audioTrack: AudioTrack? = null
    var digitalGain: Float = 1.0f // 1.0f = 100%, up to 2.0f = 200% volume boost
    var isDucked: Boolean = false
    var duckingFactor: Float = 0.35f // -9dB reduction when party chat speaks

    fun initialize() {
        if (audioTrack != null) return

        val channelConfig = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(channelConfig)
            .build()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRateHz,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Keep buffer minimal (< 10ms of audio = ~1920 bytes stereo 48kHz)
        val bufferSize = minBufferSize.coerceAtLeast(1920)

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        try {
            audioTrack = builder.build()
            audioTrack?.play()
            Log.i(TAG, "Initialized LowLatencyAudioTrack (sampleRate: $sampleRateHz, buffer: $bufferSize bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    fun writePcm(pcmData: ByteArray, offset: Int = 0, size: Int = pcmData.size): Int {
        val track = audioTrack ?: return 0
        val effectiveGain = digitalGain * (if (isDucked) duckingFactor else 1.0f)

        if (effectiveGain != 1.0f) {
            // Apply gain directly to 16-bit PCM samples
            val processed = pcmData.copyOfRange(offset, offset + size)
            for (i in 0 until processed.size - 1 step 2) {
                var sample = (processed[i].toInt() and 0xFF) or (processed[i + 1].toInt() shl 8)
                if (sample > 32767) sample -= 65536
                sample = (sample * effectiveGain).toInt().coerceIn(-32768, 32767)
                processed[i] = (sample and 0xFF).toByte()
                processed[i + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            return track.write(processed, 0, processed.size)
        }

        return track.write(pcmData, offset, size)
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
