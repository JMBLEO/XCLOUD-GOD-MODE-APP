package com.aistudio.xcloudgodmode.ai.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine
import com.aistudio.xcloudgodmode.ai.audio.SoundEventType
import com.aistudio.xcloudgodmode.ai.models.GameModel
import com.aistudio.xcloudgodmode.ai.models.SettingsModel
import kotlin.math.PI
import kotlin.math.sin

/**
 * PlayStaxBoxBackground implements the PS5 Ken Burns Hero Zoom + PS3 XMB Ribbon Wave.
 * Fully modulated by the Chameleon color engine.
 */
@Composable
fun PlayStaxBoxBackground(
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
        // 1. Base Layer: The Hero Art with Ken Burns
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(heroUrl)
                .crossfade(600)
                .build(),
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
 * PlayStaxBoxRibbon: Featured games carousel with console-like focus scaling.
 */
@Composable
fun PlayStaxBoxRibbon(
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
                            PlayStaxBoxNativeSoundEngine.play(SoundEventType.FOCUS)
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
    onApplyPreset: (String) -> Unit
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
