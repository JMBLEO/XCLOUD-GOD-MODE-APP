package com.jmbleo.xcloudgodmode.input

import android.view.KeyEvent
import com.jmbleo.xcloudgodmode.config.ResponseCurve
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object InputMath {
    const val EPSILON = 1e-4f

    fun applyRadialDeadzone(
        x: Float,
        y: Float,
        innerDeadzone: Float,
        outerDeadzone: Float,
        curve: ResponseCurve = ResponseCurve.LINEAR
    ): Pair<Float, Float> {
        val magnitude = sqrt(x * x + y * y)
        if (magnitude <= innerDeadzone) {
            return Pair(0f, 0f)
        }

        val angle = atan2(y.toDouble(), x.toDouble())
        val normalizedMag = ((magnitude - innerDeadzone) / (outerDeadzone - innerDeadzone)).coerceIn(0f, 1f)

        val curvedMag = when (curve) {
            ResponseCurve.LINEAR -> normalizedMag
            ResponseCurve.EXPONENTIAL -> normalizedMag.pow(2)
            ResponseCurve.AGGRESSIVE -> sqrt(normalizedMag)
        }

        val outX = (curvedMag * cos(angle)).toFloat()
        val outY = (curvedMag * sin(angle)).toFloat()
        return Pair(outX, outY)
    }

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
