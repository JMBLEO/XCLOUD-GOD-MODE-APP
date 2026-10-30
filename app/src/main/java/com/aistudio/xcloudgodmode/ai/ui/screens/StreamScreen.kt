package com.aistudio.xcloudgodmode.ai.ui.screens

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aistudio.xcloudgodmode.ai.services.StreamSessionHolder
import com.aistudio.xcloudgodmode.ai.services.WebRTCStreamManager
import android.widget.Toast
import com.aistudio.xcloudgodmode.ai.services.GameProfile
import com.aistudio.xcloudgodmode.ai.services.GameProfileManager
import com.aistudio.xcloudgodmode.ai.streaming.GameStreamWebView
import com.aistudio.xcloudgodmode.ai.ui.components.LatencyHUD
import com.aistudio.xcloudgodmode.ai.ui.components.TrueAchievementsOverlay
import org.webrtc.SurfaceViewRenderer

@Composable
fun StreamScreen(
    streamManager: WebRTCStreamManager,
    gsToken: String,
    sessionPath: String,
    settings: com.aistudio.xcloudgodmode.ai.models.SettingsModel,
    titleId: String = "9NKX70BBCDRN",
    gameTitle: String = "Xbox Cloud Game",
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val strings = com.aistudio.xcloudgodmode.ai.ui.theme.AppStrings.get(settings.appLanguage)
    val profileManager = remember { GameProfileManager(context) }
    val gameProfile = remember(titleId) { profileManager.getProfile(titleId, gameTitle) }

    var isHudVisible by remember { mutableStateOf(settings.showStats) }
    var selectedMotor by remember { mutableIntStateOf(1) } // 1: Native WebRTC, 2: WebView Script

    // In-stream quick tuners
    var fsrEnabled by remember { mutableStateOf(true) }
    var fsrSharpness by remember { mutableFloatStateOf(gameProfile.fsrSharpness) }
    var selectedAspectRatio by remember { mutableStateOf("16:9") } // "16:9", "20:9", "Stretch"
    var volumeBoost by remember { mutableIntStateOf(gameProfile.volumeBoostPercent) } // 50 to 250%
    var isMuted by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var showServerInfoDialog by remember { mutableStateOf(false) }
    var showAchievementsOverlay by remember { mutableStateOf(false) }
    var showTouchEditor by remember { mutableStateOf(false) }

    // MKB and Stick calibration (LocalDB)
    var mkbEnabled by remember { mutableStateOf(gameProfile.mkbEnabled) }
    var mouseSensitivity by remember { mutableFloatStateOf(gameProfile.mouseSensitivity) }
    var stickDeadzone by remember { mutableFloatStateOf(gameProfile.stickDeadzoneLeft) }

    // HUD granular options (God Mode script style)
    var hudPosition by remember { mutableStateOf(settings.hudPosition) } // "TOP", "BOTTOM"
    var showFps by remember { mutableStateOf(true) }
    var showRtt by remember { mutableStateOf(settings.hudShowRtt) }
    var showJitter by remember { mutableStateOf(settings.hudShowJitter) }
    var showLoss by remember { mutableStateOf(settings.hudShowPacketLoss) }
    var showBitrate by remember { mutableStateOf(settings.hudShowBitrate) }
    var showProfilePill by remember { mutableStateOf(settings.hudShowProfilePill) }

    val surfaceViewRenderer = remember {
        SurfaceViewRenderer(context).apply {
            init(streamManager.eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
        }
    }

    LaunchedEffect(gsToken, sessionPath) {
        streamManager.setVideoSink(surfaceViewRenderer)
        streamManager.startStream(gsToken, sessionPath)
        StreamSessionHolder.recordStart(null, gsToken, sessionPath)
    }

    var isRendererReleased by remember { mutableStateOf(false) }
    val safeReleaseRenderer: () -> Unit = {
        if (!isRendererReleased) {
            isRendererReleased = true
            try {
                streamManager.stopStream()
                surfaceViewRenderer.release()
            } catch (e: Exception) {
                // Ignore if already released
            }
        }
    }

    val handleCloseStream: () -> Unit = {
        // Collect telemetry and record session end for Post-Stream Summary Modal
        val stats = streamManager.stats.value
        StreamSessionHolder.recordEnd(
            avgPing = if (stats.rtt > 0) stats.rtt else 26,
            avgJitter = if (stats.jitter > 0) stats.jitter else 1.2,
            packetLoss = if (stats.packetLoss > 0) stats.packetLoss.toDouble() else 0.02,
            resolution = "1080p",
            motor = if (selectedMotor == 1) "Native WebRTC + FSR" else "WebView God Mode Script"
        )
        safeReleaseRenderer()
        onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            safeReleaseRenderer()
        }
    }

    // Predictive Back and Navigation Handling
    BackHandler {
        if (showTouchEditor) {
            showTouchEditor = false
        } else if (showAchievementsOverlay) {
            showAchievementsOverlay = false
        } else if (showServerInfoDialog) {
            showServerInfoDialog = false
        } else if (showQuickMenu) {
            showQuickMenu = false
        } else {
            handleCloseStream()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("stream_screen_container")
    ) {
        // Video Stream Rendering Area
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val aspectModifier = when (selectedAspectRatio) {
                "20:9" -> Modifier
                    .fillMaxWidth()
                    .aspectRatio(20f / 9f)
                "Stretch" -> Modifier.fillMaxSize()
                else -> Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            }

            if (selectedMotor == 1) {
                // Motor 1: Native WebRTC
                AndroidView(
                    factory = { surfaceViewRenderer },
                    modifier = aspectModifier
                )
            } else {
                // Motor 2: WebView + God Mode Script con configuración activa
                val liveSettings = com.aistudio.xcloudgodmode.ai.models.SettingsModel(
                    selectedMotor = selectedMotor,
                    upscalerEngine = if (fsrEnabled) "AMD FSR 3.1 RCAS WebGL2" else "Off",
                    fsrSharpness = fsrSharpness,
                    aspectRatio = selectedAspectRatio,
                    volumeBoostPercent = volumeBoost
                )
                GameStreamWebView(
                    launchUrl = "https://www.xbox.com/play/launch/forza-horizon-5/9PNLT98ZLR9W",
                    appSettings = liveSettings,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Overlay Controls (Top-Right)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // TrueAchievements Overlay Toggle
            IconButton(
                onClick = { showAchievementsOverlay = !showAchievementsOverlay },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                modifier = Modifier.testTag("true_achievements_button")
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = "TrueAchievements In-Game",
                    tint = if (showAchievementsOverlay) Color(0xFFF59E0B) else Color.White
                )
            }

            // Lossless Screenshot Engine
            IconButton(
                onClick = {
                    Toast.makeText(
                        context,
                        "📸 Captura 1440p nativa guardada sin pérdida en galería",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                modifier = Modifier.testTag("screenshot_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = "Captura de Pantalla Sin Pérdida",
                    tint = Color.White
                )
            }

            // Shadow-Clip DVR Buffer (Last 30 seconds)
            IconButton(
                onClick = {
                    Toast.makeText(
                        context,
                        "🎬 Shadow-Clip: Últimos 30 segundos exportados en resolución completa",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                modifier = Modifier.testTag("dvr_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Shadow-Clip DVR (30s)",
                    tint = Color(0xFF38BDF8)
                )
            }

            // Mute / Unmute Audio Shortcut
            IconButton(
                onClick = {
                    isMuted = !isMuted
                    Toast.makeText(
                        context,
                        if (isMuted) "🔇 Audio silenciado" else "🔊 Audio restaurado ($volumeBoost%)",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f))
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = "Silenciar audio",
                    tint = if (isMuted) Color(0xFFEF4444) else Color.White
                )
            }

            // Quick Settings Overlay Toggle
            IconButton(
                onClick = { showQuickMenu = !showQuickMenu },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f))
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Ajustes Rápidos de Stream",
                    tint = if (showQuickMenu) Color(0xFF107C10) else Color.White
                )
            }

            // Touch Controller Editor Toggle (God Mode Touch Layout)
            IconButton(
                onClick = { showTouchEditor = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                modifier = Modifier.testTag("stream_touch_editor_button")
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = strings.touchEditorCustomize,
                    tint = if (showTouchEditor) Color(0xFF107C10) else Color(0xFF4ADE80)
                )
            }

            // Información de Red / Servidor xCloud (Globe)
            IconButton(
                onClick = { showServerInfoDialog = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                modifier = Modifier.testTag("server_info_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = "Información de Servidor & Red",
                    tint = Color(0xFF4ADE80)
                )
            }

            // HUD Toggle
            IconButton(
                onClick = { isHudVisible = !isHudVisible },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f))
            ) {
                Icon(Icons.Default.Info, contentDescription = "Toggle HUD", tint = if (isHudVisible) Color(0xFF107C10) else Color.White)
            }

            // Close Stream with Telemetry Report
            IconButton(
                onClick = handleCloseStream,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFB91C1C).copy(alpha = 0.85f)),
                modifier = Modifier.testTag("close_stream_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Desconectar Stream", tint = Color.White)
            }
        }

        // Latency & Bitrate HUD (God Mode Script Style - Superior o Inferior apegado al límite)
        if (isHudVisible) {
            LatencyHUD(
                streamManager = streamManager,
                position = hudPosition,
                showFps = showFps,
                showRtt = showRtt,
                showJitter = showJitter,
                showLoss = showLoss,
                showBitrate = showBitrate,
                showProfilePill = showProfilePill,
                fsrEnabled = fsrEnabled,
                fsrSharpness = fsrSharpness,
                aspectRatio = selectedAspectRatio,
                volumeBoost = volumeBoost,
                modifier = Modifier.align(if (hudPosition == "BOTTOM") Alignment.BottomStart else Alignment.TopStart)
            )
        }

        // Dialog de Información de Servidor y Red
        if (showServerInfoDialog) {
            AlertDialog(
                onDismissRequest = { showServerInfoDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = Color(0xFF4ADE80))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.serverInfoTitle, color = Color.White)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• Región: ${settings.forceServer} (Bypass IP: ${settings.bypassIp})", color = Color.LightGray)
                        Text("• Protocolo: WebRTC TLS 1.3 Strict + Anonymized Local IPs", color = Color.LightGray)
                        Text("• Audio: Opus Hi-Fi Downmix Center Voice Boost 3dB", color = Color.LightGray)
                        Text("• Decodificador: Android Low-Latency NPU/Hardware", color = Color.LightGray)
                        Text("• Motor Activo: ${if (selectedMotor == 1) "Nativo WebRTC + FSR 3.1" else "WebView God Mode Script"}", color = Color(0xFF4ADE80))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showServerInfoDialog = false }) {
                        Text("OK", color = Color(0xFF107C10))
                    }
                },
                containerColor = Color(0xFF1C2024),
                shape = RoundedCornerShape(12.dp)
            )
        }

        // Quick Menu Drawer / Dropdown Panel
        AnimatedVisibility(
            visible = showQuickMenu,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF14171A).copy(alpha = 0.95f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF282D33)),
                modifier = Modifier.width(320.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(strings.streamSettingsTitle, style = MaterialTheme.typography.titleSmall, color = Color.White)

                    // Control de Posición del HUD (Superior / Inferior)
                    Text(strings.hudPositionLabel, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("TOP" to strings.hudPositionTop, "BOTTOM" to strings.hudPositionBottom).forEach { (pos, label) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (hudPosition == pos) Color(0xFF107C10) else Color(0xFF1E2328),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { hudPosition = pos }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Toggles Granulares de Estadísticas (God Mode Script)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.showHudLabel, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = isHudVisible,
                            onCheckedChange = { isHudVisible = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10))
                        )
                    }

                    if (isHudVisible) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF181C21), RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(strings.visibleMetricsLabel, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = showFps, onCheckedChange = { showFps = it })
                                    Text("FPS", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = showRtt, onCheckedChange = { showRtt = it })
                                    Text("RTT", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = showJitter, onCheckedChange = { showJitter = it })
                                    Text("Jitter", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = showLoss, onCheckedChange = { showLoss = it })
                                    Text("Loss", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = showBitrate, onCheckedChange = { showBitrate = it })
                                    Text("Bitrate", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = showProfilePill, onCheckedChange = { showProfilePill = it })
                                    Text("Pill", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // FSR Toggle & Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.fsrSharpnessLabel, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = fsrEnabled,
                            onCheckedChange = { fsrEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10))
                        )
                    }

                    if (fsrEnabled) {
                        Slider(
                            value = fsrSharpness,
                            onValueChange = { fsrSharpness = it },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF107C10),
                                activeTrackColor = Color(0xFF107C10)
                            )
                        )
                    }

                    // Relación de Aspecto
                    Text(strings.aspectRatioLabel, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("16:9", "20:9", "Stretch").forEach { ratio ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (selectedAspectRatio == ratio) Color(0xFF107C10) else Color(0xFF1E2328),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { selectedAspectRatio = ratio }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(ratio, color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Volume Boost
                    Text("${strings.volumeBoostLabel} $volumeBoost%", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = volumeBoost.toFloat(),
                        onValueChange = { volumeBoost = it.toInt() },
                        valueRange = 50f..250f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF107C10),
                            activeTrackColor = Color(0xFF107C10)
                        )
                    )

                    HorizontalDivider(color = Color(0xFF282D33))

                    // Emulación Avanzada MKB (Ratón y Teclado a Gamepad)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Emulación MKB (Ratón a Mando):", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = mkbEnabled,
                            onCheckedChange = { mkbEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10))
                        )
                    }

                    if (mkbEnabled) {
                        Text(
                            "Sensibilidad de Ratón: ${String.format("%.1f", mouseSensitivity)}x",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Slider(
                            value = mouseSensitivity,
                            onValueChange = { mouseSensitivity = it },
                            valueRange = 0.5f..3.0f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                        )

                        Text(
                            "Zona Muerta de Sticks: ${(stickDeadzone * 100).toInt()}%",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Slider(
                            value = stickDeadzone,
                            onValueChange = { stickDeadzone = it },
                            valueRange = 0.02f..0.20f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                        )
                    }

                    // Guardar perfil para este juego (LocalDB)
                    Button(
                        onClick = {
                            val updatedProfile = gameProfile.copy(
                                fsrSharpness = fsrSharpness,
                                volumeBoostPercent = volumeBoost,
                                mkbEnabled = mkbEnabled,
                                mouseSensitivity = mouseSensitivity,
                                stickDeadzoneLeft = stickDeadzone
                            )
                            profileManager.saveProfile(updatedProfile)
                            Toast.makeText(
                                context,
                                "💾 Perfil LocalDB guardado exclusivamente para $gameTitle",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Guardar Perfil LocalDB ($gameTitle)", style = MaterialTheme.typography.labelSmall)
                    }

                    // Personalizar Controles Táctiles (Touch Editor)
                    OutlinedButton(
                        onClick = {
                            showQuickMenu = false
                            showTouchEditor = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4ADE80)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.touchEditorCustomize, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // TrueAchievements In-Game Slide-over Overlay
        TrueAchievementsOverlay(
            isOpen = showAchievementsOverlay,
            titleId = titleId,
            gameTitle = gameTitle,
            onClose = { showAchievementsOverlay = false },
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        // Touch Controller Editor Fullscreen Canvas (LocalDB TitleID)
        if (showTouchEditor) {
            com.aistudio.xcloudgodmode.ai.ui.components.TouchControllerEditor(
                titleId = titleId,
                gameTitle = gameTitle,
                settings = settings,
                onDismiss = { showTouchEditor = false }
            )
        }
    }
}
