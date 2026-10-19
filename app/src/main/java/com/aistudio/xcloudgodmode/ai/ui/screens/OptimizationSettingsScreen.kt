package com.aistudio.xcloudgodmode.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aistudio.xcloudgodmode.ai.ui.viewmodels.OptimizationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizationSettingsScreen(
    viewModel: OptimizationViewModel,
    onBack: () -> Unit
) {
    var selectedProfile by remember { mutableStateOf("God Mode") }
    val recommendation by viewModel.recommendation.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.fetchRecommendation("Forza Horizon 5", "5G/Wi-Fi 6")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game Optimization") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Performance Profiles",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val profiles = listOf(
                OptimizationProfile(
                    name = "Battery Saver",
                    description = "Lower bitrate and frame rate to save power.",
                    icon = Icons.Default.Eco
                ),
                OptimizationProfile(
                    name = "Balanced",
                    description = "Default settings for stable gameplay.",
                    icon = Icons.Default.Speed
                ),
                OptimizationProfile(
                    name = "God Mode",
                    description = "Max performance, low latency, and upscaling.",
                    icon = Icons.Default.Bolt
                )
            )

            items(profiles) { profile ->
                ProfileCard(
                    profile = profile,
                    isSelected = selectedProfile == profile.name,
                    onSelect = { selectedProfile = profile.name }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                AIRecommendationCard(recommendation)
            }
        }
    }
}

data class OptimizationProfile(
    val name: String,
    val description: String,
    val icon: ImageVector
)

@Composable
fun ProfileCard(
    profile: OptimizationProfile,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_${profile.name.lowercase().replace(" ", "_")}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected) CardDefaults.outlinedCardBorder() else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = profile.icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else null
                )
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AIRecommendationCard(recommendation: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "AI Recommendation (Gemini)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = recommendation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}
