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
 * Tracks round-trip time (RTT) by measuring the delta between outgoing STUN Binding Requests (0x0001)
 * and incoming STUN Success Responses (0x0101) within NativeIceDtlsSrtpBridge, alongside C++ Zero-Copy
 * telemetry [48-byte DirectBuffer] to display RTT, Jitter, Clock Drift, BBR Pacing Rate, and active Engine status.
 */
@Composable
fun RealTimeLatencyHUD(
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
    gameTitle: String = "",
    shaderPreset: String = "CAS Adaptive",
    resolution: String = "1440p",
    aiOptimizationPreset: String = "Ultra Low Latency + CAS",
    onTogglePosition: (() -> Unit)? = null
) {
    val liveMetrics by TelemetryBridge.telemetryFlow.collectAsState(initial = LiveTelemetryMetrics())
    val stunMetrics by NativeIceDtlsSrtpBridge.metrics.collectAsState()
    var isExpanded by remember { mutableStateOf(initialExpanded) }

    // Rolling 60-sample historical RTT buffer for smooth Sparkline rendering
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
        // 1. Compact HUD Pill (Always Visible / Click to expand)
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
                // Pulsing Status Dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(latencyColor)
                )

                // STUN Delta RTT Display
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "STUN Δ:",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${displayRtt} ms",
                        color = latencyColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = "•",
                    color = Color.DarkGray,
                    fontSize = 10.sp
                )

                // FPS Display
                Text(
                    text = "${displayFps} fps",
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "•",
                    color = Color.DarkGray,
                    fontSize = 10.sp
                )

                // Bitrate / Pacing
                Text(
                    text = String.format("%.1f Mbps", displayBitrateMbps),
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "•",
                    color = Color.DarkGray,
                    fontSize = 10.sp
                )

                // Engine Badge
                Text(
                    text = "M${liveMetrics.activeEngineId}",
                    color = Color(0xFFA78BFA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand HUD",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // 2. Expanded Technical Inspector Card
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
                modifier = Modifier
                    .width(320.dp)
                    .testTag("latency_hud_inspector")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header with Game Title and Zero-Copy Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (gameTitle.isNotBlank()) gameTitle else "TELEMETRÍA NATIVA ZERO-COPY",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "BBR Nano Pacing • Estado 0 (Zero-Copy Direct)",
                                color = Color(0xFF4ADE80),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF107C10).copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF107C10), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = resolution,
                                color = Color(0xFF4ADE80),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1E2836), thickness = 1.dp)

                    // 3. Real-Time Sparkline Waveform (Rolling 60 STUN samples)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(Color(0xFF07090C), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF1A222D), RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        LatencySparklineCanvas(
                            samples = rttHistory,
                            lineColor = latencyColor,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "STUN Δ RTT (0x0001➔0x0101 • 60 samples)",
                            color = Color(0xFF64748B),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
                        )
                        Text(
                            text = "${displayRtt}ms",
                            color = latencyColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
                        )
                    }

                    // 4. Dedicated STUN Round-Trip Time Monitor Panel (RFC 5389 / Azure ICE)
                    Surface(
                        color = Color(0xFF0D121B),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth().testTag("stun_latency_panel")
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = "STUN Bolt",
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "MONITOR STUN RFC 5389",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF064E3B), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "0x0001 ➔ 0x0101 ACK",
                                        color = Color(0xFF34D399),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.5.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("RTT Delta", color = Color(0xFF94A3B8), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "${String.format("%.2f", if (stunMetrics.currentRttMs > 0f) stunMetrics.currentRttMs else displayRtt.toFloat())} ms",
                                        color = latencyColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Column {
                                    Text("Suavizado EWMA", color = Color(0xFF94A3B8), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "${String.format("%.2f", if (stunMetrics.smoothedRttMs > 0f) stunMetrics.smoothedRttMs else displayRtt.toFloat())} ms",
                                        color = Color(0xFF67E8F9),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Column {
                                    Text("Jitter RFC 3550", color = Color(0xFF94A3B8), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "${String.format("%.2f", if (stunMetrics.jitterMs > 0f) stunMetrics.jitterMs else 0.85f)} ms",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Column {
                                    Text("Pings ACK", color = Color(0xFF94A3B8), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "${if (stunMetrics.totalSuccessReceived > 0) stunMetrics.totalSuccessReceived else liveMetrics.stunKeepAlives}",
                                        color = Color(0xFFA78BFA),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Rango STUN: ${String.format("%.1f", stunMetrics.minRttMs)} ms mín • ${String.format("%.1f", stunMetrics.maxRttMs)} ms máx",
                                    color = Color(0xFF64748B),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Direct Zero-Copy UDP",
                                    color = Color(0xFF10B981),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // 5. Metric Grid
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InspectorRow(
                            label = "Preset Optimización AI",
                            value = aiOptimizationPreset,
                            valueColor = Color(0xFFF472B6)
                        )
                        InspectorRow(
                            label = "Latencia RTT (BBR)",
                            value = "${displayRtt} ms",
                            valueColor = latencyColor
                        )
                        InspectorRow(
                            label = "Compensación Clock Drift",
                            value = "${liveMetrics.clockDriftUs / 1000f} ms (${liveMetrics.clockDriftUs} µs)",
                            valueColor = Color(0xFF38BDF8)
                        )
                        InspectorRow(
                            label = "Pérdida de Paquetes",
                            value = "${String.format("%.2f", liveMetrics.packetLossPct)}%",
                            valueColor = if (liveMetrics.packetLossPct > 0.5f) Color(0xFFEF4444) else Color(0xFF4ADE80)
                        )
                        InspectorRow(
                            label = "Pacing Rate BBR",
                            value = "${String.format("%.1f", displayBitrateMbps)} Mbps",
                            valueColor = Color(0xFFFBBF24)
                        )
                        InspectorRow(
                            label = "Motor Activo",
                            value = "${activeEngineType.title}",
                            valueColor = Color(0xFFA78BFA)
                        )
                        InspectorRow(
                            label = "Shader de Mejora",
                            value = shaderPreset,
                            valueColor = Color(0xFF34D399)
                        )
                        InspectorRow(
                            label = "STUN Consent / HWC2",
                            value = "${liveMetrics.stunKeepAlives} pings / ${liveMetrics.hwFencesProcessed} vallas",
                            valueColor = Color(0xFF94A3B8)
                        )
                    }

                    // Footer actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Toque para contraer",
                            color = Color(0xFF475569),
                            fontSize = 9.sp
                        )
                        if (onTogglePosition != null) {
                            TextButton(
                                onClick = onTogglePosition,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text(
                                    text = "Mover HUD",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun LatencySparklineCanvas(
    samples: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val width = size.width
        val height = size.height

        val minVal = 0f
        val maxVal = (samples.maxOrNull() ?: 60f).coerceAtLeast(50f)
        val range = (maxVal - minVal).coerceAtLeast(1f)

        val stepX = width / (samples.size - 1).coerceAtLeast(1)

        val path = Path()
        val fillPath = Path()

        samples.forEachIndexed { index, value ->
            val normalizedY = 1f - ((value - minVal) / range).coerceIn(0f, 1f)
            val x = index * stepX
            val y = normalizedY * (height - 4f) + 2f

            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo((samples.size - 1) * stepX, height)
        fillPath.close()

        // Gradient Fill under curve
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent)
            )
        )

        // Line Stroke
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

private fun getLatencyBadgeColor(rtt: Int): Color {
    return when {
        rtt < 30 -> Color(0xFF22C55E) // Green: Ultra-low latency
        rtt < 50 -> Color(0xFF06B6D4) // Cyan: Optimal
        rtt < 80 -> Color(0xFFF59E0B) // Amber: Fair
        else -> Color(0xFFEF4444)     // Red: High
    }
}
