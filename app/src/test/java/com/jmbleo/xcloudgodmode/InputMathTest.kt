package com.jmbleo.xcloudgodmode

import android.view.KeyEvent
import com.jmbleo.xcloudgodmode.config.ResponseCurve
import com.jmbleo.xcloudgodmode.input.InputMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

class InputMathTest {

    @Test
    fun `radial deadzone clips input inside inner deadzone`() {
        val innerDeadzone = 0.10f
        val outerDeadzone = 0.95f

        // Vector magnitude: sqrt(0.04^2 + 0.05^2) = ~0.064 < 0.10f
        val (outX, outY) = InputMath.applyRadialDeadzone(0.04f, 0.05f, innerDeadzone, outerDeadzone)
        assertEquals(0f, outX, InputMath.EPSILON)
        assertEquals(0f, outY, InputMath.EPSILON)
    }

    @Test
    fun `radial deadzone saturates to 1_0 at or beyond outer deadzone`() {
        val innerDeadzone = 0.08f
        val outerDeadzone = 0.90f

        // Entrada en el eje X de 0.92f (mayor al outer 0.90f)
        val (outX, outY) = InputMath.applyRadialDeadzone(0.92f, 0f, innerDeadzone, outerDeadzone)
        assertEquals(1.0f, outX, InputMath.EPSILON)
        assertEquals(0.0f, outY, InputMath.EPSILON)
    }

    @Test
    fun `radial deadzone preserves angular direction`() {
        val innerDeadzone = 0.08f
        val outerDeadzone = 0.95f

        val inputX = 0.40f
        val inputY = 0.60f
        val inputAngle = atan2(inputY.toDouble(), inputX.toDouble())

        val (outX, outY) = InputMath.applyRadialDeadzone(inputX, inputY, innerDeadzone, outerDeadzone)
        val outputAngle = atan2(outY.toDouble(), outX.toDouble())

        // El ángulo polar debe ser idéntico con tolerancia epsilon
        assertEquals(inputAngle, outputAngle, 1e-4)

        // La magnitud resultante debe estar acotada exactamente en el rango [0.0, 1.0]
        val inMag = sqrt(inputX * inputX + inputY * inputY)
        val outMag = sqrt(outX * outX + outY * outY)
        assertTrue(outMag in 0.0f..1.0f)

        // Cerca del umbral interior, la magnitud siempre es atenuada respecto a la entrada
        val (nearInnerX, nearInnerY) = InputMath.applyRadialDeadzone(0.10f, 0.10f, innerDeadzone, outerDeadzone)
        val nearInnerInMag = sqrt(0.10f * 0.10f + 0.10f * 0.10f)
        val nearInnerOutMag = sqrt(nearInnerX * nearInnerX + nearInnerY * nearInnerY)
        assertTrue(nearInnerOutMag < nearInnerInMag)
    }

    @Test
    fun `exponential curve creates smoother response near center`() {
        val innerDeadzone = 0.10f
        val outerDeadzone = 0.90f

        val (linearX, _) = InputMath.applyRadialDeadzone(0.50f, 0f, innerDeadzone, outerDeadzone, ResponseCurve.LINEAR)
        val (expoX, _) = InputMath.applyRadialDeadzone(0.50f, 0f, innerDeadzone, outerDeadzone, ResponseCurve.EXPONENTIAL)

        // Con curva exponencial (x^2), el valor a medio recorrido debe ser menor que el lineal para mayor precisión
        assertTrue(expoX < linearX)
    }

    @Test
    fun `button remapping swaps AB and XY keys accurately`() {
        // Swap A and B
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, InputMath.applyButtonRemap(KeyEvent.KEYCODE_BUTTON_A, swapAB = true, swapXY = false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, InputMath.applyButtonRemap(KeyEvent.KEYCODE_BUTTON_B, swapAB = true, swapXY = false))

        // Swap X and Y
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, InputMath.applyButtonRemap(KeyEvent.KEYCODE_BUTTON_X, swapAB = false, swapXY = true))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, InputMath.applyButtonRemap(KeyEvent.KEYCODE_BUTTON_Y, swapAB = false, swapXY = true))

        // Keys unchanged when swaps disabled
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, InputMath.applyButtonRemap(KeyEvent.KEYCODE_BUTTON_A, swapAB = false, swapXY = false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, InputMath.applyButtonRemap(KeyEvent.KEYCODE_BUTTON_X, swapAB = false, swapXY = false))
    }
}
