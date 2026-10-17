package com.jmbleo.xcloudgodmode.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import com.jmbleo.xcloudgodmode.config.HudPosition
import com.jmbleo.xcloudgodmode.config.ResponseCurve
import com.jmbleo.xcloudgodmode.config.ScalingProfile

/**
 * Overlay flotante con diseño Acrylic Glassmorphism para telemetría en tiempo real
 * y panel de ajustes avanzados in-game de mandos y post-procesado.
 */
@SuppressLint("SetTextI18n")
class AcrylicOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val configManager = GodModeConfigManager.getInstance(context)

    // Vistas principales
    private val hudCard: LinearLayout
    private val settingsCard: ScrollView
    private val settingsContentLayout: LinearLayout

    // Indicadores de Telemetría
    private val tvFps: TextView
    private val tvPing: TextView
    private val tvJitterLoss: TextView
    private val tvBitrateRes: TextView
    private val healthIndicatorDot: View

    // Canvas interactivo de calibración de joystick
    private val joystickVisualizer: JoystickCalibrationView

    // Eventos externos
    var onOpenCopilotListener: (() -> Unit)? = null
    var onDetoxCacheListener: (() -> Unit)? = null

    private var currentTelemetry = TelemetryData()

    init {
        // Fondo transparente para no bloquear toques del juego en áreas libres
        setBackgroundColor(Color.TRANSPARENT)

        // 1. Tarjeta compacta del HUD
        hudCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
            background = createGlassDrawable("#D90D1A10", "#4D52E172", 20f)
            elevation = 12f

            setOnClickListener {
                toggleSettingsPanel(true)
            }
        }

        healthIndicatorDot = View(context).apply {
            val size = 16
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = 14
            }
            background = createGlassDrawable("#52E172", "#00000000", 8f)
        }
        hudCard.addView(healthIndicatorDot)

        tvFps = TextView(context).apply {
            text = "60 FPS"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 16, 0)
        }
        hudCard.addView(tvFps)

        tvPing = TextView(context).apply {
            text = "24ms"
            setTextColor(Color.parseColor("#52E172"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 16, 0)
        }
        hudCard.addView(tvPing)

        tvJitterLoss = TextView(context).apply {
            text = "J: 2ms • Loss: 0%"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 11f
            setPadding(0, 0, 16, 0)
        }
        hudCard.addView(tvJitterLoss)

        tvBitrateRes = TextView(context).apply {
            text = "1080p HQ • 14.5M"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 10f
        }
        hudCard.addView(tvBitrateRes)

        val hudParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 28
            marginEnd = 32
        }
        addView(hudCard, hudParams)

        // 2. Tarjeta expandida de ajustes Glassmorphism
        settingsContentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            background = createGlassDrawable("#EE0D1610", "#52E172", 24f)
        }

        settingsCard = ScrollView(context).apply {
            visibility = View.GONE
            elevation = 20f
            addView(settingsContentLayout)
        }

        val settingsParams = LayoutParams(
            1100,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = 32
            bottomMargin = 32
        }
        addView(settingsCard, settingsParams)

        // Vista de calibración de joystick
        joystickVisualizer = JoystickCalibrationView(context)

        // Construir contenido del menú rápido de ajustes
        buildSettingsMenuContent()

        // Sincronizar posición del HUD según configuración
        applyHudPosition(configManager.getConfig().hudPosition)
    }

    private fun buildSettingsMenuContent() {
        val currentCfg = configManager.getConfig()

        // Encabezado
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val title = TextView(context).apply {
            text = "⚡ God Mode Native Quick Settings"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = Button(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            background = createGlassDrawable("#33FFFFFF", "#00000000", 16f)
            layoutParams = LinearLayout.LayoutParams(70, 70)
            setOnClickListener {
                toggleSettingsPanel(false)
            }
        }
        headerLayout.addView(title)
        headerLayout.addView(btnClose)
        settingsContentLayout.addView(headerLayout)

        // Fila 1: Calibrador de Joystick + Sliders de Deadzone
        val joystickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 16)
        }

        joystickVisualizer.layoutParams = LinearLayout.LayoutParams(240, 240).apply {
            marginEnd = 24
        }
        joystickRow.addView(joystickVisualizer)

        val deadzoneSlidersLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvInnerLabel = TextView(context).apply {
            text = "Zona Muerta Interior: ${(currentCfg.innerDeadzone * 100).toInt()}%"
            setTextColor(Color.parseColor("#E6FFFFFF"))
            textSize = 12f
        }
        val seekInner = SeekBar(context).apply {
            max = 30
            progress = (currentCfg.innerDeadzone * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    tvInnerLabel.text = "Zona Muerta Interior: $prog%"
                    val newInner = prog / 100f
                    joystickVisualizer.updateDeadzones(newInner, joystickVisualizer.outerDeadzone)
                    if (fromUser) {
                        configManager.updateConfig(configManager.getConfig().copy(innerDeadzone = newInner))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val tvOuterLabel = TextView(context).apply {
            text = "Límite Exterior: ${(currentCfg.outerDeadzone * 100).toInt()}%"
            setTextColor(Color.parseColor("#E6FFFFFF"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        val seekOuter = SeekBar(context).apply {
            max = 30 // Rango 70% a 100%
            progress = ((currentCfg.outerDeadzone - 0.70f) * 100).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val outer = 0.70f + (prog / 100f)
                    tvOuterLabel.text = "Límite Exterior: ${(outer * 100).toInt()}%"
                    joystickVisualizer.updateDeadzones(joystickVisualizer.innerDeadzone, outer)
                    if (fromUser) {
                        configManager.updateConfig(configManager.getConfig().copy(outerDeadzone = outer))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        deadzoneSlidersLayout.addView(tvInnerLabel)
        deadzoneSlidersLayout.addView(seekInner)
        deadzoneSlidersLayout.addView(tvOuterLabel)
        deadzoneSlidersLayout.addView(seekOuter)
        joystickRow.addView(deadzoneSlidersLayout)
        settingsContentLayout.addView(joystickRow)

        // Fila 2: Curva de Respuesta
        val curveLabel = TextView(context).apply {
            text = "Curva de Sensibilidad del Stick:"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 8)
        }
        settingsContentLayout.addView(curveLabel)

        val curveGroup = RadioGroup(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        ResponseCurve.values().forEach { curve ->
            val rb = RadioButton(context).apply {
                text = curve.name
                setTextColor(Color.parseColor("#E6FFFFFF"))
                isChecked = currentCfg.responseCurve == curve
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        configManager.updateConfig(configManager.getConfig().copy(responseCurve = curve))
                    }
                }
            }
            curveGroup.addView(rb)
        }
        settingsContentLayout.addView(curveGroup)

        // Fila 3: Botones de Intercambio de Layout
        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 16)
        }
        val btnSwapAB = Button(context).apply {
            text = if (currentCfg.swapAB) "✓ Intercambio A/B Activo" else "Intercambio A/B (Switch)"
            setTextColor(if (currentCfg.swapAB) Color.BLACK else Color.WHITE)
            background = createGlassDrawable(if (currentCfg.swapAB) "#52E172" else "#26FFFFFF", "#33FFFFFF", 12f)
            setOnClickListener {
                val next = !configManager.getConfig().swapAB
                configManager.updateConfig(configManager.getConfig().copy(swapAB = next))
                text = if (next) "✓ Intercambio A/B Activo" else "Intercambio A/B (Switch)"
                background = createGlassDrawable(if (next) "#52E172" else "#26FFFFFF", "#33FFFFFF", 12f)
                setTextColor(if (next) Color.BLACK else Color.WHITE)
            }
        }
        val btnSwapXY = Button(context).apply {
            text = if (currentCfg.swapXY) "✓ Intercambio X/Y Activo" else "Intercambio X/Y"
            setTextColor(if (currentCfg.swapXY) Color.BLACK else Color.WHITE)
            background = createGlassDrawable(if (currentCfg.swapXY) "#52E172" else "#26FFFFFF", "#33FFFFFF", 12f)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
            setOnClickListener {
                val next = !configManager.getConfig().swapXY
                configManager.updateConfig(configManager.getConfig().copy(swapXY = next))
                text = if (next) "✓ Intercambio X/Y Activo" else "Intercambio X/Y"
                background = createGlassDrawable(if (next) "#52E172" else "#26FFFFFF", "#33FFFFFF", 12f)
                setTextColor(if (next) Color.BLACK else Color.WHITE)
            }
        }
        buttonsLayout.addView(btnSwapAB)
        buttonsLayout.addView(btnSwapXY)
        settingsContentLayout.addView(buttonsLayout)

        // Fila 4: Acciones rápidas (Copiloto IA y Limpieza de Caché)
        val actionsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }

        val btnDetox = Button(context).apply {
            text = "Detox Caché"
            setTextColor(Color.WHITE)
            background = createGlassDrawable("#26FFFFFF", "#33FFFFFF", 12f)
            setOnClickListener {
                onDetoxCacheListener?.invoke()
            }
        }
        val btnCopilot = Button(context).apply {
            text = "🤖 Copiloto IA"
            setTextColor(Color.BLACK)
            background = createGlassDrawable("#52E172", "#00000000", 12f)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
            setOnClickListener {
                toggleSettingsPanel(false)
                onOpenCopilotListener?.invoke()
            }
        }
        actionsLayout.addView(btnDetox)
        actionsLayout.addView(btnCopilot)
        settingsContentLayout.addView(actionsLayout)
    }

    /**
     * Alterna la visibilidad del panel de configuración rápida.
     */
    fun toggleSettingsPanel(show: Boolean? = null) {
        val willShow = show ?: (settingsCard.visibility != View.VISIBLE)
        settingsCard.visibility = if (willShow) View.VISIBLE else View.GONE
    }

    /**
     * Actualiza las métricas en tiempo real mostradas en el HUD compacto.
     */
    fun updateTelemetry(data: TelemetryData) {
        currentTelemetry = data
        tvFps.text = "${data.fps} FPS"
        tvPing.text = "${data.rttMs}ms"
        tvPing.setTextColor(data.healthState.colorInt)
        healthIndicatorDot.background = createGlassDrawable(
            colorHex = when (data.healthState) {
                HealthState.OPTIMAL -> "#52E172"
                HealthState.WARNING -> "#ECDE38"
                HealthState.CRITICAL -> "#E74040"
            },
            strokeHex = "#00000000",
            radius = 8f
        )
        tvJitterLoss.text = "J: ${data.jitterMs}ms • Loss: ${String.format("%.1f", data.packetLossPercent)}%"
        tvBitrateRes.text = "${data.resolution} • ${String.format("%.1f", data.bitrateMbps)}M"
    }

    /**
     * Transmite una posición de stick física en tiempo real para calibración visual.
     */
    fun updateLiveStickPosition(x: Float, y: Float) {
        joystickVisualizer.updateLivePosition(x, y)
    }

    private fun applyHudPosition(position: HudPosition) {
        val params = hudCard.layoutParams as? LayoutParams ?: return
        params.gravity = when (position) {
            HudPosition.TOP_LEFT -> Gravity.TOP or Gravity.START
            HudPosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
            HudPosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            HudPosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        hudCard.layoutParams = params
    }

    private fun createGlassDrawable(colorHex: String, strokeHex: String, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = radius
            if (strokeHex.isNotBlank() && strokeHex != "#00000000") {
                setStroke(2, Color.parseColor(strokeHex))
            }
        }
    }

    /**
     * Canvas 2D interactivo para visualizar los círculos concéntricos de zona muerta y el vector del stick.
     */
    inner class JoystickCalibrationView(context: Context) : View(context) {

        var innerDeadzone: Float = 0.08f
        var outerDeadzone: Float = 0.95f
        private var currentStickX: Float = 0.0f
        private var currentStickY: Float = 0.0f

        private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33000000")
            style = Paint.Style.FILL
        }
        private val paintOuterBound = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4D52E172")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val paintInnerBound = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80E74040")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val paintAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#26FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val paintStickDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#52E172")
            style = Paint.Style.FILL
        }

        fun updateDeadzones(inner: Float, outer: Float) {
            innerDeadzone = inner
            outerDeadzone = outer
            invalidate()
        }

        fun updateLivePosition(x: Float, y: Float) {
            currentStickX = x.coerceIn(-1.0f, 1.0f)
            currentStickY = y.coerceIn(-1.0f, 1.0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxRadius = (minOf(width, height) / 2f) - 12f

            // Fondo circular
            canvas.drawCircle(cx, cy, maxRadius, paintBg)

            // Ejes cartesianos
            canvas.drawLine(cx - maxRadius, cy, cx + maxRadius, cy, paintAxis)
            canvas.drawLine(cx, cy - maxRadius, cx, cy + maxRadius, paintAxis)

            // Límite exterior (Outer deadzone)
            canvas.drawCircle(cx, cy, maxRadius * outerDeadzone, paintOuterBound)

            // Límite interior (Inner deadzone)
            canvas.drawCircle(cx, cy, maxRadius * innerDeadzone, paintInnerBound)

            // Posición del Stick
            val dotX = cx + (currentStickX * maxRadius)
            val dotY = cy + (currentStickY * maxRadius)
            canvas.drawCircle(dotX, dotY, 12f, paintStickDot)
        }
    }
}
