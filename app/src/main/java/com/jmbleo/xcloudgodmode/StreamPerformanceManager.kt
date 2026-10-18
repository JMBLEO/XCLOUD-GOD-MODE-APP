package com.jmbleo.xcloudgodmode

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import android.webkit.WebView
import java.io.File

/**
 * Gestor de rendimiento nativo para streaming de ultra baja latencia.
 * Controla WifiLock, WakeLock de pantalla, monitoreo térmico y detox selectivo de caché multimedia.
 */
class StreamPerformanceManager(
    private val activity: Activity,
    private val webView: WebView
) {

    private var wifiLock: WifiManager.WifiLock? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var thermalListener: Any? = null

    var onThermalWarningListener: ((String) -> Unit)? = null

    /**
     * Adquiere el bloqueo Wi-Fi de más baja latencia soportado por el chipset (Modo 4 en Android 10+).
     */
    fun acquireLocks() {
        try {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && (wifiLock == null || wifiLock?.isHeld == false)) {
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(lockType, "XCloudGodMode:PerformanceWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Libera el WifiLock para proteger la batería del usuario en segundo plano.
     */
    fun releaseLocks() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Registra monitores térmicos y de batería.
     */
    fun startMonitoring() {
        // Monitoreo térmico en Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                        onThermalWarningListener?.invoke("Temperatura elevada del SoC. Se recomienda reducir nitidez/resolución.")
                    }
                }
                powerManager?.addThermalStatusListener(activity.mainExecutor, listener)
                thermalListener = listener
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Limpieza selectiva de caché multimedia post-sesión:
     * - Libera buffers temporales de vídeo de WebView.
     * - Limpia el directorio de caché temporal de la app en disco.
     * - Preserva intactas las Cookies de Xbox Live y las preferencias de usuario.
     */
    fun performSelectiveCacheDetox() {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                // Limpiar únicamente memoria volátil del WebView
                webView.clearCache(false)

                // Limpiar archivos multimedia en cacheDir sin borrar shared_prefs
                val cacheDir = activity.cacheDir
                deleteDirContent(cacheDir, preserveAuth = true)

                // Invocar recolección de basura suave
                System.gc()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun deleteDirContent(dir: File?, preserveAuth: Boolean) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name.lowercase()
            // Proteger Cookies de autenticación y estructuras internas de Chromium Simple Cache
            if (preserveAuth && (
                    name.contains("cookie") ||
                    name.contains("chromium") ||
                    name.contains("webview") ||
                    name.contains("org.chromium") ||
                    name.contains("cache_") ||
                    name.contains("index")
                )
            ) {
                continue
            }
            if (f.isDirectory) {
                deleteDirContent(f, preserveAuth)
            }
            try {
                f.delete()
            } catch (ignored: Exception) {}
        }
    }

    fun onDestroy() {
        releaseLocks()
        wifiLock = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                val powerManager = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
                (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                    powerManager?.removeThermalStatusListener(it)
                }
            } catch (ignored: Exception) {}
            thermalListener = null
        }
    }
}
