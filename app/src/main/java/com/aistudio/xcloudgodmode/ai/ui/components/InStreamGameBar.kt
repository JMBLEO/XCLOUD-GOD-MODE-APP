package com.aistudio.xcloudgodmode.ai.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * InStreamGameBar: A translucent overlay menu that appears over the stream.
 * Provides quick access to audio, input, and performance settings.
 */
@Composable
fun InStreamGameBar(
    isOpen: Boolean,
    onClose: () -> Unit,
    isMicMuted: Boolean,
    onToggleMic: () -> Unit,
    isGyroEnabled: Boolean,
    onToggleGyro: () -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onTakeScreenshot: () -> Unit,
    accentColor: Color = Color(0xFF107C10)
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { onClose() }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .align(Alignment.CenterEnd)
                    .clickable(enabled = false) {},
                color = Color(0xFF14171A).copy(alpha = 0.95f),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "GUÍA RÁPIDA",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        ),
                        color = accentColor
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Audio Section
                    GameBarSection(title = "Audio") {
                        GameBarItem(
                            icon = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isMicMuted) "Micrófono: Mute" else "Micrófono: On",
                            onClick = onToggleMic,
                            isActive = !isMicMuted,
                            accentColor = accentColor
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text("Volumen del Stream", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Slider(
                            value = volume,
                            onValueChange = onVolumeChange,
                            colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Input Section
                    GameBarSection(title = "Entrada") {
                        GameBarItem(
                            icon = Icons.Default.ScreenRotation,
                            label = "Apuntado por Giroscopio",
                            onClick = onToggleGyro,
                            isActive = isGyroEnabled,
                            accentColor = accentColor
                        )
                        
                        GameBarItem(
                            icon = Icons.Default.VideogameAsset,
                            label = "Co-Op Local: 2 Mandos",
                            onClick = {}, // Show info or settings
                            isActive = true,
                            accentColor = accentColor
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Tools Section
                    GameBarSection(title = "Herramientas") {
                        GameBarItem(
                            icon = Icons.Default.CameraAlt,
                            label = "Captura de Pantalla HQ",
                            onClick = onTakeScreenshot,
                            isActive = false,
                            accentColor = accentColor
                        )
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    Button(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}

@Composable
fun GameBarSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
fun GameBarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    isActive: Boolean,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) accentColor.copy(alpha = 0.1f) else Color.Transparent)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (isActive) accentColor else Color.White, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}
