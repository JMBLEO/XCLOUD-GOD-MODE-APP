package com.jmbleo.xcloudgodmode.metadata

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Extractor de metadatos oficial del catálogo de Xbox (Microsoft Store DisplayCatalog API).
 * Permite obtener carátulas BoxArt en alta resolución, fondos Hero 4K y logos en PNG.
 */
class XboxMetadataExtractor(private val context: Context) {

    companion object {
        private const val XBOX_STORE_API = "https://displaycatalog.mp.microsoft.com/v7.0/products"
    }

    sealed interface ArtworkType {
        data class BoxArt(val width: Int, val height: Int) : ArtworkType
        data class HeroBackground(val width: Int, val height: Int) : ArtworkType
        data class TileLogo(val width: Int, val height: Int) : ArtworkType
    }

    /**
     * Extrae y resuelve las URLs en ultra alta calidad para un título de Xbox (Product ID o TitleID).
     */
    suspend fun fetchArtworkUrls(productId: String): Map<ArtworkType, String> = withContext(Dispatchers.IO) {
        val artworkMap = mutableMapOf<ArtworkType, String>()
        try {
            val url = "$XBOX_STORE_API?languages=es-ES,en-US&market=US&bigIds=$productId"
            val response = URL(url).readText()
            val json = JSONObject(response)
            val products = json.optJSONArray("Products") ?: return@withContext artworkMap

            if (products.length() > 0) {
                val product = products.getJSONObject(0)
                val images = product.optJSONArray("images") ?: return@withContext artworkMap

                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    val imgPurpose = img.optString("imagePurpose", "")
                    val rawUri = img.optString("uri", "")
                    if (rawUri.isBlank()) continue

                    val uri = if (rawUri.startsWith("//")) "https:$rawUri" else rawUri

                    when (imgPurpose) {
                        "BoxArt", "Poster" -> {
                            artworkMap[ArtworkType.BoxArt(1080, 1440)] = "$uri?format=jpg&w=1080&h=1440&q=90"
                        }
                        "Hero", "SuperHero", "Background" -> {
                            artworkMap[ArtworkType.HeroBackground(3840, 2160)] = "$uri?format=jpg&w=3840&h=2160&q=95"
                        }
                        "Logo", "TitelLogo", "TitleLogo" -> {
                            artworkMap[ArtworkType.TileLogo(800, 450)] = "$uri?format=png&w=800&h=450"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext artworkMap
    }
}
