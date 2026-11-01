package com.xcloud.godmode.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.xcloud.godmode.models.DeviceType
import com.xcloud.godmode.models.GameModel
import com.xcloud.godmode.models.SettingsModel
import com.xcloud.godmode.services.StreamSessionHolder
import com.xcloud.godmode.network.XboxFullAuthManager
import com.xcloud.godmode.ui.components.*
import com.xcloud.godmode.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    authManager: XboxFullAuthManager,
    onLaunchGame: (String, String) -> Unit,
    onLaunchConsole: (String) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val userSession by viewModel.userSession.collectAsState()
    val isAuthModalOpen by viewModel.isAuthModalOpen.collectAsState()
    val selectedGameForDetails by viewModel.selectedGameForDetails.collectAsState()
    val postStreamSummary by viewModel.postStreamSummary.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val chameleonColor by viewModel.chameleonColor.collectAsState()
    val consoles by viewModel.consoles.collectAsState()
    val savedAccounts by viewModel.savedAccounts.collectAsState()
    val isMicMuted by viewModel.isMicMuted.collectAsState()
    val activeController by viewModel.activeController.collectAsState()
    val bestServerSuggestion by viewModel.bestServerSuggestion.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val strings = com.xcloud.godmode.ui.theme.AppStrings.get(settings.appLanguage)
    val tabs = listOf(strings.xcloudTab, strings.consolesTab, strings.settingsTab)

    val config = LocalConfiguration.current
    val deviceType = viewModel.determineDeviceType(config.screenWidthDp)
    val columnsCount = viewModel.getGridColumnsForDevice(deviceType)

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var isBladeOpen by remember { mutableStateOf(false) }

    // Sound Engine Layer Sync
    LaunchedEffect(Unit) {
        com.xcloud.godmode.audio.GodModeSoundEngine.currentInputLayer = com.xcloud.godmode.audio.ActiveInputLayer.COMPOSE_HOME
    }

    // Key event listener for Gamepad (Tab Switching)
    val onGamepadKeyEvent: (KeyEvent) -> Boolean = { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_L1 -> {
                    selectedTab = (selectedTab - 1 + tabs.size) % tabs.size
                    com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.TAB)
                    true
                }
                android.view.KeyEvent.KEYCODE_BUTTON_R1 -> {
                    selectedTab = (selectedTab + 1) % tabs.size
                    com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.TAB)
                    true
                }
                android.view.KeyEvent.KEYCODE_BUTTON_Y -> {
                    isBladeOpen = !isBladeOpen
                    com.xcloud.godmode.audio.GodModeSoundEngine.play(
                        if (isBladeOpen) com.xcloud.godmode.audio.SoundEventType.BLADE_OPEN else com.xcloud.godmode.audio.SoundEventType.BLADE_CLOSE
                    )
                    true
                }
                else -> false
            }
        } else false
    }

    BackHandler(enabled = isBladeOpen || selectedGameForDetails != null || postStreamSummary != null) {
        if (selectedGameForDetails != null) {
            viewModel.hideDetails()
        } else if (isBladeOpen) {
            isBladeOpen = false
        } else if (postStreamSummary != null) {
            viewModel.hideSummary()
        }
    }

    Box(modifier = Modifier.fillMaxSize().onKeyEvent(onGamepadKeyEvent)) {
        // Background
        GodModeCinemaBackground(
            heroUrl = selectedGameForDetails?.getFormattedSuperHeroUrl() ?: "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1551360/library_hero.jpg",
            accentColor = chameleonColor,
            enableWave = settings.enableXmbWaveAnimation,
            enableKenBurns = settings.enableKenBurnsHero
        )

        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                GodModeCollapsibleTopBar(
                    scrollBehavior = scrollBehavior,
                    chameleonColor = chameleonColor,
                    deviceType = deviceType,
                    settings = settings,
                    bestServerSuggestion = bestServerSuggestion,
                    isMicMuted = isMicMuted,
                    onToggleMute = { viewModel.toggleMicMute() },
                    onOpenBlade = { isBladeOpen = true },
                    userSession = userSession,
                    savedAccounts = savedAccounts,
                    onLoginClick = { viewModel.showAuth(true) },
                    onLogoutClick = { viewModel.logout() },
                    onSwitchAccount = { viewModel.switchAccount(it, authManager) }
                )
            },
            bottomBar = {
                Column {
                    // Unified Navigation Bar (Solución Púrpura/Azul integrada en scroll)
                    NavigationBar(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        contentColor = Color.White,
                        modifier = Modifier.height(64.dp)
                    ) {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { 
                                    selectedTab = index 
                                    com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.TAB)
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
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = chameleonColor,
                                    indicatorColor = chameleonColor.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                    // Brand-aware Contextual Prompts (Solución Celeste)
                    GamepadContextualPromptsBar(controller = activeController)
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                when (selectedTab) {
                    0 -> XCloudLibraryTab(
                        viewModel = viewModel,
                        columnsCount = columnsCount,
                        onGameSelected = { game -> viewModel.showDetails(game) },
                        onLaunchGame = onLaunchGame,
                        settings = settings,
                        chameleonColor = chameleonColor
                    )
                    1 -> ConsolesTab(
                        consoles = consoles,
                        onLaunchConsole = onLaunchConsole,
                        authManager = authManager,
                        viewModel = viewModel
                    )
                    2 -> AdvancedSettingsTab(
                        settings = settings,
                        onSettingsChanged = { viewModel.setSettings(it) }
                    )
                }
            }
        }

        // Modals
        if (selectedGameForDetails != null) {
            GameDetailsModal(
                game = selectedGameForDetails!!,
                settings = settings,
                onClose = { viewModel.hideDetails() },
                onStartStream = {
                    viewModel.hideDetails()
                    com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.LAUNCH)
                    val targetRegion = userSession?.xCloudRegionUri?.ifBlank { "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user" } ?: "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user"
                    onLaunchGame(selectedGameForDetails!!.id, targetRegion)
                }
            )
        }

        if (isAuthModalOpen) {
            DualAuthModal(
                authManager = authManager,
                homeViewModel = viewModel,
                settings = settings,
                onDismiss = { viewModel.showAuth(false) },
                onSuccess = { session -> viewModel.setSession(session, authManager) }
            )
        }

        if (postStreamSummary != null) {
            PostStreamPerformanceModal(
                summary = postStreamSummary!!,
                onClose = { viewModel.hideSummary() },
                onQuickReconnect = {
                    viewModel.hideSummary()
                    val targetPath = StreamSessionHolder.activeSessionPath.ifBlank { "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user" }
                    val targetTitle = StreamSessionHolder.activeGame?.id ?: "9PNLT98ZLR9W"
                    onLaunchGame(targetTitle, targetPath)
                }
            )
        }

        val recommendation by viewModel.recommendation.collectAsState()
        val isAnalyzing by viewModel.isAnalyzing.collectAsState()
        XboxBladeGuide(
            isOpen = isBladeOpen,
            onClose = { isBladeOpen = false },
            settings = settings,
            onSettingsChanged = { viewModel.setSettings(it) },
            accentColor = chameleonColor,
            onApplyPreset = { viewModel.setPreset(it) },
            recommendation = recommendation,
            isAnalyzing = isAnalyzing,
            onReAnalyze = { viewModel.getAiTip(selectedGameForDetails?.title ?: "Xbox Game", "Wi-Fi 6") },
            onTestHaptics = { viewModel.testHaptics() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GodModeCollapsibleTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    chameleonColor: Color,
    deviceType: DeviceType,
    settings: SettingsModel,
    bestServerSuggestion: String?,
    isMicMuted: Boolean,
    onToggleMute: () -> Unit,
    onOpenBlade: () -> Unit,
    userSession: XboxFullAuthManager.XboxReadySession?,
    savedAccounts: List<com.xcloud.godmode.network.XboxSessionProfile>,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onSwitchAccount: (String) -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = chameleonColor, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("God Mode Hub", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Badge(containerColor = chameleonColor.copy(alpha = 0.2f)) {
                    Text(deviceType.name, style = MaterialTheme.typography.labelSmall, color = chameleonColor, modifier = Modifier.padding(horizontal = 4.dp))
                }
                if (bestServerSuggestion != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(containerColor = Color(0xFF38BDF8).copy(alpha = 0.2f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Icon(Icons.Default.Speed, null, modifier = Modifier.size(10.dp), tint = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Best: $bestServerSuggestion", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8))
                        }
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleMute) {
                Icon(if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic, "Mute", tint = if (isMicMuted) Color.Red else chameleonColor)
            }
            IconButton(onClick = onOpenBlade) {
                Icon(Icons.Default.Bolt, "Optimizer", tint = chameleonColor)
            }
            UserAccountHeaderButton(
                userSession = userSession,
                savedAccounts = savedAccounts,
                onLoginClick = onLoginClick,
                onLogoutClick = onLogoutClick,
                onSwitchAccount = onSwitchAccount,
                modifier = Modifier.padding(end = 8.dp)
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f)),
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun GamepadContextualPromptsBar(controller: com.xcloud.godmode.viewmodels.HomeViewModel.ControllerInfo?) {
    AnimatedVisibility(
        visible = controller != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val name = controller?.name?.lowercase() ?: ""
            when {
                name.contains("ps") || name.contains("sony") || name.contains("dual") -> {
                    ContextualPrompt(badge = "✕", color = Color(0xFF00439C), label = "Detalles")
                    ContextualPrompt(badge = "R2", color = Color(0xFF00D185), label = "Jugar")
                    ContextualPrompt(badge = "□", color = Color(0xFFD36FBB), label = "Favorito")
                    ContextualPrompt(badge = "△", color = Color(0xFF22B573), label = "Buscar")
                }
                name.contains("switch") || name.contains("nintendo") || name.contains("pro") -> {
                    ContextualPrompt(badge = "A", color = Color(0xFFE60012), label = "Detalles")
                    ContextualPrompt(badge = "ZR", color = Color.White, label = "Jugar")
                    ContextualPrompt(badge = "X", color = Color.Gray, label = "Favorito")
                    ContextualPrompt(badge = "Y", color = Color.Gray, label = "Buscar")
                }
                else -> { // Xbox / Standard
                    ContextualPrompt(badge = "A", color = Color(0xFF107C10), label = "Detalles")
                    ContextualPrompt(badge = "ZR", color = Color(0xFF00E676), label = "Jugar")
                    ContextualPrompt(badge = "X", color = Color(0xFF005A9E), label = "Favorito")
                    ContextualPrompt(badge = "Y", color = Color(0xFFFFB900), label = "Buscar")
                }
            }
        }
    }
}

@Composable
fun ContextualPrompt(badge: String, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(color, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(badge, color = if (color == Color.White) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
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
    val mruGames by viewModel.mruGames.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var focusedIndex by remember { mutableIntStateOf(0) }
    var selectedFilterCategory by remember { mutableStateOf("Todos") }
    val displayGames = remember(games, selectedFilterCategory, mruGames) {
        when (selectedFilterCategory) {
            "Recientes" -> if (mruGames.isNotEmpty()) mruGames else games
            "Series X|S" -> games.filter { it.capabilities.any { c -> c.contains("Series", ignoreCase = true) } || it.genre.contains("Action", ignoreCase = true) }
            "Multijugador" -> games.filter { it.capabilities.any { c -> c.contains("Multiplayer", ignoreCase = true) } || it.description.contains("Multiplayer", ignoreCase = true) }
            "Juegos Propios (BYOG)" -> games.filter { it.isOwned || !it.isGamePass }
            else -> games
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnsCount),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = 0.99f)
            .drawWithContent {
                drawContent()
                // Cinematic Top-Down Fade (Smooth blend under top bar)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.15f to Color.Black
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        // Hero Carousel (Moved above Search per video request)
        if (searchQuery.isBlank() && displayGames.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val featured = displayGames.take(6).map { g ->
                    CarouselFeaturedItem(g.id, g.title, g.description, g.genre, g.getFormattedSuperHeroUrl(1920, 1080), g.rating)
                }
                AdaptiveHeroCarousel(items = featured, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { f ->
                    games.find { it.id == f.id }?.let { onGameSelected(it) }
                }
            }
        }

        // Search & Filter integrated into the grid (Scrolls away naturally)
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.search(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search 4K library...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = chameleonColor) },
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.4f),
                        focusedIndicatorColor = chameleonColor,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val filterCategories = listOf("Todos", "Recientes", "Series X|S", "Multijugador", "Juegos Propios (BYOG)")
                    this.items(filterCategories) { category ->
                        FilterChip(
                            selected = selectedFilterCategory == category,
                            onClick = { selectedFilterCategory = category },
                            label = { Text(category, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chameleonColor.copy(alpha = 0.3f),
                                selectedLabelColor = Color.White
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedFilterCategory == category,
                                borderColor = if (selectedFilterCategory == category) chameleonColor else Color.White.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
            }
        }

        // Grid Items
        items(displayGames, key = { it.id }) { game ->
            GamePosterCard(
                game = game,
                onFavoriteToggle = { viewModel.fav(game.id) },
                onClick = { onGameSelected(game) },
                accentColor = chameleonColor,
                settings = settings
            )
        }
    }
}


@Composable
fun GamePosterCard(
    game: GameModel,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit,
    accentColor: Color,
    settings: SettingsModel,
    modifier: Modifier = Modifier
) {
    val screenScale = com.xcloud.godmode.ui.theme.LocalScreenScale.current
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.05f else 1.0f, label = "card_scale")

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
                    else if (settings.enableLeanbackFocus) Color(0xFF107C10)
                    else accentColor
                } else Color.White.copy(alpha = 0.1f),
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
                    com.xcloud.godmode.audio.GodModeSoundEngine.play(com.xcloud.godmode.audio.SoundEventType.FAVORITE)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(screenScale.scaledDp(32f))
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (game.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (game.isFavorite) Color.Yellow else Color.White,
                    modifier = Modifier.size(screenScale.scaledDp(18f))
                )
            }
        }
    }
}
