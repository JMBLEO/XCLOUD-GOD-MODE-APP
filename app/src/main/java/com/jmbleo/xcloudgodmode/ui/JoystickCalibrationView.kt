package com.jmbleo.xcloudgodmode.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Canvas 2D interactivo para visualizar los círculos concéntricos de zona muerta y el vector del stick.
 */
class JoystickCalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

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
