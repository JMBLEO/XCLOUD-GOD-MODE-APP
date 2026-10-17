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
    private lateinit var nativeBridge: GodModeNativeBridge
    private lateinit var controllerInputManager: ControllerInputManager
    private lateinit var performanceManager: StreamPerformanceManager
    private var activeAiDialog: AlertDialog? = null

    var isDocumentStartSupported: Boolean = false
    lateinit var godModeUserscriptCode: String

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
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        acrylicOverlay = AcrylicOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val rootContainer = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(acrylicOverlay)
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
            runOnUiThread { acrylicOverlay.updateLiveStickPosition(x, y) }
        }
        acrylicOverlay.onOpenCopilotListener = {
            showAiCopilotDialog()
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

        // 8. Cargar el portal de Xbox Cloud Gaming / Remote Play
        webView.loadUrl("https://www.xbox.com/play")
        webView.requestFocus()
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
     * Diálogo de Copiloto IA con soporte para:
     * 1. Low-Latency Responses (gemini-3.1-flash-lite)
     * 2. High Thinking (gemini-4-argon con thinkingLevel = high)
     */
    fun showAiCopilotDialog() {
        if (isFinishing || isDestroyed) return

        try {
            activeAiDialog?.dismiss()
        } catch (ignored: Exception) {}

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121D15"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#4D52E172"))
            }
        }

        val headerText = TextView(this).apply {
            text = "⚡ God Mode AI Copilot"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        rootLayout.addView(headerText)

        val subText = TextView(this).apply {
            text = "Asistente de optimización de stream, baja latencia y tácticas de juego"
            textSize = 12f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(0, 4, 0, 16)
        }
        rootLayout.addView(subText)

        // Selector de modo: Baja Latencia (gemini-3.1-flash-lite) vs Pensamiento Alto (gemini-4-argon)
        val modeGroup = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        val rbLowLatency = RadioButton(this).apply {
            id = View.generateViewId()
            text = "⚡ Baja Latencia (Flash Lite)"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val rbThinking = RadioButton(this).apply {
            id = View.generateViewId()
            text = "🧠 Pensamiento Alto (Argon)"
            setTextColor(Color.WHITE)
        }
        modeGroup.addView(rbLowLatency)
        modeGroup.addView(rbThinking)
        rootLayout.addView(modeGroup)

        // Chips de sugerencia rápida
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 12)
        }
        val chipsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val promptInput = EditText(this).apply {
            hint = "Escribe tu duda de juego o consulta de red..."
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66000000"))
                cornerRadius = 12f
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
            setPadding(20, 16, 20, 16)
        }

        val suggestions = listOf(
            "Diagnosticar latencia y jitter de mi stream",
            "¿Qué shader conviene: Real-ESRGAN o SeedVR2?",
            "Optimizar controles y deadzone para FPS competitivo",
            "Estrategia para vencer al boss actual"
        )
        for (s in suggestions) {
            val chip = Button(this).apply {
                text = s
                textSize = 11f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#33FFFFFF"))
                    cornerRadius = 20f
                    setStroke(1, Color.parseColor("#26FFFFFF"))
                }
                setPadding(20, 8, 20, 8)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 12
                }
                layoutParams = params
                setOnClickListener {
                    promptInput.setText(s)
                }
            }
            chipsLayout.addView(chip)
        }
        chipsScroll.addView(chipsLayout)
        rootLayout.addView(chipsScroll)

        rootLayout.addView(promptInput)

        val progress = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, 16, 0, 16)
        }
        rootLayout.addView(progress)

        val responseScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                380
            )
            setPadding(0, 12, 0, 12)
        }
        val responseText = TextView(this).apply {
            text = "El copiloto está listo. Puedes seleccionar 'Baja Latencia' para respuestas ultra-rápidas en tiempo real, o 'Pensamiento Alto' para análisis estratégico profundo."
            textSize = 13f
            setTextColor(Color.parseColor("#E6FFFFFF"))
            setLineSpacing(4f, 1.15f)
        }
        responseScroll.addView(responseText)
        rootLayout.addView(responseScroll)

        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        val btnAsk = Button(this).apply {
            text = "Consultar"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#52E172"))
                cornerRadius = 12f
            }
            setOnClickListener {
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) return@setOnClickListener

                val mode = if (rbThinking.isChecked) "thinking" else "low_latency"
                progress.visibility = View.VISIBLE
                responseText.text = if (mode == "thinking") {
                    "Razonando a profundidad con Gemini 4 Argon (Thinking Mode)..."
                } else {
                    "Generando respuesta de baja latencia con Gemini 3.1 Flash Lite..."
                }

                lifecycleScope.launch {
                    val result = GeminiAiAssistant.query(prompt, mode)
                    if (!isFinishing && !isDestroyed) {
                        progress.visibility = View.GONE
                        responseText.text = result
                    }
                }
            }
        }

        val btnClose = Button(this).apply {
            text = "Cerrar"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33000000"))
                cornerRadius = 12f
            }
            setOnClickListener {
                dialog.dismiss()
                setupImmersiveFullscreen()
            }
        }

        buttonsLayout.addView(btnClose)
        val space = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(16, 1)
        }
        buttonsLayout.addView(space)
        buttonsLayout.addView(btnAsk)
        rootLayout.addView(buttonsLayout)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        activeAiDialog = dialog
        dialog.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        try {
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
            activeAiDialog?.dismiss()
            activeAiDialog = null
        } catch (ignored: Exception) {}

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
