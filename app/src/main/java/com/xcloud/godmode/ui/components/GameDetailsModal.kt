package com.xcloud.godmode.ui.components

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
import com.xcloud.godmode.constants.XboxAssetsCodex
import com.xcloud.godmode.models.GameModel

@Composable
fun GameDetailsModal(
    game: GameModel,
    settings: com.xcloud.godmode.models.SettingsModel = com.xcloud.godmode.models.SettingsModel(),
    onClose: () -> Unit,
    onStartStream: (String) -> Unit
) {
    var isTrailerPlaying by remember { mutableStateOf(false) }
    var showTouchEditor by remember { mutableStateOf(false) }
    val strings = com.xcloud.godmode.ui.theme.AppStrings.get(settings.appLanguage)

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
                                            // Only start if we are still active in the UI
                                            if (isTrailerPlaying) start()
                                        }
                                        setOnErrorListener { _, _, _ ->
                                            // Handle errors silently to avoid crashes in headless environments
                                            true 
                                        }
                                    }
                                },
                                onRelease = { videoView ->
                                    try {
                                        videoView.stopPlayback()
                                        videoView.setOnPreparedListener(null)
                                        videoView.setOnErrorListener(null)
                                        videoView.setOnCompletionListener(null)
                                        videoView.setVideoURI(null)
                                        videoView.suspend()
                                    } catch (e: Exception) {
                                        // Ignore release exceptions in background threads
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
                                text = game.subscriptionTier,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.1.sp
                                ),
                                color = Color.White,
                                modifier = Modifier
                                    .background(Color(0xFF107C10), RoundedCornerShape(2.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
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
                    if (game.logoUrl.isNotBlank() && !game.title.contains("STEAM", ignoreCase = true)) {
                        AsyncImage(
                            model = game.logoUrl,
                            contentDescription = game.getFullTitle(),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(72.dp)
                                .widthIn(max = 320.dp)
                        )
                    } else {
                        Text(
                            text = game.getFullTitle(),
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = strings.compatibleWith,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                        AsyncImage(
                            model = XboxAssetsCodex.SystemIcons.CONTROLLER,
                            contentDescription = strings.controller,
                            modifier = Modifier.size(22.dp)
                        )
                        if (game.supportsMkb) {
                            AsyncImage(
                                model = XboxAssetsCodex.SystemIcons.KEYBOARD_MOUSE,
                                contentDescription = strings.mouseKeyboard,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        if (game.supportsTouch) {
                            AsyncImage(
                                model = XboxAssetsCodex.SystemIcons.TOUCH,
                                contentDescription = strings.touch,
                                modifier = Modifier.size(22.dp)
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
                            text = if (game.isGamePass) "Requiere suscripción activa de Xbox Game Pass Ultimate o juego F2P compatible" else "Este juego forma parte de tu colección personal o es un título gratuito.",
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
                                    text = if (game.isGamePass) "Incluido con Game Pass" else "Jugar desde la Nube",
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
                        AsyncImage(model = XboxAssetsCodex.SystemIcons.TOUCH, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
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
                                // Official Rating Badge Box
                                val officialRatingUrl: String = remember(game.rating, game.ratingIconUrl) {
                                    resolveOfficialRatingIcon(game.rating, game.ratingIconUrl)
                                }
                                Surface(
                                    color = Color.Black,
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White),
                                    shape = RoundedCornerShape(2.dp),
                                    modifier = Modifier
                                        .width(54.dp)
                                        .height(68.dp)
                                        .testTag("official_rating_badge")
                                ) {
                                    AsyncImage(
                                        model = officialRatingUrl,
                                        contentDescription = game.rating,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(3.dp)
                                    )
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

/**
 * Resolves high-resolution official rating badges from CDN for ESRB and PEGI.
 */
fun resolveOfficialRatingIcon(rating: String, iconUrl: String): String {
    if (iconUrl.isNotBlank() && iconUrl.contains("microsoft")) return iconUrl
    val lower = rating.lowercase()
    return when {
        lower.contains("everyone 10+") || lower.contains("e10") || lower.contains("10+") ->
            XboxAssetsCodex.AgeRatings.ESRB.E10
        lower.contains("mature") || lower.contains("maduro 17+") || lower.contains("esrb m") || lower == "m" ->
            XboxAssetsCodex.AgeRatings.ESRB.M
        lower.contains("teen") || lower.contains("adolescente") || lower.contains("esrb t") || lower == "t" ->
            XboxAssetsCodex.AgeRatings.ESRB.T
        lower.contains("everyone") || lower == "e" ->
            XboxAssetsCodex.AgeRatings.ESRB.E
        else ->
            XboxAssetsCodex.AgeRatings.ESRB.E // Default to high-res everyone if uncertain
    }
}

