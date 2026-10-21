package com.aistudio.xcloudgodmode.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.xcloudgodmode.ai.constants.NetworkBypassCatalog
import com.aistudio.xcloudgodmode.ai.models.SettingsModel

@Composable
fun AdvancedSettingsTab(
    settings: SettingsModel,
    onSettingsChanged: (SettingsModel) -> Unit,
    onOpenOptimizationCenter: (() -> Unit)? = null
) {
    var activeTab by remember { mutableStateOf("Red") } // Red, Controles, Sistema
    var serverSearch by remember { mutableStateOf("") }
    var ipSearch by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).testTag("advanced_settings_tab")) {
        Text(
            text = "Configuración de Infraestructura",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Text(
            text = "Ajustes de red y sistema negociados antes del apretón de manos WebRTC.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Sub-tabs for organization
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Red", "Controles", "Sistema").forEach { tab ->
                Button(
                    onClick = { activeTab = tab },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeTab == tab) Color(0xFF107C10) else Color(0xFF1C2024),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(tab, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
            if (activeTab == "Red") {
                item {
                    SettingsCategoryCard(icon = Icons.Default.Public, title = "Bypass de Ubicación & Servidor") {
                        // Server Lock
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = settings.enableServerLock,
                                onCheckedChange = { onSettingsChanged(settings.copy(enableServerLock = it)) },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Forzar Servidor Físico Azure", color = Color.White)
                                Text("Server Unlock: ${settings.forceServer}", color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (settings.enableServerLock) {
                            OutlinedTextField(
                                value = serverSearch,
                                onValueChange = { serverSearch = it },
                                placeholder = { Text("Filtrar servidores...") },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF107C10))
                            )
                            val filteredServers = NetworkBypassCatalog.CLOUD_SERVERS.filter { 
                                it.name.contains(serverSearch, ignoreCase = true) || it.id.contains(serverSearch, ignoreCase = true)
                            }
                            Box(modifier = Modifier.heightIn(max = 150.dp).fillMaxWidth().border(1.dp, Color.DarkGray).background(Color.Black.copy(alpha = 0.2f))) {
                                LazyColumn {
                                    items(filteredServers.size) { i ->
                                        val s = filteredServers[i]
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable { onSettingsChanged(settings.copy(forceServer = s.id)) }.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(s.flag, modifier = Modifier.padding(end = 8.dp))
                                            Text(s.name, color = if (settings.forceServer == s.id) Color(0xFF4ADE80) else Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // IP Bypass
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = settings.enableNetworkBypass,
                                onCheckedChange = { onSettingsChanged(settings.copy(enableNetworkBypass = it)) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Bypass IP (X-Forwarded-For)", color = Color.White)
                                Text("IP Activa: ${settings.bypassIp}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (settings.enableNetworkBypass) {
                            OutlinedTextField(
                                value = ipSearch,
                                onValueChange = { ipSearch = it },
                                placeholder = { Text("Filtrar IPs...") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF107C10))
                            )
                            val filteredIps = NetworkBypassCatalog.BYPASS_IP_CATALOG.toList().filter { 
                                it.first.contains(ipSearch, ignoreCase = true) || it.second.contains(ipSearch)
                            }
                            Box(modifier = Modifier.heightIn(max = 150.dp).fillMaxWidth().border(1.dp, Color.DarkGray).background(Color.Black.copy(alpha = 0.2f))) {
                                LazyColumn {
                                    items(filteredIps.size) { i ->
                                        val (name, ip) = filteredIps[i]
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable { onSettingsChanged(settings.copy(bypassIp = ip)) }.padding(8.dp)
                                        ) {
                                            Text(name, color = if (settings.bypassIp == ip) Color(0xFF4ADE80) else Color.White, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    SettingsCategoryCard(icon = Icons.Default.Stream, title = "Ingeniería de Stream") {
                        Text("Perfil de Resolución Forzada", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("720p", "1080p", "1440p", "4k", "8k").forEach { res ->
                                Box(
                                    modifier = Modifier.weight(1f).height(36.dp).background(if (settings.targetResProfile == res) Color(0xFF107C10) else Color(0xFF14171A), RoundedCornerShape(4.dp))
                                        .border(1.dp, if (settings.targetResProfile == res) Color(0xFF4ADE80) else Color.DarkGray, RoundedCornerShape(4.dp))
                                        .clickable { onSettingsChanged(settings.copy(targetResProfile = res)) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(res, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.unlock120Fps, onCheckedChange = { onSettingsChanged(settings.copy(unlock120Fps = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Desbloquear 120 FPS (SDP Rewrite)", color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.infiniteBitrate, onCheckedChange = { onSettingsChanged(settings.copy(infiniteBitrate = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Bitrate Ilimitado (2 Gbps Cap)", color = Color.White)
                        }
                    }
                }
            } else if (activeTab == "Controles") {
                item {
                    SettingsCategoryCard(icon = Icons.Default.SportsEsports, title = "Controles e Integridad") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Polling Rate del Mando", modifier = Modifier.weight(1f), color = Color.White)
                            Text("${settings.pollingRateMs}ms", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.pollingRateMs.toFloat(),
                            onValueChange = { onSettingsChanged(settings.copy(pollingRateMs = it.toInt())) },
                            valueRange = 4f..16f,
                            steps = 3,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                        )
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.swapABXY, onCheckedChange = { onSettingsChanged(settings.copy(swapABXY = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Invertir botones Nintendo (A/B, X/Y)", color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.safeNativeModeOnly, onCheckedChange = { onSettingsChanged(settings.copy(safeNativeModeOnly = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Solo M&K Nativo (Anti-Baneo)", color = Color.White)
                        }
                    }
                }
            } else {
                item {
                    SettingsCategoryCard(icon = Icons.Default.Settings, title = "QoL & Pantalla") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.antiKick, onCheckedChange = { onSettingsChanged(settings.copy(antiKick = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Anti-Kick Automático", color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.skipSplash, onCheckedChange = { onSettingsChanged(settings.copy(skipSplash = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Omitir Video Inicial Splash", color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.blockAnalytics, onCheckedChange = { onSettingsChanged(settings.copy(blockAnalytics = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Bloquear Telemetría y Analíticas", color = Color.White)
                        }
                    }
                }
            }
        }

        if (onOpenOptimizationCenter != null) {
            Button(
                onClick = onOpenOptimizationCenter,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Abrir Game Optimizer (En Vivo)", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsCategoryCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C2024),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF282D33)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Color(0xFF107C10), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
            }
            content()
        }
    }
}
