package com.aistudio.xcloudgodmode.ai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aistudio.xcloudgodmode.ai.models.DeviceType
import com.aistudio.xcloudgodmode.ai.models.GameModel
import com.aistudio.xcloudgodmode.ai.models.SettingsModel
import com.aistudio.xcloudgodmode.ai.services.StreamSessionHolder
import com.aistudio.xcloudgodmode.ai.services.XboxFullAuthManager
import com.aistudio.xcloudgodmode.ai.ui.components.*
import com.aistudio.xcloudgodmode.ai.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    authManager: XboxFullAuthManager,
    onLaunchGame: (String, String) -> Unit,
    onLaunchConsole: (String) -> Unit,
    onOpenOptimization: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("xCloud Library", "My Consoles", "Advanced Settings")

    val config = LocalConfiguration.current
    val deviceType = viewModel.determineDeviceType(config.screenWidthDp)
    val columnsCount = viewModel.getGridColumnsForDevice(deviceType)

    val userSession by viewModel.userSession.collectAsState()
    val isAuthModalOpen by viewModel.isAuthModalOpen.collectAsState()
    val selectedGameForDetails by viewModel.selectedGameForDetails.collectAsState()
    val postStreamSummary by viewModel.postStreamSummary.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val chameleonColor by viewModel.chameleonColor.collectAsState()

    var isBladeOpen by remember { mutableStateOf(false) }

    // Sound Engine Initialization and Layer Sync
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.init(context)
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.currentInputLayer = com.aistudio.xcloudgodmode.ai.audio.ActiveInputLayer.COMPOSE_HOME
    }

    // Sync Chameleon Pitch Modulation
    LaunchedEffect(chameleonColor) {
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.updateChameleonPitch(
            (chameleonColor.red * 255).toInt(),
            (chameleonColor.green * 255).toInt(),
            (chameleonColor.blue * 255).toInt()
        )
    }

    // Key event listener for Gamepad
    val onGamepadKeyEvent: (KeyEvent) -> Boolean = { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_L1 -> {
                    selectedTab = (selectedTab - 1 + tabs.size) % tabs.size
                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.TAB)
                    true
                }
                android.view.KeyEvent.KEYCODE_BUTTON_R1 -> {
                    selectedTab = (selectedTab + 1) % tabs.size
                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.TAB)
                    true
                }
                android.view.KeyEvent.KEYCODE_BUTTON_Y -> {
                    isBladeOpen = !isBladeOpen
                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(
                        if (isBladeOpen) com.aistudio.xcloudgodmode.ai.audio.SoundEventType.BLADE_OPEN else com.aistudio.xcloudgodmode.ai.audio.SoundEventType.BLADE_CLOSE
                    )
                    true
                }
                else -> false
            }
        } else false
    }

    Box(modifier = Modifier.fillMaxSize().onKeyEvent(onGamepadKeyEvent)) {
        
        // 1. Inmersive Background (Ken Burns + Wave)
        PlayStaxBoxBackground(
            heroUrl = selectedGameForDetails?.getFormattedSuperHeroUrl() ?: "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1551360/library_hero.jpg",
            accentColor = chameleonColor,
            enableWave = settings.enableXmbWaveAnimation,
            enableKenBurns = settings.enableKenBurnsHero
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent, // Let the background show
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = chameleonColor,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "God Mode Hub",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(containerColor = chameleonColor.copy(alpha = 0.2f)) {
                                Text(
                                    text = deviceType.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chameleonColor,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { isBladeOpen = true }) {
                            Icon(Icons.Default.Bolt, contentDescription = "Optimizer", tint = chameleonColor)
                        }
                        
                        UserAccountHeaderButton(
                            userSession = userSession,
                            onLoginClick = { viewModel.setAuthModalOpen(true) },
                            onLogoutClick = { viewModel.logout() },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        
                        IconButton(onClick = onOpenOptimization) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Optimization Center",
                                tint = chameleonColor
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f))
                )
            },
            bottomBar = {
                if (deviceType == DeviceType.PHONE) {
                    NavigationBar(containerColor = Color.Black.copy(alpha = 0.8f)) {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { 
                                    selectedTab = index 
                                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.TAB)
                                },
                                icon = {
                                    Icon(
                                        imageVector = when (index) {
                                            0 -> Icons.Default.Cloud
                                            1 -> Icons.Default.Computer
                                            else -> Icons.Default.Settings
                                        },
                                        contentDescription = title
                                    )
                                },
                                label = { Text(title) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = chameleonColor,
                                    indicatorColor = chameleonColor.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                // Navigation Rail for Tablet/TV
                if (deviceType != DeviceType.PHONE) {
                    NavigationRail(
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        contentColor = Color.White,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        tabs.forEachIndexed { index, title ->
                            NavigationRailItem(
                                selected = selectedTab == index,
                                onClick = { 
                                    selectedTab = index 
                                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.TAB)
                                },
                                icon = {
                                    Icon(
                                        imageVector = when (index) {
                                            0 -> Icons.Default.Cloud
                                            1 -> Icons.Default.Computer
                                            else -> Icons.Default.Settings
                                        },
                                        contentDescription = title
                                    )
                                },
                                label = { Text(title, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = chameleonColor,
                                    indicatorColor = chameleonColor.copy(alpha = 0.2f)
                                )
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                
                // Desktop/Tablet Tab Row
                if (deviceType != DeviceType.PHONE) {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        contentColor = chameleonColor
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { 
                                    selectedTab = index 
                                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.TAB)
                                },
                                text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (selectedTab) {
                            0 -> XCloudLibraryTab(
                                viewModel = viewModel,
                                columnsCount = columnsCount,
                                onGameSelected = { game -> viewModel.openGameDetails(game) },
                                onLaunchGame = onLaunchGame,
                                settings = settings,
                                chameleonColor = chameleonColor
                            )
                            1 -> ConsolesTab(onLaunchConsole)
                            2 -> AdvancedSettingsTab(
                                settings = settings,
                                onSettingsChanged = { viewModel.updateSettings(it) },
                                onOpenOptimizationCenter = onOpenOptimization
                            )
                        }
                    }

                    if (deviceType == DeviceType.TV) {
                        PeopleHubSidebar()
                    }
                }
                }
            }
        }

            XboxBladeGuide(
                isOpen = isBladeOpen,
                onClose = { 
                    isBladeOpen = false 
                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.BLADE_CLOSE)
                },
                settings = settings,
                onSettingsChanged = { viewModel.updateSettings(it) },
                accentColor = chameleonColor,
                onApplyPreset = { 
                    viewModel.applyPreset(it) 
                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.PROFILE_SWITCH)
                }
            )

        // Modals
        if (selectedGameForDetails != null) {
            GameDetailsModal(
                game = selectedGameForDetails!!,
                onClose = { viewModel.closeGameDetails() },
                onStartStream = {
                    viewModel.closeGameDetails()
                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.LAUNCH)
                    val targetRegion = userSession?.xCloudRegionUri?.ifBlank { "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user" } ?: "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user"
                    onLaunchGame(selectedGameForDetails!!.id, targetRegion)
                }
            )
        }

        if (isAuthModalOpen) {
            DualAuthModal(
                authManager = authManager,
                onDismiss = { viewModel.setAuthModalOpen(false) },
                onSuccess = { session -> viewModel.setUserSession(session) }
            )
        }

        if (postStreamSummary != null) {
            PostStreamPerformanceModal(
                summary = postStreamSummary!!,
                onClose = { viewModel.dismissPostStreamModal() },
                onQuickReconnect = {
                    viewModel.dismissPostStreamModal()
                    val targetPath = StreamSessionHolder.activeSessionPath.ifBlank { "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user" }
                    val targetTitle = StreamSessionHolder.activeGame?.id ?: "9PNLT98ZLR9W"
                    onLaunchGame(targetTitle, targetPath)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XCloudLibraryTab(
    viewModel: HomeViewModel,
    columnsCount: Int,
    onGameSelected: (GameModel) -> Unit,
    onLaunchGame: (String, String) -> Unit,
    settings: SettingsModel,
    chameleonColor: Color
) {
    val games by viewModel.filteredGames.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoadingCatalog.collectAsState()

    var focusedIndex by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        
        // Search Bar (Translucent)
        TextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search 4K library...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = chameleonColor) },
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.4f),
                focusedIndicatorColor = chameleonColor,
                unfocusedIndicatorColor = Color.Transparent
            )
        )

        if (settings.homeLayoutStyle == "PlayStaxBox Hybrid" && games.isNotEmpty()) {
            // PlayStaxBox Hybrid View (Ribbon + Grid)
            PlayStaxBoxRibbon(
                games = games.take(12), // Featured Ribbon
                focusedIndex = focusedIndex,
                onGameFocused = { 
                    focusedIndex = it
                    viewModel.deriveColorFromGame(games[it])
                },
                onGameSelected = onGameSelected,
                accentColor = chameleonColor
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = Color.White.copy(alpha = 0.1f))
            
            Text(
                "Full Catalog",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }

        // Adaptive Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnsCount),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(games, key = { it.id }) { game ->
                GamePosterCard(
                    game = game,
                    onFavoriteToggle = { viewModel.toggleFavorite(game.id) },
                    onClick = { onGameSelected(game) },
                    accentColor = chameleonColor
                )
            }
        }
    }
}

@Composable
fun GamePosterCard(
    game: GameModel,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit,
    accentColor: Color
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.05f else 1.0f, label = "card_scale")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusable()
            .onFocusChanged { 
                isFocused = it.isFocused 
                if (it.isFocused) {
                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.FOCUS)
                }
            }
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) accentColor else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.DarkGray)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val posterWidth = if (LocalConfiguration.current.screenWidthDp >= 768) 600 else 320
            val posterHeight = if (LocalConfiguration.current.screenWidthDp >= 768) 900 else 480
            
            AsyncImage(
                model = game.getFormattedPosterUrl(posterWidth, posterHeight),
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            
            // Favorite Star
            IconButton(
                onClick = {
                    onFavoriteToggle()
                    com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.play(com.aistudio.xcloudgodmode.ai.audio.SoundEventType.FAVORITE)
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (game.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (game.isFavorite) Color.Yellow else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
