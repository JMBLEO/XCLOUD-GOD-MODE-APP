package com.aistudio.xcloudgodmode.ai.models

import com.aistudio.xcloudgodmode.ai.api.XboxEndpoints

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
    val chameleonRgb: Triple<Int, Int, Int>? = null
) {
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
