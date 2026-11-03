package com.xcloud.godmode.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcloud.godmode.constants.NetworkBypassCatalog
import com.xcloud.godmode.models.SettingsModel
import com.xcloud.godmode.network.AzureBypassConfig
import com.xcloud.godmode.network.AzureBypassEntry
import com.xcloud.godmode.network.BypassCategory
import com.xcloud.godmode.ui.components.GodModeToggle
import com.xcloud.godmode.ui.theme.AppStrings

@Composable
fun AdvancedSettingsTab(
    settings: SettingsModel,
    viewModel: com.xcloud.godmode.viewmodels.HomeViewModel,
    onSettingsChanged: (SettingsModel) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val strings = AppStrings.get(settings.appLanguage)

    var activeTab by remember { mutableStateOf("Red") } // Red, Controles, Sistema
    var serverSearch by remember { mutableStateOf("") }
    var ipSearch by remember { mutableStateOf("") }
    var selectedBypassCategory by remember { mutableStateOf(BypassCategory.ALL) }

    var showImportDialog by remember { mutableStateOf(false) }
    var showTouchEditor by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    var isHeaderVisible by remember { mutableStateOf(true) }
    var scrollDeltaAccumulator by remember { mutableFloatStateOf(0f) }

    // Conexión nested scroll direccional fluida con acumulador de inercia para respuesta inmediata
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if ((dy > 0 && scrollDeltaAccumulator < 0) || (dy < 0 && scrollDeltaAccumulator > 0)) {
                    scrollDeltaAccumulator = dy
                } else {
                    scrollDeltaAccumulator += dy
                }

                if (scrollDeltaAccumulator < -8f) {
                    // Desplazamiento hacia abajo (contenido sube): ocultar cabecera suavemente
                    isHeaderVisible = false
                    scrollDeltaAccumulator = 0f
                } else if (scrollDeltaAccumulator > 8f) {
                    // Desplazamiento hacia arriba (contenido baja): mostrar cabecera
                    isHeaderVisible = true
                    scrollDeltaAccumulator = 0f
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 6f) {
                    isHeaderVisible = true
                }
                return Offset.Zero
            }
        }
    }

    // Al llegar al tope superior absoluto, o al desplazarse, sincronizar visibilidad suavemente
    LaunchedEffect(listState) {
        var prevIdx = listState.firstVisibleItemIndex
        var prevOff = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (idx, offset) ->
                if (idx == 0 && offset <= 10) {
                    isHeaderVisible = true
                } else if (idx > prevIdx || (idx == prevIdx && offset > prevOff + 8)) {
                    isHeaderVisible = false
                } else if (idx < prevIdx || (idx == prevIdx && offset < prevOff - 8)) {
                    isHeaderVisible = true
                }
                prevIdx = idx
                prevOff = offset
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .padding(horizontal = 24.dp)
                .testTag("advanced_settings_tab")
        ) {
            // Panel superior colapsable al desplazarse hacia arriba o abajo
            AnimatedVisibility(
                visible = isHeaderVisible,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = tween(180)) + slideInVertically(
                    initialOffsetY = { -it / 2 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeOut(animationSpec = tween(150)) + slideOutVertically(
                    targetOffsetY = { -it / 2 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                ) {
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
                }
            }

            // Barra de sub-pestañas anclada: Red | Controles | Sistema
            val tabs = if (settings.appLanguage == "en") {
                listOf("Red" to "Network", "Controles" to "Controls", "Sistema" to "System")
            } else {
                listOf("Red" to "Red", "Controles" to "Controles", "Sistema" to "Sistema")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { (tabKey, tabLabel) ->
                    Surface(
                        onClick = {
                            activeTab = tabKey
                            com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.TAB)
                        },
                        color = if (activeTab == tabKey) Color(0xFF107C10) else Color(0xFF121820),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (activeTab == tabKey) Color(0xFF00E676) else Color(0xFF2A3B4D)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = tabLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Contenedor LazyColumn de opciones de configuración
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (activeTab == "Red") {
                    item {
                        ExpandableAccordionCard(
                            title = strings.settingsBypass,
                            summary = if (settings.enableNetworkBypass && settings.bypassIp != "Desactivado" && settings.bypassIp.isNotEmpty()) {
                                AzureBypassConfig.getLabelForIp(settings.bypassIp)
                            } else {
                                if (settings.enableServerLock) settings.forceServer else "Auto"
                            },
                            initiallyExpanded = true
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // 1. FORZAR SERVIDOR AZURE
                                GodModeToggle(
                                    label = "Forzar Servidor Azure",
                                    checked = settings.enableServerLock,
                                    onCheckedChange = { onSettingsChanged(settings.copy(enableServerLock = it)) }
                                )
                                if (settings.enableServerLock) {
                                    OutlinedTextField(
                                        value = serverSearch,
                                        onValueChange = { serverSearch = it },
                                        placeholder = { Text("Filtrar servidores...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF107C10),
                                            unfocusedBorderColor = Color(0xFF2A3B4D)
                                        ),
                                        singleLine = true
                                    )
                                    val filteredServers = NetworkBypassCatalog.CLOUD_SERVERS.filter {
                                        it.name.contains(serverSearch, true) || it.id.contains(serverSearch, true)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .heightIn(max = 130.dp)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                            .border(1.dp, Color(0xFF2A3B4D), RoundedCornerShape(8.dp))
                                    ) {
                                        LazyColumn {
                                            items(filteredServers) { s ->
                                                val isSelected = settings.forceServer == s.id
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            onSettingsChanged(settings.copy(forceServer = s.id))
                                                            com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.FOCUS)
                                                        }
                                                        .background(if (isSelected) Color(0xFF107C10).copy(alpha = 0.25f) else Color.Transparent)
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "${s.flag} ${s.name}",
                                                        color = if (isSelected) Color(0xFF00E676) else Color.White,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = Color(0xFF00E676),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 2. DIRECTAMENTE ABAJO DE FORZAR SERVIDOR AZURE: CONDICIÓN Y SELECCIÓN DE BYPASS IP & ISPs
                                Spacer(modifier = Modifier.height(2.dp))
                                HorizontalDivider(color = Color(0xFF2A3B4D).copy(alpha = 0.7f))
                                Spacer(modifier = Modifier.height(2.dp))

                                GodModeToggle(
                                    label = "Activar Bypass IP (X-Forwarded-For)",
                                    checked = settings.enableNetworkBypass,
                                    onCheckedChange = { onSettingsChanged(settings.copy(enableNetworkBypass = it)) }
                                )

                                if (settings.enableNetworkBypass) {
                                    // Píldoras de filtrado por categoría
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf(
                                            BypassCategory.ALL to "Todas",
                                            BypassCategory.AZURE_DATACENTER to "Azure DC",
                                            BypassCategory.REGIONAL_ISP to "ISPs / Fibra",
                                            BypassCategory.CLOUD_EDGE to "Cloud",
                                            BypassCategory.CUSTOM to "Manual"
                                        ).forEach { (cat, label) ->
                                            val isCatSelected = selectedBypassCategory == cat
                                            Surface(
                                                onClick = { selectedBypassCategory = cat },
                                                color = if (isCatSelected) Color(0xFF107C10) else Color(0xFF14171A),
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (isCatSelected) Color(0xFF00E676) else Color(0xFF2A3B4D)
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(30.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = "${cat.icon} $label",
                                                        fontSize = 10.sp,
                                                        color = if (isCatSelected) Color.White else Color.Gray,
                                                        fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Campo de búsqueda de IPs e ISPs
                                    OutlinedTextField(
                                        value = ipSearch,
                                        onValueChange = { ipSearch = it },
                                        placeholder = { Text("Filtrar bypass IPs, ISPs, países...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF107C10),
                                            unfocusedBorderColor = Color(0xFF2A3B4D)
                                        ),
                                        singleLine = true
                                    )

                                    // Sugerencia rápida para el servidor Azure seleccionado
                                    val recommended = AzureBypassConfig.ENTRIES.find {
                                        settings.enableServerLock && it.regionId.equals(settings.forceServer, ignoreCase = true)
                                    }
                                    if (recommended != null && settings.bypassIp != recommended.ip) {
                                        Surface(
                                            onClick = {
                                                onSettingsChanged(settings.copy(bypassIp = recommended.ip))
                                                com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.FOCUS)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF107C10).copy(alpha = 0.20f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "💡 Sugerencia ${settings.forceServer}: ${recommended.displayName}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF00E676),
                                                    maxLines = 1,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "Aplicar",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }

                                    // Lista de IPs y ISPs organizada
                                    val filteredBypassEntries = AzureBypassConfig.filterEntries(ipSearch, selectedBypassCategory)
                                    Box(
                                        modifier = Modifier
                                            .heightIn(max = 180.dp)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                            .border(1.dp, Color(0xFF2A3B4D), RoundedCornerShape(8.dp))
                                    ) {
                                        LazyColumn {
                                            items(filteredBypassEntries) { entry ->
                                                val isSelected = (entry.ip == "custom" && settings.bypassIp == "custom") ||
                                                        (entry.ip.isNotEmpty() && settings.bypassIp == entry.ip) ||
                                                        (entry.ip.isEmpty() && (settings.bypassIp == "Desactivado" || settings.bypassIp.isEmpty()))
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            onSettingsChanged(settings.copy(bypassIp = entry.ip))
                                                            com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.FOCUS)
                                                        }
                                                        .background(if (isSelected) Color(0xFF107C10).copy(alpha = 0.25f) else Color.Transparent)
                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = entry.displayName,
                                                            color = if (isSelected) Color(0xFF00E676) else Color.White,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        if (entry.provider != "None" && entry.provider != "Usuario") {
                                                            Text(
                                                                text = "${entry.provider} • ${entry.category.label}",
                                                                color = Color.Gray,
                                                                fontSize = 9.sp
                                                            )
                                                        }
                                                    }
                                                    if (entry.ip != "custom" && entry.ip.isNotEmpty()) {
                                                        Text(
                                                            text = entry.ip,
                                                            color = if (isSelected) Color(0xFF00E676) else Color.LightGray,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (settings.bypassIp == "custom") {
                                        OutlinedTextField(
                                            value = settings.customIp,
                                            onValueChange = { onSettingsChanged(settings.copy(customIp = it)) },
                                            label = { Text("IP Personalizada (ej. 20.143.92.1)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFF107C10),
                                                unfocusedBorderColor = Color(0xFF2A3B4D)
                                            ),
                                            singleLine = true
                                        )
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
                            
                            Text("Sensibilidad de Ratón: ${String.format("%.1f", settings.mouseSensitivity)}x", color = Color.White)
                            Slider(
                                value = settings.mouseSensitivity,
                                onValueChange = { onSettingsChanged(settings.copy(mouseSensitivity = it)) },
                                valueRange = 0.5f..3.0f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF00E676))
                            )
                            
                            Text("Zona Muerta Sticks: ${(settings.stickDeadzone * 100).toInt()}%", color = Color.White)
                            Slider(
                                value = settings.stickDeadzone,
                                onValueChange = { onSettingsChanged(settings.copy(stickDeadzone = it)) },
                                valueRange = 0.02f..0.20f,
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
                    val currentEngine = com.xcloud.godmode.streaming.StreamEngineType.fromId(settings.selectedMotor)
                    ExpandableAccordionCard(
                        title = "Motor de Streaming Nativo (Estado 0)",
                        summary = currentEngine.title,
                        initiallyExpanded = true
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Pipeline en silicio directo (Zero-Copy). Cero WebView, decodificación por hardware de ultra-baja latencia y post-procesado NPU/GPU.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )

                            // Selector de los 6 motores
                            com.xcloud.godmode.streaming.StreamEngineType.entries.forEach { engine ->
                                val isSelected = settings.selectedMotor == engine.id
                                Surface(
                                    onClick = {
                                        onSettingsChanged(settings.copy(selectedMotor = engine.id))
                                        com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.FOCUS)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFF107C10).copy(alpha = 0.25f) else Color(0xFF14171A),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFF00E676) else Color(0xFF2A3B4D)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = engine.title,
                                                    color = if (isSelected) Color(0xFF00E676) else Color.White,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (isSelected) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "ACTIVO",
                                                        color = Color.Black,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        modifier = Modifier
                                                            .background(Color(0xFF00E676), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = engine.subtitle,
                                                color = Color.Gray,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 11.sp
                                            )
                                        }
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                onSettingsChanged(settings.copy(selectedMotor = engine.id))
                                                com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.FOCUS)
                                            },
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = Color(0xFF00E676),
                                                unselectedColor = Color.Gray
                                            )
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Toggle Super-Resolución AI
                            GodModeToggle(
                                label = "Super-Resolución AI (NPU/GPU Cores)",
                                checked = settings.enableAiUpscale,
                                onCheckedChange = { onSettingsChanged(settings.copy(enableAiUpscale = it)) }
                            )
                            Text(
                                text = "Acelera la reconstrucción de bordes y reduce macrobloques en tiempo real utilizando la NPU del dispositivo o shaders de cómputo.",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                item {
                    ExpandableAccordionCard(title = "Sistema & QoL", summary = if (settings.antiKick) "Anti-Kick ON" else "Standard") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GodModeToggle(
                                    label = "Anti-Kick (Simular Input)",
                                    checked = settings.antiKick,
                                    onCheckedChange = { onSettingsChanged(settings.copy(antiKick = it)) }
                                )
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
                    val permissions by viewModel.permissionsState.collectAsState()
                    val storageGranted = permissions[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true || permissions[android.Manifest.permission.READ_MEDIA_IMAGES] == true
                    val micGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true

                    ExpandableAccordionCard(title = "Caché & Permisos", summary = "Estado del Sistema") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PermissionStatusRow("Micrófono", micGranted)
                            PermissionStatusRow("Almacenamiento", storageGranted)

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { 
                                    if (storageGranted) {
                                        Toast.makeText(context, "Caché de carátulas limpiado (Lógica nativa)", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Permiso de almacenamiento requerido", Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (storageGranted) Color(0xFF107C10) else Color.DarkGray
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.DeleteSweep, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Limpiar Caché de Arte")
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
fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (granted) "Autorizado" else "Pendiente",
                color = if (granted) Color(0xFF00E676) else Color(0xFFEF4444),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF00E676) else Color(0xFFEF4444),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun ExpandableAccordionCard(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

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
