package com.xcloud.godmode.network

/**
 * XboxSessionProfile: Represents a persistent user profile in the God Mode vault.
 */
data class XboxSessionProfile(
    val gamertag: String,
    val uhs: String,
    val gamerpic: String = "",
    val tierBadge: String = "Game Pass Ultimate",
    val ageGroup: String = "Adult",
    val lastUsedMs: Long = System.currentTimeMillis()
)
