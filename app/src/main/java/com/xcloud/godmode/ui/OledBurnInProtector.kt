package com.xcloud.godmode.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * Phase 65: OLED Burn-In Protector.
 * Periodically applies micro-orbital subpixel shifts (+-2 pixels every 45s)
 * and gently modulates static UI / Touch overlay opacity (between 30% and 45%)
 * to eliminate static pixel image retention on AMOLED and OLED panels.
 */
class OledBurnInProtector {

    data class ShiftState(
        val shiftX: Int = 0,
        val shiftY: Int = 0,
        val opacityFactor: Float = 0.38f
    )

    companion object {
        const val ORBIT_INTERVAL_MS = 45_000L
        val ORBIT_STEPS = listOf(
            Pair(0, 0),
            Pair(1, 0),
            Pair(2, 1),
            Pair(1, 2),
            Pair(0, 2),
            Pair(-1, 1),
            Pair(-2, 0),
            Pair(-1, -1),
            Pair(0, -2),
            Pair(1, -1)
        )
    }
}

@Composable
fun rememberOledProtectionShift(isEnabled: Boolean = true): State<Pair<Int, Int>> {
    val shiftState = remember { androidx.compose.runtime.mutableStateOf(Pair(0, 0)) }

    LaunchedEffect(isEnabled) {
        if (!isEnabled) {
            shiftState.value = Pair(0, 0)
            return@LaunchedEffect
        }
        var stepIndex = 0
        while (true) {
            delay(OledBurnInProtector.ORBIT_INTERVAL_MS)
            stepIndex = (stepIndex + 1) % OledBurnInProtector.ORBIT_STEPS.size
            shiftState.value = OledBurnInProtector.ORBIT_STEPS[stepIndex]
        }
    }
    return shiftState
}

@Composable
fun rememberOledBreathingOpacity(isEnabled: Boolean = true): State<Float> {
    val opacity = remember { Animatable(0.35f) }

    LaunchedEffect(isEnabled) {
        if (!isEnabled) {
            opacity.snapTo(0.40f)
            return@LaunchedEffect
        }
        opacity.animateTo(
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 20_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    }
    return opacity.asState()
}
