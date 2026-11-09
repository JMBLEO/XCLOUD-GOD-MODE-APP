package com.xcloud.godmode.ui.screens

import android.app.Activity
import android.content.Context
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xcloud.godmode.viewmodels.HomeViewModel
import com.xcloud.godmode.ui.*
import com.xcloud.godmode.ui.components.*
import com.xcloud.godmode.engine.ConsoleGradeHardwareEnhancer
import com.xcloud.godmode.streaming.NativeInputBridge
import com.xcloud.godmode.streaming.NativeStreamBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * StreamScreen: The primary entry point for the "God Mode" streaming experience.
 * It uses the Carrier-Grade PlayNowCoordinator via the HomeViewModel to manage 
 * session lifecycle, surface registration, and engine attachment.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun StreamScreen(
    viewModel: HomeViewModel,
    onClose: () -> Unit,
    onAction: (StreamErrorAction) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.launchState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val hwProfile by viewModel.hardwareProfile.collectAsState()
    val window = (context as? Activity)?.window
    
    val scope = rememberCoroutineScope()
    val strings = com.xcloud.godmode.ui.theme.AppStrings.get(settings.appLanguage)
    
    // UI states for overlays
    var isHudVisible by remember { mutableStateOf(settings.showStats) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var showInStreamGameBar by remember { mutableStateOf(false) }
    var showAchievementsOverlay by remember { mutableStateOf(false) }
    var isTouchControlsEnabled by remember { mutableStateOf(true) }
    var showTouchEditor by remember { mutableStateOf(false) }
    var showServerInfoDialog by remember { mutableStateOf(false) }
    var isMicMuted by remember { mutableStateOf(true) }
    var isGyroEnabled by remember { mutableStateOf(false) }
    
    // Performance Tuners
    var volumeBoost by remember { mutableIntStateOf(100) }
    var fsrEnabled by remember { mutableStateOf(settings.enableAiUpscale) }
    var fsrSharpness by remember { mutableFloatStateOf(settings.fsrSharpness) }
    var hudPosition by remember { mutableStateOf(settings.hudPosition) }

    var activeEnhancer by remember { mutableStateOf<ConsoleGradeHardwareEnhancer?>(null) }

    // Immersive Mode Sync
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == StreamPhase.STREAMING) {
            (context as? Activity)?.let { activity ->
                com.xcloud.godmode.MainActivity.restoreImmersiveMode(activity)
            }
        }
    }

    // Predictive Back Handling
    BackHandler {
        when {
            showInStreamGameBar -> showInStreamGameBar = false
            showTouchEditor -> showTouchEditor = false
            showAchievementsOverlay -> showAchievementsOverlay = false
            showServerInfoDialog -> showServerInfoDialog = false
            showQuickMenu -> showQuickMenu = false
            else -> onClose()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        StreamRoute(
            uiState = uiState,
            surfaceRegistry = viewModel.surfaceRegistry,
            surfaceFactory = { ctx ->
                SurfaceView(ctx).apply {
                    // Attach high-performance hardware enhancer (HDR10, VRR, PQ)
                    if (hwProfile != null && window != null) {
                        activeEnhancer = ConsoleGradeHardwareEnhancer(ctx, window, this, hwProfile!!).also {
                            it.activateAllmVrrAndHdrWindow(enableHdr10 = (settings.hdrMode != "off"), initialStreamFps = 60f)
                        }
                    }
                }
            },
            onAction = onAction,
            touchOverlayContent = {
                if (isTouchControlsEnabled) {
                    GodModeTouchControlsOverlay(
                        initialOpacity = 0.65f,
                        hapticsEnabled = true,
                        onClose = { isTouchControlsEnabled = false }
                    )
                }
            },
            debugHudContent = {
                if (isHudVisible) {
                    RealTimeLatencyHUD(
                        modifier = Modifier
                            .align(if (hudPosition == "BOTTOM") Alignment.BottomStart else Alignment.TopStart)
                            .padding(16.dp),
                        initialExpanded = false,
                        gameTitle = uiState.gameTitle,
                        showNetwork = uiState.hudShowNetwork,
                        showInput = uiState.hudShowInput,
                        showFps = uiState.hudShowFps,
                        onToggleNetwork = { viewModel.uiController.toggleHudNetwork() },
                        onToggleInput = { viewModel.uiController.toggleHudInput() },
                        onToggleFps = { viewModel.uiController.toggleHudFps() },
                        shaderPreset = "Cinema Hybrid",
                        resolution = "1080p HQ",
                        aiOptimizationPreset = "NPU Direct",
                        onTogglePosition = {
                            hudPosition = if (hudPosition == "BOTTOM") "TOP" else "BOTTOM"
                        }
                    )
                }
            },
            gameBarContent = {
                if (showInStreamGameBar) {
                    InStreamGameBar(
                        isOpen = showInStreamGameBar,
                        onClose = { showInStreamGameBar = false },
                        isMicMuted = isMicMuted,
                        onToggleMic = { 
                            isMicMuted = !isMicMuted
                            viewModel.toggleMicMute()
                        },
                        isGyroEnabled = isGyroEnabled,
                        onToggleGyro = { isGyroEnabled = !isGyroEnabled },
                        volume = 1.0f,
                        onVolumeChange = { /* TODO */ },
                        onTakeScreenshot = { /* TODO */ },
                        onGoBackToHub = { onClose() }
                    )
                }
            }
        )

        // Floating Overlays (Top-Right)
        if (uiState.phase == StreamPhase.STREAMING) {
            GodModeStreamControlBar(
                isHudVisible = isHudVisible,
                onToggleHud = { isHudVisible = !isHudVisible },
                showQuickMenu = showQuickMenu,
                onToggleQuickMenu = { showQuickMenu = !showQuickMenu },
                showServerInfo = showServerInfoDialog,
                onToggleServerInfo = { showServerInfoDialog = !showServerInfoDialog },
                isTouchEnabled = isTouchControlsEnabled,
                onToggleTouch = { 
                    isTouchControlsEnabled = !isTouchControlsEnabled
                    NativeInputBridge.isRawTouchEnabled = isTouchControlsEnabled
                },
                onShowGameBar = { showInStreamGameBar = true },
                onShowAchievements = { showAchievementsOverlay = true },
                onClose = onClose
            )
        }
        
        // Quick Settings Panel
        AnimatedVisibility(
            visible = showQuickMenu && uiState.phase == StreamPhase.STREAMING,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 16.dp)
        ) {
            QuickSettingsPanel(
                settings = settings,
                strings = strings,
                isHudVisible = isHudVisible,
                onToggleHud = { isHudVisible = it },
                hudPosition = hudPosition,
                onHudPositionChange = { hudPosition = it },
                fsrEnabled = fsrEnabled,
                onToggleFsr = { fsrEnabled = it },
                fsrSharpness = fsrSharpness,
                onFsrSharpnessChange = { fsrSharpness = it },
                volumeBoost = volumeBoost,
                onVolumeBoostChange = { volumeBoost = it }
            )
        }
        
        // Modals & Dialogs
        if (showServerInfoDialog) {
            ServerInfoDialog(strings = strings, onClose = { showServerInfoDialog = false })
        }
        
        if (showAchievementsOverlay) {
            TrueAchievementsOverlay(
                isOpen = showAchievementsOverlay,
                titleId = uiState.xCloudTitleId,
                gameTitle = uiState.gameTitle,
                onClose = { showAchievementsOverlay = false }
            )
        }
        
        if (showTouchEditor) {
            TouchControllerEditor(
                titleId = uiState.xCloudTitleId,
                gameTitle = uiState.gameTitle,
                settings = settings,
                onDismiss = { showTouchEditor = false }
            )
        }
    }
}

@Composable
fun GodModeStreamControlBar(
    isHudVisible: Boolean, onToggleHud: () -> Unit,
    showQuickMenu: Boolean, onToggleQuickMenu: () -> Unit,
    showServerInfo: Boolean, onToggleServerInfo: () -> Unit,
    isTouchEnabled: Boolean, onToggleTouch: () -> Unit,
    onShowGameBar: () -> Unit,
    onShowAchievements: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        Row(
            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onShowAchievements) {
                Icon(Icons.Default.EmojiEvents, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onToggleTouch) {
                Icon(Icons.Default.SportsEsports, null, tint = if (isTouchEnabled) Color(0xFF107C10) else Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onShowGameBar) {
                Icon(Icons.AutoMirrored.Filled.MenuOpen, null, tint = Color(0xFF107C10), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onToggleQuickMenu) {
                Icon(Icons.Default.Tune, null, tint = if (showQuickMenu) Color(0xFF107C10) else Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onToggleServerInfo) {
                Icon(Icons.Default.Public, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onToggleHud) {
                Icon(Icons.Default.Info, null, tint = if (isHudVisible) Color(0xFF107C10) else Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onClose, colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFB91C1C).copy(alpha = 0.8f))) {
                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun QuickSettingsPanel(
    settings: com.xcloud.godmode.models.SettingsModel,
    strings: com.xcloud.godmode.ui.theme.AppStrings,
    isHudVisible: Boolean, onToggleHud: (Boolean) -> Unit,
    hudPosition: String, onHudPositionChange: (String) -> Unit,
    fsrEnabled: Boolean, onToggleFsr: (Boolean) -> Unit,
    fsrSharpness: Float, onFsrSharpnessChange: (Float) -> Unit,
    volumeBoost: Int, onVolumeBoostChange: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF14171A).copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.width(300.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.streamSettingsTitle, style = MaterialTheme.typography.titleSmall, color = Color.White)
            
            // HUD Position
            Text(strings.hudPositionLabel, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TOP", "BOTTOM").forEach { pos ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (hudPosition == pos) Color(0xFF107C10) else Color(0xFF1E2328), RoundedCornerShape(6.dp))
                            .clickable { onHudPositionChange(pos) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(pos, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // HUD Toggle
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(strings.showHudLabel, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Switch(checked = isHudVisible, onCheckedChange = onToggleHud, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10)))
            }

            // FSR Toggle
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(strings.fsrSharpnessLabel, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Switch(checked = fsrEnabled, onCheckedChange = onToggleFsr, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF107C10)))
            }
            if (fsrEnabled) {
                Slider(value = fsrSharpness, onValueChange = onFsrSharpnessChange, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10)))
            }

            // Volume Boost
            Text("Refuerzo de Volumen: $volumeBoost%", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Slider(value = volumeBoost.toFloat(), onValueChange = { onVolumeBoostChange(it.toInt()) }, valueRange = 50f..200f, colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10)))
        }
    }
}

@Composable
fun ServerInfoDialog(strings: com.xcloud.godmode.ui.theme.AppStrings, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(strings.serverInfoTitle, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("• Protocolo: GodMode UDP (BBR2 Pacing)", color = Color.LightGray)
                Text("• Codificación: H.264 SVC / HEVC Adaptive", color = Color.LightGray)
                Text("• Latencia de Presentación: Ultra-Low (<1ms)", color = Color.LightGray)
                Text("• Audio: Opus Multichannel Lossless (Sim)", color = Color.LightGray)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("OK", color = Color(0xFF107C10)) } },
        containerColor = Color(0xFF1C2024)
    )
}
