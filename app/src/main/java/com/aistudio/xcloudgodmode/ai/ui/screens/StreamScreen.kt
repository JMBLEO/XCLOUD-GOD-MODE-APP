package com.aistudio.xcloudgodmode.ai.ui.screens

import android.content.Context
import android.media.AudioManager
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
import com.aistudio.xcloudgodmode.ai.streaming.GameStreamWebView
import com.aistudio.xcloudgodmode.ai.ui.components.LatencyHUD
import org.webrtc.SurfaceViewRenderer

@Composable
fun StreamScreen(
    streamManager: WebRTCStreamManager,
    gsToken: String,
    sessionPath: String,
    settings: com.aistudio.xcloudgodmode.ai.models.SettingsModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var isHudVisible by remember { mutableStateOf(settings.showStats) }
    var selectedMotor by remember { mutableIntStateOf(1) } // 1: Native WebRTC, 2: WebView Script

    // In-stream quick tuners
    var fsrEnabled by remember { mutableStateOf(true) }
    var fsrSharpness by remember { mutableFloatStateOf(0.6f) }
    var selectedAspectRatio by remember { mutableStateOf("16:9") } // "16:9", "20:9", "Stretch"
    var volumeBoost by remember { mutableIntStateOf(120) } // 50 to 250%
    var showQuickMenu by remember { mutableStateOf(false) }

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
        streamManager.stopStream()
        surfaceViewRenderer.release()
        onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            streamManager.stopStream()
            surfaceViewRenderer.release()
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Quick Settings Overlay Toggle
            IconButton(
                onClick = { showQuickMenu = !showQuickMenu },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Default.Tune, contentDescription = "Ajustes Rápidos de Stream", tint = if (showQuickMenu) Color(0xFF107C10) else Color.White)
            }

            // Motor Switcher (1 vs 2) On-the-Fly without killing blade session
            IconButton(
                onClick = { selectedMotor = if (selectedMotor == 1) 2 else 1 },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                modifier = Modifier.testTag("motor_switch_button")
            ) {
                Icon(
                    imageVector = if (selectedMotor == 1) Icons.Default.Public else Icons.Default.VideogameAsset,
                    contentDescription = "Cambiar Motor",
                    tint = Color(0xFF4ADE80)
                )
            }

            // HUD Toggle
            IconButton(
                onClick = { isHudVisible = !isHudVisible },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Default.Info, contentDescription = "Toggle HUD", tint = if (isHudVisible) Color(0xFF107C10) else Color.White)
            }

            // Close Stream with Telemetry Report
            IconButton(
                onClick = handleCloseStream,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFB91C1C).copy(alpha = 0.8f)),
                modifier = Modifier.testTag("close_stream_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Desconectar Stream", tint = Color.White)
            }
        }

        // Latency & Bitrate HUD (God Mode Script Style)
        if (isHudVisible && selectedMotor == 1) {
            LatencyHUD(
                streamManager = streamManager,
                position = settings.hudPosition,
                modifier = Modifier.align(if (settings.hudPosition == "BOTTOM") Alignment.BottomCenter else Alignment.TopCenter)
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
                modifier = Modifier.width(280.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Ajustes de Stream", style = MaterialTheme.typography.titleSmall, color = Color.White)

                    // HUD Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mostrar Estadísticas:", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = isHudVisible,
                            onCheckedChange = { isHudVisible = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10))
                        )
                    }

                    // FSR Toggle & Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("FSR Sharpness:", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
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

                    // Aspect Ratio
                    Text("Relación de Aspecto:", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
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
                    Text("Vol Boost: $volumeBoost%", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = volumeBoost.toFloat(),
                        onValueChange = { volumeBoost = it.toInt() },
                        valueRange = 50f..250f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF107C10),
                            activeTrackColor = Color(0xFF107C10)
                        )
                    )
                }
            }
        }
    }
}
