package com.aistudio.xcloudgodmode.ai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

enum class UiSoundTheme {
    PLAYSTAX_HYBRID,
    PS5_CRYSTALLINE,
    XBOX_BLADES_360,
    PS3_XMB_CLICK
}

enum class SoundEventType {
    FOCUS,
    TAB,
    BLADE_OPEN,
    BLADE_CLOSE,
    SELECT,
    FAVORITE,
    PROFILE_SWITCH,
    HUD_TOGGLE,
    LAUNCH
}

enum class ActiveInputLayer {
    COMPOSE_HOME,     // En menús nativos: Sonidos completos
    STREAM_ACTIVE,    // Jugando en WebRTC: Sonidos de foco bloqueados
    STREAM_OVERLAY    // Guía Optimizer sobre el juego: Sonidos de overlay permitidos
}

/**
 * PlayStaxBoxNativeSoundEngine: Procedural Audio-Haptic Engine.
 * Synthesizes console UI sounds in real-time using low-latency AudioTrack.
 */
object PlayStaxBoxNativeSoundEngine {
    private const val SAMPLE_RATE = 44100
    private val synthScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Configuration
    var isEnabled: Boolean = true
    var volumePercent: Int = 75
    var isHapticsCoupled: Boolean = true
    var activeTheme: UiSoundTheme = UiSoundTheme.PLAYSTAX_HYBRID
    var currentInputLayer: ActiveInputLayer = ActiveInputLayer.COMPOSE_HOME
    var chameleonPitchMultiplier: Float = 1.0f

    private var appContext: Context? = null
    private var lastFocusTimestamp = 0L

    // Pre-rendered PCM cache for 0ms latency navigation sounds
    private val pcmCache = ConcurrentHashMap<String, ShortArray>()

    fun init(context: Context) {
        appContext = context.applicationContext
        warmupStaticBuffers()
    }

    private fun warmupStaticBuffers() {
        synthScope.launch {
            pcmCache["FOCUS_DEFAULT"] = synthesizeDualTone(587.33f, 880f, 1174.66f, 1760f, 0.065f, 0.32f)
            pcmCache["TAB_DEFAULT"] = synthesizeDualTone(260f, 1240f, 440f, 659.25f, 0.125f, 0.32f)
            pcmCache["SELECT_DEFAULT"] = synthesizeDualTone(523.25f, 783.99f, 1046.5f, 1567.98f, 0.14f, 0.32f)
        }
    }

    fun updateChameleonPitch(r: Int, g: Int, b: Int) {
        val normLum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        chameleonPitchMultiplier = (0.92f + normLum * 0.16f).coerceIn(0.88f, 1.12f)
    }

    fun play(event: SoundEventType, fromWebView: Boolean = false) {
        if (!isEnabled || volumePercent <= 0) return

        // Audio Policy: Silence focus sounds during active stream
        if (currentInputLayer == ActiveInputLayer.STREAM_ACTIVE && event == SoundEventType.FOCUS) return
        if (currentInputLayer != ActiveInputLayer.COMPOSE_HOME && !fromWebView && event == SoundEventType.FOCUS) return

        val now = System.currentTimeMillis()
        if (event == SoundEventType.FOCUS && now - lastFocusTimestamp < 38L) return
        if (event == SoundEventType.FOCUS) lastFocusTimestamp = now

        // Trigger coupled haptic feedback
        if (isHapticsCoupled) {
            triggerCoupledHaptic(event)
        }

        val gain = (volumePercent.coerceIn(1, 100) / 100f) * 0.32f
        val shift = chameleonPitchMultiplier
        val theme = activeTheme

        synthScope.launch {
            val pcmSamples: ShortArray = when (event) {
                SoundEventType.FOCUS -> {
                    when (theme) {
                        UiSoundTheme.PS5_CRYSTALLINE -> synthesizeDualTone(880f * shift, 1320f * shift, 1760f * shift, 2640f * shift, 0.045f, gain * 0.8f)
                        UiSoundTheme.PS3_XMB_CLICK -> synthesizeDualTone(1420f * shift, 780f * shift, 2840f * shift, 1420f * shift, 0.038f, gain)
                        UiSoundTheme.XBOX_BLADES_360 -> synthesizeDualTone(310f * shift, 440f * shift, 620f * shift, 880f * shift, 0.055f, gain)
                        else -> synthesizeDualTone(587.33f * shift, 880f * shift, 1174.66f * shift, 1760f * shift, 0.065f, gain)
                    }
                }

                SoundEventType.TAB -> {
                    when (theme) {
                        UiSoundTheme.PS5_CRYSTALLINE -> synthesizeArpeggio(floatArrayOf(1320f * shift, 1760f * shift), 0.02f, 0.15f, gain)
                        else -> synthesizeDualTone(240f * shift, 1320f * shift, 440f * shift, 659.25f * shift, 0.11f, gain)
                    }
                }

                SoundEventType.BLADE_OPEN -> synthesizeArpeggio(
                    frequencies = floatArrayOf(440f * shift, 659.25f * shift, 880f * shift, 1318.5f * shift),
                    noteSpacingSec = 0.035f, noteDurationSec = 0.22f, gain = gain
                )

                SoundEventType.BLADE_CLOSE -> synthesizeArpeggio(
                    frequencies = floatArrayOf(880f * shift, 659.25f * shift, 440f * shift, 329.63f * shift),
                    noteSpacingSec = 0.032f, noteDurationSec = 0.18f, gain = gain
                )

                SoundEventType.FAVORITE -> synthesizeArpeggio(
                    frequencies = floatArrayOf(880f * shift, 1108.73f * shift, 1318.51f * shift, 1760f * shift),
                    noteSpacingSec = 0.028f, noteDurationSec = 0.15f, gain = gain
                )

                SoundEventType.PROFILE_SWITCH, SoundEventType.SELECT -> synthesizeDualTone(
                    523.25f * shift, 783.99f * shift, 1046.5f * shift, 1567.98f * shift, 0.14f, gain
                )

                SoundEventType.HUD_TOGGLE -> synthesizeDualTone(
                    700f * shift, 1050f * shift, 1400f * shift, 2100f * shift, 0.08f, gain
                )

                SoundEventType.LAUNCH -> synthesizeArpeggio(
                    frequencies = floatArrayOf(110f * shift, 220f * shift, 329.63f * shift, 440f * shift, 659.25f * shift, 880f * shift),
                    noteSpacingSec = 0.024f, noteDurationSec = 0.50f, gain = gain
                )
            }

            playPcmBuffer(pcmSamples)
        }
    }

    private fun triggerCoupledHaptic(event: SoundEventType) {
        val ctx = appContext ?: return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (event) {
                    SoundEventType.FOCUS -> VibrationEffect.createOneShot(10L, 30)
                    SoundEventType.TAB -> VibrationEffect.createOneShot(20L, 65)
                    SoundEventType.BLADE_OPEN -> VibrationEffect.createWaveform(longArrayOf(0, 15, 30, 25), intArrayOf(0, 50, 0, 90), -1)
                    SoundEventType.BLADE_CLOSE -> VibrationEffect.createOneShot(18L, 45)
                    SoundEventType.FAVORITE -> VibrationEffect.createWaveform(longArrayOf(0, 12, 20, 18), intArrayOf(0, 60, 0, 100), -1)
                    SoundEventType.PROFILE_SWITCH -> VibrationEffect.createOneShot(35L, 120)
                    SoundEventType.LAUNCH -> VibrationEffect.createWaveform(longArrayOf(0, 40, 40, 80), intArrayOf(0, 80, 0, 200), -1)
                    else -> VibrationEffect.createOneShot(15L, 40)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(18L)
            }
        } catch (_: Exception) {}
    }

    private fun synthesizeDualTone(
        startF1: Float, endF1: Float,
        startF2: Float, endF2: Float,
        durationSec: Float, gain: Float
    ): ShortArray {
        val totalSamples = max(64, (SAMPLE_RATE * durationSec).toInt())
        val buffer = ShortArray(totalSamples)
        var phase1 = 0.0
        var phase2 = 0.0

        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples.toFloat()
            val f1 = startF1 * (endF1 / startF1).pow(progress)
            val f2 = startF2 * (endF2 / startF2).pow(progress)

            phase1 += 2.0 * PI * f1 / SAMPLE_RATE
            phase2 += 2.0 * PI * f2 / SAMPLE_RATE

            val attack = min(1f, i / (SAMPLE_RATE * 0.005f))
            val decay = exp(-4.6f * progress)
            val env = attack * decay

            val sample = ((sin(phase1) * 0.70 + sin(phase2) * 0.30) * env * gain)
            buffer[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return buffer
    }

    private fun synthesizeArpeggio(
        frequencies: FloatArray,
        noteSpacingSec: Float,
        noteDurationSec: Float,
        gain: Float
    ): ShortArray {
        val totalDurationSec = (frequencies.size - 1) * noteSpacingSec + noteDurationSec
        val totalSamples = max(128, (SAMPLE_RATE * totalDurationSec).toInt())
        val mixBuffer = FloatArray(totalSamples)

        frequencies.forEachIndexed { idx, freq ->
            val startSample = (idx * noteSpacingSec * SAMPLE_RATE).toInt()
            val noteSamples = (noteDurationSec * SAMPLE_RATE).toInt()
            var phase = 0.0
            val voiceScale = 0.65f / (idx * 0.28f + 1f)

            for (i in 0 until noteSamples) {
                val targetIdx = startSample + i
                if (targetIdx >= totalSamples) break
                val progress = i.toFloat() / noteSamples.toFloat()
                phase += 2.0 * PI * freq / SAMPLE_RATE

                val attack = min(1f, i / (SAMPLE_RATE * 0.007f))
                val decay = exp(-5.0f * progress)
                val harmonic = sin(phase) * 0.75 + sin(phase * 2.0) * 0.25

                mixBuffer[targetIdx] += (harmonic * attack * decay * gain * voiceScale).toFloat()
            }
        }

        return ShortArray(totalSamples) { i ->
            (mixBuffer[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun playPcmBuffer(pcmData: ShortArray) {
        try {
            val byteSize = pcmData.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(byteSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcmData, 0, pcmData.size)
            track.setNotificationMarkerPosition(pcmData.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(audioTrack: AudioTrack?) {
                    audioTrack?.stop()
                    audioTrack?.release()
                }
                override fun onPeriodicNotification(audioTrack: AudioTrack?) {}
            })
            track.play()
        } catch (_: Exception) {}
    }
}
