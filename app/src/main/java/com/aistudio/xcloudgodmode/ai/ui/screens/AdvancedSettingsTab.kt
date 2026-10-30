package com.aistudio.xcloudgodmode.ai.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.xcloudgodmode.ai.constants.NetworkBypassCatalog
import com.aistudio.xcloudgodmode.ai.models.SettingsModel
import com.aistudio.xcloudgodmode.ai.ui.theme.AppStrings

@Composable
fun AdvancedSettingsTab(
    settings: SettingsModel,
    onSettingsChanged: (SettingsModel) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val strings = AppStrings.get(settings.appLanguage)

    var activeTab by remember { mutableStateOf("Red") } // Red, Controles, Sistema
    var serverSearch by remember { mutableStateOf("") }
    var ipSearch by remember { mutableStateOf("") }

    var showImportDialog by remember { mutableStateOf(false) }
    var showTouchEditor by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).testTag("advanced_settings_tab")) {
        Text(
            text = strings.settingsTab,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Text(
            text = "God Mode Master Engine Architecture & Customization.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Sub-tabs for organization
        val tabs = if (settings.appLanguage == "en") {
            listOf("Red" to "Network", "Controles" to "Controls", "Sistema" to "System")
        } else {
            listOf("Red" to "Red", "Controles" to "Controles", "Sistema" to "Sistema")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { (tabKey, tabLabel) ->
                Button(
                    onClick = { activeTab = tabKey },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeTab == tabKey) Color(0xFF107C10) else Color(0xFF1C2024),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(tabLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
            if (activeTab == "Red") {
                item {
                    SettingsCategoryCard(icon = Icons.Default.Public, title = strings.settingsBypass) {
                        // Server Lock
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = settings.enableServerLock,
                                onCheckedChange = { onSettingsChanged(settings.copy(enableServerLock = it)) },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Servidor Azure", color = Color.White)
                                Text("ID: ${settings.forceServer}", color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
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
                                Text("Bypass IP", color = Color.White)
                                Text("IP: ${settings.bypassIp}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
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
                    SettingsCategoryCard(icon = Icons.Default.Stream, title = strings.settingsStreaming) {
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
                            Text("Unlock 120 FPS", color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.infiniteBitrate, onCheckedChange = { onSettingsChanged(settings.copy(infiniteBitrate = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Bitrate Ilimitado", color = Color.White)
                        }
                    }
                }
            } else if (activeTab == "Controles") {
                item {
                    SettingsCategoryCard(icon = Icons.Default.SportsEsports, title = strings.settingsInput) {
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
                            Text("Nintendo Layout (A/B, X/Y)", color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.safeNativeModeOnly, onCheckedChange = { onSettingsChanged(settings.copy(safeNativeModeOnly = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Safe M&K Mode", color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Botón de acceso directo al Editor de Controles Táctiles
                        Button(
                            onClick = { showTouchEditor = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("open_touch_editor_button")
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.touchEditorCustomize, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            } else {
                item {
                    SettingsCategoryCard(icon = Icons.Default.Settings, title = "QoL & Pantalla") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.antiKick, onCheckedChange = { onSettingsChanged(settings.copy(antiKick = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Anti-Kick", color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.skipSplash, onCheckedChange = { onSettingsChanged(settings.copy(skipSplash = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Omitir Splash", color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = settings.blockAnalytics, onCheckedChange = { onSettingsChanged(settings.copy(blockAnalytics = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10)))
                            Text("Bloquear Analíticas", color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Sonido de Interfaz", color = Color(0xFF107C10), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = settings.enableUiSounds,
                                onCheckedChange = { onSettingsChanged(settings.copy(enableUiSounds = it)) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Efectos PlayStaxBox", color = Color.White)
                        }

                        if (settings.enableUiSounds) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("HYBRID", "PS5", "360", "PS3").forEach { theme ->
                                    val fullTheme = when(theme) {
                                        "HYBRID" -> "PLAYSTAX_HYBRID"
                                        "PS5" -> "PS5_CRYSTALLINE"
                                        "360" -> "XBOX_BLADES_360"
                                        else -> "PS3_XMB_CLICK"
                                    }
                                    Box(
                                        modifier = Modifier.weight(1f).height(32.dp)
                                            .background(if (settings.uiSoundTheme == fullTheme) Color(0xFF107C10) else Color(0xFF14171A), RoundedCornerShape(4.dp))
                                            .border(1.dp, if (settings.uiSoundTheme == fullTheme) Color(0xFF4ADE80) else Color.DarkGray, RoundedCornerShape(4.dp))
                                            .clickable { onSettingsChanged(settings.copy(uiSoundTheme = fullTheme)) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(theme, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(strings.settingsRender, color = Color(0xFF107C10), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        
                        // Text Scale
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.scaleFontLabel, modifier = Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Text("${settings.textScalePercent}%", color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(
                            value = settings.textScalePercent.toFloat(),
                            onValueChange = { onSettingsChanged(settings.copy(textScalePercent = it.toInt())) },
                            valueRange = 85f..160f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                        )

                        // Icon Scale
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.scaleIconLabel, modifier = Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Text("${settings.iconScalePercent}%", color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(
                            value = settings.iconScalePercent.toFloat(),
                            onValueChange = { onSettingsChanged(settings.copy(iconScalePercent = it.toInt())) },
                            valueRange = 80f..140f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                        )

                        // Poster Scale
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.scalePosterLabel, modifier = Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Text("${settings.posterScalePercent}%", color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(
                            value = settings.posterScalePercent.toFloat(),
                            onValueChange = { onSettingsChanged(settings.copy(posterScalePercent = it.toInt())) },
                            valueRange = 80f..130f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                        )

                        // QR Code Scale (Fluid Screen Aspect Scaling Override)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.scaleQrLabel, modifier = Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Text("${settings.qrScalePercent}%", color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(
                            value = settings.qrScalePercent.toFloat(),
                            onValueChange = { onSettingsChanged(settings.copy(qrScalePercent = it.toInt())) },
                            valueRange = 70f..150f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(strings.settingsSystem, color = Color(0xFF107C10), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)

                        // Dynamic Real Language Selector
                        Text(strings.languageSelectLabel, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "AUTO" to strings.langAuto,
                                "es" to "Español",
                                "en" to "English"
                            ).forEach { (langCode, langName) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .background(
                                            if (settings.appLanguage == langCode) Color(0xFF107C10) else Color(0xFF14171A),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (settings.appLanguage == langCode) Color(0xFF4ADE80) else Color.DarkGray,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { onSettingsChanged(settings.copy(appLanguage = langCode)) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(langName, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // 10-Foot Leanback Navigation Focus Toggle
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = settings.enableLeanbackFocus,
                                onCheckedChange = { onSettingsChanged(settings.copy(enableLeanbackFocus = it)) },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("10-Foot Leanback Focus Rings", color = Color.White)
                                Text("Aura verde (#107C10) en elementos activos para navegación con mando o TV", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Backup & Restore (JSON)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Respaldo & Migración de Ajustes", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val json = settings.toJson()
                                    clipboardManager.setText(AnnotatedString(json))
                                    Toast.makeText(context, strings.backupExportSuccess, Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.exportSettingsLabel, style = MaterialTheme.typography.labelSmall)
                            }

                            OutlinedButton(
                                onClick = {
                                    importJsonText = ""
                                    importError = null
                                    showImportDialog = true
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.importSettingsLabel, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        // Import Dialog
        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text(strings.importSettingsLabel, color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Pega aquí la configuración JSON exportada previamente:",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = importJsonText,
                            onValueChange = {
                                importJsonText = it
                                importError = null
                            },
                            placeholder = { Text("{\"forceServer\": ...}", color = Color.DarkGray) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF107C10),
                                unfocusedBorderColor = Color.DarkGray
                            ),
                            maxLines = 6
                        )
                        if (importError != null) {
                            Text(importError ?: "", color = Color(0xFFEF4444), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val parsed = SettingsModel.fromJson(importJsonText)
                            if (parsed != null) {
                                onSettingsChanged(parsed)
                                Toast.makeText(context, strings.backupImportSuccess, Toast.LENGTH_SHORT).show()
                                showImportDialog = false
                            } else {
                                importError = strings.backupInvalidJson
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))
                    ) {
                        Text("Cargar", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) {
                        Text("Cancelar", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1C2024),
                shape = RoundedCornerShape(12.dp)
            )
        }

        if (showTouchEditor) {
            com.aistudio.xcloudgodmode.ai.ui.components.TouchControllerEditor(
                titleId = "GLOBAL_DEFAULT",
                gameTitle = "Perfil Global de Controles",
                settings = settings,
                onDismiss = { showTouchEditor = false }
            )
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
