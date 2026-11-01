package com.xcloud.godmode.ui.components

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcloud.godmode.streaming.NativeInputBridge
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GodModeTouchControlsOverlay: Translucent on-screen gamepad overlay with D-Pad,
 * dual analog thumbsticks, ABXY action diamond, triggers/bumpers, and multi-touch haptic support.
 * Events are dispatched directly into NativeInputBridge at 250Hz.
 */
@Composable
fun GodModeTouchControlsOverlay(
    modifier: Modifier = Modifier,
    initialOpacity: Float = 0.65f,
    hapticsEnabled: Boolean = true,
    onClose: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    var opacity by remember { mutableFloatStateOf(initialOpacity) }

    val vibrator = remember {
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val triggerHapticTick: () -> Unit = {
        if (hapticsEnabled) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(18)
                }
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("god_mode_touch_controls_overlay")
    ) {
        // Top System Bar (LB, LT, RB, RT, View, Xbox Guide, Menu)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Bumpers & Triggers (LT, LB)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TouchTriggerButton(
                    label = "LT",
                    opacity = opacity,
                    onStateChange = { isPressed ->
                        NativeInputBridge.dispatchTrigger(0, isLeft = true, if (isPressed) 1.0f else 0f)
                        if (isPressed) triggerHapticTick()
                    }
                )
                TouchTriggerButton(
                    label = "LB",
                    opacity = opacity,
                    onStateChange = { isPressed ->
                        NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_LEFT_SHOULDER, isPressed)
                        if (isPressed) triggerHapticTick()
                    }
                )
            }

            // Center System Buttons (View, Xbox Guide, Menu)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TouchSystemButton(
                    icon = Icons.Default.FilterNone,
                    contentDesc = "View (Select)",
                    opacity = opacity,
                    onClick = {
                        triggerHapticTick()
                        NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_BACK, true)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_BACK, false)
                        }, 80)
                    }
                )

                // Xbox Guide Button (Pulsing green ring)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = opacity))
                        .border(BorderStroke(2.dp, Color(0xFF107C10)), CircleShape)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                triggerHapticTick()
                                NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_GUIDE, true)
                                do {
                                    val event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })
                                NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_GUIDE, false)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "", // Xbox Symbol or X
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                TouchSystemButton(
                    icon = Icons.Default.Menu,
                    contentDesc = "Menu (Start)",
                    opacity = opacity,
                    onClick = {
                        triggerHapticTick()
                        NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_START, true)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_START, false)
                        }, 80)
                    }
                )
            }

            // Right Bumpers & Triggers (RB, RT)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TouchTriggerButton(
                    label = "RB",
                    opacity = opacity,
                    onStateChange = { isPressed ->
                        NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_RIGHT_SHOULDER, isPressed)
                        if (isPressed) triggerHapticTick()
                    }
                )
                TouchTriggerButton(
                    label = "RT",
                    opacity = opacity,
                    onStateChange = { isPressed ->
                        NativeInputBridge.dispatchTrigger(0, isLeft = false, if (isPressed) 1.0f else 0f)
                        if (isPressed) triggerHapticTick()
                    }
                )
            }
        }

        // Left Controls Cluster: Virtual Analog Thumbstick & Directional D-Pad
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Left Thumbstick
            VirtualAnalogThumbstick(
                label = "L3",
                size = 140.dp,
                opacity = opacity,
                onDeltaChange = { dx, dy ->
                    NativeInputBridge.dispatchStick(0, isLeft = true, dx, dy)
                },
                onClick = {
                    triggerHapticTick()
                    NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_LEFT_THUMB, true)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_LEFT_THUMB, false)
                    }, 80)
                }
            )

            // D-Pad Cross
            VirtualDirectionalPad(
                size = 130.dp,
                opacity = opacity,
                onDirectionChange = { up, down, left, right ->
                    var mask = 0
                    if (up) mask = mask or NativeInputBridge.BTN_DPAD_UP
                    if (down) mask = mask or NativeInputBridge.BTN_DPAD_DOWN
                    if (left) mask = mask or NativeInputBridge.BTN_DPAD_LEFT
                    if (right) mask = mask or NativeInputBridge.BTN_DPAD_RIGHT

                    val state = NativeInputBridge.activeInstance?.let {
                        it.setButtonState(0, NativeInputBridge.BTN_DPAD_UP, up)
                        it.setButtonState(0, NativeInputBridge.BTN_DPAD_DOWN, down)
                        it.setButtonState(0, NativeInputBridge.BTN_DPAD_LEFT, left)
                        it.setButtonState(0, NativeInputBridge.BTN_DPAD_RIGHT, right)
                    }
                    if (mask != 0) triggerHapticTick()
                }
            )
        }

        // Right Controls Cluster: ABXY Action Diamond & Right Analog Thumbstick
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Right Thumbstick (Camera / Aim)
            VirtualAnalogThumbstick(
                label = "R3",
                size = 140.dp,
                opacity = opacity,
                onDeltaChange = { dx, dy ->
                    NativeInputBridge.dispatchStick(0, isLeft = false, dx, dy)
                },
                onClick = {
                    triggerHapticTick()
                    NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_RIGHT_THUMB, true)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        NativeInputBridge.dispatchButton(0, NativeInputBridge.BTN_RIGHT_THUMB, false)
                    }, 80)
                }
            )

            // ABXY Action Diamond
            VirtualActionDiamond(
                size = 150.dp,
                opacity = opacity,
                onButtonPressed = { mask, isDown ->
                    NativeInputBridge.dispatchButton(0, mask, isDown)
                    if (isDown) triggerHapticTick()
                }
            )
        }
    }
}

/**
 * Virtual Analog Thumbstick: Smooth draggable knob with radial normalization [-1.0 .. 1.0].
 */
@Composable
private fun VirtualAnalogThumbstick(
    label: String,
    size: Dp,
    opacity: Float,
    onDeltaChange: (Float, Float) -> Unit,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val radiusPx = sizePx / 2f
    val knobRadiusPx = radiusPx * 0.42f

    var knobOffsetX by remember { mutableFloatStateOf(0f) }
    var knobOffsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = opacity * 0.75f))
            .border(BorderStroke(2.dp, Color.White.copy(alpha = opacity * 0.35f)), CircleShape)
            .pointerInput(sizePx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val dx = (offset.x - radiusPx).coerceIn(-radiusPx, radiusPx)
                        val dy = (offset.y - radiusPx).coerceIn(-radiusPx, radiusPx)
                        val dist = sqrt((dx * dx) + (dy * dy))
                        val maxDist = radiusPx - knobRadiusPx
                        val clampedDist = dist.coerceAtMost(maxDist)
                        val angle = Math.atan2(dy.toDouble(), dx.toDouble())

                        knobOffsetX = (cos(angle) * clampedDist).toFloat()
                        knobOffsetY = (sin(angle) * clampedDist).toFloat()

                        val normX = (knobOffsetX / maxDist).coerceIn(-1f, 1f)
                        val normY = (knobOffsetY / maxDist).coerceIn(-1f, 1f)
                        onDeltaChange(normX, normY)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newX = knobOffsetX + dragAmount.x
                        val newY = knobOffsetY + dragAmount.y
                        val dist = sqrt((newX * newX) + (newY * newY))
                        val maxDist = radiusPx - knobRadiusPx

                        if (dist <= maxDist) {
                            knobOffsetX = newX
                            knobOffsetY = newY
                        } else {
                            val angle = Math.atan2(newY.toDouble(), newX.toDouble())
                            knobOffsetX = (cos(angle) * maxDist).toFloat()
                            knobOffsetY = (sin(angle) * maxDist).toFloat()
                        }

                        val normX = (knobOffsetX / maxDist).coerceIn(-1f, 1f)
                        val normY = (knobOffsetY / maxDist).coerceIn(-1f, 1f)
                        onDeltaChange(normX, normY)
                    },
                    onDragEnd = {
                        knobOffsetX = 0f
                        knobOffsetY = 0f
                        onDeltaChange(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffsetX = 0f
                        knobOffsetY = 0f
                        onDeltaChange(0f, 0f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Inner Floating Knob
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffsetX.roundToInt(), knobOffsetY.roundToInt()) }
                .size(size * 0.44f)
                .clip(CircleShape)
                .background(Color(0xFF22C55E).copy(alpha = opacity))
                .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.8f)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Virtual Directional Pad (D-Pad Cross).
 */
@Composable
private fun VirtualDirectionalPad(
    size: Dp,
    opacity: Float,
    onDirectionChange: (up: Boolean, down: Boolean, left: Boolean, right: Boolean) -> Unit
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val centerPx = sizePx / 2f
    val deadzonePx = centerPx * 0.28f

    var upActive by remember { mutableStateOf(false) }
    var downActive by remember { mutableStateOf(false) }
    var leftActive by remember { mutableStateOf(false) }
    var rightActive by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = opacity * 0.75f))
            .border(BorderStroke(2.dp, Color(0xFF38BDF8).copy(alpha = opacity * 0.4f)), CircleShape)
            .pointerInput(sizePx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var pos = down.position
                    val updateDirections = { p: Offset ->
                        val dx = p.x - centerPx
                        val dy = p.y - centerPx
                        val dist = sqrt((dx * dx) + (dy * dy))

                        if (dist > deadzonePx) {
                            val up = dy < -deadzonePx && Math.abs(dy) > Math.abs(dx) * 0.45f
                            val downD = dy > deadzonePx && Math.abs(dy) > Math.abs(dx) * 0.45f
                            val left = dx < -deadzonePx && Math.abs(dx) > Math.abs(dy) * 0.45f
                            val right = dx > deadzonePx && Math.abs(dx) > Math.abs(dy) * 0.45f

                            if (up != upActive || downD != downActive || left != leftActive || right != rightActive) {
                                upActive = up
                                downActive = downD
                                leftActive = left
                                rightActive = right
                                onDirectionChange(up, downD, left, right)
                            }
                        } else {
                            if (upActive || downActive || leftActive || rightActive) {
                                upActive = false
                                downActive = false
                                leftActive = false
                                rightActive = false
                                onDirectionChange(false, false, false, false)
                            }
                        }
                    }

                    updateDirections(pos)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            upActive = false
                            downActive = false
                            leftActive = false
                            rightActive = false
                            onDirectionChange(false, false, false, false)
                            break
                        }
                        pos = change.position
                        updateDirections(pos)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Cross Visual Accents
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "D-Pad Up",
            tint = if (upActive) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .size(28.dp)
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "D-Pad Down",
            tint = if (downActive) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .size(28.dp)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "D-Pad Left",
            tint = if (leftActive) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(28.dp)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "D-Pad Right",
            tint = if (rightActive) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(28.dp)
        )
    }
}

/**
 * Virtual Action Diamond (ABXY Buttons arranged in diamond layout).
 */
@Composable
private fun VirtualActionDiamond(
    size: Dp,
    opacity: Float,
    onButtonPressed: (buttonMask: Int, isDown: Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .size(size),
        contentAlignment = Alignment.Center
    ) {
        // Y Button (Top, Yellow)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
        ) {
            XboxFaceButton(
                label = "Y",
                color = Color(0xFFF59E0B),
                opacity = opacity,
                mask = NativeInputBridge.BTN_Y,
                onPressed = onButtonPressed
            )
        }

        // A Button (Bottom, Green)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
        ) {
            XboxFaceButton(
                label = "A",
                color = Color(0xFF107C10),
                opacity = opacity,
                mask = NativeInputBridge.BTN_A,
                onPressed = onButtonPressed
            )
        }

        // X Button (Left, Blue)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 2.dp)
        ) {
            XboxFaceButton(
                label = "X",
                color = Color(0xFF38BDF8),
                opacity = opacity,
                mask = NativeInputBridge.BTN_X,
                onPressed = onButtonPressed
            )
        }

        // B Button (Right, Red)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
        ) {
            XboxFaceButton(
                label = "B",
                color = Color(0xFFEF4444),
                opacity = opacity,
                mask = NativeInputBridge.BTN_B,
                onPressed = onButtonPressed
            )
        }
    }
}

@Composable
private fun XboxFaceButton(
    label: String,
    color: Color,
    opacity: Float,
    mask: Int,
    onPressed: (Int, Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isPressed) color.copy(alpha = 0.95f) else Color.Black.copy(alpha = opacity * 0.85f))
            .border(BorderStroke(2.dp, color.copy(alpha = if (isPressed) 1f else 0.7f)), CircleShape)
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true
                    onPressed(mask, true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    isPressed = false
                    onPressed(mask, false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isPressed) Color.White else color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun TouchTriggerButton(
    label: String,
    opacity: Float,
    onStateChange: (Boolean) -> Unit
) {
    var isDown by remember { mutableStateOf(false) }

    Surface(
        color = if (isDown) Color(0xFF107C10) else Color.Black.copy(alpha = opacity * 0.85f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.4f)),
        modifier = Modifier
            .width(54.dp)
            .height(34.dp)
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown()
                    isDown = true
                    onStateChange(true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    isDown = false
                    onStateChange(false)
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TouchSystemButton(
    icon: ImageVector,
    contentDesc: String,
    opacity: Float,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = opacity * 0.85f)
        ),
        modifier = Modifier
            .size(36.dp)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}
