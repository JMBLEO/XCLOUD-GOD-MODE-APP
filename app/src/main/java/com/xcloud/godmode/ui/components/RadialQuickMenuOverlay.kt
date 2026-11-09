package com.xcloud.godmode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 82: Radial Quick Menu Overlay (Translucent In-Game HUD).
 * Provides immediate circular access to essential gaming toggles:
 * FSR Sharpness, Mic Mute, Capture Screenshot, Record Clip, and HUD telemetry.
 */
@Composable
fun RadialQuickMenuOverlay(
    isVisible: Boolean,
    isMicMuted: Boolean,
    isHudEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleHud: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onToggleRecord: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF181818).copy(alpha = 0.92f))
                    .border(2.dp, Color(0xFF107C10), CircleShape)
                    .clickable(enabled = false) {}, // Prevent dismiss when tapping center
                contentAlignment = Alignment.Center
            ) {
                // Center close button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF242424))
                        .testTag("radial_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar menú radial",
                        tint = Color.White
                    )
                }

                // Top: Mic Toggle
                RadialItem(
                    icon = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (isMicMuted) "Micro Off" else "Micro On",
                    isActive = !isMicMuted,
                    onClick = onToggleMic,
                    testTag = "radial_mic_button",
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                )

                // Bottom: HUD Telemetry
                RadialItem(
                    icon = Icons.Default.Info,
                    label = "HUD Telemetría",
                    isActive = isHudEnabled,
                    onClick = onToggleHud,
                    testTag = "radial_hud_button",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                )

                // Left: Screenshot
                RadialItem(
                    icon = Icons.Default.PhotoCamera,
                    label = "Captura",
                    isActive = false,
                    onClick = onCaptureScreenshot,
                    testTag = "radial_screenshot_button",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp)
                )

                // Right: Recording
                RadialItem(
                    icon = Icons.Default.Videocam,
                    label = "Grabar Clip",
                    isActive = false,
                    onClick = onToggleRecord,
                    testTag = "radial_record_button",
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun RadialItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) Color(0xFF107C10) else Color.White,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = if (isActive) Color(0xFF107C10) else Color(0xFFCCCCCC)
        )
    }
}
