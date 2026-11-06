package com.xcloud.godmode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcloud.godmode.config.*
import com.xcloud.godmode.launch.EngineId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GodModeSettingsScreen(
    configRepository: AppConfigurationRepository,
    onConfigSavedAndInjected: (GodModeGlobalConfig) -> Unit = {}
) {
    val persistedConfig by configRepository.configFlow.collectAsState(initial = GodModeGlobalConfig())

    // Estado borrador editable en la UI
    var draftConfig by remember(persistedConfig) { mutableStateOf(persistedConfig) }
    var selectedTab by remember { mutableStateOf(0) }
    var showSavedBanner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hasUnsavedChanges = draftConfig != persistedConfig

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Ajustes",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "God Mode Master Engine Architecture & Customization.",
                        color = Color(0xFF8A949E),
                        fontSize = 13.sp
                    )
                }

                // Botón de guardado rápido en la cabecera
                Button(
                    onClick = {
                        scope.launch {
                            configRepository.saveConfig(draftConfig)
                            onConfigSavedAndInjected(draftConfig)
                            // Solution 11: Pre-warm shaders
                            com.xcloud.godmode.streaming.VulkanFsrShaderPrewarmer.prewarm(configRepository.context)
                            showSavedBanner = true
                            delay(2500)
                            showSavedBanner = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasUnsavedChanges) Color(0xFF107C10) else Color(0xFF1F2933)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (hasUnsavedChanges) "💾 Guardar e Inyectar (*)" else "✓ Inyectado en Global",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pestañas: Red | Controles | Sistema | SLA
            val tabs = listOf("Red", "Controles", "Sistema", "SLA")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .background(
                                if (isSelected) Color(0xFF107C10) else Color(0xFF14181D),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF2ECC71) else Color(0xFF262C34),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedTab = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            when (selectedTab) {
                0 -> NetworkAndStreamingTab(
                    draft = draftConfig,
                    onDraftChange = { updated ->
                        draftConfig = updated
                    }
                )
                1 -> ControlsSettingsTab(
                    draft = draftConfig,
                    onDraftChange = { updated ->
                        draftConfig = updated
                    }
                )
                2 -> EngineSelectionTab(
                    draft = draftConfig,
                    onDraftChange = { updated ->
                        draftConfig = updated
                    }
                )
                3 -> SlaInfoTab(draft = draftConfig)
            }
        }

        // Barra flotante de confirmación y guardado explícito
        if (showSavedBanner || hasUnsavedChanges) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 72.dp),
                color = Color(0xFF122316),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showSavedBanner) {
                            "✓ Configuración guardada e inyectada en Login, Catálogo, SDP y los 6 Motores."
                        } else {
                            "Cambios pendientes de inyección global"
                        },
                        color = Color(0xFF4ADE80),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                configRepository.saveConfig(draftConfig)
                                onConfigSavedAndInjected(draftConfig)
                                // Solution 11: Pre-warm shaders
                                com.xcloud.godmode.streaming.VulkanFsrShaderPrewarmer.prewarm(configRepository.context)
                                showSavedBanner = true
                                delay(2500)
                                showSavedBanner = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))
                    ) {
                        Text("GUARDAR AHORA", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkAndStreamingTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Bloque 1: Forzar Servidor Azure
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Forzar Servidor Azure", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Checkbox(
                        checked = draft.forceAzureServer,
                        onCheckedChange = { onDraftChange(draft.copy(forceAzureServer = it)) },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10))
                    )
                }

                OutlinedTextField(
                    value = draft.forcedRegionCode,
                    onValueChange = { onDraftChange(draft.copy(forcedRegionCode = it.uppercase())) },
                    label = { Text("Código de Región (CHI, BR, ARG, US)", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF107C10),
                        unfocusedBorderColor = Color(0xFF2A323D)
                    )
                )

                Text(
                    text = "Activo: ${draft.selectedAzureGateway} | Ruta: ${draft.bypassAndNetworkRoute}",
                    color = Color(0xFF4ADE80),
                    fontSize = 12.sp
                )
            }
        }

        // Bloque 2: Resolución, Bitrate y Codec
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Streaming y Rendimiento", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${draft.resolution.label} @ ${draft.maxBitrateMbps}Mbps (${draft.preferredCodec.label})",
                        color = Color(0xFF4ADE80),
                        fontWeight = FontWeight.Bold
                    )
                }

                Text("Resolución", color = Color(0xFF8A949E), fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoResolution.entries.forEach { res ->
                        val selected = draft.resolution == res
                        Button(
                            onClick = { onDraftChange(draft.copy(resolution = res)) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF107C10) else Color(0xFF1A2027)
                            ),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(res.label, color = Color.White)
                        }
                    }
                }

                Text("Bitrate: ${draft.maxBitrateMbps} Mbps", color = Color.White, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = draft.maxBitrateMbps.toFloat(),
                    onValueChange = { onDraftChange(draft.copy(maxBitrateMbps = it.toInt())) },
                    valueRange = 10f..150f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF2ECC71),
                        activeTrackColor = Color(0xFF107C10)
                    )
                )

                Text("Codec de Video (God Mode Engine)", color = Color(0xFF8A949E), fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoCodecProfile.entries.take(3).forEach { codec ->
                        val selected = draft.preferredCodec == codec
                        Button(
                            onClick = { onDraftChange(draft.copy(preferredCodec = codec)) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF107C10) else Color(0xFF1A2027)
                            ),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(codec.label, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlsSettingsTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mostrar Controles Táctiles al iniciar el Stream", color = Color.White)
                Switch(
                    checked = draft.enableTouchControlsByDefault,
                    onCheckedChange = { onDraftChange(draft.copy(enableTouchControlsByDefault = it)) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mostrar HUD de Telemetría (FPS / Mbps) por defecto", color = Color.White)
                Switch(
                    checked = draft.enableTelemetryHudByDefault,
                    onCheckedChange = { onDraftChange(draft.copy(enableTelemetryHudByDefault = it)) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = Color(0xFF2A323D))
            Spacer(modifier = Modifier.height(8.dp))

            Text("Sensibilidad Táctil (Simulación de Sticks)", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = draft.touchSensitivity,
                    onValueChange = { onDraftChange(draft.copy(touchSensitivity = it)) },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF2ECC71), activeTrackColor = Color(0xFF107C10))
                )
                Text(text = String.format("%.2fx", draft.touchSensitivity), color = Color(0xFF4ADE80), modifier = Modifier.width(48.dp))
            }

            Text("Zona Muerta Táctil (Deadzone)", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = draft.touchDeadzone,
                    onValueChange = { onDraftChange(draft.copy(touchDeadzone = it)) },
                    valueRange = 0.0f..0.5f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF2ECC71), activeTrackColor = Color(0xFF107C10))
                )
                Text(text = String.format("%.2f", draft.touchDeadzone), color = Color(0xFF4ADE80), modifier = Modifier.width(48.dp))
            }
        }
    }
}

@Composable
private fun EngineSelectionTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Motor Nativo Activo para el Player:", color = Color.White, fontWeight = FontWeight.Bold)
            EngineId.entries.forEach { engine ->
                val isSelected = draft.preferredEngine == engine
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) Color(0xFF14331B) else Color(0xFF181D24),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onDraftChange(draft.copy(preferredEngine = engine)) }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(engine.name, color = Color.White, fontSize = 13.sp)
                    RadioButton(
                        selected = isSelected,
                        onClick = { onDraftChange(draft.copy(preferredEngine = engine)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SlaInfoTab(draft: GodModeGlobalConfig) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Estado de Inyección Global", color = Color.White, fontWeight = FontWeight.Bold)
            Text("• Región Inyectada: ${draft.forcedRegionCode} (${draft.selectedAzureGateway})", color = Color(0xFF4ADE80))
            Text("• Perfil SDP/ICE: ${draft.resolution.width}x${draft.resolution.height} @ ${draft.maxBitrateMbps} Mbps", color = Color(0xFF4ADE80))
            Text("• Codec Preferido: ${draft.preferredCodec.label}", color = Color(0xFF4ADE80))
            Text("• Motor Asignado: ${draft.preferredEngine.name}", color = Color(0xFF4ADE80))
        }
    }
}
