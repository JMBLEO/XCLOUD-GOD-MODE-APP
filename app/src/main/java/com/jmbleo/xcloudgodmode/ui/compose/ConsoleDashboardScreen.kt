package com.jmbleo.xcloudgodmode.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import com.aistudio.xcloudgodmode.ai.auth.XboxAuthManager
import com.aistudio.xcloudgodmode.ai.auth.XboxLoginDialog
import com.aistudio.xcloudgodmode.ai.ui.components.UserAccountHeaderButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmbleo.xcloudgodmode.config.GodModeConfig
import com.jmbleo.xcloudgodmode.config.GodModeConfigManager
import com.jmbleo.xcloudgodmode.config.ResponseCurve
import com.jmbleo.xcloudgodmode.config.ScalingProfile

data class GameCatalogItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val genre: String,
    val tagColor: Color,
    val badge: String = "X|S OPTIMIZED",
    val titleId: String,
    val directLaunchUrl: String = buildDirectLaunchUrl(titleId)
)

/**
 * Construye la URL de ejecución directa Deep Link para omitir el portal web de xCloud.
 * Formato oficial: https://www.xbox.com/play/launch/{titleId}
 */
fun buildDirectLaunchUrl(titleId: String): String {
    val cleanId = titleId.trim()
    return if (cleanId.startsWith("http://") || cleanId.startsWith("https://")) {
        cleanId
    } else {
        "https://www.xbox.com/play/launch/$cleanId"
    }
}

data class ConsoleDeviceItem(
    val id: String,
    val name: String,
    val ip: String,
    val status: String,
    val isOnline: Boolean = true
)

/**
 * Pantalla principal nativa de consola para XCloud God Mode.
 * Inspirada en las mejores interfaces de XStreaming, XFly y XBPlay.
 * Aísla completamente la experiencia de usuario: los juegos, carátulas, consolas y
 * ajustes avanzados se gestionan de forma nativa e independiente, reservando la web
 * exclusivamente para la autenticación y la sesión de streaming WebRTC.
 */
@Composable
fun ConsoleDashboardScreen(
    onLaunchGame: (url: String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onApplyRefreshRate: ((Float) -> Unit)? = null,
    onDetoxCache: (() -> Unit)? = null,
    onTestRumble: (() -> Unit)? = null,
    triggerAccountWarning: Boolean = false,
    onResetAccountWarning: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configManager = remember { GodModeConfigManager.getInstance(context) }
    var config by remember { mutableStateOf(configManager.getConfig()) }

    var hasXboxSession by remember { mutableStateOf(XboxAuthManager.hasActiveSession(context)) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var showAccountWarningDialog by remember { mutableStateOf(false) }
    var pendingLaunchUrl by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        hasXboxSession = XboxAuthManager.hasActiveSession(context)
    }

    // Reaccionar a avisos de sesión requerida activados desde el cliente web de streaming
    androidx.compose.runtime.LaunchedEffect(triggerAccountWarning) {
        if (triggerAccountWarning) {
            hasXboxSession = XboxAuthManager.hasActiveSession(context)
            showAccountWarningDialog = true
            onResetAccountWarning?.invoke()
        }
    }

    // Interceptor seguro: Si no está iniciada la sesión, guarda pendingLaunchUrl, lanza la advertencia y vuelve al Home sin cargar el stream
    val safeLaunchGame: (String) -> Unit = { targetUrl ->
        val sessionAvailable = XboxAuthManager.hasActiveSession(context)
        hasXboxSession = sessionAvailable
        if (!sessionAvailable) {
            pendingLaunchUrl = targetUrl
            showAccountWarningDialog = true
        } else {
            onLaunchGame(targetUrl)
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Todos") }
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var showAdvancedSettingsDialog by remember { mutableStateOf(false) }
    var showAddConsoleDialog by remember { mutableStateOf(false) }

    // Lista interactiva de consolas guardadas
    var consoles by remember {
        mutableStateOf(
            listOf(
                ConsoleDeviceItem("xsx_salon", "Xbox Series X (Salón)", "192.168.1.140", "En línea • Wi-Fi 5GHz (0ms)", true),
                ConsoleDeviceItem("xss_cuarto", "Xbox Series S (Dormitorio)", "192.168.1.142", "En espera • LAN 1 Gbps", true),
                ConsoleDeviceItem("xone_old", "Xbox One S (FD001A27514F4DE7)", "192.168.1.185", "En reposo • Nube", false)
            )
        )
    }

    // Catálogo nativo completo de Xbox Game Pass
    val catalog = remember {
        listOf(
            GameCatalogItem("fc26", "EA SPORTS FC™ 26", "Xbox Series X|S", "Deportes • 60 FPS", Color(0xFF1E3A24), "X|S OPTIMIZED", "9NK95VBDG6C2"),
            GameCatalogItem("forza6", "Forza Horizon 5", "Edición Premium", "Carreras • 4K HDR", Color(0xFF2C2415), "4K 60FPS", "9NKX70BBC2TN"),
            GameCatalogItem("gears", "Gears Tactics", "Campaña Completa", "Estrategia • 120 FPS", Color(0xFF381A1A), "120 FPS", "9NNFMGQW5QKA"),
            GameCatalogItem("halo", "Halo Infinite", "Multijugador / Campaña", "Shooter • 120 FPS", Color(0xFF162D24), "120 FPS", "9PP5G1F0C2B6"),
            GameCatalogItem("palworld", "Palworld", "Multijugador", "Supervivencia", Color(0xFF1A2E3B), "NUBE", "9NKV0V50P0F2"),
            GameCatalogItem("starfield", "Starfield", "Actualizado", "RPG Espacial", Color(0xFF1B2438), "60 FPS HQ", "9NCGJPH079DN"),
            GameCatalogItem("sea", "Sea of Thieves", "2026 Edition", "Aventura", Color(0xFF12332C), "CROSSPLAY", "9P545PB0CR1J"),
            GameCatalogItem("cyberpunk", "Cyberpunk 2077", "Edición Ultimate", "Acción RPG • Ray Tracing", Color(0xFF38192A), "HDR HQ", "9PNKX3RJVC9C"),
            GameCatalogItem("gta5", "Grand Theft Auto V", "Next-Gen Series X", "Mundo Abierto • 60 FPS", Color(0xFF2D2A18), "60 FPS", "9NX6BN031F50"),
            GameCatalogItem("cod", "Call of Duty: Black Ops", "Temporada Activa", "Acción Multijugador", Color(0xFF2A1C14), "120 FPS", "9NZ423B0QZ2D"),
            GameCatalogItem("skate3", "Skate 3", "Xbox 360", "Deportes Clásico", Color(0xFF383318), "RETRO", "C48ZFTFZG4CV"),
            GameCatalogItem("ark", "ARK: Survival Ascended", "Next-Gen", "Supervivencia", Color(0xFF163236), "X|S", "9P00W74J7J5R")
        )
    }

    val filteredGames = remember(searchQuery, selectedCategory) {
        catalog.filter { game ->
            val matchesSearch = game.title.contains(searchQuery, ignoreCase = true) ||
                    game.genre.contains(searchQuery, ignoreCase = true)
            val matchesCategory = when (selectedCategory) {
                "Todos" -> true
                "Favoritos" -> game.id in listOf("fc26", "forza6", "halo", "starfield")
                "Nube (xCloud)" -> true
                "Juego Remoto" -> game.id in listOf("fc26", "forza6", "gta5", "cod")
                "60 / 120 FPS" -> game.genre.contains("60 FPS") || game.genre.contains("120 FPS")
                else -> true
            }
            matchesSearch && matchesCategory
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B09))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // 1. Barra de Usuario & Estado Nativo (Inspirada en XFly / XStreaming)
            item {
                DashboardHeader(
                    gamertag = if (hasXboxSession) "JMB Nel" else "Invitado",
                    subscription = if (hasXboxSession) "Ultimate" else "Sin Sesión",
                    region = "🇨🇱 CHL (18ms)",
                    isLoggedIn = hasXboxSession,
                    onLoginClick = { showLoginDialog = true },
                    onLogoutClick = {
                        XboxAuthManager.clearSession(context = context, clearWebStorage = false) {
                            hasXboxSession = false
                            pendingLaunchUrl = null
                        }
                    },
                    onSettingsClick = { showAdvancedSettingsDialog = true },
                    onCalibrationClick = { showCalibrationDialog = true },
                    onDetoxClick = { onDetoxCache?.invoke() }
                )
            }

            // 2. Hero Card Principal "Continuar Partida" (Inspirado en XFly)
            item {
                FeaturedHeroCard(
                    title = "EA SPORTS FC™ 26",
                    subtitle = "Xbox Series X|S • Modo Carrera Activo",
                    tag = "CONTINUAR PARTIDA",
                    onPlayClick = { safeLaunchGame(buildDirectLaunchUrl("9NK95VBDG6C2")) }
                )
            }

            // 3. Modos Rápidos (Estilo Neón Cyberpunk de XBPlay / XStreaming)
            item {
                ModeQuickAccessRow(
                    onCloudClick = { safeLaunchGame(buildDirectLaunchUrl("9NKX70BBC2TN")) },
                    onRemotePlayClick = { safeLaunchGame(buildDirectLaunchUrl("9NK95VBDG6C2")) },
                    onCalibrationClick = { showCalibrationDialog = true },
                    onAdvancedSettingsClick = { showAdvancedSettingsDialog = true }
                )
            }

            // 4. Barra de Búsqueda y Filtros de Categoría
            item {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                "Buscar títulos, géneros o consolas...",
                                color = Color(0xFF80FFFFFF),
                                fontSize = 14.sp
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color(0xFF52E172))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Limpiar", tint = Color.White)
                                }
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF101913),
                            unfocusedContainerColor = Color(0xFF101913),
                            focusedIndicatorColor = Color(0xFF52E172),
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF2652E172), RoundedCornerShape(16.dp))
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    val categories = listOf("Todos", "Favoritos", "Nube (xCloud)", "Juego Remoto", "60 / 120 FPS")
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        categories.forEach { cat ->
                            val isSelected = cat == selectedCategory
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF52E172) else Color(0xFF142018))
                                    .clickable { selectedCategory = cat }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = cat,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // 5. Catálogo Horizontal "Biblioteca Game Pass"
            item {
                SectionHeader(title = "Juegos de la Nube & Instalados", counter = "${filteredGames.size} títulos disponibles")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(filteredGames) { game ->
                        GamePosterCard(game = game, onPlay = { safeLaunchGame(game.directLaunchUrl) })
                    }
                }
            }

            // 6. Sección de Consolas Remotas Locales (XStreaming)
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Juego Remoto en Consolas", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { showAddConsoleDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF162B1D)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF52E172), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Añadir Consola", color = Color(0xFF52E172), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                consoles.forEach { console ->
                    ConsoleDeviceCard(
                        device = console,
                        onConnect = { safeLaunchGame(buildDirectLaunchUrl("console/${console.id}")) }
                    )
                }
            }
        }

        // Modal de Calibración de Sticks
        if (showCalibrationDialog) {
            CalibrationDialog(
                currentConfig = config,
                onDismiss = { showCalibrationDialog = false },
                onSave = { updated ->
                    config = updated
                    configManager.updateConfig(updated)
                    showCalibrationDialog = false
                },
                onTestRumble = onTestRumble
            )
        }

        // Modal de Ajustes Avanzados de la App
        if (showAdvancedSettingsDialog) {
            AdvancedSettingsDialog(
                currentConfig = config,
                onDismiss = { showAdvancedSettingsDialog = false },
                onSave = { updated ->
                    config = updated
                    configManager.updateConfig(updated)
                    onApplyRefreshRate?.invoke(updated.preferredRefreshRate)
                    showAdvancedSettingsDialog = false
                },
                onDetoxCache = onDetoxCache,
                onTestRumble = onTestRumble
            )
        }

        // Modal para añadir consola manualmente
        if (showAddConsoleDialog) {
            AddConsoleDialog(
                onDismiss = { showAddConsoleDialog = false },
                onAdd = { name, ip ->
                    consoles = consoles + ConsoleDeviceItem(
                        id = "console_${System.currentTimeMillis()}",
                        name = name,
                        ip = ip,
                        status = "Configurada • $ip",
                        isOnline = true
                    )
                    showAddConsoleDialog = false
                }
            )
        }

        // Diálogo oficial de inicio de sesión de Xbox Live / Microsoft
        if (showLoginDialog) {
            XboxLoginDialog(
                onDismiss = {
                    showLoginDialog = false
                },
                onAuthenticated = {
                    val saved = XboxAuthManager.saveSession(context)
                    hasXboxSession = saved
                    showLoginDialog = false
                    showAccountWarningDialog = false

                    if (saved) {
                        pendingLaunchUrl?.also(onLaunchGame)
                        pendingLaunchUrl = null
                    }
                }
            )
        }

        // Advertencia cuando se intenta iniciar un juego sin sesión activa de Xbox Live
        if (showAccountWarningDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    showAccountWarningDialog = false
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Problemas con la Cuenta",
                        tint = Color(0xFFFFB900),
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "Problemas con la Cuenta de Xbox",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                text = {
                    Text(
                        text = "No se ha detectado una sesión activa de Xbox Live o tus credenciales han caducado.\n\nPara iniciar el stream de juegos en la nube con XCloud God Mode y acceder a tus partidas guardadas, debes iniciar sesión con tu cuenta de Microsoft.",
                        color = Color(0xFFDDDDDD),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAccountWarningDialog = false
                            showLoginDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Iniciar Sesión Ahora", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showAccountWarningDialog = false
                            pendingLaunchUrl = null
                        }
                    ) {
                        Text("Volver al Inicio", color = Color.LightGray)
                    }
                },
                containerColor = Color(0xFF1A1A1A),
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun DashboardHeader(
    gamertag: String,
    subscription: String,
    region: String,
    isLoggedIn: Boolean,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCalibrationClick: () -> Unit,
    onDetoxClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E3A24))
                        .border(2.dp, if (isLoggedIn) Color(0xFF52E172) else Color(0xFF666666), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎮", fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = gamertag,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isLoggedIn) Color(0xFF1A52E172) else Color(0xFF282828))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = subscription,
                                color = if (isLoggedIn) Color(0xFF52E172) else Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "XCloud God Mode • $region",
                        color = Color(0xFF80FFFFFF),
                        fontSize = 11.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onDetoxClick,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF142217))
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = "Detox Caché", tint = Color(0xFF52E172))
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onCalibrationClick,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF142217))
                ) {
                    Icon(Icons.Default.Gamepad, contentDescription = "Calibrar Mando", tint = Color(0xFF52E172))
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF142217))
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Ajustes Avanzados", tint = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Botón interactivo de Cuenta / Inicio de Sesión
        UserAccountHeaderButton(
            isConnected = isLoggedIn,
            onLoginClick = onLoginClick,
            onLogoutClick = onLogoutClick
        )
    }
}

@Composable
private fun FeaturedHeroCard(
    title: String,
    subtitle: String,
    tag: String,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF14301D), Color(0xFF0F1E14), Color(0xFF0A120D))
                    )
                )
                .border(1.5.dp, Color(0xFF3852E172), RoundedCornerShape(22.dp))
                .padding(24.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF52E172))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tag,
                        color = Color(0xFF52E172),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 28.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color(0xFFB3FFFFFF),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "1080p HQ • FSR 1 Habilitado • Latencia de Red 0ms",
                    color = Color(0xFF80FFFFFF),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF52E172),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("JUGAR AHORA", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ModeQuickAccessRow(
    onCloudClick: () -> Unit,
    onRemotePlayClick: () -> Unit,
    onCalibrationClick: () -> Unit,
    onAdvancedSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CyberButton(
            title = "NUBE XCLOUD",
            subtitle = "Game Pass Stream",
            icon = Icons.Default.Cloud,
            tint = Color(0xFF52E172),
            modifier = Modifier.weight(1f),
            onClick = onCloudClick
        )
        CyberButton(
            title = "JUEGO REMOTO",
            subtitle = "Consola Series X|S",
            icon = Icons.Default.Cast,
            tint = Color(0xFF38A2EC),
            modifier = Modifier.weight(1f),
            onClick = onRemotePlayClick
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CyberButton(
            title = "MANDO Y STICKS",
            subtitle = "Deadzones 0ms",
            icon = Icons.Default.Gamepad,
            tint = Color(0xFFECDE38),
            modifier = Modifier.weight(1f),
            onClick = onCalibrationClick
        )
        CyberButton(
            title = "AJUSTES APP",
            subtitle = "FSR / Red / 120Hz",
            icon = Icons.Default.Tune,
            tint = Color(0xFFE74040),
            modifier = Modifier.weight(1f),
            onClick = onAdvancedSettingsClick
        )
    }
}

@Composable
private fun CyberButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF101913))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = Color(0xFF80FFFFFF),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun GamePosterCard(
    game: GameCatalogItem,
    onPlay: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(155.dp)
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111913))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(game.tagColor, Color(0xFF0C130E))
                        )
                    )
                    .padding(12.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = game.badge,
                            color = Color(0xFF52E172),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF52E172)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Jugar",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = game.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = game.genre,
                    color = Color(0xFF80FFFFFF),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ConsoleDeviceCard(
    device: ConsoleDeviceItem,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101913))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2652E172), RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF192A1E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Gamepad, contentDescription = null, tint = Color(0xFF52E172))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(device.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(device.status, color = if (device.isOnline) Color(0xFF52E172) else Color(0xFF80FFFFFF), fontSize = 11.sp)
                }
            }

            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B3822)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Conectar", color = Color(0xFF52E172), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, counter: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (counter != null) {
            Text(counter, color = Color(0xFF52E172), fontSize = 12.sp)
        }
    }
}

/**
 * Modal de Ajustes Avanzados de la App (independiente de los ajustes del script web).
 * Permite configurar red, bloqueos de latencia, tasa de refresco, resoluciones y audio.
 */
@Composable
private fun AdvancedSettingsDialog(
    currentConfig: GodModeConfig,
    onDismiss: () -> Unit,
    onSave: (GodModeConfig) -> Unit,
    onDetoxCache: (() -> Unit)?,
    onTestRumble: (() -> Unit)?
) {
    var selectedTab by remember { mutableStateOf("Red & Vídeo") }

    var lowLatencyWifi by remember { mutableStateOf(currentConfig.lowLatencyWifi) }
    var cpuWakeLock by remember { mutableStateOf(currentConfig.cpuWakeLock) }
    var refreshRate by remember { mutableFloatStateOf(currentConfig.preferredRefreshRate) }
    var resolution by remember { mutableStateOf(currentConfig.preferredResolution) }
    var codec by remember { mutableStateOf(currentConfig.preferredCodec) }
    var audioLowLatency by remember { mutableStateOf(currentConfig.audioLowLatency) }

    var scalingProfile by remember { mutableStateOf(currentConfig.scalingProfile) }
    var sharpness by remember { mutableStateOf(currentConfig.sharpness) }

    var swapAB by remember { mutableStateOf(currentConfig.swapAB) }
    var swapXY by remember { mutableStateOf(currentConfig.swapXY) }
    var comboMenuEnabled by remember { mutableStateOf(currentConfig.comboMenuEnabled) }
    var responseCurve by remember { mutableStateOf(currentConfig.responseCurve) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        color = Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101913))
            ) {
                Column(
                    modifier = Modifier
                        .border(1.5.dp, Color(0xFF52E172), RoundedCornerShape(24.dp))
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "⚡ Ajustes Avanzados de la App",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Pestañas de categoría de ajustes
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Red & Vídeo", "Controles & Háptica", "Mantenimiento").forEach { tab ->
                            val isSel = tab == selectedTab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) Color(0xFF52E172) else Color(0xFF16251B))
                                    .clickable { selectedTab = tab }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tab,
                                    color = if (isSel) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    when (selectedTab) {
                        "Red & Vídeo" -> {
                            // 1. Wi-Fi & WakeLock
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Bloqueo Wi-Fi Baja Latencia", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Elimina micro-cortes forzando modo de alto rendimiento", color = Color(0xFF80FFFFFF), fontSize = 11.sp)
                                }
                                Switch(
                                    checked = lowLatencyWifi,
                                    onCheckedChange = { lowLatencyWifi = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF52E172))
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("WakeLock de CPU Activo", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Evita que Android entre en reposo durante el juego", color = Color(0xFF80FFFFFF), fontSize = 11.sp)
                                }
                                Switch(
                                    checked = cpuWakeLock,
                                    onCheckedChange = { cpuWakeLock = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF52E172))
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 2. Tasa de Refresco
                            Text("Tasa de Refresco de Pantalla (OLED Sync):", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(60.0f, 120.0f).forEach { hz ->
                                    val isSel = refreshRate == hz
                                    Button(
                                        onClick = { refreshRate = hz },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSel) Color(0xFF52E172) else Color(0xFF1E2D22)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("${hz.toInt()} Hz", color = if (isSel) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 3. Resolución Preferida
                            Text("Resolución de Stream:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("720p Ahorro", "1080p HQ", "1440p God Mode").forEach { res ->
                                    val isSel = resolution == res
                                    Button(
                                        onClick = { resolution = res },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSel) Color(0xFF52E172) else Color(0xFF1E2D22)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(res, color = if (isSel) Color.Black else Color.White, fontSize = 11.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 4. Códec de GPU
                            Text("Códec de Decodificación Hardware:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Auto", "HEVC / H.265", "AV1", "H.264").forEach { c ->
                                    val isSel = codec == c
                                    Button(
                                        onClick = { codec = c },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSel) Color(0xFF52E172) else Color(0xFF1E2D22)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(c, color = if (isSel) Color.Black else Color.White, fontSize = 10.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 5. Motor FSR / CAS
                            Text("Perfil de Escalado & Nitidez:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(ScalingProfile.CAS, ScalingProfile.FSR_1, ScalingProfile.OFF).forEach { prof ->
                                    val isSel = prof == scalingProfile
                                    Button(
                                        onClick = { scalingProfile = prof },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSel) Color(0xFF52E172) else Color(0xFF1E2D22)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(prof.name, color = if (isSel) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Nitidez: $sharpness%", color = Color(0xFF80FFFFFF), fontSize = 12.sp)
                            Slider(
                                value = sharpness.toFloat(),
                                onValueChange = { sharpness = it.toInt() },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF52E172), activeTrackColor = Color(0xFF52E172))
                            )
                        }

                        "Controles & Háptica" -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Combo 'View + Menu' (Overlay)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Presiona ambos botones simultáneamente para abrir menú", color = Color(0xFF80FFFFFF), fontSize = 11.sp)
                                }
                                Switch(
                                    checked = comboMenuEnabled,
                                    onCheckedChange = { comboMenuEnabled = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF52E172))
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Intercambiar Botones A/B", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Layout estilo Nintendo Switch / Retro", color = Color(0xFF80FFFFFF), fontSize = 11.sp)
                                }
                                Switch(
                                    checked = swapAB,
                                    onCheckedChange = { swapAB = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF52E172))
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Intercambiar Botones X/Y", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Ajuste ergonómico para consolas portátiles", color = Color(0xFF80FFFFFF), fontSize = 11.sp)
                                }
                                Switch(
                                    checked = swapXY,
                                    onCheckedChange = { swapXY = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF52E172))
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text("Curva de Respuesta de Sticks:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(ResponseCurve.LINEAR, ResponseCurve.ENHANCED, ResponseCurve.AGGRESSIVE).forEach { c ->
                                    val isSel = responseCurve == c
                                    Button(
                                        onClick = { responseCurve = c },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSel) Color(0xFF52E172) else Color(0xFF1E2D22)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(c.name, color = if (isSel) Color.Black else Color.White, fontSize = 10.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Button(
                                onClick = { onTestRumble?.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B3822)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Vibration, contentDescription = null, tint = Color(0xFF52E172))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Probar Vibración Háptica (Rumble)", color = Color(0xFF52E172), fontWeight = FontWeight.Bold)
                            }
                        }

                        "Mantenimiento" -> {
                            Text("Optimización y Rendimiento de Memoria", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Limpia la caché de shaders compilados, texturas temporales y buffer de WebRTC sin cerrar tu sesión de Xbox Live.",
                                color = Color(0xFFB3FFFFFF),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(18.dp))

                            Button(
                                onClick = { onDetoxCache?.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E1818)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, tint = Color(0xFFFF5252))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("🧹 Ejecutar Detox de Caché Multimedia", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(26.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26FFFFFF))
                        ) {
                            Text("Cancelar", color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = {
                                onSave(
                                    currentConfig.copy(
                                        lowLatencyWifi = lowLatencyWifi,
                                        cpuWakeLock = cpuWakeLock,
                                        preferredRefreshRate = refreshRate,
                                        preferredResolution = resolution,
                                        preferredCodec = codec,
                                        audioLowLatency = audioLowLatency,
                                        scalingProfile = scalingProfile,
                                        sharpness = sharpness,
                                        swapAB = swapAB,
                                        swapXY = swapXY,
                                        comboMenuEnabled = comboMenuEnabled,
                                        responseCurve = responseCurve
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF52E172))
                        ) {
                            Text("Guardar y Aplicar", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modal interactivo de calibración de palancas analógicas con retícula en tiempo real.
 */
@Composable
private fun CalibrationDialog(
    currentConfig: GodModeConfig,
    onDismiss: () -> Unit,
    onSave: (GodModeConfig) -> Unit,
    onTestRumble: (() -> Unit)?
) {
    var innerDeadzone by remember { mutableFloatStateOf(currentConfig.innerDeadzone) }
    var outerDeadzone by remember { mutableFloatStateOf(currentConfig.outerDeadzone) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        color = Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101913))
            ) {
                Column(
                    modifier = Modifier
                        .border(1.5.dp, Color(0xFF52E172), RoundedCornerShape(24.dp))
                        .padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "🎯 Calibración de Sticks & Deadzones",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Retícula de prueba interactiva
                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0A100C))
                            .border(1.5.dp, Color(0xFF2652E172), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val maxRadius = size.width / 2f

                            // Círculo límite exterior
                            drawCircle(
                                color = Color(0x3352E172),
                                radius = maxRadius * outerDeadzone,
                                center = center,
                                style = Stroke(width = 2f)
                            )

                            // Círculo zona muerta interior
                            drawCircle(
                                color = Color(0x80E74040),
                                radius = maxRadius * innerDeadzone,
                                center = center
                            )

                            // Ejes de coordenadas
                            drawLine(Color(0x40FFFFFF), Offset(0f, center.y), Offset(size.width, center.y), 1.5f)
                            drawLine(Color(0x40FFFFFF), Offset(center.x, 0f), Offset(center.x, size.height), 1.5f)

                            // Punto central neutral
                            drawCircle(Color(0xFF52E172), radius = 6f, center = center)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Zona Muerta Interior: ${(innerDeadzone * 100).toInt()}% (Filtra Drift)", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = innerDeadzone,
                        onValueChange = { innerDeadzone = it },
                        valueRange = 0.0f..0.35f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF52E172), activeTrackColor = Color(0xFF52E172))
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Límite Exterior: ${(outerDeadzone * 100).toInt()}% (Saturación Máxima)", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = outerDeadzone,
                        onValueChange = { outerDeadzone = it },
                        valueRange = 0.70f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF52E172), activeTrackColor = Color(0xFF52E172))
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26FFFFFF))
                        ) {
                            Text("Cancelar", color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = {
                                onSave(currentConfig.copy(innerDeadzone = innerDeadzone, outerDeadzone = outerDeadzone))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF52E172))
                        ) {
                            Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modal para añadir consolas manualmente para Juego Remoto.
 */
@Composable
private fun AddConsoleDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, ip: String) -> Unit
) {
    var consoleName by remember { mutableStateOf("") }
    var consoleIp by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        color = Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101913))
            ) {
                Column(
                    modifier = Modifier
                        .border(1.5.dp, Color(0xFF52E172), RoundedCornerShape(22.dp))
                        .padding(24.dp)
                ) {
                    Text("🎮 Vincular Nueva Consola Xbox", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = consoleName,
                        onValueChange = { consoleName = it },
                        label = { Text("Nombre (Ej: Xbox Series X Salón)", color = Color(0xFF80FFFFFF)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF52E172),
                            unfocusedBorderColor = Color(0xFF2652E172),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = consoleIp,
                        onValueChange = { consoleIp = it },
                        label = { Text("IP Local o Live ID (Ej: 192.168.1.150)", color = Color(0xFF80FFFFFF)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF52E172),
                            unfocusedBorderColor = Color(0xFF2652E172),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26FFFFFF))) {
                            Text("Cancelar", color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = {
                                if (consoleName.isNotBlank() && consoleIp.isNotBlank()) {
                                    onAdd(consoleName, consoleIp)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF52E172)),
                            enabled = consoleName.isNotBlank() && consoleIp.isNotBlank()
                        ) {
                            Text("Añadir", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
