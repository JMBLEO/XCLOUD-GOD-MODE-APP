package com.xcloud.godmode.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcloud.godmode.engine.EngineId
import com.xcloud.godmode.models.*
import com.xcloud.godmode.network.DatacenterRegistry
import com.xcloud.godmode.services.AppConfigurationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 8-Tab Modular GodMode Settings Screen:
 * 0: Audio | 1: Video | 2: Red | 3: Mando | 4: Giroscopio | 5: Háptica | 6: Consola | 7: Avanzado
 */
@Composable
fun GodModeSettingsScreen(
    configRepository: AppConfigurationRepository,
    onConfigSavedAndInjected: (GodModeGlobalConfig) -> Unit = {}
) {
    val persistedConfig by configRepository.configFlow.collectAsState(initial = GodModeGlobalConfig())

    var draftConfig by remember(persistedConfig) { mutableStateOf(persistedConfig) }
    var selectedTab by remember { mutableStateOf(0) }
    var showSavedBanner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hasUnsavedChanges = draftConfig != persistedConfig

    val tabs = listOf(
        "Audio",
        "Video",
        "Red",
        "Mando",
        "Giroscopio",
        "Háptica",
        "Consola",
        "Avanzado"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp)
        ) {
            // Header
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
                        text = "God Mode Master Engine - 8 Módulos de Rendimiento",
                        color = Color(0xFF8A949E),
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            configRepository.saveConfig(draftConfig)
                            onConfigSavedAndInjected(draftConfig)
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
                        text = if (hasUnsavedChanges) "💾 Guardar (*)" else "✓ Inyectado",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Horizontally Scrollable Tab Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Surface(
                        onClick = { selectedTab = index },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF107C10) else Color(0xFF14181D),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF2ECC71) else Color(0xFF262C34)
                        )
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Tab Content
            when (selectedTab) {
                0 -> AudioTab(draft = draftConfig, onDraftChange = { draftConfig = it })
                1 -> VideoTab(draft = draftConfig, onDraftChange = { draftConfig = it })
                2 -> NetworkTab(draft = draftConfig, onDraftChange = { draftConfig = it })
                3 -> GamepadTab(draft = draftConfig, onDraftChange = { draftConfig = it })
                4 -> GyroscopeTab(draft = draftConfig, onDraftChange = { draftConfig = it })
                5 -> HapticsTab(draft = draftConfig, onDraftChange = { draftConfig = it })
                6 -> ConsoleTab(draft = draftConfig, onDraftChange = { draftConfig = it })
                7 -> AdvancedTab(draft = draftConfig, onDraftChange = { draftConfig = it })
            }
        }

        // Floating Save Confirmation Banner
        if (showSavedBanner || hasUnsavedChanges) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 68.dp),
                color = Color(0xFF122316),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showSavedBanner) {
                            "✓ Ajustes guardados e inyectados."
                        } else {
                            "Hay cambios pendientes de guardar."
                        },
                        color = Color(0xFF4ADE80),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                configRepository.saveConfig(draftConfig)
                                onConfigSavedAndInjected(draftConfig)
                                com.xcloud.godmode.streaming.VulkanFsrShaderPrewarmer.prewarm(configRepository.context)
                                showSavedBanner = true
                                delay(2500)
                                showSavedBanner = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))
                    ) {
                        Text("GUARDAR AHORA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/* ==========================================================================================
   TAB 0: AUDIO
   ========================================================================================== */
@Composable
private fun AudioTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Pipeline de Audio de Baja Latencia", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "Buffer PCM 16-bit 48kHz con PERFORMANCE_MODE_LOW_LATENCY (<10ms).",
                color = Color.LightGray,
                fontSize = 12.sp
            )

            HorizontalDivider(color = Color(0xFF263238))

            // Volume Boost
            Text("Amplificación Digital de Volumen: ${(draft.audioGainBoost * 100).toInt()}%", color = Color.White)
            Slider(
                value = draft.audioGainBoost,
                onValueChange = { onDraftChange(draft.copy(audioGainBoost = it)) },
                valueRange = 1.0f..2.0f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF2ECC71), activeTrackColor = Color(0xFF107C10))
            )

            // Ducking
            Text("Atenuación Automática de Juego en Chat (Audio Ducking): ${draft.audioDuckingDb.toInt()} dB", color = Color.White)
            Slider(
                value = draft.audioDuckingDb,
                onValueChange = { onDraftChange(draft.copy(audioDuckingDb = it)) },
                valueRange = -18f..-3f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF2ECC71), activeTrackColor = Color(0xFF107C10))
            )

            // Bass Rumble
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Vibración Acústica por Graves (Audio Rumble)", color = Color.White)
                    Text("Dispara vibración por análisis RMS de explosiones", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = draft.enableAudioRumble,
                    onCheckedChange = { onDraftChange(draft.copy(enableAudioRumble = it)) }
                )
            }
        }
    }
}

/* ==========================================================================================
   TAB 1: VIDEO & SHADERS
   ========================================================================================== */
@Composable
private fun VideoTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Pipeline de Video & FSR / LSFG", color = Color.White, fontWeight = FontWeight.Bold)

            // Resolution
            Text("Resolución de Stream:", color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoResolution.entries.forEach { res ->
                    val isSelected = draft.resolution == res
                    Surface(
                        onClick = { onDraftChange(draft.copy(resolution = res)) },
                        color = if (isSelected) Color(0xFF107C10) else Color(0xFF1A222C),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(res.label, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
                    }
                }
            }

            // Codec (NO video/avc)
            Text("Códec Preferido (Cero H.264):", color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(VideoCodecProfile.HEVC_H265, VideoCodecProfile.AV1, VideoCodecProfile.VVC_H266).forEach { codec ->
                    val isSelected = draft.preferredCodec == codec
                    Surface(
                        onClick = { onDraftChange(draft.copy(preferredCodec = codec)) },
                        color = if (isSelected) Color(0xFF107C10) else Color(0xFF1A222C),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(codec.label, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
                    }
                }
            }

            // Tizen Spoofing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Identidad Samsung Tizen SmartTV", color = Color.White)
                    Text("Desbloquea perfil de stream HQ (15-20+ Mbps / 1440p)", color = Color(0xFF4ADE80), fontSize = 11.sp)
                }
                Switch(
                    checked = draft.enableTizenSpoofing,
                    onCheckedChange = { onDraftChange(draft.copy(enableTizenSpoofing = it)) }
                )
            }

            HorizontalDivider(color = Color(0xFF263238))

            // FSR 1.0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reescalado FSR 1.0 (EASU + RCAS)", color = Color.White)
                Switch(
                    checked = draft.enableFsr,
                    onCheckedChange = { onDraftChange(draft.copy(enableFsr = it)) }
                )
            }

            // LSFG
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Generación de Fotogramas LSFG (60 -> 120 FPS)", color = Color.White)
                    Text("Versión: ${draft.lsfgVersion} | Multiplicador: ${draft.lsfgMultiplier}", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = draft.enableLsfg,
                    onCheckedChange = { onDraftChange(draft.copy(enableLsfg = it)) }
                )
            }
        }
    }
}

/* ==========================================================================================
   TAB 2: RED & BYPASS
   ========================================================================================== */
@Composable
private fun NetworkTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Red y Conmutación de Datacenters Azure", color = Color.White, fontWeight = FontWeight.Bold)

            // Force server toggle & region
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Forzar Servidor Azure Específico", color = Color.White)
                Switch(
                    checked = draft.forceAzureServer,
                    onCheckedChange = { onDraftChange(draft.copy(forceAzureServer = it)) }
                )
            }

            OutlinedTextField(
                value = draft.forcedRegionCode,
                onValueChange = { onDraftChange(draft.copy(forcedRegionCode = it.uppercase())) },
                label = { Text("Código de Región (CHI, BR, EUS, WUS)", color = Color.Gray) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Session handover
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Traspaso en Caliente Wi-Fi <-> 5G (Session Re-Anchor)", color = Color.White)
                    Text("Evita desconexiones sin reiniciar MediaCodec ni Surface", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = draft.enableSessionHandover,
                    onCheckedChange = { onDraftChange(draft.copy(enableSessionHandover = it)) }
                )
            }

            HorizontalDivider(color = Color(0xFF263238))

            Text("Datacenters Azure Disponibles (19 Regiones):", color = Color.White, fontSize = 13.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DatacenterRegistry.ALL_REGIONS.take(5).forEach { dc ->
                    Surface(
                        onClick = {
                            onDraftChange(draft.copy(forcedRegionCode = dc.regionCode, selectedAzureGateway = dc.name))
                        },
                        color = Color(0xFF181F28),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${dc.flagEmoji} ${dc.name} (${dc.regionCode})", color = Color.White, fontSize = 12.sp)
                            Text("RTT Óptimo", color = Color(0xFF4ADE80), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/* ==========================================================================================
   TAB 3: MANDO & ENTRADA
   ========================================================================================== */
@Composable
private fun GamepadTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Controlador de Entrada y Sondeo", color = Color.White, fontWeight = FontWeight.Bold)

            // Polling Rate
            Text("Tasa de Sondeo (Polling Rate): ${draft.gamepadPollingRateHz} Hz (${1000 / draft.gamepadPollingRateHz} ms)", color = Color.White)
            Slider(
                value = draft.gamepadPollingRateHz.toFloat(),
                onValueChange = { onDraftChange(draft.copy(gamepadPollingRateHz = it.toInt())) },
                valueRange = 60f..250f,
                steps = 2,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF2ECC71), activeTrackColor = Color(0xFF107C10))
            )

            // Auto-sprint L3
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Auto-Sprint en L3", color = Color.White)
                    Text("Dispara L3 al empujar stick izquierdo al ≥85%", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = draft.enableAutoSprintL3,
                    onCheckedChange = { onDraftChange(draft.copy(enableAutoSprintL3 = it)) }
                )
            }

            // KBM Emulator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Emulador Teclado y Ratón (KBM)", color = Color.White)
                    Text("Mapeo directo de teclas WASD y clicks de ratón", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = draft.enableKbmEmulator,
                    onCheckedChange = { onDraftChange(draft.copy(enableKbmEmulator = it)) }
                )
            }
        }
    }
}

/* ==========================================================================================
   TAB 4: GIROSCOPIO
   ========================================================================================== */
@Composable
private fun GyroscopeTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Giroscopio Fuente Dual (Móvil / Mando)", color = Color.White, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Activar Apuntado por Giroscopio", color = Color.White)
                Switch(
                    checked = draft.enableGyroscope,
                    onCheckedChange = { onDraftChange(draft.copy(enableGyroscope = it)) }
                )
            }

            Text("Compuerta de Activación: ${draft.gyroGate}", color = Color.LightGray, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("HOLD_LEFT_TRIGGER", "ALWAYS_ON", "HOLD_LEFT_BUMPER").forEach { gate ->
                    val isSelected = draft.gyroGate == gate
                    Surface(
                        onClick = { onDraftChange(draft.copy(gyroGate = gate)) },
                        color = if (isSelected) Color(0xFF107C10) else Color(0xFF1A222C),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        val label = when (gate) {
                            "HOLD_LEFT_TRIGGER" -> "Mantener LT (ADS)"
                            "ALWAYS_ON" -> "Siempre Activo"
                            else -> "Mantener LB"
                        }
                        Text(label, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp)
                    }
                }
            }

            Text("Sensibilidad Giroscopio X/Y: ${String.format("%.1fx", draft.gyroSensitivityX)}", color = Color.White)
            Slider(
                value = draft.gyroSensitivityX,
                onValueChange = { onDraftChange(draft.copy(gyroSensitivityX = it, gyroSensitivityY = it)) },
                valueRange = 0.5f..3.0f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF2ECC71), activeTrackColor = Color(0xFF107C10))
            )
        }
    }
}

/* ==========================================================================================
   TAB 5: HÁPTICA & DUALSENSE
   ========================================================================================== */
@Composable
private fun HapticsTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Háptica Avanzada y Gatillos Adaptativos", color = Color.White, fontWeight = FontWeight.Bold)

            Text("Modo DualSense (Paquetes HID 48B/64B):", color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SECTION_SNAP", "CONTINUOUS_RESISTANCE", "MACHINE_GUN_RECOIL").forEach { mode ->
                    val isSelected = draft.dualSenseTriggerMode == mode
                    Surface(
                        onClick = { onDraftChange(draft.copy(dualSenseTriggerMode = mode)) },
                        color = if (isSelected) Color(0xFF107C10) else Color(0xFF1A222C),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        val label = when (mode) {
                            "SECTION_SNAP" -> "Snap / Arco"
                            "CONTINUOUS_RESISTANCE" -> "Resistencia"
                            else -> "Retroceso"
                        }
                        Text(label, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Gatillos de Impulso Xbox (LT/RT)", color = Color.White)
                    Text("Vibración háptica independiente en motores de gatillo", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = draft.enableImpulseTriggers,
                    onCheckedChange = { onDraftChange(draft.copy(enableImpulseTriggers = it)) }
                )
            }
        }
    }
}

/* ==========================================================================================
   TAB 6: CONSOLA XHOME
   ========================================================================================== */
@Composable
private fun ConsoleTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Juego Remoto en Consola (xHome)", color = Color.White, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Encendido Silencioso (Direct-to-Stream)", color = Color.White)
                    Text("Mantiene HDMI y LED de la consola apagados", color = Color(0xFF4ADE80), fontSize = 11.sp)
                }
                Switch(
                    checked = draft.xHomePowerMode == "SILENT_DIRECT_TO_STREAM",
                    onCheckedChange = {
                        onDraftChange(draft.copy(xHomePowerMode = if (it) "SILENT_DIRECT_TO_STREAM" else "FULL_POWER_ON"))
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Prioridad IPv6 & Túneles Teredo", color = Color.White)
                    Text("Travesía NAT en puertos UDP 3074 / 3544", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = draft.xHomePreferIpv6,
                    onCheckedChange = { onDraftChange(draft.copy(xHomePreferIpv6 = it)) }
                )
            }
        }
    }
}

/* ==========================================================================================
   TAB 7: AVANZADO & DIAGNÓSTICO
   ========================================================================================== */
@Composable
private fun AdvancedTab(
    draft: GodModeGlobalConfig,
    onDraftChange: (GodModeGlobalConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Avanzado & Diagnóstico SLA", color = Color.White, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Composición SurfaceView Zero Overdraw", color = Color.White)
                    Text("Pipeline directo en plano VPU del SoC", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = draft.enableZeroCopySurface,
                    onCheckedChange = { onDraftChange(draft.copy(enableZeroCopySurface = it)) }
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        val exporter = com.xcloud.godmode.services.PersistentDiagnosticExporter(context)
                        val file = exporter.exportLogsToCacheFile(listOf("God Mode Launch Audit v3.0", "Engine: ${draft.preferredEngine}", "Region: ${draft.forcedRegionCode}"))
                        android.widget.Toast.makeText(context, "Diagnóstico guardado en: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2933)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.BugReport, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Exportar Logs de Diagnóstico (SLA)", fontSize = 13.sp)
            }
        }
    }
}
