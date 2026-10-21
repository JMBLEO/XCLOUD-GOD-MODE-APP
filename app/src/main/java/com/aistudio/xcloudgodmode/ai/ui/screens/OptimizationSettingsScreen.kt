package com.aistudio.xcloudgodmode.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.xcloudgodmode.ai.ui.viewmodels.OptimizationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizationSettingsScreen(
    viewModel: OptimizationViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val recommendation by viewModel.recommendation.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game Optimizer (En Vivo)", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .background(Color(0xFF107C10).copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF107C10), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Camaleón Activo", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4ADE80))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0C0E10))
            )
        },
        containerColor = Color(0xFF0C0E10)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Perfiles de 1 Clic
            item {
                Text("Perfiles de Rendimiento", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val profiles = listOf(
                        Triple("Battery Saver", "🍃", "balanced"),
                        Triple("Balanced", "⏱️", "balanced"),
                        Triple("God Mode", "⚡", "god_mode")
                    )
                    profiles.forEach { (name, icon, key) ->
                        ProfileButton(
                            name = name,
                            icon = icon,
                            isSelected = settings.optimizerProfile == name.lowercase().replace(" ", "_"),
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.applyPreset(name) }
                        )
                    }
                }
            }

            // 2. Procesamiento de Video GPU (WebGL / Shaders)
            item {
                OptimizerCategory(icon = Icons.Default.Visibility, title = "Procesamiento de Video (GPU)") {
                    Text("Filtro GPU Shaders", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    val engines = listOf("Off", "AMD FSR 3.1 RCAS WebGL2", "Real-ESRGAN SRVGG", "SeedVR2")
                    engines.forEach { engine ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.updateSettings(settings.copy(upscalerEngine = engine)) }
                        ) {
                            RadioButton(
                                selected = settings.upscalerEngine == engine,
                                onClick = { viewModel.updateSettings(settings.copy(upscalerEngine = engine)) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF107C10))
                            )
                            Text(engine, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nitidez Dinámica", modifier = Modifier.weight(1f), color = Color.White)
                        Text("${(settings.fsrSharpness * 10).toInt()}", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = settings.fsrSharpness,
                        onValueChange = { viewModel.updateSettings(settings.copy(fsrSharpness = it)) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                    )
                }
            }

            // 3. Audio y Háptica
            item {
                OptimizerCategory(icon = Icons.Default.VolumeUp, title = "Audio y Háptica Dinámica") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ganancia de Volumen", modifier = Modifier.weight(1f), color = Color.White)
                        Text("${settings.volumeBoostPercent}%", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = settings.volumeBoostPercent.toFloat(),
                        onValueChange = { viewModel.updateSettings(settings.copy(volumeBoostPercent = it.toInt())) },
                        valueRange = 100f..300f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Perfil Háptico Activo", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("standard", "dualsense", "pro").forEach { profile ->
                            Box(
                                modifier = Modifier.weight(1f).height(40.dp)
                                    .background(if (settings.vibProfile == profile) Color(0xFF107C10) else Color(0xFF14171A), RoundedCornerShape(8.dp))
                                    .border(1.dp, if (settings.vibProfile == profile) Color(0xFF4ADE80) else Color.DarkGray, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.updateSettings(settings.copy(vibProfile = profile)) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(profile.uppercase(), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.testVibration() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2429)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Probar Respuesta Háptica", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // 4. Ghost HUD & IA Diagnostics
            item {
                OptimizerCategory(icon = Icons.Default.Analytics, title = "Telemetría & Diagnóstico IA") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Ghost HUD (OSD Maestro)", color = Color.White)
                        Switch(
                            checked = settings.showStats,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(showStats = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10))
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        color = Color(0xFF101216),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF107C10).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, contentDescription = null, tint = Color(0xFF4ADE80))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copiloto IA God Mode", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = recommendation,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                            if (isAnalyzing) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Color(0xFF107C10))
                            }
                            TextButton(onClick = { viewModel.fetchRecommendation("Forza Horizon 5", "Wi-Fi 6") }, modifier = Modifier.align(Alignment.End)) {
                                Text("Re-Analizar Sesión", color = Color(0xFF4ADE80))
                            }
                        }
                    }
                }
            }
            
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ProfileButton(name: String, icon: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFF107C10).copy(alpha = 0.2f) else Color(0xFF1C2024),
        border = androidx.compose.foundation.BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) Color(0xFF107C10) else Color(0xFF2D3238)),
        modifier = modifier.height(80.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(name, fontWeight = FontWeight.Bold, color = if (isSelected) Color.White else Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OptimizerCategory(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
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
