package com.xcloud.godmode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcloud.godmode.network.NatType
import com.xcloud.godmode.network.NetworkDiagnostic
import com.xcloud.godmode.network.NetworkDiagnosticResult
import kotlinx.coroutines.launch

@Composable
fun NetworkDiagnosticDialog(
    consoleName: String = "Xbox Series X",
    consoleId: String,
    onDismiss: () -> Unit,
    onLaunchStream: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostic = remember { NetworkDiagnostic(context) }

    var isScanning by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<NetworkDiagnosticResult?>(null) }

    fun runScan() {
        scope.launch {
            isScanning = true
            result = diagnostic.runDiagnostic()
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        runScan()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SettingsEthernet,
                    contentDescription = null,
                    tint = Color(0xFF4ADE80),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Diagnóstico de Red xHome",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "Rastreo ICE & Estado NAT para $consoleName",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("network_diagnostic_content")
            ) {
                if (isScanning) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF107C10),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "Rastreando candidatos ICE y comprobando túneles UDP...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                } else if (result != null) {
                    val res = result!!
                    AnimatedVisibility(visible = true, enter = fadeIn()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // NAT Badge Banner
                            Surface(
                                color = Color(res.natType.colorHex).copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(res.natType.colorHex)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (res.natType) {
                                            NatType.OPEN -> Icons.Default.CheckCircle
                                            NatType.MODERATE -> Icons.Default.Warning
                                            NatType.STRICT -> Icons.Default.Error
                                            NatType.UNAVAILABLE -> Icons.Default.Cancel
                                        },
                                        contentDescription = null,
                                        tint = Color(res.natType.colorHex),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = res.natType.title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(res.natType.colorHex)
                                        )
                                        Text(
                                            text = res.natType.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.9f)
                                        )
                                    }
                                }
                            }

                            // Telemetry Metrics Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MetricBox(label = "RTT", value = "${res.rttMs}ms", color = Color(0xFF4ADE80), modifier = Modifier.weight(1f))
                                MetricBox(label = "Jitter", value = "%.1fms".format(java.util.Locale.US, res.jitterMs), color = Color(0xFF38BDF8), modifier = Modifier.weight(1f))
                                MetricBox(label = "Pérdida", value = "${res.packetLossPercent}%", color = if (res.packetLossPercent > 0) Color(0xFFEF4444) else Color(0xFF4ADE80), modifier = Modifier.weight(1f))
                                MetricBox(label = "MTU", value = "${res.mtuSize}", color = Color.White, modifier = Modifier.weight(1f))
                            }

                            // Candidates List
                            Text(
                                text = "Candidatos ICE Detectados (${res.localCandidates.size}):",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF107C10)
                            )

                            Surface(
                                color = Color(0xFF121418),
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242A33)),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp)
                            ) {
                                LazyColumn(contentPadding = PaddingValues(6.dp)) {
                                    items(res.localCandidates) { cand ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    color = when (cand.candidateType) {
                                                        "host" -> Color(0xFF1E3A8A)
                                                        "srflx" -> Color(0xFF14532D)
                                                        else -> Color(0xFF713F12)
                                                    },
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = cand.candidateType.uppercase(),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "${cand.address}:${cand.port}",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = Color.LightGray
                                                )
                                            }
                                            Text(
                                                text = "${cand.latencyMs}ms",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = Color(0xFF4ADE80)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onLaunchStream(consoleId)
                },
                enabled = !isScanning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                modifier = Modifier.testTag("launch_xhome_stream_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Iniciar Transmisión xHome")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isScanning) {
                    TextButton(onClick = { runScan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reintentar", color = Color.LightGray)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar", color = Color.Gray)
                }
            }
        },
        containerColor = Color(0xFF16191E),
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun MetricBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF1C2026),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF282D33)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 9.sp, color = Color.Gray)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
        }
    }
}
