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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.xcloudgodmode.ai.services.WebRTCStreamManager

@Composable
fun LatencyHUD(
    streamManager: WebRTCStreamManager,
    position: String = "TOP",
    modifier: Modifier = Modifier
) {
    val stats by streamManager.stats.collectAsState()

    val alignment = if (position == "BOTTOM") Alignment.BottomCenter else Alignment.TopCenter
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("latency_hud")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HUDStatItem(label = "RTT", value = "${stats.rtt}ms", color = getLatencyColor(stats.rtt))
            HUDStatItem(label = "Jitter", value = String.format("%.1fms", stats.jitter), color = Color.White)
            HUDStatItem(label = "Loss", value = "${stats.packetLoss}%", color = if (stats.packetLoss > 0) Color.Red else Color.Green)
            HUDStatItem(label = "Bitrate", value = "${stats.bitrate / 1000}kbps", color = Color.Cyan)
        }
    }
}

@Composable
private fun HUDStatItem(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label: ",
            color = Color.Gray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = value,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
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
