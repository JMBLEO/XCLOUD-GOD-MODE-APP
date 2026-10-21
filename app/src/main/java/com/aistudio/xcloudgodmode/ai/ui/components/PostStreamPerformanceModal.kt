package com.aistudio.xcloudgodmode.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aistudio.xcloudgodmode.ai.services.SessionTelemetrySummary

@Composable
fun PostStreamPerformanceModal(
    summary: SessionTelemetrySummary?,
    onClose: () -> Unit,
    onQuickReconnect: () -> Unit
) {
    if (summary == null) return

    val minutes = summary.durationSeconds / 60
    val seconds = summary.durationSeconds % 60

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .testTag("post_stream_performance_modal"),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF16161B),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Column {
                    Text(
                        text = "📊 Informe de Rendimiento de la Sesión",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF4ADE80)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${summary.gameTitle} • Tiempo jugado: ${minutes}m ${seconds}s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFA0A0AB)
                    )
                }

                // 2x2 Telemetry Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricBox(
                        modifier = Modifier.weight(1f),
                        title = "Latencia Media (RTT)",
                        value = "${summary.avgPingMs} ms",
                        valueColor = Color.White
                    )
                    MetricBox(
                        modifier = Modifier.weight(1f),
                        title = "Jitter de Red",
                        value = String.format("%.1f ms", summary.avgJitterMs),
                        valueColor = Color.White
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricBox(
                        modifier = Modifier.weight(1f),
                        title = "Pérdida de Paquetes",
                        value = String.format("%.2f%%", summary.packetLossPercent),
                        valueColor = if (summary.packetLossPercent > 2.0) Color(0xFFF87171) else Color(0xFF4ADE80)
                    )
                    MetricBox(
                        modifier = Modifier.weight(1f),
                        title = "Motor & Calidad",
                        value = "${summary.resolutionUsed} (${summary.motorUsed.take(7)})",
                        valueColor = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Actions: Quick Reconnect #303 + Return to Home
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onQuickReconnect,
                        modifier = Modifier
                            .weight(1.5f)
                            .height(48.dp)
                            .testTag("quick_reconnect_button"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))
                    ) {
                        Text(
                            text = "⚡ Reconectar Sesión (#303)",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("close_summary_button"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBox(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    valueColor: Color
) {
    Column(
        modifier = modifier
            .background(Color(0xFF1F1F27), RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = valueColor
        )
    }
}
