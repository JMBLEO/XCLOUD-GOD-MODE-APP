package com.xcloud.godmode.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * InStreamGameBar: Translucent Glassmorphism Guide Menu overlay appearing over the stream.
 * Provides live controls for V-Sync, Haptics, Audio, Gyroscope, Touch Controls,
 * TrueAchievements overlay, and "Go Back To" hub minimization without destroying the stream socket.
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
    accentColor: Color = Color(0xFF107C10),
    isVsyncOff: Boolean = true,
    onToggleVsync: () -> Unit = {},
    isTouchControlsEnabled: Boolean = false,
    onToggleTouchControls: () -> Unit = {},
    onGoBackToHub: () -> Unit = {},
    onViewAchievements: () -> Unit = {}
) {
    var selectedGuideTab by remember { mutableIntStateOf(0) } // 0: Juego, 1: Amigos/Logros, 2: Configuración

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { onClose() }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp)
                    .align(Alignment.CenterEnd)
                    .clickable(enabled = false) {},
                color = Color(0xFF0F1318).copy(alpha = 0.96f),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3B4D).copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MENÚ GUÍA",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            ),
                            color = accentColor
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.Gray)
                        }
                    }

                    // Pestañas del Menú Guía
                    TabRow(
                        selectedTabIndex = selectedGuideTab,
                        containerColor = Color.Transparent,
                        contentColor = accentColor,
                        divider = {}
                    ) {
                        Tab(
                            selected = selectedGuideTab == 0,
                            onClick = { selectedGuideTab = 0 },
                            text = { Text("Juego", fontSize = 12.sp, fontWeight = if (selectedGuideTab == 0) FontWeight.Bold else FontWeight.Normal) }
                        )
                        Tab(
                            selected = selectedGuideTab == 1,
                            onClick = { selectedGuideTab = 1 },
                            text = { Text("Logros", fontSize = 12.sp, fontWeight = if (selectedGuideTab == 1) FontWeight.Bold else FontWeight.Normal) }
                        )
                        Tab(
                            selected = selectedGuideTab == 2,
                            onClick = { selectedGuideTab = 2 },
                            text = { Text("Ajustes", fontSize = 12.sp, fontWeight = if (selectedGuideTab == 2) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    when (selectedGuideTab) {
                        0 -> {
                            // Pestaña Juego
                            GameBarSection(title = "Navegación Rápida") {
                                GameBarItem(
                                    icon = Icons.Default.Home,
                                    label = "Go Back To Hub (Minimizar)",
                                    onClick = onGoBackToHub,
                                    isActive = true,
                                    accentColor = Color(0xFF38BDF8)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                GameBarItem(
                                    icon = Icons.Default.CameraAlt,
                                    label = "Captura de Pantalla HQ",
                                    onClick = onTakeScreenshot,
                                    isActive = false,
                                    accentColor = accentColor
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            GameBarSection(title = "Controles en Pantalla") {
                                GameBarItem(
                                    icon = Icons.Default.TouchApp,
                                    label = if (isTouchControlsEnabled) "Controles Táctiles: Activados" else "Controles Táctiles: Desactivados",
                                    onClick = onToggleTouchControls,
                                    isActive = isTouchControlsEnabled,
                                    accentColor = accentColor
                                )
                            }
                        }

                        1 -> {
                            // Pestaña Logros
                            GameBarSection(title = "TrueAchievements") {
                                GameBarItem(
                                    icon = Icons.Default.EmojiEvents,
                                    label = "Abrir Overlay de Logros",
                                    onClick = {
                                        onClose()
                                        onViewAchievements()
                                    },
                                    isActive = true,
                                    accentColor = Color(0xFFFFD700)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Consulta guías comunitarias y logros desbloqueados sin salir de la partida.",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        2 -> {
                            // Pestaña Ajustes en Vivo
                            GameBarSection(title = "Audio y Micrófono") {
                                GameBarItem(
                                    icon = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    label = if (isMicMuted) "Micrófono: Mute" else "Micrófono: On",
                                    onClick = onToggleMic,
                                    isActive = !isMicMuted,
                                    accentColor = accentColor
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text("Volumen del Stream", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Slider(
                                    value = volume,
                                    onValueChange = onVolumeChange,
                                    colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            GameBarSection(title = "Rendimiento y Pantalla") {
                                GameBarItem(
                                    icon = Icons.Default.Speed,
                                    label = if (isVsyncOff) "VSync-Off: Presentación Inmediata" else "VSync: Bloqueado a 60Hz",
                                    onClick = onToggleVsync,
                                    isActive = isVsyncOff,
                                    accentColor = if (isVsyncOff) Color(0xFF22C55E) else Color.Gray
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                GameBarItem(
                                    icon = Icons.Default.ScreenRotation,
                                    label = if (isGyroEnabled) "Giroscopio: Activado" else "Giroscopio: Desactivado",
                                    onClick = onToggleGyro,
                                    isActive = isGyroEnabled,
                                    accentColor = accentColor
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C222B)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Reanudar Partida", fontWeight = FontWeight.Bold)
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
        Spacer(modifier = Modifier.height(8.dp))
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
            .background(if (isActive) accentColor.copy(alpha = 0.12f) else Color(0xFF141920))
            .border(1.dp, if (isActive) accentColor.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (isActive) accentColor else Color.White, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontSize = 13.sp)
    }
}
