package com.aistudio.xcloudgodmode.ai.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.xcloudgodmode.ai.models.DeviceType
import com.aistudio.xcloudgodmode.ai.models.GameModel
import com.aistudio.xcloudgodmode.ai.models.SettingsModel
import com.aistudio.xcloudgodmode.ai.services.CatalogRepository
import com.aistudio.xcloudgodmode.ai.services.SessionTelemetrySummary
import com.aistudio.xcloudgodmode.ai.services.StreamSessionHolder
import com.aistudio.xcloudgodmode.ai.services.XboxFullAuthManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "user_preferences")

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "HomeViewModel"
    private val context = application.applicationContext
    private val catalogRepo = CatalogRepository()
    private val FAVORITES_KEY = stringSetPreferencesKey("favorites")

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

    // Settings
    private val _settings = MutableStateFlow(SettingsModel())
    val settings: StateFlow<SettingsModel> = _settings.asStateFlow()

    // Chameleon Engine Color
    private val _chameleonColor = MutableStateFlow(Color(0xFF107C10)) // Default Xbox Green
    val chameleonColor: StateFlow<Color> = _chameleonColor.asStateFlow()

    // Optimizer AI Diagnostics
    private val _recommendation = MutableStateFlow("Inicia un juego para ver recomendaciones de rendimiento.")
    val recommendation: StateFlow<String> = _recommendation.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val geminiService = com.aistudio.xcloudgodmode.ai.services.GeminiService()

    // Favorites persistence via DataStore
    private val _favorites = context.dataStore.data
        .map { preferences -> preferences[FAVORITES_KEY] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredGames = combine(_rawGames, _searchQuery, _favorites) { games, query, favorites ->
        games.map { it.copy(isFavorite = favorites.contains(it.id)) }
            .filter { game ->
                game.title.contains(query, ignoreCase = true) ||
                game.genre.contains(query, ignoreCase = true) ||
                game.publisher.contains(query, ignoreCase = true)
            }
            .sortedBy { !it.isFavorite }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadGames()
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
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading catalog: ${e.message}")
            } finally {
                _isLoadingCatalog.value = false
            }
        }
    }

    fun setChameleon(color: Color) {
        _chameleonColor.value = color
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.updateChameleonPitch(
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
        _userSession.value = session
        _isAuthModalOpen.value = false
        if (session != null && authManager != null) {
            loadGames(authManager)
            fetchConsoles(authManager)
            fetchMruTitles(authManager)
        }
    }

    private fun fetchConsoles(authManager: XboxFullAuthManager) {
        val session = _userSession.value ?: return
        viewModelScope.launch {
            try {
                val json = authManager.getConsoles(session.uhs, session.webXstsToken)
                val result = json.optJSONArray("result")
                val consoleList = mutableListOf<JSONObject>()
                if (result != null) {
                    for (i in 0 until result.length()) {
                        consoleList.add(result.getJSONObject(i))
                    }
                }
                _consoles.value = consoleList
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
                        // Reuse parseGamePassProduct logic from repo? No, it's private.
                        // I'll just map it here or make it public.
                        // Actually I can just call the repo to hydrate them if I modify it.
                        // For now, I'll manually parse to keep it simple here.
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
        
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.isEnabled = newSettings.enableUiSounds
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.volumePercent = newSettings.uiSoundVolume
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.isHapticsCoupled = newSettings.enableHapticCoupling
        try {
            com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.activeTheme = 
                com.aistudio.xcloudgodmode.ai.audio.UiSoundTheme.valueOf(newSettings.uiSoundTheme)
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

    fun logout() {
        _userSession.value = null
    }
}
