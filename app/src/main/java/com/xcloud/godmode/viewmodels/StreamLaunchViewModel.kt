package com.xcloud.godmode.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloud.godmode.network.CredentialResolutionResult
import com.xcloud.godmode.network.SessionVaultStorage
import com.xcloud.godmode.network.StreamingCredentialOrchestrator
import com.xcloud.godmode.network.StreamingSessionCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EngineDialogState {
    data object Hidden : EngineDialogState

    data class AuthRequired(
        val title: String = "Motor de Streaming Nativo",
        val message: String
    ) : EngineDialogState

    data class GenericError(
        val title: String = "Motor de Streaming Nativo",
        val message: String
    ) : EngineDialogState
}

data class HubSessionUiState(
    val isCheckingCredentials: Boolean = false,
    val isLoggedIn: Boolean = false,
    val gamertag: String? = null,
    val showDeviceAuthModal: Boolean = false,
    val showProfileMenu: Boolean = false,
    val engineDialog: EngineDialogState = EngineDialogState.Hidden
)

class StreamLaunchViewModel(
    private val credentialOrchestrator: StreamingCredentialOrchestrator,
    private val vaultStorage: SessionVaultStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(HubSessionUiState())
    val uiState: StateFlow<HubSessionUiState> = _uiState.asStateFlow()

    init {
        // Synchronize and validate active streaming credentials on startup
        refreshSessionOnStartup()
    }

    fun refreshSessionOnStartup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingCredentials = true) }
            when (val result = credentialOrchestrator.ensureValidStreamingToken()) {
                is CredentialResolutionResult.Ready -> {
                    _uiState.update {
                        it.copy(
                            isCheckingCredentials = false,
                            isLoggedIn = true,
                            gamertag = result.credentials.gamertag,
                            engineDialog = EngineDialogState.Hidden
                        )
                    }
                }
                is CredentialResolutionResult.RequiresInteractiveLogin -> {
                    // Purge zombie state if gsToken is missing and refresh token is invalid
                    _uiState.update {
                        it.copy(
                            isCheckingCredentials = false,
                            isLoggedIn = false,
                            gamertag = null
                        )
                    }
                }
                is CredentialResolutionResult.NetworkFailure -> {
                    val cached = vaultStorage.loadCredentials()
                    _uiState.update {
                        it.copy(
                            isCheckingCredentials = false,
                            isLoggedIn = cached != null,
                            gamertag = cached?.gamertag
                        )
                    }
                }
            }
        }
    }

    /**
     * Invoked exclusively when the user clicks 'JUGAR AHORA' on a game.
     */
    fun onPlayNowClicked(
        titleId: String,
        isFreeToPlay: Boolean = false,
        onAuthorizedToStartStream: (StreamingSessionCredentials) -> Unit
    ) {
        if (_uiState.value.isCheckingCredentials) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingCredentials = true, engineDialog = EngineDialogState.Hidden) }

            when (val res = credentialOrchestrator.ensureValidStreamingToken(isFreeToPlay = isFreeToPlay)) {
                is CredentialResolutionResult.Ready -> {
                    _uiState.update {
                        it.copy(
                            isCheckingCredentials = false,
                            isLoggedIn = true,
                            gamertag = res.credentials.gamertag,
                            engineDialog = EngineDialogState.Hidden
                        )
                    }
                    onAuthorizedToStartStream(res.credentials)
                }
                is CredentialResolutionResult.RequiresInteractiveLogin -> {
                    // Zero friction: directly purge zombie session and open Device Code modal
                    _uiState.update {
                        it.copy(
                            isCheckingCredentials = false,
                            isLoggedIn = false,
                            gamertag = null,
                            showDeviceAuthModal = true,
                            engineDialog = EngineDialogState.Hidden
                        )
                    }
                }
                is CredentialResolutionResult.NetworkFailure -> {
                    _uiState.update {
                        it.copy(
                            isCheckingCredentials = false,
                            engineDialog = EngineDialogState.GenericError(
                                message = res.reason
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Deterministically dismisses the engine status dialog on first tap.
     */
    fun dismissEngineDialog(openLoginIfAuthRequired: Boolean = true) {
        val currentDialog = _uiState.value.engineDialog
        val shouldOpenLogin = openLoginIfAuthRequired && currentDialog is EngineDialogState.AuthRequired

        _uiState.update {
            it.copy(
                engineDialog = EngineDialogState.Hidden,
                showDeviceAuthModal = if (shouldOpenLogin) true else it.showDeviceAuthModal
            )
        }
    }

    /**
     * Header user chip interaction: opens profile dropdown if logged in, or Device Code modal if logged out.
     */
    fun onUserChipClicked() {
        if (_uiState.value.isLoggedIn) {
            _uiState.update { it.copy(showProfileMenu = true) }
        } else {
            _uiState.update { it.copy(showDeviceAuthModal = true) }
        }
    }

    fun onForceReLoginRequested() {
        viewModelScope.launch { vaultStorage.clearCredentials() }
        _uiState.update {
            it.copy(
                isLoggedIn = false,
                gamertag = null,
                showProfileMenu = false,
                engineDialog = EngineDialogState.Hidden,
                showDeviceAuthModal = true
            )
        }
    }

    fun onLogoutRequested() {
        viewModelScope.launch { vaultStorage.clearCredentials() }
        _uiState.update {
            it.copy(
                isLoggedIn = false,
                gamertag = null,
                showProfileMenu = false,
                engineDialog = EngineDialogState.Hidden
            )
        }
    }

    fun onDeviceAuthCompleted(newCredentials: StreamingSessionCredentials) {
        viewModelScope.launch { vaultStorage.saveCredentials(newCredentials) }
        _uiState.update {
            it.copy(
                isLoggedIn = true,
                gamertag = newCredentials.gamertag,
                showDeviceAuthModal = false,
                engineDialog = EngineDialogState.Hidden
            )
        }
    }

    fun dismissDeviceAuthModal() {
        _uiState.update { it.copy(showDeviceAuthModal = false) }
    }

    fun dismissProfileMenu() {
        _uiState.update { it.copy(showProfileMenu = false) }
    }
}
