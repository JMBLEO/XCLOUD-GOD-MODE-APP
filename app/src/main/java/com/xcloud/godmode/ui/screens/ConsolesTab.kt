package com.xcloud.godmode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcloud.godmode.ui.components.NetworkDiagnosticDialog
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ConsolesTab(
    consoles: List<JSONObject>,
    onLaunchConsole: (String) -> Unit,
    authManager: com.xcloud.godmode.network.XboxFullAuthManager,
    viewModel: com.xcloud.godmode.viewmodels.HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    // 1. Carga automática de consolas remotas (Nube) al montar la vista
    LaunchedEffect(Unit) {
        viewModel.fetchCloudConsolesFromXboxLive(authManager)
    }
    val coroutineScope = rememberCoroutineScope()
    var isScanningLan by remember { mutableStateOf(false) }
    var lanConsoles by remember { mutableStateOf<List<com.xcloud.godmode.network.WakeOnLanManager.DiscoveredConsole>>(emptyList()) }
    var diagnosticTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (diagnosticTarget != null) {
        NetworkDiagnosticDialog(
            consoleName = diagnosticTarget!!.first,
            consoleId = diagnosticTarget!!.second,
            onDismiss = { diagnosticTarget = null },
            onLaunchStream = { id ->
                diagnosticTarget = null
                onLaunchConsole(id)
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text("Mis Consolas Xbox", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Cargadas desde tu cuenta vinculada y red doméstica (xHome).", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }

        // Section 1: Cloud Consoles
        item {
            Text("Consolas Cloud (Remotas)", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }

        if (consoles.isEmpty()) {
            item {
                Surface(
                    onClick = { viewModel.fetchCloudConsolesFromXboxLive(authManager) },
                    color = Color(0xFF121820),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Buscando consolas asociadas... (Toca para reintentar)",
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(consoles) { console ->
                ConsoleCardItem(
                    console = console,
                    source = "Nube Microsoft",
                    onConnect = { onLaunchConsole(console.optString("id")) },
                    onDiagnose = { diagnosticTarget = console.optString("name") to console.optString("id") }
                )
            }
        }

        // Section 2: Local Network (SSDP)
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Red Local (LAN SSDP)", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        isScanningLan = true
                        coroutineScope.launch {
                            lanConsoles = com.xcloud.godmode.network.WakeOnLanManager.discoverLocalConsoles()
                            isScanningLan = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                    enabled = !isScanningLan,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isScanningLan) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Escanear LAN")
                }
            }
        }

        if (lanConsoles.isEmpty()) {
            item {
                Text("Inicia un escaneo para detectar consolas en tu Wi-Fi.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(lanConsoles) { lanConsole ->
                val obj = remember(lanConsole) {
                    org.json.JSONObject().apply {
                        put("name", lanConsole.serverName)
                        put("id", lanConsole.ipAddress)
                        put("consoleType", "LAN")
                        put("powerState", "ConnectedStandby")
                    }
                }
                ConsoleCardItem(
                    console = obj,
                    source = "Red Doméstica",
                    onConnect = { onLaunchConsole(lanConsole.ipAddress) },
                    onDiagnose = { diagnosticTarget = lanConsole.serverName to lanConsole.ipAddress }
                )
            }
        }
        
        item { Spacer(modifier = Modifier.height(80.dp)) } // Padding for BottomBar
    }
}

@Composable
fun ConsoleCardItem(
    console: org.json.JSONObject,
    source: String,
    onConnect: () -> Unit,
    onDiagnose: () -> Unit
) {
    val name = console.optString("name", "Xbox Console")
    val state = console.optString("powerState", "Unknown")
    
    Surface(
        onClick = onDiagnose,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF121820),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3B4D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SettingsRemote,
                contentDescription = null,
                tint = if (state == "ConnectedStandby") Color(0xFF00E676) else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold)
                Text("$source • $state", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Conectar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
