package com.xcloud.godmode.services

import com.xcloud.godmode.models.AuthenticationState
import com.xcloud.godmode.models.SecureAuthTokenReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AuthenticationRepository {
    val authState: StateFlow<AuthenticationState>
    val tokenReference: SecureAuthTokenReference?

    suspend fun authenticate()
    suspend fun refreshSession()
    suspend fun clearSession()
}

class DefaultAuthenticationRepository : AuthenticationRepository {
    private val _authState = MutableStateFlow(AuthenticationState.NOT_CONFIGURED)
    override val authState: StateFlow<AuthenticationState> = _authState.asStateFlow()

    private var _tokenReference: SecureAuthTokenReference? = null
    override val tokenReference: SecureAuthTokenReference?
        get() = _tokenReference

    override suspend fun authenticate() {
        _authState.value = AuthenticationState.AUTHENTICATING
        // Real authentication gate logic will update status without exposing raw tokens in UI models
        _tokenReference = SecureAuthTokenReference(
            keyAlias = "godmode_sec_token_alias",
            expiresAtEpochMs = System.currentTimeMillis() + 86400000L,
            isEncryptedInKeystore = true
        )
        _authState.value = AuthenticationState.AUTHENTICATED
    }

    override suspend fun refreshSession() {
        if (_authState.value == AuthenticationState.AUTHENTICATED || _authState.value == AuthenticationState.REFRESH_REQUIRED) {
            _authState.value = AuthenticationState.AUTHENTICATING
            _authState.value = AuthenticationState.AUTHENTICATED
        }
    }

    override suspend fun clearSession() {
        _tokenReference = null
        _authState.value = AuthenticationState.NOT_CONFIGURED
    }
}
