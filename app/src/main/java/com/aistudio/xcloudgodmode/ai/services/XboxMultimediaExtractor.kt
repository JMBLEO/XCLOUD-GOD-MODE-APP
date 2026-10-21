package com.aistudio.xcloudgodmode.ai.services

import android.util.Log
import com.aistudio.xcloudgodmode.ai.api.XboxEndpoints
import com.aistudio.xcloudgodmode.ai.models.GameModel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class XboxMultimediaExtractor(
    private val uhs: String,
    private val webToken: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "XboxMultimediaExtractor"
    private val authHeader = "XBL3.0 x=$uhs;$webToken"

    private fun getStandardHeaders(contractVersion: String = "3"): okhttp3.Headers {
        return okhttp3.Headers.Builder()
            .add("Authorization", authHeader)
            .add("x-xbl-contract-version", contractVersion)
            .add("x-ms-api-version", "1.0")
            .add("Accept", "application/json")
            .add("Accept-Language", "es-CL,es;q=0.9,en-US;q=0.8")
            .build()
    }

    suspend fun fetchRichMediaForProducts(productIds: List<String>): List<GameModel> {
        return try {
            val idsParam = productIds.joinToString(",")
            val url = "${XboxEndpoints.MEDIA_AND_API_DOMAINS["EMERALD_CATALOG"]}/xboxcomfd/products/$idsParam?locale=es-CL"
            
            val request = Request.Builder()
                .url(url)
                .headers(getStandardHeaders("2"))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val json = JSONObject(response.body?.string() ?: "{}")
            val products = json.optJSONArray("Products") ?: json.optJSONArray("products") ?: return emptyList()

            val hydratedGames = mutableListOf<GameModel>()
            for (i in 0 until products.length()) {
                val prod = products.optJSONObject(i) ?: continue
                
                val images = prod.optJSONArray("Images") ?: prod.optJSONArray("images") ?: org.json.JSONArray()
                
                var poster = ""
                var hero = ""
                var logo = ""
                val screenshots = mutableListOf<String>()

                for (j in 0 until images.length()) {
                    val img = images.optJSONObject(j) ?: continue
                    val purpose = img.optString("ImagePurpose", "").lowercase()
                    val uri = img.optString("Uri", "")
                    val normalized = XboxEndpoints.normalizeImageUrl(uri)
                    
                    when (purpose) {
                        "poster", "boxart" -> if (poster.isEmpty()) poster = normalized
                        "titledheroart", "heroart" -> if (hero.isEmpty()) hero = normalized
                        "logo" -> logo = normalized
                        "screenshot" -> screenshots.add(normalized)
                    }
                }

                hydratedGames.add(
                    GameModel(
                        id = prod.optString("ProductId", ""),
                        title = prod.optString("ProductTitle", "Xbox Title"),
                        posterUrl = poster,
                        heroUrl = hero,
                        superHeroUrl = hero.ifBlank { poster },
                        logoUrl = logo,
                        screenshots = screenshots,
                        rating = prod.optJSONArray("Properties")?.optJSONObject(0)?.optJSONArray("Ratings")?.optJSONObject(0)?.optString("RatingDescriptor") ?: "ESRB Everyone"
                    )
                )
            }
            hydratedGames
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching media: ${e.message}")
            emptyList()
        }
    }
}
