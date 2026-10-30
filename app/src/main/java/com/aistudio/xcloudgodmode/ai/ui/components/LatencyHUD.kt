package com.aistudio.xcloudgodmode.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.xcloudgodmode.ai.services.WebRTCStreamManager

@Composable
fun LatencyHUD(
    streamManager: WebRTCStreamManager,
    position: String = "TOP",
    showFps: Boolean = true,
    showRtt: Boolean = true,
    showJitter: Boolean = true,
    showLoss: Boolean = true,
    showBitrate: Boolean = true,
    showProfilePill: Boolean = true,
    fsrEnabled: Boolean = true,
    fsrSharpness: Float = 0.6f,
    aspectRatio: String = "16:9",
    volumeBoost: Int = 120,
    modifier: Modifier = Modifier
) {
    val stats by streamManager.stats.collectAsState()

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("latency_hud")
    ) {
        // Bloque vertical de estadísticas fijado al límite de la pantalla (God Mode Script Style)
        Column(
            modifier = Modifier.padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (showFps) {
                StatLine(
                    label = "FPS",
                    value = "${stats.fps} fps",
                    valueColor = Color(0xFF4ADE80)
                )
            }
            if (showRtt) {
                StatLine(
                    label = "RTT",
                    value = "${stats.rtt}ms",
                    valueColor = getLatencyColor(stats.rtt)
                )
            }
            if (showJitter) {
                StatLine(
                    label = "Jitter",
                    value = String.format("%.1fms", stats.jitter),
                    valueColor = Color(0xFFE2E8F0)
                )
            }
            if (showLoss) {
                StatLine(
                    label = "Loss",
                    value = "${stats.packetLoss}%",
                    valueColor = if (stats.packetLoss > 0) Color(0xFFEF4444) else Color(0xFF4ADE80)
                )
            }
            if (showBitrate) {
                StatLine(
                    label = "Bitrate",
                    value = "${stats.bitrate / 1000}kbps",
                    valueColor = Color(0xFF38BDF8)
                )
            }
        }

        // Píldora inferior de perfil de renderizado: FSR 3.1 RCAS: ON (60%)  Ratio: 16:9  Vol: 120%
        if (showProfilePill) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color(0xFF282D33))
            ) {
                val fsrText = if (fsrEnabled) "FSR 3.1 RCAS: ON (${(fsrSharpness * 100).toInt()}%)" else "FSR: OFF"
                Text(
                    text = "$fsrText  Ratio: $aspectRatio  Vol: $volumeBoost%",
                    color = Color(0xFF4ADE80),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, valueColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: ",
            color = Color(0xFFA0AEC0),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 48.dp)
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun getLatencyColor(rtt: Long): Color {
    return when {
        rtt < 30 -> Color(0xFF22C55E) // Green
        rtt < 60 -> Color(0xFFFACC15) // Yellow
        rtt < 100 -> Color(0xFFFB923C) // Orange
        else -> Color(0xFFEF4444) // Red
    }
}
