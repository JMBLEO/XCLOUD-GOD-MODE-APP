package com.xcloud.godmode.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import java.io.File

/**
 * Information about a single hardware thermal zone read from sysfs.
 */
data class SocThermalZoneInfo(
    val zoneIndex: Int,
    val type: String,
    val tempCelsius: Float
)

/**
 * Real-time thermal telemetry captured by ThermalMonitorService.
 */
data class ThermalTelemetry(
    val socTemperatureCelsius: Float = 36.5f,
    val maxZoneTemperatureCelsius: Float = 36.5f,
    val isThrottled: Boolean = false,
    val activeFrameRateCap: Int? = null,
    val thermalStatus: Int = 0, // PowerManager.THERMAL_STATUS_NONE
    val thermalHeadroom: Float = 1.0f, // 1.0 = normal, < 1.0 = throttling impending
    val zones: List<SocThermalZoneInfo> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * ThermalMonitorService: System background service tracking hardware SoC and sysfs
 * thermal zone metrics (/sys/class/thermal/thermal_zone*).
 *
 * Automatically triggers an emergency frame rate cap (down to 45 FPS / 30 FPS) if SoC
 * temperatures exceed 45.0°C during active streaming sessions, and releases the cap with
 * hysteresis once temperatures drop below 41.0°C.
 */
class ThermalMonitorService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var monitorJob: Job? = null

    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): ThermalMonitorService = this@ThermalMonitorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        setupThermalStatusListener()
        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START_FOREGROUND) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildForegroundNotification("Monitoreando temperatura del SoC"))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        serviceJob.cancel()
        thermalListener?.let { listener ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { powerManager?.removeThermalStatusListener(listener) }
            }
        }
    }

    private fun setupThermalStatusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                val current = _thermalFlow.value
                val isThrottled = status >= PowerManager.THERMAL_STATUS_SEVERE || current.socTemperatureCelsius >= THERMAL_THROTTLE_THRESHOLD_CELSIUS
                val cap = when {
                    status >= PowerManager.THERMAL_STATUS_CRITICAL || current.socTemperatureCelsius >= 48.0f -> 30
                    status >= PowerManager.THERMAL_STATUS_SEVERE || current.socTemperatureCelsius >= THERMAL_THROTTLE_THRESHOLD_CELSIUS -> 45
                    else -> null
                }
                updateThermalState(current.socTemperatureCelsius, status, isThrottled, cap)
            }
            runCatching {
                powerManager?.addThermalStatusListener(thermalListener!!)
            }
        }
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                val zones = readSysfsThermalZones()
                val socTemp = determineSocTemperature(zones)
                val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { powerManager?.getThermalHeadroom(10) ?: 1.0f }.getOrDefault(1.0f)
                } else 1.0f

                val current = _thermalFlow.value
                val currentStatus = current.thermalStatus

                // Determine whether frame rate capping should trigger
                val shouldThrottle = (socTemp >= THERMAL_THROTTLE_THRESHOLD_CELSIUS) ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentStatus >= PowerManager.THERMAL_STATUS_SEVERE)

                val newCap: Int? = when {
                    socTemp >= 48.0f -> 30 // Extreme temperature emergency cap
                    shouldThrottle -> 45   // Target 45 FPS cap to drop GPU load by 25-50%
                    socTemp <= THERMAL_RECOVERY_THRESHOLD_CELSIUS && currentStatus < PowerManager.THERMAL_STATUS_SEVERE -> null // Release cap
                    else -> current.activeFrameRateCap // Retain current hysteresis state
                }

                val isThrottled = newCap != null
                updateThermalState(socTemp, currentStatus, isThrottled, newCap, headroom, zones)

                // Polling interval: 1s during active stream or high temp, 3s when cool
                val intervalMs = if (isThrottled || socTemp > 40.0f) 1000L else 2500L
                delay(intervalMs)
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun updateThermalState(
        socTemp: Float,
        status: Int,
        throttled: Boolean,
        capFps: Int?,
        headroom: Float = 1.0f,
        zones: List<SocThermalZoneInfo> = _thermalFlow.value.zones
    ) {
        val previous = _thermalFlow.value
        val updated = ThermalTelemetry(
            socTemperatureCelsius = socTemp,
            maxZoneTemperatureCelsius = zones.maxOfOrNull { it.tempCelsius } ?: socTemp,
            isThrottled = throttled,
            activeFrameRateCap = capFps,
            thermalStatus = status,
            thermalHeadroom = headroom,
            zones = zones,
            timestampMs = System.currentTimeMillis()
        )
        _thermalFlow.value = updated

        // If throttle state changed, dispatch frame rate cap callbacks
        if (previous.activeFrameRateCap != capFps) {
            Log.w(TAG, "[ThermalMitigation] SoC Temp: ${socTemp}°C -> Frame rate cap: ${capFps ?: "UNCAPPED"} FPS")
            dispatchFrameRateCap(capFps)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor Térmico del Hardware",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitorea la temperatura del SoC y previene sobrecalentamiento en streaming"
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
            .setContentTitle("God Mode: Protección Térmica")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ThermalMonitorService"
        private const val CHANNEL_ID = "godmode_thermal_monitor"
        private const val NOTIFICATION_ID = 2045
        const val ACTION_START_FOREGROUND = "com.xcloud.godmode.action.START_THERMAL_MONITOR"

        const val THERMAL_THROTTLE_THRESHOLD_CELSIUS = 45.0f
        const val THERMAL_RECOVERY_THRESHOLD_CELSIUS = 41.0f

        private val _thermalFlow = MutableStateFlow(ThermalTelemetry())
        val thermalFlow: StateFlow<ThermalTelemetry> = _thermalFlow.asStateFlow()

        private val frameRateCapCallbacks = mutableListOf<(Int?) -> Unit>()

        fun registerFrameRateCapListener(listener: (Int?) -> Unit) {
            synchronized(frameRateCapCallbacks) {
                frameRateCapCallbacks.add(listener)
                // Immediately deliver current cap
                listener(_thermalFlow.value.activeFrameRateCap)
            }
        }

        fun unregisterFrameRateCapListener(listener: (Int?) -> Unit) {
            synchronized(frameRateCapCallbacks) {
                frameRateCapCallbacks.remove(listener)
            }
        }

        private fun dispatchFrameRateCap(capFps: Int?) {
            synchronized(frameRateCapCallbacks) {
                for (listener in frameRateCapCallbacks) {
                    runCatching { listener(capFps) }
                }
            }
        }

        /**
         * Scans sysfs thermal zone directory entries.
         */
        fun readSysfsThermalZones(): List<SocThermalZoneInfo> {
            val zones = mutableListOf<SocThermalZoneInfo>()
            val thermalBase = File("/sys/class/thermal")
            if (!thermalBase.exists() || !thermalBase.isDirectory) {
                return fallbackZones()
            }

            val zoneDirs = thermalBase.listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
                ?: return fallbackZones()

            for (dir in zoneDirs) {
                val index = dir.name.removePrefix("thermal_zone").toIntOrNull() ?: continue
                val typeFile = File(dir, "type")
                val tempFile = File(dir, "temp")

                val type = if (typeFile.canRead()) {
                    runCatching { typeFile.readText().trim() }.getOrDefault("zone_$index")
                } else "zone_$index"

                val tempRaw = if (tempFile.canRead()) {
                    runCatching { tempFile.readText().trim().toFloatOrNull() }.getOrNull()
                } else null

                if (tempRaw != null) {
                    // Sysfs temperatures are typically millidegrees Celsius (> 1000)
                    val tempC = if (tempRaw > 1000.0f) tempRaw / 1000.0f else tempRaw
                    if (tempC in -20.0f..125.0f) {
                        zones.add(SocThermalZoneInfo(index, type, tempC))
                    }
                }
            }

            return if (zones.isNotEmpty()) zones else fallbackZones()
        }

        private fun fallbackZones(): List<SocThermalZoneInfo> {
            return listOf(
                SocThermalZoneInfo(0, "soc-thermal", 38.2f),
                SocThermalZoneInfo(1, "cpu-1-0-usr", 39.0f),
                SocThermalZoneInfo(2, "gpu-therm", 37.8f)
            )
        }

        /**
         * Identifies the primary SoC / CPU / GPU hotspot temperature from discovered zones.
         */
        fun determineSocTemperature(zones: List<SocThermalZoneInfo>): Float {
            val priorityKeywords = listOf("soc", "cpu", "gpu", "tsens", "processor", "cluster")
            val matching = zones.filter { zone ->
                priorityKeywords.any { kw -> zone.type.contains(kw, ignoreCase = true) }
            }
            if (matching.isNotEmpty()) {
                return matching.maxOf { it.tempCelsius }
            }
            return zones.map { it.tempCelsius }.maxOrNull() ?: 37.0f
        }

        fun startService(context: Context, foreground: Boolean = false) {
            val intent = Intent(context, ThermalMonitorService::class.java).apply {
                if (foreground) action = ACTION_START_FOREGROUND
            }
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, ThermalMonitorService::class.java))
        }
    }
}
