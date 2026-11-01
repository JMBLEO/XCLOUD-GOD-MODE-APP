package com.xcloud.godmode.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.xcloud.godmode.constants.NetworkBypassCatalog
import com.xcloud.godmode.models.SettingsModel
import com.xcloud.godmode.ui.theme.AppStrings

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

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp).testTag("advanced_settings_tab")) {
        Text(
            text = strings.settingsTab,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Text(
            text = "God Mode Master Engine Architecture & Customization.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Sub-tabs for organization (Códex Style)
        val tabs = if (settings.appLanguage == "en") {
            listOf("Red" to "Network", "Controles" to "Controls", "Sistema" to "System")
        } else {
            listOf("Red" to "Red", "Controles" to "Controles", "Sistema" to "Sistema")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { (tabKey, tabLabel) ->
                Surface(
                    onClick = { activeTab = tabKey },
                    color = if (activeTab == tabKey) Color(0xFF107C10) else Color(0xFF121820),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (activeTab == tabKey) Color(0xFF00E676) else Color(0xFF2A3B4D)),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(tabLabel, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
            if (activeTab == "Red") {
                item {
                    ExpandableAccordionCard(title = strings.settingsBypass, summary = if (settings.enableServerLock) settings.forceServer else "Auto") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = settings.enableServerLock,
                                    onCheckedChange = { onSettingsChanged(settings.copy(enableServerLock = it)) },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10))
                                )
                                Text("Forzar Servidor Azure", color = Color.White)
                            }
                            if (settings.enableServerLock) {
                                OutlinedTextField(
                                    value = serverSearch,
                                    onValueChange = { serverSearch = it },
                                    placeholder = { Text("Filtrar servidores...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF107C10))
                                )
                                val filtered = NetworkBypassCatalog.CLOUD_SERVERS.filter { it.name.contains(serverSearch, true) || it.id.contains(serverSearch, true) }
                                Box(modifier = Modifier.heightIn(max = 120.dp).fillMaxWidth().background(Color.Black.copy(alpha = 0.3f))) {
                                    LazyColumn {
                                        items(filtered) { s ->
                                            Row(modifier = Modifier.fillMaxWidth().clickable { onSettingsChanged(settings.copy(forceServer = s.id)) }.padding(12.dp)) {
                                                Text("${s.flag} ${s.name}", color = if (settings.forceServer == s.id) Color(0xFF00E676) else Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    ExpandableAccordionCard(title = strings.settingsStreaming, summary = "${settings.targetResProfile} @ ${settings.maxBitrateMbps}Mbps") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Resolución", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("720p", "1080p", "1440p", "4k").forEach { res ->
                                    Surface(
                                        onClick = { onSettingsChanged(settings.copy(targetResProfile = res)) },
                                        color = if (settings.targetResProfile == res) Color(0xFF107C10) else Color(0xFF14171A),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(res, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            Text("Bitrate: ${settings.maxBitrateMbps} Mbps", color = Color.White)
                            Slider(
                                value = settings.maxBitrateMbps.toFloat(),
                                onValueChange = { onSettingsChanged(settings.copy(maxBitrateMbps = it.toInt())) },
                                valueRange = 10f..150f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF00E676))
                            )

                            Text("Codec de Video (God Mode Engine)", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            val codecOptions = listOf("H.264 SVC", "HEVC / H.265", "AV1", "VVC / H.266", "AV2 (Exp)")
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                codecOptions.chunked(3).forEach { rowOptions ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        rowOptions.forEach { codec ->
                                            Surface(
                                                onClick = { onSettingsChanged(settings.copy(videoCodec = codec)) },
                                                color = if (settings.videoCodec == codec) Color(0xFF107C10) else Color(0xFF14171A),
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier.weight(1f).height(32.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(codec, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        if (rowOptions.size < 3) {
                                            Spacer(modifier = Modifier.weight((3 - rowOptions.size).toFloat()))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (activeTab == "Controles") {
                item {
                    ExpandableAccordionCard(title = strings.settingsInput, summary = "${settings.pollingRateMs}ms Polling") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Polling Rate del Mando", color = Color.White)
                            Slider(
                                value = settings.pollingRateMs.toFloat(),
                                onValueChange = { onSettingsChanged(settings.copy(pollingRateMs = it.toInt())) },
                                valueRange = 4f..16f,
                                steps = 3,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF00E676))
                            )
                            Button(
                                onClick = { showTouchEditor = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Personalizar Controles Táctiles")
                            }
                        }
                    }
                }
            } else {
                item {
                    ExpandableAccordionCard(title = "Sistema & QoL", summary = if (settings.antiKick) "Anti-Kick ON" else "Standard") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = settings.antiKick, onCheckedChange = { onSettingsChanged(settings.copy(antiKick = it)) }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10)))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Anti-Kick (Simular Input)", color = Color.White)
                            }
                            Text("Idioma: ${settings.appLanguage}", color = Color.White)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("es" to "Español", "en" to "English").forEach { (code, label) ->
                                    Button(onClick = { onSettingsChanged(settings.copy(appLanguage = code)) }, colors = ButtonDefaults.buttonColors(containerColor = if (settings.appLanguage == code) Color(0xFF107C10) else Color(0xFF14171A)), modifier = Modifier.weight(1f)) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    ExpandableAccordionCard(title = "Respaldo JSON", summary = "Import/Export") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { 
                                val json = settings.toJson()
                                clipboardManager.setText(AnnotatedString(json))
                                Toast.makeText(context, "Exportado al portapapeles", Toast.LENGTH_SHORT).show()
                            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)), modifier = Modifier.weight(1f)) {
                                Text("Exportar")
                            }
                            OutlinedButton(onClick = { importJsonText = ""; showImportDialog = true }, modifier = Modifier.weight(1f)) {
                                Text("Importar")
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }

        // Import Dialog
        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text("Importar Configuración", color = Color.White) },
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
            com.xcloud.godmode.ui.components.TouchControllerEditor(
                titleId = "GLOBAL",
                gameTitle = "Perfil Global",
                settings = settings,
                onDismiss = { showTouchEditor = false }
            )
        }
    }
}

@Composable
fun ExpandableAccordionCard(title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        onClick = { isExpanded = !isExpanded },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF121820),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isExpanded) Color(0xFF00E676) else Color(0xFF2A3B4D)),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = summary, color = Color(0xFF00E676), style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }
        }
    }
}
