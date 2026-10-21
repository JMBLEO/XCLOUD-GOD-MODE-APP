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

    // Settings
    private val _settings = MutableStateFlow(SettingsModel())
    val settings: StateFlow<SettingsModel> = _settings.asStateFlow()

    // Chameleon Engine Color
    private val _chameleonColor = MutableStateFlow(Color(0xFF107C10)) // Default Xbox Green
    val chameleonColor: StateFlow<Color> = _chameleonColor.asStateFlow()

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
        refreshCatalog()
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            _isLoadingCatalog.value = true
            try {
                val live = catalogRepo.fetchLiveCatalog()
                if (live.isNotEmpty()) {
                    _rawGames.value = live
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error refrescando catálogo, usando respaldo verificado: ${e.message}")
            } finally {
                _isLoadingCatalog.value = false
            }
        }
    }

    fun updateChameleonColor(color: Color) {
        _chameleonColor.value = color
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.updateChameleonPitch(
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
    }

    fun deriveColorFromGame(game: GameModel) {
        val hash = game.title.hashCode()
        val r = (hash and 0xFF0000 shr 16).coerceIn(100, 240)
        val g = (hash and 0x00FF00 shr 8).coerceIn(100, 240)
        val b = (hash and 0x0000FF).coerceIn(100, 240)
        updateChameleonColor(Color(r, g, b))
    }

    fun validateAndRefreshSession(authManager: XboxFullAuthManager) {
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

    fun applyPreset(presetName: String) {
        val current = _settings.value
        val newSettings = when (presetName) {
            "Battery Saver" -> current.copy(
                targetResProfile = "720p",
                optimizerProfile = "battery_saver",
                upscalerEngine = "Off",
                unlock120Fps = false,
                vibrationEnabled = false
            )
            "Balanced" -> current.copy(
                targetResProfile = "1080p",
                optimizerProfile = "balanced",
                upscalerEngine = "AMD FSR 3.1 RCAS WebGL2",
                unlock120Fps = true,
                vibrationEnabled = true
            )
            "God Mode" -> current.copy(
                targetResProfile = "1440p",
                optimizerProfile = "god_mode",
                upscalerEngine = "Real-ESRGAN SRVGG",
                unlock120Fps = true,
                vibrationEnabled = true,
                forceSeriesX = true
            )
            else -> current
        }
        updateSettings(newSettings)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.length >= 2) {
            viewModelScope.launch {
                val liveResults = catalogRepo.searchStore(query)
                if (liveResults.isNotEmpty()) {
                    // Combinar resultados locales con live
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

    fun toggleFavorite(gameId: String) {
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

    fun setAuthModalOpen(open: Boolean) {
        _isAuthModalOpen.value = open
    }

    fun setUserSession(session: XboxFullAuthManager.XboxReadySession?) {
        _userSession.value = session
        _isAuthModalOpen.value = false
    }

    fun openGameDetails(game: GameModel) {
        _selectedGameForDetails.value = game
        deriveColorFromGame(game)
    }

    fun closeGameDetails() {
        _selectedGameForDetails.value = null
    }

    fun updateSettings(newSettings: SettingsModel) {
        _settings.value = newSettings
        
        // Sync Sound Engine
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.isEnabled = newSettings.enableUiSounds
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.volumePercent = newSettings.uiSoundVolume
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.isHapticsCoupled = newSettings.enableHapticCoupling
        try {
            com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.activeTheme = 
                com.aistudio.xcloudgodmode.ai.audio.UiSoundTheme.valueOf(newSettings.uiSoundTheme)
        } catch (_: Exception) {}
    }

    fun dismissPostStreamModal() {
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
