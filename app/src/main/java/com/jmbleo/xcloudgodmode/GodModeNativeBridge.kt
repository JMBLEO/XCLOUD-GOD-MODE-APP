package com.jmbleo.xcloudgodmode

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

class GodModeNativeBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {
    @Volatile var enableNetworkBypass: Boolean = false
    @Volatile var activeBypassIp: String = ""
    @Volatile var blockAnalytics: Boolean = true
    @Volatile var skipFeedback: Boolean = true
    @Volatile var storeLocale: String = ""

    @JavascriptInterface
    fun updateNativeConfig(jsonConfig: String?) {
        if (jsonConfig.isNullOrBlank()) return
        try {
            val obj = JSONObject(jsonConfig)
            enableNetworkBypass = obj.optBoolean("enableNetworkBypass", false)
            val ipChoice = obj.optString("bypassIp", "").replace("[\r\n\t]".toRegex(), "").trim()
            val customIp = obj.optString("customIp", "").replace("[\r\n\t]".toRegex(), "").trim()
            activeBypassIp = if (ipChoice.equals("custom", ignoreCase = true)) customIp else ipChoice
            blockAnalytics = obj.optBoolean("blockAnalytics", true)
            skipFeedback = obj.optBoolean("skipFeedback", true)
            storeLocale = obj.optString("storeLocale", "").replace("[\r\n\t]".toRegex(), "").trim()
        } catch (e: Exception) {
            // Log y fallback seguro a valores por defecto para no colapsar la app
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun updateTelemetry(fps: Int, rttMs: Int, jitterMs: Int, packetLoss: Float, bitrate: Float, resolution: String?) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            activity.updateTelemetry(fps, rttMs, jitterMs, packetLoss, bitrate, resolution)
        }
    }

    @JavascriptInterface
    fun toggleGodModeOverlay() {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            activity.toggleGodModeOverlay()
        }
    }

    /**
     * Se ejecuta cuando termina runPostGameCacheDetox() en el script JS.
     * Limpia archivos multimedia temporales y caché de RAM/Disco de WebView en el hilo principal,
     * manteniendo 100% intactos CookieManager (sesión Xbox Live) y domStorage (GodModeUltimateConfig).
     */
    @JavascriptInterface
    fun clearNativeMediaCache() {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                activity.performCacheDetox()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Acciona los motores de vibración del mando físico Bluetooth/USB (Android 12+)
     * con soporte para rumble estéreo multicanal (motor izquierdo fuerte + motor derecho suave)
     * y redirige la vibración al motor háptico del teléfono/tablet con blindaje total de excepciones.
     */
    @JavascriptInterface
    fun vibrateController(durationMs: Int, weakAmplitude: Int, strongAmplitude: Int) {
        try {
            val safeDuration = durationMs.coerceIn(10, 4000).toLong()
            val safeWeak = weakAmplitude.coerceIn(0, 255)
            val safeStrong = strongAmplitude.coerceIn(0, 255)
            val combinedAmp = maxOf(safeWeak, safeStrong).coerceIn(1, 255)

            var didVibrateGamepad = false

            // 1. Mandos en Android 12+ (API 31+) con VibratorManager multicanal (DualSense, Xbox Wireless, Gamesir, etc.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (deviceId in InputDevice.getDeviceIds()) {
                    val dev = InputDevice.getDevice(deviceId) ?: continue
                    val isGamepad = (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                                    (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    if (isGamepad) {
                        try {
                            val vm = dev.vibratorManager
                            val ids = vm.vibratorIds
                            if (ids.isNotEmpty()) {
                                if (ids.size >= 2) {
                                    val effLeft = VibrationEffect.createOneShot(safeDuration, if (safeStrong > 0) safeStrong else combinedAmp)
                                    val effRight = VibrationEffect.createOneShot(safeDuration, if (safeWeak > 0) safeWeak else combinedAmp)
                                    val combined = CombinedVibration.startParallel()
                                        .addVibrator(ids[0], effLeft)
                                        .addVibrator(ids[1], effRight)
                                        .combine()
                                    vm.vibrate(combined)
                                } else {
                                    val effect = VibrationEffect.createOneShot(safeDuration, combinedAmp)
                                    vm.vibrate(CombinedVibration.createParallel(effect))
                                }
                                didVibrateGamepad = true
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } else {
                // 2. Mandos en Android 8 a 11 (API 26 a 30) usando dev.vibrator
                for (deviceId in InputDevice.getDeviceIds()) {
                    val dev = InputDevice.getDevice(deviceId) ?: continue
                    val isGamepad = (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                                    (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    if (isGamepad) {
                        try {
                            @Suppress("DEPRECATION")
                            val vib = dev.vibrator
                            if (vib?.hasVibrator() == true) {
                                vib.vibrate(VibrationEffect.createOneShot(safeDuration, combinedAmp))
                                didVibrateGamepad = true
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            // 3. Si no vibró un mando externo, o como respuesta táctil activa, vibrar el dispositivo (teléfono o tablet)
            if (!didVibrateGamepad) {
                try {
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                        vm?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }

                    if (vibrator?.hasVibrator() == true) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(safeDuration, combinedAmp))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(safeDuration)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun vibrate(durationMs: Int, weakAmplitude: Int, strongAmplitude: Int) {
        vibrateController(durationMs, weakAmplitude, strongAmplitude)
    }

    @JavascriptInterface
    fun vibrate(durationMs: Int) {
        vibrateController(durationMs, 180, 180)
    }

    /**
     * Captura nativa del cursor de ratón USB/Bluetooth en Android (Oculta el cursor negro de Android
     * y envía deltas infinitos al emulador M&K del script).
     * Se valida versión de API y adjunción a ventana antes de ejecutar.
     */
    @JavascriptInterface
    fun setPointerCapture(enable: Boolean) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    if (enable) {
                        if (webView.isAttachedToWindow) {
                            webView.requestFocus()
                            webView.requestPointerCapture()
                        }
                    } else {
                        if (webView.hasPointerCapture()) {
                            webView.releasePointerCapture()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Ajusta la frecuencia de refresco física de la pantalla de Android a 60Hz o 120Hz
     * según el switch unlock120Fps de God Mode AI.
     */
    @JavascriptInterface
    fun setPreferredRefreshRate(targetHz: Float) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                activity.applyDisplayRefreshRate(targetHz)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Abre el Copiloto IA nativo (Gemini 3.1 Flash Lite / Gemini 4 Argon) de forma segura en UI thread.
     */
    @JavascriptInterface
    fun openAiCopilot() {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                activity.showAiCopilotDialog()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
