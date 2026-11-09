package com.xcloud.godmode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 64: Virtual Magnifier Overlay (Pan & Zoom).
 * Enables GPU-accelerated 1.5x - 3.0x pinch-to-zoom with 2-finger pan gestures
 * for reading small text/HUD elements on mobile screens without stream latency penalty.
 */
@Composable
fun VirtualMagnifierContainer(
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isEnabled) {
                if (!isEnabled) {
                    scale = 1.0f
                    offsetX = 0f
                    offsetY = 0f
                    return@pointerInput
                }
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1.0f, 3.5f)
                    scale = newScale
                    if (newScale > 1.0f) {
                        val maxPanX = (size.width * (newScale - 1f)) / 2f
                        val maxPanY = (size.height * (newScale - 1f)) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxPanX, maxPanX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxPanY, maxPanY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        ) {
            content()
        }

        // Zoom level indicator overlay
        AnimatedVisibility(
            visible = scale > 1.05f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF107C10), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("magnifier_zoom_badge")
            ) {
                Text(
                    text = "${String.format("%.1f", scale)}x Lupa",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}
