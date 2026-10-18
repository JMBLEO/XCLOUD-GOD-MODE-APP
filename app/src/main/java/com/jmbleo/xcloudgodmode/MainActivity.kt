package com.jmbleo.xcloudgodmode

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jmbleo.xcloudgodmode.ui.AcrylicOverlayView
import com.jmbleo.xcloudgodmode.ui.TelemetryData
import kotlinx.coroutines.launch

open class MainActivity : AppCompatActivity() {

    companion object {
        init {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    lateinit var webView: WebView
    lateinit var acrylicOverlay: AcrylicOverlayView
    lateinit var composeDashboard: androidx.compose.ui.platform.ComposeView
    lateinit var streamContainer: FrameLayout
    private lateinit var nativeBridge: GodModeNativeBridge
    private lateinit var controllerInputManager: ControllerInputManager
    private lateinit var performanceManager: StreamPerformanceManager

    var isDocumentStartSupported: Boolean = false
    lateinit var godModeUserscriptCode: String
    val triggerAccountWarningState = androidx.compose.runtime.mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 0. Configurar tempranamente las banderas de aceleración por hardware y formato EGL para evitar errores de Rendernode
        try {
            window.setFormat(PixelFormat.RGBA_8888)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        super.onCreate(savedInstanceState)
        com.aistudio.xcloudgodmode.ai.auth.XboxAuthManager.configureCookieStorage()

        // 1. Configurar Pantalla Completa Inmersiva (Incluyendo área de Notch/Cutout) y Mantener Pantalla Encendida
        setupImmersiveFullscreen()

        // 2. Comprobar y solicitar permisos Bluetooth en tiempo de ejecución al iniciar la app para Chromium
        checkBluetoothPermissions()

        // 3. Bloquear el chip Wi-Fi en Modo de Ultra Baja Latencia (Cero DTIM Skipping / Cero Lag Spikes)
        acquireLowLatencyWifiLock()

        // 4. Configurar el subsistema de Audio de Android para Baja Latencia en WebRTC
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. Leer el script maestro xcloud-god-mode-ai.user.js desde assets/
        godModeUserscriptCode = try {
            assets.open("xcloud-god-mode-ai.user.js")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }

        // 6. Inicializar y Optimizar el Motor WebView con Aceleración por Hardware Obligatoria
        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = true
            // Aceleración por GPU en la superficie de la ventana, evitando la asignación de capas off-screen de Mesa
            setLayerType(View.LAYER_TYPE_NONE, null)
        }

        acrylicOverlay = AcrylicOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        streamContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(acrylicOverlay)
        }

        composeDashboard = androidx.compose.ui.platform.ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.VISIBLE
            setContent {
                val triggerWarning by triggerAccountWarningState
                com.jmbleo.xcloudgodmode.ui.compose.ConsoleDashboardScreen(
                    onLaunchGame = { targetUrl ->
                        startStreamSession(targetUrl)
                    },
                    onOpenSettings = {},
                    onApplyRefreshRate = { hz ->
                        applyDisplayRefreshRate(hz)
                    },
                    onDetoxCache = {
                        performCacheDetox()
                    },
                    onTestRumble = {
                        nativeBridge.vibrateController(300, 180, 220)
                    },
                    triggerAccountWarning = triggerWarning,
                    onResetAccountWarning = {
                        triggerAccountWarningState.value = false
                    }
                )
            }
        }

        val rootContainer = FrameLayout(this).apply {
            addView(streamContainer)
            addView(composeDashboard)
        }
        setContentView(rootContainer)

        nativeBridge = GodModeNativeBridge(this, webView)
        controllerInputManager = ControllerInputManager(webView)
        performanceManager = StreamPerformanceManager(this, webView)
        performanceManager.startMonitoring()

        controllerInputManager.onComboTriggered = {
            runOnUiThread { toggleGodModeOverlay() }
        }
        controllerInputManager.onStickMovedListener = { x, y ->
            runOnUiThread {
                acrylicOverlay.updateLiveStickPosition(x, y)
            }
        }
        acrylicOverlay.onExitStreamListener = {
            exitStreamSession()
        }
        acrylicOverlay.onDetoxCacheListener = {
            performCacheDetox()
        }

        webView.addJavascriptInterface(nativeBridge, "GodModeAndroid")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // User-Agent de Microsoft Edge en Windows 11 para desbloquear nativamente 1080p/1440p/4K, HEVC/AV1 y alto bitrate
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
        }

        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        webView.webViewClient = GodModeWebViewClient(this, nativeBridge)
        webView.webChromeClient = GodModeWebChromeClient(this, webView)

        // 7. INYECCIÓN TEMPRANA REAL (@run-at document-start)
        // Inyecta el script en el milisegundo 0 de creación del objeto window en cualquier subdominio de xbox.com
        if (godModeUserscriptCode.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            isDocumentStartSupported = true
            val allowedOrigins = setOf("https://*.xbox.com", "https://xbox.com")
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, godModeUserscriptCode, allowedOrigins)
            } catch (e: Exception) {
                e.printStackTrace()
                isDocumentStartSupported = false
            }
        }
    }

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
            if (permissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 102)
            }
        }
    }

    private fun setupImmersiveFullscreen() {
        try {
            window.setFormat(PixelFormat.RGBA_8888)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.decorView.setBackgroundColor(Color.BLACK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireLowLatencyWifiLock() {
        try {
            performanceManager.acquireLocks()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Fija la tasa de refresco física del panel OLED/AMOLED de Android (60Hz o 120Hz)
     * para sincronizar los fotogramas del stream de WebRTC sin micro-stuttering.
     */
    @Suppress("DEPRECATION")
    fun applyDisplayRefreshRate(targetHz: Float) {
        if (isFinishing || isDestroyed) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) this.display else windowManager.defaultDisplay
                val modes = display?.supportedModes ?: return
                val bestMode = modes.minByOrNull { kotlin.math.abs(it.refreshRate - targetHz) }
                if (bestMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = bestMode.modeId
                    window.attributes = params
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Notifica que se requiere iniciar sesión en Xbox Live, retornando de inmediato al Home
     * de la nueva interfaz y desplegando la advertencia de problemas con la cuenta.
     */
    fun notifySessionRequired() {
        runOnUiThread {
            exitStreamSession()
            triggerAccountWarningState.value = true
        }
    }

    /**
     * Inicia una sesión inmersiva de streaming con aceleración por GPU e inyección de script God Mode.
     * Adquiere WifiLock y WakeLock para garantizar latencia mínima y evitar suspensión.
     */
    fun startStreamSession(targetUrl: String = "https://www.xbox.com/play/launch/9NKX70BBC2TN") {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread

            // Validar sesión antes de cargar el stream; si no hay sesión, volver al Home y advertir
            if (!com.aistudio.xcloudgodmode.ai.auth.XboxAuthManager.hasActiveSession(this)) {
                notifySessionRequired()
                return@runOnUiThread
            }

            composeDashboard.visibility = View.GONE
            streamContainer.visibility = View.VISIBLE
            setupImmersiveFullscreen()
            performanceManager.acquireLocks()
            if (webView.url.isNullOrBlank() || targetUrl != webView.url) {
                webView.loadUrl(targetUrl)
            }
            webView.requestFocus()
        }
    }

    /**
     * Sale de la sesión de streaming activa y regresa instantáneamente al Launcher Nativo en Compose.
     * Detiene el render de WebRTC, libera los bloqueos de hardware y ejecuta la limpieza de caché temporal.
     */
    fun exitStreamSession() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            performanceManager.releaseLocks()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            streamContainer.visibility = View.GONE
            composeDashboard.visibility = View.VISIBLE
            acrylicOverlay.toggleSettingsPanel(false)
            setupImmersiveFullscreen()
            performCacheDetox()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        try {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                if (streamContainer.visibility == View.VISIBLE) {
                    exitStreamSession()
                    return true
                }
            }
            if (controllerInputManager.handleKeyEvent(event)) {
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        try {
            if (controllerInputManager.handleGenericMotionEvent(event)) {
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    fun updateTelemetry(fps: Int, rttMs: Int, jitterMs: Int, packetLoss: Float, bitrate: Float, resolution: String?) {
        acrylicOverlay.updateTelemetry(
            TelemetryData(
                fps = fps,
                rttMs = rttMs,
                jitterMs = jitterMs,
                packetLossPercent = packetLoss,
                bitrateMbps = bitrate,
                resolution = resolution ?: "1080p HQ"
            )
        )
    }

    fun toggleGodModeOverlay() {
        acrylicOverlay.toggleSettingsPanel()
    }

    fun performCacheDetox() {
        performanceManager.performSelectiveCacheDetox()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupImmersiveFullscreen()
        }
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveFullscreen()
        try {
            performanceManager.acquireLocks()
            webView.resumeTimers()
            webView.onResume()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        try {
            performanceManager.releaseLocks()
            webView.onPause()
            webView.pauseTimers()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onPause()
    }

    /**
     * Prevención estricta de fugas de memoria (Memory Leaks):
     * - Remueve el WebView dinámicamente de su ViewGroup padre.
     * - Detiene cargas activas y desregistra clientes.
     * - Destruye el WebView con seguridad.
     * - Libera locks y listeners del performance manager.
     */
    override fun onDestroy() {
        try {
            performanceManager.onDestroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            CookieManager.getInstance().flush()
        } catch (ignored: Exception) {}

        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = android.webkit.WebViewClient()
            webView.removeJavascriptInterface("GodModeAndroid")
            webView.clearHistory()
            webView.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        super.onDestroy()
    }
}
