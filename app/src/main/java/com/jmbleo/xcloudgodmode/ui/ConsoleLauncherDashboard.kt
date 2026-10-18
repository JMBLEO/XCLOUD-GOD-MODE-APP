package com.jmbleo.xcloudgodmode.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.jmbleo.xcloudgodmode.config.GodModeConfig
import com.jmbleo.xcloudgodmode.config.GodModeConfigManager
import com.jmbleo.xcloudgodmode.config.ResponseCurve
import com.jmbleo.xcloudgodmode.config.ScalingProfile

/**
 * Dashboard nativo estilo consola inspirado en XStreaming y XFly.
 * Reemplaza la interfaz web directa con un lanzador moderno de juegos,
 * gestión de Remote Play, calibración en tiempo real de mandos y ajustes de renderizado.
 */
@SuppressLint("SetTextI18n")
class ConsoleLauncherDashboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val configManager = GodModeConfigManager.getInstance(context)

    // Callbacks de navegación y control
    var onLaunchStreamListener: ((url: String) -> Unit)? = null
    var onTestRumbleListener: (() -> Unit)? = null
    var onDetoxCacheListener: (() -> Unit)? = null
    var onApplyRefreshRateListener: ((hz: Float) -> Unit)? = null

    // Contenedores
    private val contentScrollView: ScrollView
    private val mainContainer: LinearLayout
    private val tabContentContainer: FrameLayout

    // Tabs
    private var currentTab = 0 // 0: Juegos Cloud, 1: Calibración Mando, 2: Ajustes de Render

    // Botones de Tab
    private lateinit var btnTabGames: Button
    private lateinit var btnTabController: Button
    private lateinit var btnTabVideo: Button

    // Visualizador de joystick en vivo para la pestaña de calibración
    private val liveStickView = JoystickCalibrationView(context)

    init {
        // Fondo base oscuro profundo de consola
        setBackgroundColor(Color.parseColor("#0A0F0C"))

        contentScrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 40)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        contentScrollView.addView(mainContainer)
        addView(contentScrollView)

        // 1. Barra Superior / Status Bar de Consola
        buildTopBar()

        // 2. Banner Hero de Lanzamiento Rápido
        buildHeroBanner()

        // 3. Barra de Pestañas de Navegación de Consola (XStreaming / XFly)
        buildTabBar()

        // 4. Contenedor Dinámico de Pestañas
        tabContentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
            }
        }
        mainContainer.addView(tabContentContainer)

        // Mostrar pestaña inicial (Juegos Cloud)
        switchTab(0)
    }

    private fun buildTopBar() {
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }

        // Logo y Título de Consola
        val logoLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        val logoBadge = TextView(context).apply {
            text = "⚡ GOD MODE"
            setTextColor(Color.BLACK)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = createShape("#52E172", radius = 10f)
            setPadding(16, 6, 16, 6)
        }
        val appTitle = TextView(context).apply {
            text = "XSTREAMING SUITE"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 0, 0, 0)
        }
        logoLayout.addView(logoBadge)
        logoLayout.addView(appTitle)
        topBar.addView(logoLayout)

        // Chips de Estado (Mando + Wi-Fi)
        val chipsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val chipGamepad = TextView(context).apply {
            text = "🎮 Gamepad Listo"
            setTextColor(Color.parseColor("#52E172"))
            textSize = 12f
            background = createShape("#1A52E172", strokeColor = "#4D52E172", radius = 16f)
            setPadding(20, 8, 20, 8)
        }
        val chipWifi = TextView(context).apply {
            text = "📶 Wi-Fi Low-Latency"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 12f
            background = createShape("#1FFFFFFF", strokeColor = "#26FFFFFF", radius = 16f)
            setPadding(20, 8, 20, 8)
            val p = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 12
            }
            layoutParams = p
        }
        chipsLayout.addView(chipGamepad)
        chipsLayout.addView(chipWifi)
        topBar.addView(chipsLayout)

        mainContainer.addView(topBar)
    }

    private fun buildHeroBanner() {
        val heroCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 32, 36, 32)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#152D1C"), Color.parseColor("#0E1A12"), Color.parseColor("#08120A"))
            ).apply {
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#4D52E172"))
            }
            elevation = 8f
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        val heroTag = TextView(context).apply {
            text = "STREAMING ULTRA BAJA LATENCIA (NATIVO)"
            setTextColor(Color.parseColor("#52E172"))
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val heroTitle = TextView(context).apply {
            text = "Xbox Cloud Gaming & Remote Play"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 6)
        }
        val heroDesc = TextView(context).apply {
            text = "Desbloqueo de resolución 1080p HQ / 1440p, escalado FSR/CAS y sondeo de controles a 0ms de latencia."
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 13f
        }
        textCol.addView(heroTag)
        textCol.addView(heroTitle)
        textCol.addView(heroDesc)
        heroCard.addView(textCol)

        // Botones de acción rápida en el hero
        val buttonsCol = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 0, 0, 0)
        }

        val btnRemotePlay = Button(context).apply {
            text = "📡 Remote Play"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = createShape("#2EFFFFFF", strokeColor = "#40FFFFFF", radius = 16f)
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                onLaunchStreamListener?.invoke("https://www.xbox.com/play")
            }
        }
        val btnLaunchCloud = Button(context).apply {
            text = "🚀 Iniciar Cloud Stream"
            setTextColor(Color.BLACK)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = createShape("#52E172", radius = 16f)
            setPadding(28, 12, 28, 12)
            val p = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 14
            }
            layoutParams = p
            setOnClickListener {
                onLaunchStreamListener?.invoke("https://www.xbox.com/play")
            }
        }
        buttonsCol.addView(btnRemotePlay)
        buttonsCol.addView(btnLaunchCloud)
        heroCard.addView(buttonsCol)

        mainContainer.addView(heroCard)
    }

    private fun buildTabBar() {
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 28
            }
        }

        val tabLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnTabGames = createTabButton("🎮 Catálogo Cloud", 0)
        btnTabController = createTabButton("🕹️ Calibración de Mando", 1)
        btnTabVideo = createTabButton("🎛️ Render & Escalado FSR", 2)

        tabLayout.addView(btnTabGames)
        tabLayout.addView(btnTabController)
        tabLayout.addView(btnTabVideo)
        tabScroll.addView(tabLayout)

        mainContainer.addView(tabScroll)
    }

    private fun createTabButton(title: String, index: Int): Button {
        return Button(context).apply {
            text = title
            textSize = 13f
            setPadding(28, 10, 28, 10)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 14
            }
            layoutParams = params
            setOnClickListener {
                switchTab(index)
            }
        }
    }

    private fun switchTab(index: Int) {
        currentTab = index

        // Estilos activos vs inactivos
        val tabs = listOf(btnTabGames, btnTabController, btnTabVideo)
        tabs.forEachIndexed { i, btn ->
            val isActive = i == currentTab
            btn.setTextColor(if (isActive) Color.BLACK else Color.WHITE)
            btn.background = if (isActive) {
                createShape("#52E172", radius = 16f)
            } else {
                createShape("#1AFFFFFF", strokeColor = "#26FFFFFF", radius = 16f)
            }
        }

        tabContentContainer.removeAllViews()
        when (index) {
            0 -> tabContentContainer.addView(buildGamesCatalogTab())
            1 -> tabContentContainer.addView(buildControllerCalibrationTab())
            2 -> tabContentContainer.addView(buildVideoSettingsTab())
        }
    }

    // --- TAB 1: Catálogo de Juegos Cloud Gaming ---
    private fun buildGamesCatalogTab(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val sectionTitle = TextView(context).apply {
            text = "Acceso Rápido a Títulos de Xbox Cloud Gaming"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 16)
        }
        root.addView(sectionTitle)

        val games = listOf(
            Triple("Halo Infinite", "Shooter • 60/120 FPS", "#1A3A28"),
            Triple("Forza Horizon 5", "Carreras • 4K HDR", "#2A2A1A"),
            Triple("Starfield", "RPG Espacial • 1440p HQ", "#182436"),
            Triple("Gears 5", "Acción Táctica • 120 FPS", "#361A1A"),
            Triple("Sea of Thieves", "Aventura Cooperativa", "#163436"),
            Triple("Hi-Fi RUSH", "Ritmo & Acción • Cero Latencia", "#341E3A")
        )

        // Cuadrícula 2 columnas o filas horizontales
        for (i in games.indices step 2) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 16)
            }

            row.addView(createGameCard(games[i]))
            if (i + 1 < games.size) {
                row.addView(createGameCard(games[i + 1]))
            }
            root.addView(row)
        }

        return root
    }

    private fun createGameCard(game: Triple<String, String, String>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 16
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor(game.third), Color.parseColor("#101612"))
            ).apply {
                cornerRadius = 18f
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }

            val title = TextView(context).apply {
                text = game.first
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val subtitle = TextView(context).apply {
                text = game.second
                setTextColor(Color.parseColor("#80FFFFFF"))
                textSize = 12f
                setPadding(0, 4, 0, 14)
            }
            val btnPlay = Button(context).apply {
                text = "▶ Jugar en la Nube"
                setTextColor(Color.BLACK)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = createShape("#52E172", radius = 12f)
                setOnClickListener {
                    onLaunchStreamListener?.invoke("https://www.xbox.com/play")
                }
            }

            addView(title)
            addView(subtitle)
            addView(btnPlay)
        }
    }

    // --- TAB 2: Calibración de Mando & Deadzones ---
    private fun buildControllerCalibrationTab(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = createShape("#E6121C15", strokeColor = "#3352E172", radius = 20f)
        }

        val cfg = configManager.getConfig()

        val title = TextView(context).apply {
            text = "Calibración Matemática de Joysticks (Latencia Cero)"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        val desc = TextView(context).apply {
            text = "Mueve las palancas de tu mando físico para observar el vector en tiempo real y calibrar las zonas muertas radiales."
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 12f
            setPadding(0, 0, 0, 20)
        }
        root.addView(title)
        root.addView(desc)

        // Fila: Canvas de stick + Sliders
        val stickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        liveStickView.layoutParams = LinearLayout.LayoutParams(260, 260).apply {
            marginEnd = 32
        }
        liveStickView.updateDeadzones(cfg.innerDeadzone, cfg.outerDeadzone)
        stickRow.addView(liveStickView)

        val slidersCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvInner = TextView(context).apply {
            text = "Zona Muerta Interior (Radial): ${(cfg.innerDeadzone * 100).toInt()}%"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val seekInner = SeekBar(context).apply {
            max = 35
            progress = (cfg.innerDeadzone * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    tvInner.text = "Zona Muerta Interior (Radial): $prog%"
                    val inner = prog / 100f
                    liveStickView.updateDeadzones(inner, liveStickView.outerDeadzone)
                    if (fromUser) {
                        configManager.updateConfig(configManager.getConfig().copy(innerDeadzone = inner))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val tvOuter = TextView(context).apply {
            text = "Límite Exterior (Saturación al 100%): ${(cfg.outerDeadzone * 100).toInt()}%"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }
        val seekOuter = SeekBar(context).apply {
            max = 30
            progress = ((cfg.outerDeadzone - 0.70f) * 100).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val outer = 0.70f + (prog / 100f)
                    tvOuter.text = "Límite Exterior (Saturación al 100%): ${(outer * 100).toInt()}%"
                    liveStickView.updateDeadzones(liveStickView.innerDeadzone, outer)
                    if (fromUser) {
                        configManager.updateConfig(configManager.getConfig().copy(outerDeadzone = outer))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        slidersCol.addView(tvInner)
        slidersCol.addView(seekInner)
        slidersCol.addView(tvOuter)
        slidersCol.addView(seekOuter)
        stickRow.addView(slidersCol)
        root.addView(stickRow)

        // Botones de Layout A/B, X/Y y Prueba de Vibración Háptica
        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }

        val btnSwapAB = Button(context).apply {
            text = if (cfg.swapAB) "✓ Mapeo A/B Activo (Switch)" else "Intercambiar A/B"
            setTextColor(if (cfg.swapAB) Color.BLACK else Color.WHITE)
            background = createShape(if (cfg.swapAB) "#52E172" else "#26FFFFFF", strokeColor = "#33FFFFFF", radius = 12f)
            setOnClickListener {
                val next = !configManager.getConfig().swapAB
                configManager.updateConfig(configManager.getConfig().copy(swapAB = next))
                text = if (next) "✓ Mapeo A/B Activo (Switch)" else "Intercambiar A/B"
                background = createShape(if (next) "#52E172" else "#26FFFFFF", strokeColor = "#33FFFFFF", radius = 12f)
                setTextColor(if (next) Color.BLACK else Color.WHITE)
            }
        }

        val btnSwapXY = Button(context).apply {
            text = if (cfg.swapXY) "✓ Mapeo X/Y Activo" else "Intercambiar X/Y"
            setTextColor(if (cfg.swapXY) Color.BLACK else Color.WHITE)
            background = createShape(if (cfg.swapXY) "#52E172" else "#26FFFFFF", strokeColor = "#33FFFFFF", radius = 12f)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
            setOnClickListener {
                val next = !configManager.getConfig().swapXY
                configManager.updateConfig(configManager.getConfig().copy(swapXY = next))
                text = if (next) "✓ Mapeo X/Y Activo" else "Intercambiar X/Y"
                background = createShape(if (next) "#52E172" else "#26FFFFFF", strokeColor = "#33FFFFFF", radius = 12f)
                setTextColor(if (next) Color.BLACK else Color.WHITE)
            }
        }

        val btnTestRumble = Button(context).apply {
            text = "⚡ Probar Vibración"
            setTextColor(Color.WHITE)
            background = createShape("#2EFFFFFF", strokeColor = "#4D52E172", radius = 12f)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
            setOnClickListener {
                onTestRumbleListener?.invoke()
            }
        }

        buttonsRow.addView(btnSwapAB)
        buttonsRow.addView(btnSwapXY)
        buttonsRow.addView(btnTestRumble)
        root.addView(buttonsRow)

        return root
    }

    // --- TAB 3: Ajustes de Render & Escalado FSR/CAS ---
    private fun buildVideoSettingsTab(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = createShape("#E6121C15", strokeColor = "#3352E172", radius = 20f)
        }

        val cfg = configManager.getConfig()

        val title = TextView(context).apply {
            text = "Motor de Renderizado & Post-Procesado Visual (XFly / FSR)"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        // Selector de Perfil de Escalado
        val profileLabel = TextView(context).apply {
            text = "Perfil de Reconstrucción de Imagen:"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        root.addView(profileLabel)

        val profileGroup = RadioGroup(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        ScalingProfile.values().forEach { profile ->
            val rb = RadioButton(context).apply {
                text = profile.name
                setTextColor(Color.WHITE)
                isChecked = cfg.scalingProfile == profile
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        configManager.updateConfig(configManager.getConfig().copy(scalingProfile = profile))
                    }
                }
            }
            profileGroup.addView(rb)
        }
        root.addView(profileGroup)

        // Sliders de Nitidez (Sharpness) y Saturación
        val tvSharpness = TextView(context).apply {
            text = "Nitidez FSR/CAS: ${cfg.sharpness}%"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val seekSharpness = SeekBar(context).apply {
            max = 100
            progress = cfg.sharpness
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    tvSharpness.text = "Nitidez FSR/CAS: $prog%"
                    if (fromUser) {
                        configManager.updateConfig(configManager.getConfig().copy(sharpness = prog))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(tvSharpness)
        root.addView(seekSharpness)

        // Fila: Frecuencia de Refresco (60Hz / 120Hz) y Detox de Caché
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 0)
        }

        val btn60Hz = Button(context).apply {
            text = "Pantalla 60 Hz"
            setTextColor(Color.WHITE)
            background = createShape("#26FFFFFF", strokeColor = "#33FFFFFF", radius = 12f)
            setOnClickListener {
                onApplyRefreshRateListener?.invoke(60f)
            }
        }
        val btn120Hz = Button(context).apply {
            text = "Pantalla 120 Hz"
            setTextColor(Color.BLACK)
            background = createShape("#52E172", radius = 12f)
            val p = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
            layoutParams = p
            setOnClickListener {
                onApplyRefreshRateListener?.invoke(120f)
            }
        }
        val btnDetox = Button(context).apply {
            text = "🧹 Detox Caché"
            setTextColor(Color.WHITE)
            background = createShape("#33E74040", strokeColor = "#80E74040", radius = 12f)
            val p = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
            layoutParams = p
            setOnClickListener {
                onDetoxCacheListener?.invoke()
            }
        }
        actionsRow.addView(btn60Hz)
        actionsRow.addView(btn120Hz)
        actionsRow.addView(btnDetox)
        root.addView(actionsRow)

        return root
    }

    /**
     * Actualiza la visualización de la palanca del mando en vivo cuando se mueve un stick.
     */
    fun updateLiveStickPosition(x: Float, y: Float) {
        liveStickView.updateLivePosition(x, y)
    }

    private fun createShape(
        colorHex: String,
        strokeColor: String? = null,
        radius: Float = 16f
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = radius
            if (!strokeColor.isNullOrBlank()) {
                setStroke(2, Color.parseColor(strokeColor))
            }
        }
    }
}
