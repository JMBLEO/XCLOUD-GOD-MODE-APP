package com.aistudio.xcloudgodmode.ai.ui.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.aistudio.xcloudgodmode.ai.models.GameModel

@Composable
fun GameDetailsModal(
    game: GameModel,
    settings: com.aistudio.xcloudgodmode.ai.models.SettingsModel = com.aistudio.xcloudgodmode.ai.models.SettingsModel(),
    onClose: () -> Unit,
    onStartStream: (String) -> Unit
) {
    var isTrailerPlaying by remember { mutableStateOf(false) }
    var showTouchEditor by remember { mutableStateOf(false) }
    val strings = com.aistudio.xcloudgodmode.ai.ui.theme.AppStrings.get(settings.appLanguage)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .testTag("game_details_modal_${game.id}"),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0F1115),
            tonalElevation = 12.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 1. Full Xbox Store Hero / SuperHeroArt Background
                if (!isTrailerPlaying) {
                    AsyncImage(
                        model = game.getFormattedSuperHeroUrl(1920, 1080),
                        contentDescription = "Fondo de Store para ${game.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .align(Alignment.TopCenter)
                    )

                    // Cinematic Dark Gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Black.copy(alpha = 0.2f),
                                        0.28f to Color(0xFF0F1115).copy(alpha = 0.85f),
                                        0.45f to Color(0xFF0F1115),
                                        1.0f to Color(0xFF0F1115)
                                    )
                                )
                            )
                    )
                }

                // Main Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState())
                ) {

                    // Trailer Player (if activated)
                    if (isTrailerPlaying && game.trailerUrl.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoURI(Uri.parse(game.trailerUrl))
                                        val controller = MediaController(ctx)
                                        controller.setAnchorView(this)
                                        setMediaController(controller)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = true
                                            start()
                                        }
                                    }
                                },
                                onRelease = { videoView ->
                                    try {
                                        videoView.stopPlayback()
                                        videoView.suspend()
                                    } catch (e: Exception) {
                                        // Ignore release exceptions
                                    }
                                }
                            )
                            IconButton(
                                onClick = { isTrailerPlaying = false },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar Tráiler", tint = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // A. Game Pass Badge / Brand Ribbon
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (game.isGamePass) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "XBOX GAME PASS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                ),
                                color = Color.White
                            )
                        } else if (game.isOwned) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF107C10).copy(alpha = 0.3f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF107C10))
                            ) {
                                Text(
                                    text = "JUEGO COMPRADO / PROPIEDAD",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF4ADE80),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // B. Title & Transparent Logo
                    if (game.logoUrl.isNotBlank()) {
                        AsyncImage(
                            model = game.logoUrl,
                            contentDescription = game.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(64.dp)
                                .widthIn(max = 280.dp)
                        )
                    } else {
                        Text(
                            text = game.title,
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // C. Publisher • Genre line
                    Text(
                        text = "${game.publisher} • ${game.genre}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // D. Compatibility: Jugar con mando / teclado
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.compatibleWith,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = strings.controller,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = strings.mouseKeyboard,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        if (game.capabilities.any { it.contains("Táctil", ignoreCase = true) }) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = strings.touch,
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // E. Languages & Accessibility stats row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        val languageCountText = if (game.languages.isNotEmpty()) {
                            if (game.languages.first().contains("idiomas", ignoreCase = true) || game.languages.first().contains("languages", ignoreCase = true)) game.languages.first()
                            else "${game.languages.size} ${strings.languagesCount}"
                        } else "22 ${strings.languagesCount}"

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = languageCountText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }

                        val accessibilityText = if (game.accessibilityFeatures.isNotEmpty()) {
                            if (game.accessibilityFeatures.first().contains("accesibilidad", ignoreCase = true) || game.accessibilityFeatures.first().contains("accessibility", ignoreCase = true)) game.accessibilityFeatures.first()
                            else "${game.accessibilityFeatures.size} ${strings.accessibilityCount}"
                        } else "20 ${strings.accessibilityCount}"

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccessibilityNew,
                                contentDescription = null,
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = accessibilityText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // F. Info Banner: Subscription / Purchase requirement
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.subscriptionNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // G. Big Action Buttons Row (JUGAR AHORA + Share + Store/Cart)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onStartStream(game.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .testTag("modal_start_stream_button")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = strings.playNow,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                    color = Color.White
                                )
                                Text(
                                    text = strings.allIncludedSubscription,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE2E8F0)
                                )
                            }
                        }

                        // Trailer or Share button
                        if (game.trailerUrl.isNotBlank()) {
                            FilledTonalIconButton(
                                onClick = { isTrailerPlaying = !isTrailerPlaying },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.size(56.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF1E242B),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Tráiler")
                            }
                        }

                        FilledTonalIconButton(
                            onClick = {},
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF1E242B),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Compartir")
                        }

                        FilledTonalIconButton(
                            onClick = {},
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF1E242B),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = "Comprar")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Customize Touch Controls Button
                    OutlinedButton(
                        onClick = { showTouchEditor = true },
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3842)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4ADE80)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("game_details_customize_touch_button")
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.touchEditorCustomize, style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // H. Content Rating Box (ESRB / MADURO 17+ with descriptors and legal asterisks)
                    Surface(
                        color = Color(0xFF161A20),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242A32)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                // Rating Badge Box
                                Surface(
                                    color = Color.Black,
                                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier
                                        .width(52.dp)
                                        .height(60.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = if (game.rating.contains("17", ignoreCase = true) || game.rating.contains("Maduro", ignoreCase = true)) "M" else "T",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 24.sp,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "ESRB",
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF0F2E1E),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF107C10))
                                    ) {
                                        Text(
                                            text = "${game.rating.uppercase()} ↗",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF4ADE80),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    val descriptors = if (game.contentDescriptors.isNotEmpty()) {
                                        game.contentDescriptors.joinToString(", ")
                                    } else {
                                        "Derramamiento de sangre, Lenguaje fuerte, Violencia intensa"
                                    }

                                    Text(
                                        text = descriptors,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFE2E8F0)
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "Compras dentro del juego, Interacción de usuarios",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = Color(0xFF242A32))
                            Spacer(modifier = Modifier.height(10.dp))

                            // Legal Disclaimers with Asterisks
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "* Es posible que se apliquen tarifas de Internet o de proveedores de red.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    text = "* Streaming compatible con una cuenta de XBOX. Se aplican límites de sesión.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    text = "* Contains in-app purchases.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Close Button Top-Right (Placed at the end of Box to be on top of Z-stack)
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                        .testTag("close_modal_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                }
            }
        }

        if (showTouchEditor) {
            TouchControllerEditor(
                titleId = game.id,
                gameTitle = game.title,
                settings = settings,
                onDismiss = { showTouchEditor = false }
            )
        }
    }
}
