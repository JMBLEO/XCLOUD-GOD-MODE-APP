package com.xcloud.godmode.viewmodels

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.xcloud.godmode.network.SecureTokenEnvelope
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xcloud.godmode.models.DeviceType
import com.xcloud.godmode.models.GameModel
import com.xcloud.godmode.models.SettingsModel
import com.xcloud.godmode.network.CatalogRepository
import com.xcloud.godmode.services.SessionTelemetrySummary
import com.xcloud.godmode.services.StreamSessionHolder
import com.xcloud.godmode.network.XboxFullAuthManager
import com.xcloud.godmode.network.XboxSessionProfile
import com.xcloud.godmode.engine.DeviceHardwareProfile
import com.xcloud.godmode.engine.HardwareCapabilityProfiler
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "user_preferences")

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "HomeViewModel"
    private val context = application.applicationContext
    private val catalogRepo = CatalogRepository()
    private val FAVORITES_KEY = stringSetPreferencesKey("favorites")
    private val CAROUSEL_LAST_SHOWN_PREFIX = "carousel_shown_"
    private val networkClient = com.xcloud.godmode.network.NetworkClient(enableDoh = true)
    private val xboxAuthManager = com.xcloud.godmode.network.XboxFullAuthManager(networkClient)

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Catalog state
    private val _rawGames = MutableStateFlow<List<GameModel>>(catalogRepo.verifiedDefaultGames)
    val rawGames: StateFlow<List<GameModel>> = _rawGames.asStateFlow()

    private val _isLoadingCatalog = MutableStateFlow(false)
    val isLoadingCatalog: StateFlow<Boolean> = _isLoadingCatalog.asStateFlow()

    // Auth state
    private val _userSession = MutableStateFlow<XboxFullAuthManager.XboxReadySession?>(null)
    val userSession: StateFlow<XboxFullAuthManager.XboxReadySession?> = _userSession.asStateFlow()

    val savedAccounts: StateFlow<List<XboxSessionProfile>> = MutableStateFlow(emptyList<XboxSessionProfile>()).asStateFlow()
    val activeVaultProfile: StateFlow<XboxSessionProfile?> = MutableStateFlow<XboxSessionProfile?>(null).asStateFlow()

    // Controller detection state
    data class ControllerInfo(val name: String, val vendorId: Int, val productId: Int)
    private val _activeController = MutableStateFlow<ControllerInfo?>(null)
    val activeController: StateFlow<ControllerInfo?> = _activeController.asStateFlow()

    private val _isInStreamMode = MutableStateFlow(false)
    val isInStreamMode: StateFlow<Boolean> = _isInStreamMode.asStateFlow()

    private val _isAuthModalOpen = MutableStateFlow(false)
    val isAuthModalOpen: StateFlow<Boolean> = _isAuthModalOpen.asStateFlow()

    // Game Details Modal
    private val _selectedGameForDetails = MutableStateFlow<GameModel?>(null)
    val selectedGameForDetails: StateFlow<GameModel?> = _selectedGameForDetails.asStateFlow()

    // Post-Stream Telemetry Modal
    val postStreamSummary: StateFlow<SessionTelemetrySummary?> = StreamSessionHolder.lastSessionSummary

    // Consoles state
    private val _consoles = MutableStateFlow<List<JSONObject>>(emptyList())
    val consoles: StateFlow<List<JSONObject>> = _consoles.asStateFlow()

    // MRU Games state
    private val _mruGames = MutableStateFlow<List<GameModel>>(emptyList())
    val mruGames: StateFlow<List<GameModel>> = _mruGames.asStateFlow()

    // Captured Auth Code (from deep link)
    private val _capturedAuthCode = MutableStateFlow<String?>(null)
    val capturedAuthCode: StateFlow<String?> = _capturedAuthCode.asStateFlow()

    // Settings
    private val _settings = MutableStateFlow(SettingsModel())
    val settings: StateFlow<SettingsModel> = _settings.asStateFlow()

    // Hardware Profile
    private val _hardwareProfile = MutableStateFlow<DeviceHardwareProfile?>(null)
    val hardwareProfile: StateFlow<DeviceHardwareProfile?> = _hardwareProfile.asStateFlow()

    // Chameleon Engine Color
    private val _chameleonColor = MutableStateFlow(Color(0xFF107C10)) // Default Xbox Green
    val chameleonColor: StateFlow<Color> = _chameleonColor.asStateFlow()

    // Optimizer AI Diagnostics
    private val _recommendation = MutableStateFlow("Inicia un juego para ver recomendaciones de rendimiento.")
    val recommendation: StateFlow<String> = _recommendation.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    // Permission Authorization State (Microphone, Storage, Bluetooth)
    private val _permissionsState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val permissionsState: StateFlow<Map<String, Boolean>> = _permissionsState.asStateFlow()

    private val rootContainer = com.xcloud.godmode.engine.GodModeRootContainer.getInstance(application)
    
    val uiController = rootContainer.uiController
    val launchState = uiController.state
    
    private val nanoCoordinator = rootContainer.nanoCoordinator
    
    val configRepository = rootContainer.configRepository
    val globalConfig = configRepository.configFlow

    private val unifiedVault = rootContainer.authVault
    val headerState = unifiedVault.sessionState

    fun launchGame(recordId: String) {
        viewModelScope.launch {
            nanoCoordinator.launchWithCurrentSavedConfig(com.xcloud.godmode.network.CatalogRecordId(recordId))
        }
    }

    fun handleStreamAction(action: com.xcloud.godmode.ui.StreamErrorAction, onNavigateBack: () -> Unit) {
        // NanoPlayNowCoordinator handles most errors internally via uiController,
        // but we can add specific return-to-catalog logic here if needed.
        uiController.enterIdle()
        onNavigateBack()
    }

    fun cancelLaunch() {
        // Coordinator manages cancellations via its internal Job
    }

    fun restartStream() {
        nanoCoordinator.restartActiveSession()
    }

    fun stopStream() {
        viewModelScope.launch {
            rootContainer.activeEngineSession.detachCurrent(com.xcloud.godmode.engine.EngineDetachReason.USER_EXIT)
        }
    }

    fun onSurfaceCreated(attemptId: com.xcloud.godmode.engine.LaunchAttemptId, view: android.view.SurfaceView) {
        rootContainer.surfaceHostRegistry.onSurfaceViewCreated(attemptId, view)
    }

    val surfaceRegistry get() = rootContainer.surfaceHostRegistry

    // Track B Silicon Validation
    private val _siliconReport = MutableStateFlow<com.xcloud.godmode.engine.SiliconValidationReport?>(null)
    val siliconReport: StateFlow<com.xcloud.godmode.engine.SiliconValidationReport?> = _siliconReport.asStateFlow()

    fun runHardwareValidation(titleId: String) {
        viewModelScope.launch {
            val blackbox = com.xcloud.godmode.network.SessionBlackboxRecorder()
            val vault = com.xcloud.godmode.network.HardwareTokenVault(context)
            val scorer = com.xcloud.godmode.network.RegionScoringEngine(blackbox)
            val authAuth = com.xcloud.godmode.network.AuthenticationAuthority(vault, scorer, blackbox)
            val capEngine = com.xcloud.godmode.network.CapabilityNegotiationEngine()
            
            val authority = com.xcloud.godmode.network.GlobalSessionAuthority(
                vault = vault,
                authAuthority = authAuth,
                capabilityEngine = capEngine,
                blackbox = blackbox
            )

            val harness = com.xcloud.godmode.engine.HardwareValidationHarness(
                context = context,
                sessionAuthority = authority,
                blackbox = blackbox
            )
            _siliconReport.value = harness.executeSiliconStressSuite(titleId)
        }
    }

    fun updatePermissionState(permission: String, granted: Boolean) {
        _permissionsState.value = _permissionsState.value + (permission to granted)
    }

    fun isAuthorized(permission: String): Boolean = _permissionsState.value[permission] ?: false

    private val geminiService = com.xcloud.godmode.ai.GeminiService()
    private val db = com.xcloud.godmode.models.GodModeDatabase.getDatabase(context)

    // Selected Category Filter (0=Todos, 1=Recientes, 2=Series X|S, 3=Mundial, 4=Chile, 5=LATAM, 6=BYOG)
    private val _selectedCategory = MutableStateFlow(0)
    val selectedCategory: StateFlow<Int> = _selectedCategory.asStateFlow()

    fun setCategoryFilter(index: Int) {
        _selectedCategory.value = index
    }

    // Room DB Full Catalog Reactive Flow
    private val roomCatalogFlow: Flow<List<GameModel>> = db.dao().getAllCatalogGames()
        .map { entities ->
            entities.map { entity ->
                GameModel(
                    id = entity.productId,
                    title = entity.title,
                    genre = entity.genre,
                    publisher = entity.publisher,
                    description = entity.description,
                    posterUrl = entity.posterUrl,
                    heroUrl = entity.heroUrl,
                    superHeroUrl = entity.superHeroUrl.ifEmpty { entity.heroUrl },
                    rating = entity.rating,
                    capabilities = if (entity.supportsMkb) listOf("Jugar con Teclado y Ratón", "Control Táctil Nativo", "Xbox Series X|S") else listOf("Control Táctil Nativo", "Xbox Series X|S"),
                    isGamePass = !entity.isByog,
                    isOwned = entity.isByog,
                    supportsTouch = true,
                    supportsMkb = entity.supportsMkb,
                    isSeriesOptimized = entity.isSeriesOptimized
                )
            }
        }

    // Favorites persistence via DataStore
    private val _favorites = context.dataStore.data
        .map { preferences -> preferences[FAVORITES_KEY] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredGames = combine(roomCatalogFlow, _rawGames, _searchQuery, _selectedCategory, _favorites) { roomList, defaultList, query, catIndex, favorites ->
        val pool = if (roomList.isNotEmpty()) {
            val map = (roomList + defaultList).distinctBy { it.id }
            map
        } else {
            defaultList
        }

        pool.map { it.copy(isFavorite = favorites.contains(it.id)) }
            .filter { game ->
                val matchesQuery = query.isBlank() ||
                        game.title.contains(query, ignoreCase = true) ||
                        game.genre.contains(query, ignoreCase = true) ||
                        game.publisher.contains(query, ignoreCase = true)

                val matchesCat = when (catIndex) {
                    1 -> game.description.contains("Jugado recientemente", ignoreCase = true) || game.isFavorite
                    2 -> game.isSeriesOptimized || game.capabilities.any { it.contains("Series", ignoreCase = true) }
                    3 -> true // Mundial
                    4 -> true // Chile
                    5 -> true // LATAM
                    6 -> game.isOwned || !game.isGamePass // Juegos Propios (BYOG)
                    else -> true // Todos
                }

                matchesQuery && matchesCat
            }
            .sortedByDescending { it.isFavorite }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), catalogRepo.verifiedDefaultGames)


    // Hero Carousel Rotation Logic (48h Cooldown)
    private val _carouselItems = MutableStateFlow<List<GameModel>>(emptyList())
    val carouselItems: StateFlow<List<GameModel>> = _carouselItems.asStateFlow()

    private fun updateCarouselSelection(games: List<GameModel>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val fortyEightHoursMs = TimeUnit.HOURS.toMillis(48)
            
            val prefs = context.dataStore.data.first()
            val filteredForCarousel = games.filter { game ->
                val lastShown = prefs[longPreferencesKey(CAROUSEL_LAST_SHOWN_PREFIX + game.id)] ?: 0L
                (now - lastShown) > fortyEightHoursMs
            }

            val finalSelection = if (filteredForCarousel.size >= 5) {
                filteredForCarousel.shuffled().take(8)
            } else {
                // If pool is too small, reset or just take shuffled raw
                games.shuffled().take(8)
            }

            // Record display time
            context.dataStore.edit { p ->
                finalSelection.forEach { game ->
                    p[longPreferencesKey(CAROUSEL_LAST_SHOWN_PREFIX + game.id)] = now
                }
            }
            
            _carouselItems.value = finalSelection
        }
    }

    // Layer 1: Best Server Suggestion by History (Reactive)
    val bestServerSuggestion: StateFlow<String?> = db.dao().getAllTelemetry()
        .map { _ -> db.dao().getBestServerByHistory() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun saveTelemetry(summary: com.xcloud.godmode.services.SessionTelemetrySummary) {
        viewModelScope.launch {
            db.dao().insertTelemetry(
                com.xcloud.godmode.models.TelemetryEntry(
                    serverId = summary.serverId,
                    avgRtt = summary.avgPingMs.toInt(),
                    avgJitter = summary.avgJitterMs,
                    packetLoss = summary.packetLossPercent,
                    resolution = summary.resolutionUsed,
                    motor = summary.motorUsed,
                    networkType = summary.networkType
                )
            )
        }
    }



    private fun startPeriodicCatalogShuffle() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60000) // Shuffle every 60s
                if (_searchQuery.value.isBlank() && !_isLoadingCatalog.value) {
                    val current = _rawGames.value.toMutableList()
                    current.shuffle()
                    _rawGames.value = current
                    Log.d(TAG, "Periodic Catalog Shuffle: Keeping UI fresh.")
                }
            }
        }
    }

    fun setInStreamMode(active: Boolean) {
        _isInStreamMode.value = active
    }

    private fun startControllerDetection() {
        viewModelScope.launch {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
            while (true) {
                val deviceIds = inputManager.inputDeviceIds
                var foundGamepad: ControllerInfo? = null
                for (id in deviceIds) {
                    val device = inputManager.getInputDevice(id) ?: continue
                    val sources = device.sources
                    if ((sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                        (sources and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
                        foundGamepad = ControllerInfo(device.name, device.vendorId, device.productId)
                        break
                    }
                }
                _activeController.value = foundGamepad
                kotlinx.coroutines.delay(2000) // Poll every 2s
            }
        }
    }

    private fun loadSession() {
        viewModelScope.launch {
            val envelope = unifiedVault.loadEnvelope()
            if (envelope != null) {
                val state = unifiedVault.sessionState.first()
                _userSession.value = XboxFullAuthManager.XboxReadySession(
                    gamertag = state.gamertag,
                    uhs = String(envelope.userHashUhs),
                    msaRefreshToken = String(envelope.msaRefreshToken),
                    webXstsToken = String(envelope.xblUserToken),
                    xHomeGsToken = String(envelope.gsStreamToken),
                    xHomeRegionUri = envelope.discoveredBaseRegionUri,
                    xCloudGsToken = String(envelope.gsStreamToken),
                    xCloudRegionUri = envelope.discoveredBaseRegionUri
                )
            }
        }
    }

    private fun saveSession(session: XboxFullAuthManager.XboxReadySession?) {
        viewModelScope.launch {
            if (session == null) {
                unifiedVault.clearSession()
            } else {
                // Already saved via setSession -> unifiedVault.saveSession
            }
        }
    }

    fun loadGames(authManager: XboxFullAuthManager? = null) {
        viewModelScope.launch {
            _isLoadingCatalog.value = true
            try {
                val session = _userSession.value
                val live = if (session != null && authManager != null) {
                    catalogRepo.fetchLiveCatalog(authManager, session.xCloudGsToken, session.xCloudRegionUri)
                } else {
                    catalogRepo.fetchLiveCatalog()
                }

                if (live.isNotEmpty()) {
                    _rawGames.value = live
                    updateCarouselSelection(live)
                }

                // Sync Full Xbox Catalog into Room DB for instant FTS4 queries & multi-market filtering
                val dynamicRepo = com.xcloud.godmode.network.DynamicXboxCatalogRepository(catalogRepo.client)
                dynamicRepo.syncFullCatalogToRoom(db.dao(), session)

            } catch (e: Exception) {
                Log.w(TAG, "Error loading catalog: ${e.message}")
            } finally {
                _isLoadingCatalog.value = false
            }
        }
    }


    fun setChameleon(color: Color) {
        _chameleonColor.value = color
        com.xcloud.godmode.audio.GodModeSoundEngine.updateChameleonPitch(
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
    }

    fun autoChameleon(game: GameModel) {
        val hash = game.title.hashCode()
        val r = (hash and 0xFF0000 shr 16).coerceIn(100, 240)
        val g = (hash and 0x00FF00 shr 8).coerceIn(100, 240)
        val b = (hash and 0x0000FF).coerceIn(100, 240)
        setChameleon(Color(r, g, b))
    }

    fun refreshSession(authManager: XboxFullAuthManager) {
        val currentSession = _userSession.value ?: return
        viewModelScope.launch {
            try {
                val isValid = authManager.validateActiveSession(currentSession.webXstsToken, currentSession.uhs)
                if (!isValid && currentSession.msaRefreshToken.isNotBlank()) {
                    val refreshed = authManager.refreshMsaAccessToken(currentSession.msaRefreshToken)
                    val newRefresh = refreshed.second
                    _userSession.value = currentSession.copy(msaRefreshToken = newRefresh)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Silent refresh error: ${e.message}")
            }
        }
    }

    fun setPreset(presetName: String) {
        val current = _settings.value
        val newSettings = when (presetName) {
            "Battery Saver" -> current.copy(
                targetResProfile = "720p",
                optimizerProfile = "battery_saver",
                upscalerEngine = "Off",
                unlock120Fps = false,
                vibrationEnabled = false,
                audioCodecProfile = "Standard Opus"
            )
            "Balanced" -> current.copy(
                targetResProfile = "1080p",
                optimizerProfile = "balanced",
                upscalerEngine = "AMD FSR 3.1 RCAS WebGL2",
                fsrSharpness = 0.5f,
                unlock120Fps = true,
                vibrationEnabled = true,
                audioCodecProfile = "Opus Hi-Fi (510 kbps)"
            )
            "God Mode" -> current.copy(
                targetResProfile = "1440p",
                optimizerProfile = "god_mode",
                upscalerEngine = "Real-ESRGAN SRVGG",
                fsrSharpness = 0.8f,
                unlock120Fps = true,
                vibrationEnabled = true,
                vibrationIntensity = 120,
                audioCodecProfile = "Opus Hi-Fi (510 kbps)",
                forceSeriesX = true
            )
            else -> current
        }
        setSettings(newSettings)
    }

    fun getAiTip(gameTitle: String, networkType: String) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            try {
                _recommendation.value = geminiService.getOptimizationRecommendation(gameTitle, networkType)
            } catch (e: Exception) {
                _recommendation.value = "Recomendación: Perfil God Mode Ultra activo con HEVC 1080pHQ y nitidez AMD FSR 3.1."
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun testHaptics() {
        val intensity = _settings.value.vibrationIntensity
        if (intensity <= 0 || !_settings.value.vibrationEnabled) return

        try {
            val durationMs = 250L
            val amplitude = (255 * (intensity.coerceIn(10, 200) / 200f)).toInt().coerceIn(1, 255)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(durationMs)
                }
            }
        } catch (_: Throwable) {}
    }

    fun search(query: String) {
        _searchQuery.value = query
        if (query.length >= 2) {
            viewModelScope.launch {
                val liveResults = catalogRepo.searchStore(query)
                if (liveResults.isNotEmpty()) {
                    val currentRaw = _rawGames.value.toMutableList()
                    liveResults.forEach { liveGame ->
                        if (currentRaw.none { it.id == liveGame.id }) {
                            currentRaw.add(liveGame)
                        }
                    }
                    _rawGames.value = currentRaw
                }
            }
        }
    }

    fun fav(gameId: String) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                val current = preferences[FAVORITES_KEY] ?: emptySet()
                if (current.contains(gameId)) {
                    preferences[FAVORITES_KEY] = current - gameId
                } else {
                    preferences[FAVORITES_KEY] = current + gameId
                }
            }
        }
    }

    fun showAuth(open: Boolean) {
        _isAuthModalOpen.value = open
    }

    fun setSession(session: XboxFullAuthManager.XboxReadySession?, authManager: XboxFullAuthManager? = null) {
        if (session != null && (session.ageGroup == "Child" || session.ageGroup == "Teen")) {
            android.widget.Toast.makeText(context, "Advertencia: Cuenta restringida (Age Group: ${session.ageGroup}). Algunos juegos podrían no cargar.", android.widget.Toast.LENGTH_LONG).show()
        }

        _userSession.value = session
        
        // Also save to vault if it's a new session
        if (session != null) {
            viewModelScope.launch {
                val envelope = SecureTokenEnvelope(
                    msaAccessToken = session.msaRefreshToken.toCharArray(),
                    msaRefreshToken = session.msaRefreshToken.toCharArray(),
                    msaExpiresAtElapsedMs = SystemClock.elapsedRealtime() + 14_400_000L,
                    lptTransferToken = CharArray(0),
                    xblUserToken = session.webXstsToken.toCharArray(),
                    userHashUhs = session.uhs.toCharArray(),
                    xstsWebToken = CharArray(0),
                    xstsGssvToken = session.webXstsToken.toCharArray(),
                    xstsExpiresAtElapsedMs = SystemClock.elapsedRealtime() + 14_400_000L,
                    gsStreamToken = session.xCloudGsToken.toCharArray(),
                    gsExpiresAtElapsedMs = SystemClock.elapsedRealtime() + 14_400_000L,
                    discoveredBaseRegionUri = session.xCloudRegionUri,
                    market = "CL"
                )
                unifiedVault.saveSession(session.gamertag, session.ageGroup ?: "Game Pass Ultimate", envelope)
            }
        }

        _isAuthModalOpen.value = false
        if (session != null && authManager != null) {
            loadGames(authManager)
            fetchConsoles(authManager)
            fetchMruTitles(authManager)
        }
    }

    fun switchAccount(xuid: String, authManager: XboxFullAuthManager) {
        // Implement switch logic if unifiedVault supports multiple accounts, 
        // for now we just handle the current one.
    }

    fun removeAccount(xuid: String) {
        viewModelScope.launch {
            if (_userSession.value?.uhs == xuid) {
                logout()
            }
        }
    }

    fun fetchCloudConsolesFromXboxLive(authManager: XboxFullAuthManager) {
        fetchConsoles(authManager)
    }

    fun fetchConsoles(authManager: XboxFullAuthManager) {
        val session = _userSession.value
        viewModelScope.launch {
            if (session == null || session.webXstsToken.isBlank()) {
                // If not logged in, provide a demo xHome environment for instant exploration
                if (_consoles.value.isEmpty()) {
                    val demoConsole = JSONObject().apply {
                        put("id", "demo_series_x")
                        put("name", "Xbox Series X (Nube xHome)")
                        put("consoleType", "XboxSeriesX")
                        put("powerState", "ConnectedStandby")
                        put("readyForRemotePlay", true)
                        put("isCloudDirect", true)
                    }
                    _consoles.value = listOf(demoConsole)
                }
                return@launch
            }

            try {
                val json = authManager.getXHomeConsoles(session.uhs, session.webXstsToken)
                val consoleList = mutableListOf<JSONObject>()

                // Check possible keys: result, devices, environments
                val array = json.optJSONArray("result") 
                    ?: json.optJSONArray("devices")
                    ?: json.optJSONArray("environments")

                if (array != null) {
                    for (i in 0 until array.length()) {
                        consoleList.add(array.getJSONObject(i))
                    }
                }
                if (consoleList.isNotEmpty()) {
                    _consoles.value = consoleList
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching consoles: ${e.message}")
            }
        }
    }

    private fun fetchMruTitles(authManager: XboxFullAuthManager) {
        val session = _userSession.value ?: return
        viewModelScope.launch {
            try {
                val json = authManager.getMruTitles(session.xCloudGsToken, session.xCloudRegionUri)
                val results = json.optJSONArray("results") ?: org.json.JSONArray()
                val productIds = mutableListOf<String>()
                for (i in 0 until results.length()) {
                    val title = results.optJSONObject(i) ?: continue
                    val productId = title.optJSONObject("details")?.optString("productId") ?: ""
                    if (productId.isNotBlank()) productIds.add(productId)
                }

                if (productIds.isNotEmpty()) {
                    val details = authManager.getCloudTitleDetails(productIds)
                    val hydratedList = mutableListOf<GameModel>()
                    for (pid in productIds) {
                        val prod = details.optJSONObject(pid) ?: continue
                        hydratedList.add(parseGamePassProductLocal(prod))
                    }
                    _mruGames.value = hydratedList
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching MRU: ${e.message}")
            }
        }
    }

    private fun parseGamePassProductLocal(json: org.json.JSONObject): GameModel {
        val prodId = json.optString("StoreId")
        val title = json.optString("ProductTitle", "Unknown Game")
        val poster = json.optJSONObject("Image_Poster")?.optString("URL", "")?.let { if (it.startsWith("//")) "https:$it" else it } ?: ""
        val hero = json.optJSONObject("Image_Hero")?.optString("URL", "")?.let { if (it.startsWith("//")) "https:$it" else it } ?: ""
        
        return GameModel(
            id = prodId,
            title = title,
            posterUrl = poster,
            heroUrl = hero,
            chameleonRgb = Triple(16, 124, 16)
        )
    }

    fun showDetails(game: GameModel) {
        _selectedGameForDetails.value = game
        autoChameleon(game)
    }

    fun hideDetails() {
        _selectedGameForDetails.value = null
    }

    fun setSettings(newSettings: SettingsModel) {
        _settings.value = newSettings
        
        com.xcloud.godmode.audio.GodModeSoundEngine.isEnabled = newSettings.enableUiSounds
        com.xcloud.godmode.audio.GodModeSoundEngine.volumePercent = newSettings.uiSoundVolume
        com.xcloud.godmode.audio.GodModeSoundEngine.isHapticsCoupled = newSettings.enableHapticCoupling
        try {
            com.xcloud.godmode.audio.GodModeSoundEngine.activeTheme = 
                com.xcloud.godmode.audio.UiSoundTheme.valueOf(newSettings.uiSoundTheme)
        } catch (_: Exception) {}
    }

    fun hideSummary() {
        StreamSessionHolder.clearSummary()
    }

    fun determineDeviceType(screenWidthDp: Int): DeviceType {
        return when (_settings.value.deviceOverride) {
            "Force Phone" -> DeviceType.PHONE
            "Force Tablet" -> DeviceType.TABLET
            "Force Smart TV" -> DeviceType.TV
            else -> {
                when {
                    screenWidthDp >= 1200 -> DeviceType.TV
                    screenWidthDp >= 768 -> DeviceType.TABLET
                    else -> DeviceType.PHONE
                }
            }
        }
    }

    fun getGridColumnsForDevice(deviceType: DeviceType): Int {
        return when (deviceType) {
            DeviceType.PHONE -> 2
            DeviceType.TABLET -> 4
            DeviceType.TV -> 6
        }
    }

    fun setCapturedAuthCode(code: String) {
        _capturedAuthCode.value = code
        _isAuthModalOpen.value = true // Ensure modal is open to process the code
    }

    fun clearCapturedAuthCode() {
        _capturedAuthCode.value = null
    }

    fun toggleMicMute() {
        val newState = !_isMicMuted.value
        _isMicMuted.value = newState
        com.xcloud.godmode.audio.OboeAudioBridge.setMute(newState)
    }

    fun logout() {
        viewModelScope.launch {
            _userSession.value = null
            unifiedVault.clearSession()
        }
    }
    init {
        val profiler = com.xcloud.godmode.engine.HardwareCapabilityProfiler(context)
        _hardwareProfile.value = profiler.scanDeviceAndFilterEngines()
        
        loadGames()
        loadSession()
        startControllerDetection()
        startPeriodicCatalogShuffle()

        viewModelScope.launch {
            globalConfig.collect { cfg ->
                com.xcloud.godmode.streaming.NativeInputBridge.activeInstance?.let { bridge ->
                    bridge.touchSensitivity = cfg.touchSensitivity
                    bridge.touchDeadzone = cfg.touchDeadzone
                }
            }
        }
    }

}
