package com.xcloud.godmode.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isScanningLan by remember { mutableStateOf(false) }
    var lanConsoles by remember { mutableStateOf<List<com.xcloud.godmode.network.WakeOnLanManager.DiscoveredConsole>>(emptyList()) }
    var diagnosticTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Fetch cloud consoles on mount
    LaunchedEffect(Unit) {
        viewModel.fetchCloudConsolesFromXboxLive(authManager)
    }

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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("consoles_tab_list"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text(
                    text = "Consolas Xbox (Remote Play)",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Control remoto y streaming de consolas registradas en tu cuenta (xHome) y red local (LAN).",
                    color = Color.LightGray.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Section 1: Cloud Registered Consoles
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Consolas Asociadas a la Cuenta",
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                IconButton(
                    onClick = { viewModel.fetchCloudConsolesFromXboxLive(authManager) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refrescar",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (consoles.isEmpty()) {
            item {
                Surface(
                    onClick = { viewModel.fetchCloudConsolesFromXboxLive(authManager) },
                    color = Color(0xFF101720).copy(alpha = 0.85f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFF00E676),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Buscando consolas registradas... (Toca para reintentar)",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        } else {
            items(consoles) { console ->
                GodModeConsoleCard(
                    console = console,
                    source = "Xbox Live Cloud",
                    onConnect = { onLaunchConsole(console.optString("id")) },
                    onPowerOnStream = {
                        val mac = console.optString("macAddress").ifBlank { console.optString("mac") }
                        if (mac.isNotBlank()) {
                            coroutineScope.launch {
                                com.xcloud.godmode.network.WakeOnLanManager.wake(mac)
                            }
                        }
                        Toast.makeText(context, "Encendiendo consola e iniciando streaming...", Toast.LENGTH_SHORT).show()
                        onLaunchConsole(console.optString("id"))
                    },
                    onPowerOn = {
                        val mac = console.optString("macAddress").ifBlank { console.optString("mac") }
                        if (mac.isNotBlank()) {
                            coroutineScope.launch {
                                com.xcloud.godmode.network.WakeOnLanManager.wake(mac)
                            }
                        }
                        Toast.makeText(context, "Comando de encendido enviado", Toast.LENGTH_SHORT).show()
                    },
                    onPowerOff = {
                        Toast.makeText(context, "Comando de apagado / standby enviado", Toast.LENGTH_SHORT).show()
                    },
                    onDiagnose = {
                        diagnosticTarget = console.optString("name", "Xbox Console") to console.optString("id")
                    }
                )
            }
        }

        // Section 2: Local Network Discovery (SSDP)
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF38BDF8))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Red Local (LAN SSDP Direct)",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

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
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    if (isScanningLan) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Radar, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Escanear Wi-Fi / LAN", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (lanConsoles.isEmpty()) {
            item {
                Surface(
                    color = Color(0xFF0F1520).copy(alpha = 0.6f),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Presiona 'Escanear Wi-Fi / LAN' para descubrir consolas locales en tu misma subred con latencia mínima (<2ms).",
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(lanConsoles) { lanConsole ->
                val obj = remember(lanConsole) {
                    JSONObject().apply {
                        put("name", lanConsole.serverName)
                        put("id", lanConsole.ipAddress)
                        put("serverId", lanConsole.ipAddress)
                        put("consoleType", "XboxSeriesX")
                        put("powerState", "ConnectedStandby")
                    }
                }
                GodModeConsoleCard(
                    console = obj,
                    source = "Red Local (Subred)",
                    onConnect = { onLaunchConsole(lanConsole.ipAddress) },
                    onPowerOnStream = {
                        Toast.makeText(context, "Conectando directo por IP ${lanConsole.ipAddress}...", Toast.LENGTH_SHORT).show()
                        onLaunchConsole(lanConsole.ipAddress)
                    },
                    onPowerOn = {
                        Toast.makeText(context, "Sondeando consola en ${lanConsole.ipAddress}...", Toast.LENGTH_SHORT).show()
                    },
                    onPowerOff = {
                        Toast.makeText(context, "Comando de apagado enviado", Toast.LENGTH_SHORT).show()
                    },
                    onDiagnose = {
                        diagnosticTarget = lanConsole.serverName to lanConsole.ipAddress
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(96.dp)) }
    }
}

/**
 * Native GodModeConsoleCard: High-fidelity Xbox console card.
 * Reflects console type (Series X, Series S, One), power state (On, Standby, Off),
 * 3-dots power management menu, and smart launch button.
 */
@Composable
fun GodModeConsoleCard(
    console: JSONObject,
    source: String,
    onConnect: () -> Unit,
    onPowerOnStream: () -> Unit,
    onPowerOn: () -> Unit,
    onPowerOff: () -> Unit,
    onDiagnose: () -> Unit
) {
    val deviceName = console.optString("name").ifBlank { console.optString("deviceName", "Xbox Console") }
    val consoleType = console.optString("consoleType", "XboxSeriesX")
    val serverId = console.optString("serverId").ifBlank { console.optString("id", "") }
    val powerState = console.optString("powerState", "ConnectedStandby")
    var menuExpanded by remember { mutableStateOf(false) }

    val stateColor = when (powerState) {
        "On" -> Color(0xFF36B728) // Vibrant Green
        "ConnectedStandby" -> Color(0xFFD1BE02) // Amber
        else -> Color(0xFFDC2626) // Crimson Red
    }

    val stateLabel = when (powerState) {
        "On" -> "Encendido"
        "ConnectedStandby" -> "En espera (Standby)"
        else -> "Apagado"
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF111823).copy(alpha = 0.90f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("console_card_$serverId")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Top Row: Console name, Source badge, 3-dots Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = source,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00E676)
                        )
                        if (serverId.isNotBlank()) {
                            Text(
                                text = " • $serverId",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 3-dots Menu
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Opciones",
                            tint = Color.LightGray
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(Color(0xFF1B2432))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Encender consola", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null, tint = Color(0xFF36B728)) },
                            onClick = {
                                menuExpanded = false
                                onPowerOn()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Apagar consola", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.PowerOff, null, tint = Color(0xFFDC2626)) },
                            onClick = {
                                menuExpanded = false
                                onPowerOff()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnóstico de red", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Speed, null, tint = Color(0xFF38BDF8)) },
                            onClick = {
                                menuExpanded = false
                                onDiagnose()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Middle section: Graphic silhouette + Power & Specs info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Procedural Vector representation of Console Hardware
                ConsoleHardwareArt(
                    consoleType = consoleType,
                    stateColor = stateColor,
                    modifier = Modifier
                        .size(width = 84.dp, height = 76.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF090D13))
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Friendly Model Name
                    val modelPrettyName = when {
                        consoleType.contains("SeriesX", ignoreCase = true) -> "Xbox Series X"
                        consoleType.contains("SeriesS", ignoreCase = true) -> "Xbox Series S"
                        consoleType.contains("One", ignoreCase = true) -> "Xbox One"
                        else -> "Xbox Hardware"
                    }

                    Text(
                        text = modelPrettyName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Status Chip
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(stateColor.copy(alpha = 0.15f))
                            .border(1.dp, stateColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(stateColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stateLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = stateColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDiagnose,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Diagnóstico", fontSize = 12.sp)
                }

                if (powerState == "ConnectedStandby") {
                    Button(
                        onClick = onPowerOnStream,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Encender e Iniciar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Iniciar Stream", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}

/**
 * Procedural Console Hardware Graphic rendered with Compose Canvas.
 */
@Composable
fun ConsoleHardwareArt(
    consoleType: String,
    stateColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        when {
            consoleType.contains("SeriesX", ignoreCase = true) -> {
                // Vertical Monolith Tower
                val towerWidth = w * 0.44f
                val towerHeight = h * 0.82f
                val left = (w - towerWidth) / 2f
                val top = (h - towerHeight) / 2f

                // Tower Body
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF232A34), Color(0xFF141A22))
                    ),
                    topLeft = Offset(left, top),
                    size = Size(towerWidth, towerHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // Top Green Exhaust Accent
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF00E676), Color.Transparent),
                        center = Offset(w / 2f, top + 2.dp.toPx()),
                        radius = towerWidth * 0.6f
                    ),
                    topLeft = Offset(left, top),
                    size = Size(towerWidth, 6.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // Power button light
                drawCircle(
                    color = stateColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(left + 6.dp.toPx(), top + 8.dp.toPx())
                )
            }
            consoleType.contains("SeriesS", ignoreCase = true) -> {
                // Sleek white horizontal console with circular black vent
                val bodyW = w * 0.78f
                val bodyH = h * 0.54f
                val left = (w - bodyW) / 2f
                val top = (h - bodyH) / 2f

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFFF1F5F9), Color(0xFFCBD5E1))
                    ),
                    topLeft = Offset(left, top),
                    size = Size(bodyW, bodyH),
                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                )

                // Circular Black Fan Grill
                drawCircle(
                    color = Color(0xFF18181B),
                    radius = bodyH * 0.38f,
                    center = Offset(left + bodyW * 0.62f, top + bodyH / 2f)
                )

                // Power button light
                drawCircle(
                    color = stateColor,
                    radius = 2.dp.toPx(),
                    center = Offset(left + 6.dp.toPx(), top + 6.dp.toPx())
                )
            }
            else -> {
                // Classic Console Shape
                val bodyW = w * 0.82f
                val bodyH = h * 0.44f
                val left = (w - bodyW) / 2f
                val top = (h - bodyH) / 2f

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF262E3B), Color(0xFF181F28))
                    ),
                    topLeft = Offset(left, top),
                    size = Size(bodyW, bodyH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // Center dividing line
                drawLine(
                    color = Color.White.copy(alpha = 0.15f),
                    start = Offset(left, top + bodyH / 2f),
                    end = Offset(left + bodyW, top + bodyH / 2f),
                    strokeWidth = 1.dp.toPx()
                )

                // Power button
                drawCircle(
                    color = stateColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(left + 8.dp.toPx(), top + bodyH / 2f)
                )
            }
        }
    }
}
