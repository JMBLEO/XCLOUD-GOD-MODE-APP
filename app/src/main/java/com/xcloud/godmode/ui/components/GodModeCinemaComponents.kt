package com.xcloud.godmode.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xcloud.godmode.audio.GodModeSoundEngine
import com.xcloud.godmode.audio.SoundEventType
import com.xcloud.godmode.models.GameModel
import com.xcloud.godmode.models.SettingsModel
import kotlin.math.PI
import kotlin.math.sin

/**
 * GodModeCinemaBackground implements the PS5 Ken Burns Hero Zoom + PS3 XMB Ribbon Wave.
 * Fully modulated by the Chameleon color engine.
 */
@Composable
fun GodModeCinemaBackground(
    heroUrl: String,
    accentColor: Color,
    enableWave: Boolean = true,
    enableKenBurns: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bg_anim")
    
    val kbScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ken_burns"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val imageRequest = remember(heroUrl) {
            ImageRequest.Builder(context)
                .data(heroUrl)
                .crossfade(600)
                .build()
        }

        // 1. Base Layer: The Hero Art with Ken Burns
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(if (enableWave) 20.dp else 4.dp)
                .scale(if (enableKenBurns) kbScale else 1.0f)
                .alpha(0.35f)
        )

        // 2. Chameleon Radial Glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.4f),
                            accentColor.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(x = 1000f, y = 200f)
                    )
                )
        )

        // 3. PS3 XMB Ribbon Wave (Canvas)
        if (enableWave) {
            val wavePhase by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 2f * PI.toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(12000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "wave_phase"
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.25f)
                    .zIndex(1f)
            ) {
                val path = Path()
                val midY = size.height * 0.75f
                path.moveTo(0f, midY)

                for (x in 0..size.width.toInt() step 5) {
                    val angle = (x.toFloat() / size.width) * 2f * PI.toFloat() + wavePhase
                    val y = midY + sin(angle) * 80f + sin(angle * 0.5f) * 40f
                    path.lineTo(x.toFloat(), y)
                }

                drawPath(
                    path = path,
                    color = accentColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )

                // Secondary subtle wave
                val path2 = Path()
                path2.moveTo(0f, midY + 40f)
                for (x in 0..size.width.toInt() step 5) {
                    val angle = (x.toFloat() / size.width) * 1.5f * PI.toFloat() - wavePhase * 0.7f
                    val y = midY + 40f + sin(angle) * 60f
                    path2.lineTo(x.toFloat(), y)
                }
                drawPath(
                    path = path2,
                    color = accentColor.copy(alpha = 0.5f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // 4. Dark Vignette Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black
                        ),
                        startY = 0f
                    )
                )
        )
    }
}

/**
 * GodModeCinemaRibbon: Featured games carousel with console-like focus scaling.
 */
@Composable
fun GodModeCinemaRibbon(
    games: List<GameModel>,
    focusedIndex: Int,
    onGameFocused: (Int) -> Unit,
    onGameSelected: (GameModel) -> Unit,
    accentColor: Color
) {
    val listState = rememberLazyListState()
    
    LaunchedEffect(focusedIndex) {
        listState.animateScrollToItem(focusedIndex)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(games) { index, game ->
            val isFocused = index == focusedIndex
            val scale by animateFloatAsState(if (isFocused) 1.15f else 0.95f, label = "card_scale")
            
            Card(
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(2f / 3f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .border(
                        width = if (isFocused) 3.dp else 1.dp,
                        color = if (isFocused) accentColor else Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .onFocusChanged {
                        if (it.isFocused) {
                            onGameFocused(index)
                            GodModeSoundEngine.play(SoundEventType.FOCUS)
                        }
                    }
                    .clickable { onGameSelected(game) },
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 12.dp else 2.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = game.getFormattedPosterUrl(600, 900),
                        contentDescription = game.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * XboxBladeGuide: Side-drawer for real-time Game Optimization.
 */
@Composable
fun XboxBladeGuide(
    isOpen: Boolean,
    onClose: () -> Unit,
    settings: SettingsModel,
    onSettingsChanged: (SettingsModel) -> Unit,
    accentColor: Color,
    onApplyPreset: (String) -> Unit,
    recommendation: String = "",
    isAnalyzing: Boolean = false,
    onReAnalyze: () -> Unit = {},
    onTestHaptics: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = Modifier.zIndex(1000f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { onClose() }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(340.dp)
                    .align(Alignment.CenterEnd)
                    .clickable(enabled = false) {},
                color = Color(0xFF101216),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.Gray)
                        }
                        Text(
                            "Game Optimization",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    HorizontalDivider(color = accentColor.copy(alpha = 0.3f))

                    Text("Perfiles de 1 Clic", style = MaterialTheme.typography.labelMedium, color = accentColor)
                    
                    listOf(
                        Triple("Battery Saver", "🍃", "battery_saver"),
                        Triple("Balanced", "⏱️", "balanced"),
                        Triple("God Mode", "⚡", "god_mode")
                    ).forEach { (name, icon, key) ->
                        ProfileItem(
                            name = name,
                            icon = icon,
                            isSelected = settings.optimizerProfile == key,
                            accentColor = accentColor,
                            onClick = { onApplyPreset(name) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Procesamiento en Vivo", style = MaterialTheme.typography.labelMedium, color = accentColor)

                    // Live Sharpness
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Nitidez Dinámica", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Text("${(settings.fsrSharpness * 10).toInt()}", color = accentColor, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.fsrSharpness,
                            onValueChange = { onSettingsChanged(settings.copy(fsrSharpness = it)) },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                        )
                    }

                    // Audio Boost
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Audio Boost (GainNode)", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Text("${settings.volumeBoostPercent}%", color = accentColor, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.volumeBoostPercent.toFloat(),
                            onValueChange = { onSettingsChanged(settings.copy(volumeBoostPercent = it.toInt())) },
                            valueRange = 100f..300f,
                            colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                        )
                    }

                    // Test Haptics Button
                    Button(
                        onClick = onTestHaptics,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2429)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Probar Háptica", style = MaterialTheme.typography.labelMedium)
                    }

                    // AI Diagnostics Section
                    Surface(
                        color = Color(0xFF101216),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copiloto IA", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = recommendation,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                                fontSize = 10.sp
                            )
                            if (isAnalyzing) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = accentColor)
                            }
                            TextButton(onClick = onReAnalyze, modifier = Modifier.align(Alignment.End)) {
                                Text("Re-Analizar", color = accentColor, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Ghost HUD (OSD)", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = settings.showStats,
                            onCheckedChange = { onSettingsChanged(settings.copy(showStats = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = accentColor)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileItem(name: String, icon: String, isSelected: Boolean, accentColor: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) accentColor.copy(alpha = 0.2f) else Color(0xFF1C1F24),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) accentColor else Color(0xFF2D3238)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(name, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/**
 * GameCardFocusable: Focusable Card with 120Hz D-Pad support, animated scale (1.06x), and gold accent border.
 */
@Composable
fun GameCardFocusable(
    game: GameModel,
    onGameSelected: (GameModel) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.06f else 1.0f, label = "focusScale")

    Box(
        modifier = modifier
            .width(160.dp)
            .height(240.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusable(interactionSource = interactionSource)
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) Color(0xFFFFD700) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null) {
                GodModeSoundEngine.play(SoundEventType.LAUNCH)
                onGameSelected(game)
            }
    ) {
        AsyncImage(
            model = game.getFormattedPosterUrl(480, 720),
            contentDescription = game.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Gradient overlay for title on card bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
        )

        Text(
            text = game.title,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

/**
 * BottomGamepadPrompts: Persistent console button prompts bar adapting to connected controller.
 */
@Composable
fun BottomGamepadPrompts(
    controller: com.xcloud.godmode.viewmodels.HomeViewModel.ControllerInfo? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = controller != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            color = Color(0xFF0C1016).copy(alpha = 0.9f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2A3B4D).copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Adaptive layout based on hardware Vendor ID (Xbox vs PlayStation vs Switch)
                val isSony = controller?.vendorId == 1356 || controller?.vendorId == 0x054C
                val isNintendo = controller?.vendorId == 1406 || controller?.vendorId == 0x057E
                
                if (isSony) {
                    GamepadPromptBadge(badge = "✕", label = "Detalles", color = Color(0xFF38BDF8))
                    GamepadPromptBadge(badge = "R2", label = "Jugar", color = Color.White)
                    GamepadPromptBadge(badge = "□", label = "Favorito", color = Color(0xFFD36FBB))
                    GamepadPromptBadge(badge = "△", label = "Buscar", color = Color(0xFF22C55E))
                } else if (isNintendo) {
                    GamepadPromptBadge(badge = "A", label = "Detalles", color = Color(0xFFE60012))
                    GamepadPromptBadge(badge = "ZR", label = "Jugar", color = Color.White)
                    GamepadPromptBadge(badge = "Y", label = "Favorito", color = Color.Gray)
                    GamepadPromptBadge(badge = "X", label = "Buscar", color = Color.Gray)
                } else {
                    GamepadPromptBadge(badge = "A", label = "Detalles", color = Color(0xFF22C55E))
                    GamepadPromptBadge(badge = "ZR", label = "Jugar", color = Color(0xFF38BDF8))
                    GamepadPromptBadge(badge = "X", label = "Favorito", color = Color(0xFFF59E0B))
                    GamepadPromptBadge(badge = "Y", label = "Buscar", color = Color(0xFFA855F7))
                }
            }
        }
    }
}

/**
 * ExpandableSettingCard: Accordion card to hide sliders and save vertical space.
 */
@Composable
fun ExpandableSettingCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onExpandToggle() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141920).copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF282D33).copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = Color(0xFF107C10), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }
        }
    }
}

@Composable
fun GamepadPromptBadge(badge: String, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.2f),
            border = BorderStroke(1.dp, color)
        ) {
            Text(
                text = badge,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * QuickActionModal: Floating quick action modal when pressing 'A' on a game card.
 */
@Composable
fun QuickActionModal(
    game: GameModel?,
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onPlayNow: (GameModel) -> Unit,
    onToggleFavorite: (GameModel) -> Unit,
    isFavorite: Boolean,
    onOpenDetails: (GameModel) -> Unit
) {
    if (!isOpen || game == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F141C),
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AsyncImage(
                    model = game.getFormattedPosterUrl(200, 300),
                    contentDescription = game.title,
                    modifier = Modifier
                        .size(width = 50.dp, height = 75.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Column {
                    Text(
                        text = game.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = if (game.genre.isNotBlank()) game.genre else "Xbox Cloud Game",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF107C10)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onDismiss()
                        onPlayNow(game)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Jugar ahora", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onToggleFavorite(game) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFF2A3B4D)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) Color.Red else Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos")
                }

                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenDetails(game)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ver detalles completos", color = Color(0xFF38BDF8))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = Color.Gray)
            }
        }
    )
}

/**
 * StreamStatusOverlay: Fullscreen overlay for xCloud provisioning and queue states.
 */
@Composable
fun StreamStatusOverlay(
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            CircularProgressIndicator(
                color = Color(0xFF107C10),
                strokeWidth = 4.dp,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
