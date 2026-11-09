package com.xcloud.godmode.streaming

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.util.Log
import com.xcloud.godmode.network.NetworkClient
import com.xcloud.godmode.engine.FirstFrameResult
import com.xcloud.godmode.models.GodModeGlobalConfig
import com.xcloud.godmode.services.AppConfigurationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * NativeStreamPipelineManager: Pure Native Cloud Gaming Pipeline Manager.
 * Operates at Estado 0 (Zero-Copy Pure Native Direct Sockets).
 * Strictly Zero-WebRTC, Zero-FFmpeg, Zero-H.264.
 */
class NativeStreamPipelineManager(
    val context: Context,
    private val networkClient: NetworkClient,
    val diagnostics: com.xcloud.godmode.network.CloudLaunchDiagnostics,
    val configRepository: AppConfigurationRepository
) {
    private val TAG = "NativeStreamPipeline"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var latencyMonitor: ControllerLatencyMonitor? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var configJob: Job? = null
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Audio Focus LOST: Muting stream audio")
                NativeStreamBridge.stopAudio()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio Focus Transient LOSS: Lowering volume")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio Focus GAINED: Resuming stream audio")
                NativeStreamBridge.startAudio()
            }
        }
    }
    
    private var lastTrafficMs = System.currentTimeMillis()
    
    private val heartbeat = com.xcloud.godmode.network.SessionKeepaliveHeartbeat(scope) {
        // En NANO, el heartbeat se envía por el canal de control binario
        NativeStreamBridge.requestKeyframe()
    }
    
    private val drsController = DynamicResolutionScalingController { w, h ->
        Log.i(TAG, "DRS: Adjusting resolution to ${w}x${h}")
        NativeStreamBridge.onSurfaceResized(w, h)
        // Update remote preference via NANO control
    }

    private val hapticEngine = com.xcloud.godmode.haptics.UnifiedHapticTelemetricEngine(context)
    private var lastKeyframeRequestMs = 0L
    private var isConnectedState = false
    private var activeConfig: GodModeGlobalConfig = GodModeGlobalConfig()

    val okHttpClient: OkHttpClient = com.xcloud.godmode.network.HttpClientFactory.createHardenedClient()

    data class StreamStats(
        val rtt: Int = 0,
        val jitter: Double = 0.0,
        val packetLoss: Double = 0.0,
        val bitrate: Int = 0,
        val fps: Int = 0
    )

    val abraController = RtccAbraController()
    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private val _firstFramePresented = MutableStateFlow<FirstFrameResult?>(null)
    val firstFramePresented: StateFlow<FirstFrameResult?> = _firstFramePresented.asStateFlow()

    fun applyConfig(config: GodModeGlobalConfig) {
        this.activeConfig = config
    }

    fun updateTelemetry(rtt: Int, jitter: Double, packetLoss: Double, bitrate: Int, fps: Int) {
        _stats.value = StreamStats(rtt, jitter, packetLoss, bitrate, fps)
        
        if (activeConfig.enableTelemetryHudByDefault && abraController.evaluate(packetLoss.toFloat(), rtt)) {
            // Adjust bitrate via NANO control
        }
        
        TelemetryBridge.updateCoreStats(
            rttMs = rtt,
            fps = fps,
            packetLossPct = packetLoss.toFloat(),
            bitrateKbps = bitrate / 1000,
            activeEngineId = NativeStreamBridge.getActiveEngineId()
        )

        if (latencyMonitor == null && isConnectedState) {
            NativeInputBridge.activeInstance?.let { input ->
                latencyMonitor = ControllerLatencyMonitor(input, diagnostics, scope).apply {
                    start()
                }
            }
        }

        if (fps > 0 && _firstFramePresented.value == null) {
            _firstFramePresented.value = FirstFrameResult(true, 1L, System.nanoTime())
        }
        
        drsController.evaluateNetworkMetrics(packetLoss.toFloat(), rtt)
    }

    fun startSessionServices() {
        heartbeat.start()
        
        configJob?.cancel()
        configJob = scope.launch {
            configRepository.configFlow.collect { cfg ->
                applyConfig(cfg)
            }
        }
        
        // Iniciar audio de ultra-baja latencia (AAudio / Oboe hybrid)
        NativeStreamBridge.startUltraLowLatencyAudio(128)
        
        val serviceIntent = android.content.Intent(this@NativeStreamPipelineManager.context, com.xcloud.godmode.services.CloudStreamForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            this@NativeStreamPipelineManager.context.startForegroundService(serviceIntent)
        } else {
            this@NativeStreamPipelineManager.context.startService(serviceIntent)
        }

        @Suppress("DEPRECATION")
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus request failed!")
        }
    }

    fun isConnected(): Boolean = isConnectedState

    fun requestKeyframe(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastKeyframeRequestMs) < 1500L) return
        lastKeyframeRequestMs = now
        NativeStreamBridge.requestKeyframe()
    }

    fun sendInput(data: ByteArray, slot: Int = 0) {
        NativeStreamBridge.pushRawInput(data, data.size)
        lastTrafficMs = System.currentTimeMillis()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        com.xcloud.godmode.audio.OboeAudioBridge.setMute(!enabled)
    }

    fun stopStream() {
        executor.execute {
            latencyMonitor?.stop()
            latencyMonitor = null
            configJob?.cancel()
            configJob = null
            heartbeat.stop()
            
            this@NativeStreamPipelineManager.context.stopService(android.content.Intent(this@NativeStreamPipelineManager.context, com.xcloud.godmode.services.CloudStreamForegroundService::class.java))
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
            
            isConnectedState = false
            try {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
                wifiLock = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing Wi-Fi lock: ${e.message}")
            }
            _firstFramePresented.value = null
            NativeStreamBridge.stopNativeReactor()
            NativeStreamBridge.stopAudio()
            com.xcloud.godmode.audio.OboeAudioBridge.stopCapture()
            hapticEngine.stopAllVibrations()

            NativeStreamBridge.zeroTraceShutdown()

            try {
                this@NativeStreamPipelineManager.context.cacheDir?.deleteRecursively()
                this@NativeStreamPipelineManager.context.externalCacheDir?.deleteRecursively()
                Log.d(TAG, "Zero-Trace: Cache purged successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Notice during cache purge: ${e.message}")
            }
        }
    }
}
