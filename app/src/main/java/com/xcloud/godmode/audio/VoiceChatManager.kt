package com.xcloud.godmode.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VoiceChatManager: Manages optional party voice chat capture with Acoustic Echo Cancellation (AEC)
 * and triggers game audio ducking during voice activity.
 *
 * NOTE: Microphone capture is completely OPTIONAL and disabled by default. It never blocks game launch.
 */
class VoiceChatManager(
    private val context: Context,
    private val onAudioDuckingStateChanged: (isDucked: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "VoiceChatManager"
        private const val SAMPLE_RATE = 24000
    }

    private val _isMicActive = MutableStateFlow(false)
    val isMicActive: StateFlow<Boolean> = _isMicActive.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureJob: Job? = null

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun toggleMicrophone(scope: CoroutineScope, enable: Boolean) {
        if (!enable) {
            stopCapture()
            _isMicActive.value = false
            onAudioDuckingStateChanged(false)
            return
        }

        if (!hasRecordPermission()) {
            Log.w(TAG, "Cannot start microphone capture: RECORD_AUDIO permission not granted by user.")
            _isMicActive.value = false
            return
        }

        startCapture(scope)
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(scope: CoroutineScope) {
        stopCapture()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }

            // Enable AEC and Noise Suppression if supported by device
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply {
                    enabled = true
                }
            }

            audioRecord = record
            record.startRecording()
            _isMicActive.value = true

            captureJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                var voiceActiveFrames = 0

                while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Detect voice activity level to trigger ducking
                        val isSpeaking = detectVoiceActivity(buffer, read)
                        if (isSpeaking) {
                            voiceActiveFrames = 15 // hold ducking for ~300ms
                            onAudioDuckingStateChanged(true)
                        } else if (voiceActiveFrames > 0) {
                            voiceActiveFrames--
                            if (voiceActiveFrames == 0) {
                                onAudioDuckingStateChanged(false)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing microphone capture", e)
            stopCapture()
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}

        echoCanceler = null
        noiseSuppressor = null
        audioRecord = null
        _isMicActive.value = false
    }

    private fun detectVoiceActivity(buffer: ByteArray, length: Int): Boolean {
        var sum = 0L
        for (i in 0 until length - 1 step 2) {
            var sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            if (sample > 32767) sample -= 65536
            sum += kotlin.math.abs(sample)
        }
        val avg = sum / (length / 2).coerceAtLeast(1)
        return avg > 1500 // threshold for human speech
    }
}
