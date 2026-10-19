package com.aistudio.xcloudgodmode.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.xcloudgodmode.ai.services.WebRTCStreamManager

@Composable
fun LatencyHUD(
    streamManager: WebRTCStreamManager,
    modifier: Modifier = Modifier
) {
    val stats by streamManager.stats.collectAsState()

    Box(
        modifier = modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(8.dp)
            .testTag("latency_hud")
    ) {
        Column(horizontalAlignment = Alignment.End) {
            HUDStatRow(label = "RTT", value = "${stats.rtt}ms", color = getLatencyColor(stats.rtt))
            HUDStatRow(label = "Jitter", value = String.format("%.1fms", stats.jitter), color = Color.White)
            HUDStatRow(label = "Loss", value = "${stats.packetLoss}%", color = if (stats.packetLoss > 0) Color.Red else Color.Green)
            HUDStatRow(label = "Bitrate", value = "${stats.bitrate / 1000}kbps", color = Color.Cyan)
        }
    }
}

@Composable
private fun HUDStatRow(label: String, value: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Text(
            text = "$label: ",
            color = Color.LightGray,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = value,
            color = color,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun getLatencyColor(rtt: Long): Color {
    return when {
        rtt < 30 -> Color.Green
        rtt < 60 -> Color.Yellow
        rtt < 100 -> Color(0xFFFFA500) // Orange
        else -> Color.Red
    }
}
