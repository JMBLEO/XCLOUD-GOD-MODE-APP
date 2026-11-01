package com.xcloud.godmode.models

import com.xcloud.godmode.network.XboxEndpoints

data class GameModel(
    val id: String,
    val title: String,
    val genre: String = "Action & Adventure",
    val publisher: String = "Xbox Game Studios",
    val description: String = "",
    val posterUrl: String = "",
    val heroUrl: String = "",
    val superHeroUrl: String = "",
    val logoUrl: String = "",
    val trailerUrl: String = "",
    val screenshots: List<String> = emptyList(),
    val rating: String = "ESRB Everyone",
    val ratingIconUrl: String = "",
    val contentDescriptors: List<String> = emptyList(),
    val capabilities: List<String> = listOf("Cloud Enabled", "Xbox Series X|S", "Multiplayer"),
    val languages: List<String> = emptyList(),
    val accessibilityFeatures: List<String> = emptyList(),
    val isGamePass: Boolean = true,
    val isOwned: Boolean = false,
    val isFavorite: Boolean = false,
    val chameleonRgb: Triple<Int, Int, Int>? = null,
    val editionType: String? = null,
    val subscriptionTier: String = "ULTIMATE · PREMIUM",
    val supportsTouch: Boolean = true,
    val supportsMkb: Boolean = false,
    val isSeriesOptimized: Boolean = true
) {
    /**
     * Returns the full title including the 'Maldad' (top version) suffix.
     * Only appends ' Edition' if editionType is non-null and not blank.
     */
    fun getFullTitle(): String {
        return if (!editionType.isNullOrBlank()) {
            if (editionType.contains("Edition", ignoreCase = true) || editionType.contains("Collection", ignoreCase = true)) {
                "$title $editionType"
            } else {
                "$title $editionType Edition"
            }
        } else title
    }
    /**
     * Formats poster URL with Microsoft Store or custom sizing
     */
    fun getFormattedPosterUrl(width: Int = 320, height: Int = 480): String {
        if (posterUrl.isBlank()) return ""
        return XboxEndpoints.normalizeImageUrl(posterUrl, width, height)
    }

    /**
     * Formats super hero or background art URL with HD/4K parameters
     */
    fun getFormattedSuperHeroUrl(width: Int = 1920, height: Int = 1080): String {
        val target = when {
            superHeroUrl.isNotBlank() -> superHeroUrl
            heroUrl.isNotBlank() -> heroUrl
            else -> posterUrl
        }
        if (target.isBlank()) return ""
        return XboxEndpoints.normalizeImageUrl(target, width, height)
    }
}
