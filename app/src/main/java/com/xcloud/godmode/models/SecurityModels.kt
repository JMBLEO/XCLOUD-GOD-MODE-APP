package com.xcloud.godmode.models

enum class AuthenticationState(val displayName: String) {
    NOT_CONFIGURED("Sin configurar"),
    AUTHENTICATING("Autenticando..."),
    AUTHENTICATED("Autenticado"),
    REFRESH_REQUIRED("Renovación requerida"),
    EXPIRED("Sesión caducada"),
    REVOKED("Sesión revocada"),
    UNAVAILABLE("Servicio no disponible")
}

data class SecureAuthTokenReference(
    val keyAlias: String,
    val expiresAtEpochMs: Long,
    val isEncryptedInKeystore: Boolean
)
