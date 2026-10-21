package com.aistudio.xcloudgodmode.ai.models

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
     * Devuelve la URL formateada para la CDN de Microsoft con parámetros de redimensionado según el dispositivo
     */
    fun getFormattedPosterUrl(width: Int = 320, height: Int = 480): String {
        if (posterUrl.isBlank()) return ""
        val sanitized = if (posterUrl.startsWith("//")) "https:$posterUrl" else posterUrl
        return if (!sanitized.contains("?")) {
            "$sanitized?w=$width&h=$height&format=jpg&q=85"
        } else {
            sanitized
        }
    }

    fun getFormattedSuperHeroUrl(width: Int = 1280, height: Int = 720): String {
        if (superHeroUrl.isBlank()) return getFormattedPosterUrl(width, height)
        val sanitized = if (superHeroUrl.startsWith("//")) "https:$superHeroUrl" else superHeroUrl
        return if (!sanitized.contains("?")) {
            "$sanitized?w=$width&h=$height&format=jpg&q=85"
        } else {
            sanitized
        }
    }
}
