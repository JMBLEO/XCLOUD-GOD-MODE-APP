package com.aistudio.xcloudgodmode.ai.ui.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideogameAsset
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.aistudio.xcloudgodmode.ai.models.GameModel

@Composable
fun GameDetailsModal(
    game: GameModel,
    onClose: () -> Unit,
    onStartStream: (String) -> Unit
) {
    var isTrailerPlaying by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f)
                .testTag("game_details_modal_${game.id}"),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF101214),
            tonalElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header: 16:9 SuperHeroArt + Logo Overlay + Close Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                ) {
                    if (isTrailerPlaying && game.trailerUrl.isNotBlank()) {
                        // Trailer Video Player
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
                            }
                        )
                    } else {
                        // SuperHeroArt / Hero Background
                        AsyncImage(
                            model = game.getFormattedSuperHeroUrl(1280, 720),
                            contentDescription = game.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Gradient protection layer
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color(0xFF101214).copy(alpha = 0.95f)
                                        ),
                                        startY = 80f
                                    )
                                )
                        )

                        // Transparent PNG Logo or Title Overlay
                        if (game.logoUrl.isNotBlank()) {
                            AsyncImage(
                                model = game.logoUrl,
                                contentDescription = "Logo de ${game.title}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 20.dp, bottom = 16.dp)
                                    .height(64.dp)
                                    .widthIn(max = 240.dp)
                            )
                        } else {
                            Text(
                                text = game.title,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 20.dp, bottom = 16.dp)
                            )
                        }
                    }

                    // Close Button (Top-Right)
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                    }

                    // Play Trailer Toggle button (if available)
                    if (game.trailerUrl.isNotBlank()) {
                        Button(
                            onClick = { isTrailerPlaying = !isTrailerPlaying },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        ) {
                            Icon(
                                if (isTrailerPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isTrailerPlaying) "Cerrar Tráiler" else "Ver Tráiler Oficial")
                        }
                    }
                }

                // Content Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Badges: ESRB Rating, Capabilities
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF242A30)
                        ) {
                            Text(
                                text = game.rating,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFE2E8F0),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        game.capabilities.forEach { cap ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF107C10).copy(alpha = 0.25f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF107C10).copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = cap,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF4ADE80),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Publisher & Genre
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Desarrollador: ${game.publisher}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            text = "Género: ${game.genre}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    // Description
                    Text(
                        text = game.description.ifBlank { "Disfruta de este título optimizado para Xbox Cloud Gaming con soporte nativo de mando y alta tasa de refresco." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCBD5E1)
                    )

                    // Screenshots Gallery (if available)
                    if (game.screenshots.isNotEmpty()) {
                        Text(
                            text = "Capturas Oficiales",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(game.screenshots) { screenshotUrl ->
                                AsyncImage(
                                    model = screenshotUrl,
                                    contentDescription = "Captura de pantalla",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(220.dp)
                                        .height(125.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFF262A30), RoundedCornerShape(8.dp))
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // START STREAM Button
                    Button(
                        onClick = { onStartStream(game.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("modal_start_stream_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))
                    ) {
                        Icon(Icons.Default.VideogameAsset, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "START STREAM (xCloud Series X)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
