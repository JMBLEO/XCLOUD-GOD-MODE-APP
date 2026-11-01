package com.xcloud.godmode.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xcloud.godmode.constants.XboxAssetsCodex
import com.xcloud.godmode.models.GameModel
import com.xcloud.godmode.models.SettingsModel

/**
 * ResilientCoverArt: Unified game card component with dynamic overlays for 
 * Game Pass, capabilities (Touch, Mkb, Controller), and ownership badges.
 * Uses official Xbox Assets Codex for robust icon loading.
 */
@Composable
fun ResilientCoverArt(
    game: GameModel,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit,
    accentColor: Color,
    settings: SettingsModel,
    modifier: Modifier = Modifier
) {
    val screenScale = com.xcloud.godmode.ui.theme.LocalScreenScale.current
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.06f else 1.0f, label = "card_scale")

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                val baseScale = screenScale.posterScale
                scaleX = scale * baseScale
                scaleY = scale * baseScale
            }
            .focusable()
            .onFocusChanged { 
                isFocused = it.isFocused 
                if (it.isFocused) {
                    com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.FOCUS)
                }
            }
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) {
                    if (settings.homeLayoutStyle == "Cinema Hybrid") Color(0xFFFFD700)
                    else accentColor
                } else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF15191E))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val posterWidth = if (LocalConfiguration.current.screenWidthDp >= 768) 600 else 320
            val posterHeight = if (LocalConfiguration.current.screenWidthDp >= 768) 900 else 480
            
            AsyncImage(
                model = game.getFormattedPosterUrl(posterWidth, posterHeight),
                contentDescription = game.getFullTitle(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            
            // 1. Favorite Toggle (Top-Right)
            IconButton(
                onClick = {
                    onFavoriteToggle()
                    com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.FAVORITE)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(screenScale.scaledDp(30f))
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (game.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (game.isFavorite) Color(0xFFFFD700) else Color.White,
                    modifier = Modifier.size(screenScale.scaledDp(16f))
                )
            }

            // 2. Dynamic Metadata Overlays (Bottom-Left Group)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Game Pass Badge
                if (game.isGamePass) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.height(18.dp).wrapContentWidth()
                    ) {
                        Icon(
                            imageVector = XboxUnifiedIcons.GamePass,
                            contentDescription = "Game Pass",
                            tint = Color(0xFF107C10),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).fillMaxHeight()
                        )
                    }
                }
                
                // icon-container (Adaptive CSS-like logic - width: max-content, background: transparent)
                val hasController = game.capabilities.any { it.contains("Mando", true) || it.contains("Controller", true) || it.contains("Gamepad", true) }
                val hasTouch = game.supportsTouch
                val hasMkb = game.supportsMkb
                
                if (hasController || hasTouch || hasMkb) {
                    Row(
                        modifier = Modifier
                            .wrapContentWidth()
                            .background(Color.Transparent)
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasController) {
                            Icon(
                                imageVector = XboxUnifiedIcons.Controller,
                                contentDescription = "Controller",
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        
                        if (hasTouch) {
                            Icon(
                                imageVector = XboxUnifiedIcons.Touch,
                                contentDescription = "Touch",
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        
                        if (hasMkb) {
                            Icon(
                                imageVector = XboxUnifiedIcons.Keyboard,
                                contentDescription = "MnK",
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                // X|S Badge
                if (game.isSeriesOptimized) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.height(18.dp).wrapContentWidth().border(0.5.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    ) {
                        AsyncImage(
                            model = XboxAssetsCodex.SystemIcons.SERIES_X_S,
                            contentDescription = "Series X|S",
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp).fillMaxHeight(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            // 3. Ownership Overlay (Bottom-Right)
            if (game.isOwned) {
                Surface(
                    color = Color(0xFF107C10).copy(alpha = 0.85f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(20.dp)
                ) {
                    Icon(
                        imageVector = XboxUnifiedIcons.Owned,
                        contentDescription = "Owned",
                        tint = Color.White,
                        modifier = Modifier.padding(3.dp)
                    )
                }
            }
        }
    }
}
