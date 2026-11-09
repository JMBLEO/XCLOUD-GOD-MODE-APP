package com.xcloud.godmode.viewmodels

import androidx.lifecycle.ViewModel
import com.xcloud.godmode.engine.EngineCatalog
import com.xcloud.godmode.engine.NativeEngineGate
import com.xcloud.godmode.engine.NativeEngineGateResult
import com.xcloud.godmode.models.ApplicationRestartAction
import com.xcloud.godmode.models.AuthenticationState
import com.xcloud.godmode.models.BypassConfiguration
import com.xcloud.godmode.models.CodecPreference
import com.xcloud.godmode.models.EngineCatalogVisibility
import com.xcloud.godmode.models.EngineId
import com.xcloud.godmode.models.EngineMenuEntry
import com.xcloud.godmode.models.RootApplicationConfiguration
import com.xcloud.godmode.models.RootConfigurationState
import com.xcloud.godmode.models.StreamConnectionMetrics
import com.xcloud.godmode.models.StreamServerConfiguration
import com.xcloud.godmode.models.VerifiedStreamQuality
import com.xcloud.godmode.network.StreamMetricsProvider
import com.xcloud.godmode.services.AuthenticationRepository
import com.xcloud.godmode.services.RootConfigurationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PlayNowAction {
    data class EngineResolved(
        val engineId: EngineId
    ) : PlayNowAction

    data class Blocked(
        val reason: String
    ) : PlayNowAction
}

data class PlayNowUiState(
    val engineMenuVisible: Boolean = false,
    val engineEntries: List<EngineMenuEntry> = emptyList(),
    val selectedEngineId: EngineId? = null,
    val configurationState: RootConfigurationState? = null,
    val connectionMetrics: StreamConnectionMetrics? = null,
    val verifiedQuality: VerifiedStreamQuality? = null,
    val bypassEditorVisible: Boolean = false,
    val serverEditorVisible: Boolean = false,
    val codecEditorVisible: Boolean = false,
    val restartDialogVisible: Boolean = false,
    val message: String? = null
)

class PlayNowViewModel(
    private val engineCatalog: EngineCatalog,
    private val nativeEngineGate: NativeEngineGate,
    private val rootConfigurationRepository: RootConfigurationRepository,
    private val authenticationRepository: AuthenticationRepository,
    private val streamMetricsProvider: StreamMetricsProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayNowUiState())
    val uiState: StateFlow<PlayNowUiState> = _uiState.asStateFlow()

    fun onPlayNowPressed(visibility: EngineCatalogVisibility = EngineCatalogVisibility.IMPLEMENTED_ONLY): PlayNowAction {
        val authState = authenticationRepository.authState.value
        if (authState != AuthenticationState.AUTHENTICATED) {
            val reason = when (authState) {
                AuthenticationState.REFRESH_REQUIRED -> "SESSION_REFRESH_REQUIRED: La sesión ha caducado y requiere renovación."
                else -> "AUTHENTICATION_REQUIRED: Se requiere autenticación para acceder al servicio."
            }
            _uiState.value = _uiState.value.copy(message = reason)
            return PlayNowAction.Blocked(reason)
        }

        val configState = rootConfigurationRepository.state.value
        if (configState.restartRequired) {
            val reason = "RESTART_REQUIRED: Los cambios de configuración están guardados pero requieren reiniciar el proceso."
            _uiState.value = _uiState.value.copy(
                engineMenuVisible = true,
                configurationState = configState,
                restartDialogVisible = true,
                message = reason
            )
            return PlayNowAction.Blocked(reason)
        }

        val entries = engineCatalog.entries(visibility)
        if (entries.isEmpty()) {
            val reason = "NATIVE_ENGINE_UNAVAILABLE: No existe un motor nativo implementado."
            _uiState.value = _uiState.value.copy(
                engineMenuVisible = false,
                engineEntries = emptyList(),
                selectedEngineId = null,
                message = reason
            )
            return PlayNowAction.Blocked(reason)
        }

        _uiState.value = _uiState.value.copy(
            engineMenuVisible = true,
            engineEntries = entries,
            selectedEngineId = null,
            configurationState = configState,
            connectionMetrics = streamMetricsProvider.metrics.value,
            verifiedQuality = VerifiedStreamQuality(
                width = 0,
                height = 0,
                frameRate = 0.0,
                mimeType = "Desconocido",
                bitDepth = 0,
                decoderName = "Sin verificar",
                verified = false
            ),
            message = null
        )

        return PlayNowAction.Blocked("MENU_OPENED: Menú flotante desplegado para selección.")
    }

    fun onEngineSelected(entry: EngineMenuEntry): PlayNowAction {
        val configState = rootConfigurationRepository.state.value
        if (configState.restartRequired) {
            val reason = "RESTART_REQUIRED: Los cambios de configuración están guardados pero requieren reiniciar el proceso."
            _uiState.value = _uiState.value.copy(message = reason)
            return PlayNowAction.Blocked(reason)
        }

        if (!entry.selectable) {
            val reason = "NATIVE_ENGINE_UNAVAILABLE: El motor seleccionado no está implementado."
            _uiState.value = _uiState.value.copy(message = reason)
            return PlayNowAction.Blocked(reason)
        }

        return when (val result = nativeEngineGate.resolve(listOf(entry.engineId))) {
            is NativeEngineGateResult.Available -> {
                _uiState.value = _uiState.value.copy(
                    engineMenuVisible = false,
                    selectedEngineId = result.engineId,
                    message = null
                )
                PlayNowAction.EngineResolved(result.engineId)
            }
            is NativeEngineGateResult.Unavailable -> {
                _uiState.value = _uiState.value.copy(
                    selectedEngineId = null,
                    message = result.reason
                )
                PlayNowAction.Blocked(result.reason)
            }
        }
    }

    suspend fun savePendingBypass(bypass: BypassConfiguration) {
        val currentApplied = rootConfigurationRepository.state.value.applied
        val updated = currentApplied.copy(bypass = bypass)
        rootConfigurationRepository.savePending(updated)
        _uiState.value = _uiState.value.copy(
            configurationState = rootConfigurationRepository.state.value,
            restartDialogVisible = true,
            bypassEditorVisible = false
        )
    }

    suspend fun savePendingServer(server: StreamServerConfiguration) {
        val currentApplied = rootConfigurationRepository.state.value.applied
        val updated = currentApplied.copy(server = server)
        rootConfigurationRepository.savePending(updated)
        _uiState.value = _uiState.value.copy(
            configurationState = rootConfigurationRepository.state.value,
            restartDialogVisible = true,
            serverEditorVisible = false
        )
    }

    suspend fun savePendingCodec(codec: CodecPreference) {
        val currentApplied = rootConfigurationRepository.state.value.applied
        val updated = currentApplied.copy(codecPreference = codec)
        rootConfigurationRepository.savePending(updated)
        _uiState.value = _uiState.value.copy(
            configurationState = rootConfigurationRepository.state.value,
            restartDialogVisible = true,
            codecEditorVisible = false
        )
    }

    fun handleRestartAction(action: ApplicationRestartAction) {
        when (action) {
            ApplicationRestartAction.Requested -> {
                _uiState.value = _uiState.value.copy(
                    restartDialogVisible = false,
                    message = "RESTART_REQUIRED: Reinicie la aplicación para aplicar los cambios pendientes."
                )
            }
            ApplicationRestartAction.Deferred -> {
                _uiState.value = _uiState.value.copy(
                    restartDialogVisible = false
                )
            }
        }
    }

    fun openBypassEditor() {
        _uiState.value = _uiState.value.copy(bypassEditorVisible = true)
    }

    fun closeBypassEditor() {
        _uiState.value = _uiState.value.copy(bypassEditorVisible = false)
    }

    fun openServerEditor() {
        _uiState.value = _uiState.value.copy(serverEditorVisible = true)
    }

    fun closeServerEditor() {
        _uiState.value = _uiState.value.copy(serverEditorVisible = false)
    }

    fun openCodecEditor() {
        _uiState.value = _uiState.value.copy(codecEditorVisible = true)
    }

    fun closeCodecEditor() {
        _uiState.value = _uiState.value.copy(codecEditorVisible = false)
    }

    fun dismissEngineMenu() {
        _uiState.value = _uiState.value.copy(engineMenuVisible = false)
    }
}
