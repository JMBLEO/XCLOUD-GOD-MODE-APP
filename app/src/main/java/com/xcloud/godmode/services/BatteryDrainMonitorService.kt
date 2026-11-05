package com.xcloud.godmode.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xcloud.godmode.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Optimization profiles suggested dynamically during active gaming sessions.
 */
enum class GamingOptimizationProfile(
    val title: String,
    val targetFps: Int,
    val targetBitrateBps: Int,
    val description: String
) {
    PERFORMANCE(
        title = "Rendimiento Extremo",
        targetFps = 120,
        targetBitrateBps = 25_000_000,
        description = "120 FPS sin restricciones, máxima nitidez CAS y latencia mínima de audio."
    ),
    BALANCED(
        title = "Equilibrado",
        targetFps = 60,
        targetBitrateBps = 15_000_000,
        description = "60 FPS estables, filtrado equilibrado y buffer adaptativo de bajo consumo."
    ),
    BATTERY_SAVER(
        title = "Ahorro de Batería",
        targetFps = 45,
        targetBitrateBps = 9_000_000,
        description = "Tope a 45 FPS, reducción de shaders y extensión de autonomía de batería."
    ),
    THERMAL_THROTTLED(
        title = "Mitigación Térmica",
        targetFps = 30,
        targetBitrateBps = 7_000_000,
        description = "Tope estricto a 30 FPS y shaders apagados para disipar calor del SoC."
    )
}

/**
 * Real-time battery telemetry and drain diagnostics.
 */
data class BatteryTelemetry(
    val batteryLevelPercent: Int = 100,
    val voltageMv: Int = 4200,
    val batteryTemperatureCelsius: Float = 30.0f,
    val dischargeCurrentMa: Int = 0,
    val instantaneousPowerWatts: Float = 0.0f,
    val drainRatePercentPerHour: Float = 0.0f,
    val estimatedRemainingPlaytimeMinutes: Int = 300,
    val isCharging: Boolean = false,
    val suggestedProfile: GamingOptimizationProfile = GamingOptimizationProfile.PERFORMANCE,
    val profileReason: String = "Condiciones óptimas de batería y temperatura",
    val sessionElapsedSeconds: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * BatteryDrainMonitorService: Background service tracking power consumption, discharge rate,
 * and battery thermals during high-performance gaming sessions.
 *
 * Correlates battery drain with SoC thermal telemetry from ThermalMonitorService to suggest
 * or automatically enact GamingOptimizationProfile adjustments.
 */
class BatteryDrainMonitorService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var samplingJob: Job? = null

    private var batteryManager: BatteryManager? = null
    private var initialLevel: Int = -1
    private var sessionStartMs: Long = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                updateBatteryFromIntent(intent)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BatteryDrainMonitorService = this@BatteryDrainMonitorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        sessionStartMs = System.currentTimeMillis()

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)

        startPeriodicSampling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START_FOREGROUND) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildForegroundNotification("Monitoreando consumo energético"))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        samplingJob?.cancel()
        serviceJob.cancel()
    }

    private fun updateBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (scale > 0 && level >= 0) (level * 100) / scale else 100
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000)
        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300)
        val tempCelsius = tempRaw / 10.0f
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        if (initialLevel < 0) {
            initialLevel = pct
        }

        recomputeTelemetry(pct, voltage, tempCelsius, isCharging)
    }

    private fun startPeriodicSampling() {
        samplingJob?.cancel()
        samplingJob = serviceScope.launch {
            while (isActive) {
                val current = _batteryFlow.value
                recomputeTelemetry(
                    current.batteryLevelPercent,
                    current.voltageMv,
                    current.batteryTemperatureCelsius,
                    current.isCharging
                )
                delay(2000L)
            }
        }
    }

    private fun recomputeTelemetry(level: Int, voltageMv: Int, tempC: Float, isCharging: Boolean) {
        val bm = batteryManager
        var currentMicroAmps = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        // Some chipsets report current in mA or negative for discharge
        if (abs(currentMicroAmps) > 10_000) {
            currentMicroAmps /= 1000 // Convert uA to mA
        }
        val dischargeMa = abs(currentMicroAmps)
        val powerWatts = (voltageMv.toFloat() * dischargeMa.toFloat()) / 1_000_000.0f

        val elapsedMs = max(1000L, System.currentTimeMillis() - sessionStartMs)
        val elapsedHours = elapsedMs / 3_600_000.0f
        val droppedPct = if (initialLevel > 0) max(0, initialLevel - level) else 0

        // Calculate drain rate: use measured current if available, or percentage drop
        val drainRatePerHour = when {
            isCharging -> 0.0f
            elapsedHours > 0.1f && droppedPct > 0 -> droppedPct / elapsedHours
            dischargeMa > 100 -> (dischargeMa.toFloat() / 4500.0f) * 100.0f // Normalized against typical 4500mAh pack
            else -> 15.0f // Baseline streaming gaming drain ~15%/hr
        }

        val remainingMinutes = if (drainRatePerHour > 0.0f && !isCharging) {
            ((level / drainRatePerHour) * 60.0f).toInt().coerceIn(10, 720)
        } else {
            720
        }

        // Consult Thermal Telemetry from ThermalMonitorService
        val thermal = ThermalMonitorService.thermalFlow.value
        val socTemp = thermal.socTemperatureCelsius

        // Suggest optimization profile based on thermal and battery telemetry
        val (suggestedProfile, reason) = when {
            thermal.isThrottled || socTemp >= ThermalMonitorService.THERMAL_THROTTLE_THRESHOLD_CELSIUS -> {
                GamingOptimizationProfile.THERMAL_THROTTLED to "SoC a ${socTemp}°C excede umbral seguro de 45°C. Se fuerza mitigación."
            }
            level <= 20 -> {
                GamingOptimizationProfile.BATTERY_SAVER to "Batería baja (${level}%). Se recomienda perfil de ahorro a 45 FPS."
            }
            socTemp >= 39.0f || level <= 50 -> {
                GamingOptimizationProfile.BALANCED to "Temperatura moderada (${socTemp}°C) o batería intermedia (${level}%)."
            }
            else -> {
                GamingOptimizationProfile.PERFORMANCE to "Batería (${level}%) y temperatura (${socTemp}°C) en rango óptimo."
            }
        }

        val previous = _batteryFlow.value
        val updated = BatteryTelemetry(
            batteryLevelPercent = level,
            voltageMv = voltageMv,
            batteryTemperatureCelsius = tempC,
            dischargeCurrentMa = dischargeMa,
            instantaneousPowerWatts = powerWatts,
            drainRatePercentPerHour = drainRatePerHour,
            estimatedRemainingPlaytimeMinutes = remainingMinutes,
            isCharging = isCharging,
            suggestedProfile = suggestedProfile,
            profileReason = reason,
            sessionElapsedSeconds = elapsedMs / 1000L,
            timestampMs = System.currentTimeMillis()
        )
        _batteryFlow.value = updated

        if (previous.suggestedProfile != suggestedProfile) {
            Log.i(TAG, "[BatteryOptimization] Perfil sugerido: ${suggestedProfile.name} ($reason)")
            dispatchProfileSuggestion(suggestedProfile, reason)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor de Batería y Rendimiento",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitorea la descarga energética y optimiza sesiones de juego"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("God Mode: Diagnóstico de Energía")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "BatteryDrainMonitor"
        private const val CHANNEL_ID = "godmode_battery_monitor"
        private const val NOTIFICATION_ID = 2046
        const val ACTION_START_FOREGROUND = "com.xcloud.godmode.action.START_BATTERY_MONITOR"

        private val _batteryFlow = MutableStateFlow(BatteryTelemetry())
        val batteryFlow: StateFlow<BatteryTelemetry> = _batteryFlow.asStateFlow()

        private val profileListeners = mutableListOf<(GamingOptimizationProfile, String) -> Unit>()

        fun registerProfileListener(listener: (GamingOptimizationProfile, String) -> Unit) {
            synchronized(profileListeners) {
                profileListeners.add(listener)
                val curr = _batteryFlow.value
                listener(curr.suggestedProfile, curr.profileReason)
            }
        }

        fun unregisterProfileListener(listener: (GamingOptimizationProfile, String) -> Unit) {
            synchronized(profileListeners) {
                profileListeners.remove(listener)
            }
        }

        private fun dispatchProfileSuggestion(profile: GamingOptimizationProfile, reason: String) {
            synchronized(profileListeners) {
                for (l in profileListeners) {
                    runCatching { l(profile, reason) }
                }
            }
        }

        fun startService(context: Context, foreground: Boolean = false) {
            val intent = Intent(context, BatteryDrainMonitorService::class.java).apply {
                if (foreground) action = ACTION_START_FOREGROUND
            }
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, BatteryDrainMonitorService::class.java))
        }
    }
}
