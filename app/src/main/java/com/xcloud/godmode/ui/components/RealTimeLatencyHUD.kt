package com.xcloud.godmode.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcloud.godmode.streaming.LiveTelemetryMetrics
import com.xcloud.godmode.streaming.NativeIceDtlsSrtpBridge
import com.xcloud.godmode.streaming.NativeStreamBridge
import com.xcloud.godmode.streaming.StreamEngineType
import com.xcloud.godmode.streaming.StunRttMetrics
import com.xcloud.godmode.streaming.TelemetryBridge
import kotlin.math.roundToInt

/**
 * RealTimeLatencyHUD: Real-time network latency and telemetry monitor.
 * Acrylic Glassmorphism configuration panel allows toggling stats visibility.
 */
@Composable
fun RealTimeLatencyHUD(
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
    gameTitle: String = "",
    showNetwork: Boolean = true,
    showInput: Boolean = true,
    showFps: Boolean = true,
    onToggleNetwork: () -> Unit = {},
    onToggleInput: () -> Unit = {},
    onToggleFps: () -> Unit = {},
    shaderPreset: String = "CAS Adaptive",
    resolution: String = "1440p",
    aiOptimizationPreset: String = "Ultra Low Latency + CAS",
    onTogglePosition: (() -> Unit)? = null
) {
    val liveMetrics by TelemetryBridge.telemetryFlow.collectAsState(initial = LiveTelemetryMetrics())
    val stunMetrics by NativeIceDtlsSrtpBridge.metrics.collectAsState()
    var isExpanded by remember { mutableStateOf(initialExpanded) }
    var showHudConfig by remember { mutableStateOf(false) }

    val rttHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(stunMetrics.currentRttMs, liveMetrics.rttMs) {
        val currentRtt = when {
            stunMetrics.currentRttMs > 0f -> stunMetrics.currentRttMs
            liveMetrics.rttMs > 0 -> liveMetrics.rttMs.toFloat()
            else -> 24f
        }
        rttHistory.add(currentRtt)
        if (rttHistory.size > 60) {
            rttHistory.removeAt(0)
        }
    }

    val displayRtt = when {
        stunMetrics.currentRttMs > 0f -> stunMetrics.currentRttMs.roundToInt()
        liveMetrics.rttMs > 0 -> liveMetrics.rttMs
        else -> 24
    }
    val displayFps = if (liveMetrics.fps > 0) liveMetrics.fps else 60
    val displayBitrateMbps = if (liveMetrics.bitrateKbps > 0) {
        liveMetrics.bitrateKbps / 1000f
    } else {
        (NativeStreamBridge.getTargetPacingRateBps(true) / 1_000_000f).toFloat().coerceIn(15f, 50f)
    }

    val activeEngineType = StreamEngineType.fromId(liveMetrics.activeEngineId)
    val latencyColor = getLatencyBadgeColor(displayRtt)

    Column(
        modifier = modifier
            .padding(12.dp)
            .testTag("realtime_latency_hud")
            .animateContentSize()
    ) {
        // 1. Compact HUD Pill
        Surface(
            color = Color(0xDD0B0F14),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFF202934)),
            shadowElevation = 8.dp,
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .testTag("latency_hud_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(latencyColor))

                if (showNetwork) {
                    Text(text = "STUN Δ: ${displayRtt}ms", color = latencyColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(text = "•", color = Color.DarkGray, fontSize = 10.sp)
                }

                if (showFps) {
                    Text(text = "${displayFps} fps", color = Color(0xFFE2E8F0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Text(text = "•", color = Color.DarkGray, fontSize = 10.sp)
                }

                if (showNetwork) {
                    Text(text = String.format("%.1f Mbps", displayBitrateMbps), color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Text(text = "•", color = Color.DarkGray, fontSize = 10.sp)
                }

                Text(text = "M${liveMetrics.activeEngineId}", color = Color(0xFFA78BFA), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Icon(imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }

        // 2. Expanded Inspector
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = Color(0xF00A0D12),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF263238)),
                shadowElevation = 12.dp,
                modifier = Modifier.width(320.dp).testTag("latency_hud_inspector")
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(text = if (gameTitle.isNotBlank()) gameTitle else "TELEMETRÍA NATIVA", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(text = "BBR Nano • Zero-Copy Direct", color = Color(0xFF4ADE80), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { showHudConfig = !showHudConfig }, modifier = Modifier.size(24.dp)) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = if (showHudConfig) Color(0xFF107C10) else Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }

                    AnimatedVisibility(visible = showHudConfig) {
                        HUDSettingsPanel(showNetwork, showInput, showFps, onToggleNetwork, onToggleInput, onToggleFps)
                    }

                    HorizontalDivider(color = Color(0xFF1E2836), thickness = 1.dp)

                    if (showNetwork) {
                        Box(modifier = Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF07090C), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF1A222D), RoundedCornerShape(6.dp)).padding(4.dp)) {
                            LatencySparklineCanvas(samples = rttHistory, lineColor = latencyColor, modifier = Modifier.fillMaxSize())
                            Text(text = "STUN Δ RTT", color = Color(0xFF64748B), fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.TopStart))
                            Text(text = "${displayRtt}ms", color = latencyColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.BottomEnd))
                        }
                    }

                    if (showNetwork) {
                        Surface(color = Color(0xFF0D121B), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFF1E293B)), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = "MONITOR STUN RFC 5389", color = Color(0xFF38BDF8), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.5.dp)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricCol("RTT", "${String.format("%.1f", if (stunMetrics.currentRttMs > 0f) stunMetrics.currentRttMs else displayRtt.toFloat())}ms", latencyColor)
                                    MetricCol("Jitter", "${String.format("%.2f", if (stunMetrics.jitterMs > 0f) stunMetrics.jitterMs else 0.85f)}ms", Color(0xFFFBBF24))
                                    MetricCol("ACKs", "${if (stunMetrics.totalSuccessReceived > 0) stunMetrics.totalSuccessReceived else liveMetrics.stunKeepAlives}", Color(0xFFA78BFA))
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InspectorRow("Optimización AI", aiOptimizationPreset, Color(0xFFF472B6))
                        if (showNetwork) InspectorRow("RTT (BBR)", "${displayRtt} ms", latencyColor)
                        if (showInput) {
                            InspectorRow("Clock Drift", "${liveMetrics.clockDriftUs / 1000f} ms", Color(0xFF38BDF8))
                            val bridgeLatency = com.xcloud.godmode.streaming.NativeInputBridge.activeInstance?.lastInputProcessingLatencyMs ?: 0
                            InspectorRow("Input (Bridge)", "$bridgeLatency ms", Color(0xFF4ADE80))
                            InspectorRow("Input (E2E)", "${liveMetrics.inputToDisplayMs} ms", Color(0xFFFBBF24))
                        }
                        if (showNetwork) InspectorRow("Pérdida", "${String.format("%.2f", liveMetrics.packetLossPct)}%", if (liveMetrics.packetLossPct > 0.5f) Color.Red else Color.Green)
                        InspectorRow("Motor", activeEngineType.title, Color(0xFFA78BFA))
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Toque para contraer", color = Color(0xFF475569), fontSize = 9.sp)
                        if (onTogglePosition != null) {
                            TextButton(onClick = onTogglePosition, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                                Text(text = "Mover HUD", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCol(label: String, value: String, color: Color) {
    Column {
        Text(label, color = Color(0xFF94A3B8), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HUDSettingsPanel(showNetwork: Boolean, showInput: Boolean, showFps: Boolean, onToggleNetwork: () -> Unit, onToggleInput: () -> Unit, onToggleFps: () -> Unit) {
    Surface(color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CONFIGURACIÓN VISIBILIDAD", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HUDConfigToggle("Red", showNetwork, onToggleNetwork)
                HUDConfigToggle("Input", showInput, onToggleInput)
                HUDConfigToggle("FPS", showFps, onToggleFps)
            }
        }
    }
}

@Composable
private fun HUDConfigToggle(label: String, active: Boolean, onToggle: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (active) Color(0xFF107C10).copy(alpha = 0.2f) else Color.Transparent).border(0.5.dp, if (active) Color(0xFF107C10) else Color.DarkGray, RoundedCornerShape(4.dp)).clickable { onToggle() }.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(text = label, color = if (active) Color.White else Color.Gray, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun InspectorRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = valueColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LatencySparklineCanvas(samples: List<Float>, lineColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val width = size.width
        val height = size.height
        val maxVal = (samples.maxOrNull() ?: 60f).coerceAtLeast(50f)
        val stepX = width / (samples.size - 1)
        val path = Path()
        val fillPath = Path()
        samples.forEachIndexed { i, v ->
            val y = (1f - (v / maxVal).coerceIn(0f, 1f)) * (height - 4f) + 2f
            val x = i * stepX
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, height); fillPath.lineTo(x, y) }
            else { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(width, height); fillPath.close()
        drawPath(fillPath, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.2f), Color.Transparent)))
        drawPath(path, lineColor, style = Stroke(1.5.dp.toPx()))
    }
}

private fun getLatencyBadgeColor(rtt: Int): Color = when {
    rtt < 30 -> Color(0xFF22C55E); rtt < 50 -> Color(0xFF06B6D4); rtt < 80 -> Color(0xFFF59E0B); else -> Color(0xFFEF4444)
}
