package com.jmbleo.xcloudgodmode.input

import android.view.KeyEvent
import com.jmbleo.xcloudgodmode.config.ResponseCurve
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Operaciones matemáticas puras para procesamiento de joysticks y mandos analógicos.
 * Garantiza paridad absoluta, protección contra NaN/división por cero y precisión angular de 360°.
 */
object InputMath {

    const val EPSILON = 1e-5f

    /**
     * Aplica zona muerta radial y curva de respuesta a una coordenada de stick (x, y).
     *
     * @param x Entrada eje horizontal (-1.0f a 1.0f)
     * @param y Entrada eje vertical (-1.0f a 1.0f)
     * @param innerDeadzone Umbral inferior (0.0f a 0.35f) donde la salida es forzada a 0.
     * @param outerDeadzone Umbral superior (ej. 0.95f) a partir del cual se alcanza la saturación máxima (1.0).
     * @param curve Curva de sensibilidad aplicada a la magnitud normalizada.
     * @return Par (x', y') con magnitud mapeada a [0.0f, 1.0f] preservando exactamente el ángulo polar.
     */
    fun applyRadialDeadzone(
        x: Float,
        y: Float,
        innerDeadzone: Float = 0.08f,
        outerDeadzone: Float = 0.95f,
        curve: ResponseCurve = ResponseCurve.LINEAR
    ): Pair<Float, Float> {
        // Blindaje contra entradas inválidas
        if (x.isNaN() || y.isNaN()) return Pair(0f, 0f)

        val clampedInner = innerDeadzone.coerceIn(0.0f, 0.40f)
        val clampedOuter = outerDeadzone.coerceIn(clampedInner + 0.05f, 1.0f)

        val magnitude = sqrt((x * x + y * y).toDouble()).toFloat()

        // 1. Filtrar si está dentro de la zona muerta interior
        if (magnitude <= clampedInner || magnitude < EPSILON) {
            return Pair(0f, 0f)
        }

        // 2. Normalizar la magnitud entre la zona interior y exterior hacia el rango [0.0, 1.0]
        val range = clampedOuter - clampedInner
        val normalizedMagnitude = if (range > EPSILON) {
            ((magnitude - clampedInner) / range).coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }

        // 3. Aplicar curva de respuesta seleccionada
        val curvedMagnitude = when (curve) {
            ResponseCurve.LINEAR -> normalizedMagnitude
            ResponseCurve.ENHANCED -> (0.5f * normalizedMagnitude + 0.5f * normalizedMagnitude * normalizedMagnitude)
            ResponseCurve.AGGRESSIVE -> normalizedMagnitude.toDouble().pow(0.75).toFloat()
            ResponseCurve.EXPONENTIAL -> (normalizedMagnitude * normalizedMagnitude)
        }.coerceIn(0.0f, 1.0f)

        // 4. Preservar dirección angular exacta y proyectar a las nuevas componentes (x', y')
        val angle = atan2(y.toDouble(), x.toDouble())
        val newX = (cos(angle) * curvedMagnitude).toFloat()
        val newY = (sin(angle) * curvedMagnitude).toFloat()

        return Pair(newX, newY)
    }

    /**
     * Aplica el remapeo de botones físicos según la configuración de layout.
     *
     * @param keyCode Código de tecla original (ej. KEYCODE_BUTTON_A)
     * @param swapAB Intercambia botón A con botón B (perfil Nintendo)
     * @param swapXY Intercambia botón X con botón Y
     * @return Código de tecla resultante
     */
    fun applyButtonRemap(keyCode: Int, swapAB: Boolean, swapXY: Boolean): Int {
        if (swapAB) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_A) return KeyEvent.KEYCODE_BUTTON_B
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B) return KeyEvent.KEYCODE_BUTTON_A
        }
        if (swapXY) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_X) return KeyEvent.KEYCODE_BUTTON_Y
            if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) return KeyEvent.KEYCODE_BUTTON_X
        }
        return keyCode
    }
}
