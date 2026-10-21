package com.aistudio.xcloudgodmode.ai.services

import android.util.Log
import com.aistudio.xcloudgodmode.ai.models.GameModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CatalogRepository(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()
) {
    private val TAG = "CatalogRepository"

    val verifiedDefaultGames = listOf(
        GameModel(
            id = "9PNLT98ZLR9W",
            title = "Forza Horizon 5",
            genre = "Racing & Flying",
            publisher = "Playground Games",
            description = "Tu aventura definitiva en Horizon te espera. Explora los paisajes dinámicos y en constante evolución del mundo abierto de México con una conducción ilimitada y divertida en cientos de los mejores autos del mundo.",
            posterUrl = "https://store-images.s-microsoft.com/image/apps.43956.13727851868390641.c9cc8f66-aff8-44ae-85fb-5b6c3ea31e17.c467a304-4b47-49f3-8f0a-37c02b1f8fb8",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.58667.13727851868390641.c9cc8f66-aff8-44ae-85fb-5b6c3ea31e17.2285897e-128a-4934-be57-2e21c3248384",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.47454.13727851868390641.c9cc8f66-aff8-44ae-85fb-5b6c3ea31e17.8c0c4516-ecbe-4cf6-9fc0-fa9c6a1b028d",
            logoUrl = "https://store-images.s-microsoft.com/image/apps.24921.13727851868390641.c9cc8f66-aff8-44ae-85fb-5b6c3ea31e17.a4c49d32-d8b5-4428-bb8d-56540c117b96",
            trailerUrl = "https://assets.xboxservices.com/assets/79/53/79532585-78e7-49e0-8451-0eeea34e569c.mp4",
            screenshots = listOf(
                "https://store-images.s-microsoft.com/image/apps.47454.13727851868390641.c9cc8f66-aff8-44ae-85fb-5b6c3ea31e17.8c0c4516-ecbe-4cf6-9fc0-fa9c6a1b028d",
                "https://store-images.s-microsoft.com/image/apps.58667.13727851868390641.c9cc8f66-aff8-44ae-85fb-5b6c3ea31e17.2285897e-128a-4934-be57-2e21c3248384"
            ),
            rating = "ESRB Everyone",
            capabilities = listOf("Control Táctil Nativo", "4K Ultra HD", "HDR10", "60+ FPS"),
            languages = listOf("Español (AL)", "Inglés", "Francés", "Alemán"),
            accessibilityFeatures = listOf("Subtítulos", "Narrador", "Modo Daltónico"),
            chameleonRgb = Triple(244, 63, 134)
        ),
        GameModel(
            id = "9PP5G1F0C2B6",
            title = "Halo Infinite",
            genre = "Shooter",
            publisher = "343 Industries",
            description = "Cuando se pierde toda esperanza y el destino de la humanidad pende de un hilo, el Jefe Maestro está listo para enfrentarse al enemigo más despiadado que jamás haya encarado.",
            posterUrl = "https://store-images.s-microsoft.com/image/apps.21536.13727851868390641.5bfa6060-6395-4eb8-8c1d-15ec8b3c9ec4.0e6aa457-3773-4560-8f69-7c46f14e74dc",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.23249.13727851868390641.5bfa6060-6395-4eb8-8c1d-15ec8b3c9ec4.8e041639-65cb-4fa1-831e-450fdf58882b",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.23249.13727851868390641.5bfa6060-6395-4eb8-8c1d-15ec8b3c9ec4.8e041639-65cb-4fa1-831e-450fdf58882b",
            logoUrl = "https://store-images.s-microsoft.com/image/apps.21536.13727851868390641.5bfa6060-6395-4eb8-8c1d-15ec8b3c9ec4.0e6aa457-3773-4560-8f69-7c46f14e74dc",
            trailerUrl = "https://assets.xboxservices.com/assets/f4/bf/f4bfa556-9ff5-430c-b26a-9faeb65d7ca4.mp4",
            screenshots = listOf(
                "https://store-images.s-microsoft.com/image/apps.23249.13727851868390641.5bfa6060-6395-4eb8-8c1d-15ec8b3c9ec4.8e041639-65cb-4fa1-831e-450fdf58882b"
            ),
            rating = "ESRB Teen",
            capabilities = listOf("Control Táctil Nativo", "Multijugador Online", "120 FPS"),
            languages = listOf("Español", "Inglés", "Portugués"),
            chameleonRgb = Triple(56, 189, 248)
        ),
        GameModel(
            id = "BX3J7PHQ2KVX",
            title = "Gears 5",
            genre = "Action & Adventure",
            publisher = "The Coalition",
            description = "De una de las sagas más aclamadas de los videojuegos, Gears 5 es más grande que nunca, con cinco modos emocionantes y la campaña más profunda hasta la fecha.",
            posterUrl = "https://store-images.s-microsoft.com/image/apps.49077.14327242129181180.e4d36ef5-33ec-42f8-a149-a2926719e7a8.723cb829-d5e1-45a4-9e79-56e6d1eb2be1",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.49077.14327242129181180.e4d36ef5-33ec-42f8-a149-a2926719e7a8.723cb829-d5e1-45a4-9e79-56e6d1eb2be1",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.49077.14327242129181180.e4d36ef5-33ec-42f8-a149-a2926719e7a8.723cb829-d5e1-45a4-9e79-56e6d1eb2be1",
            logoUrl = "https://store-images.s-microsoft.com/image/apps.49077.14327242129181180.e4d36ef5-33ec-42f8-a149-a2926719e7a8.723cb829-d5e1-45a4-9e79-56e6d1eb2be1",
            trailerUrl = "",
            screenshots = emptyList(),
            rating = "ESRB Mature 17+",
            capabilities = listOf("Control Táctil Nativo", "Co-op Local", "HDR10"),
            languages = listOf("Español", "Inglés"),
            chameleonRgb = Triple(239, 68, 68)
        )
,
        GameModel(
            id = "9NCGNJMRWB1Q",
            title = "Starfield",
            genre = "Role Playing",
            publisher = "Bethesda Game Studios",
            description = "Starfield es el primer universo nuevo en más de 25 años de Bethesda Game Studios, los galardonados creadores de The Elder Scrolls V: Skyrim y Fallout 4.",
            posterUrl = "https://store-images.s-microsoft.com/image/apps.43956.14561084620577041.5d9c79fa-7023-4519-aa57-4184ec6ef0dc.ca58f000-f0c3-4d44-be1f-824db21868cb",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.28424.14561084620577041.5d9c79fa-7023-4519-aa57-4184ec6ef0dc.3ecff3a7-59b3-4679-b132-7a24559b158c",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.28424.14561084620577041.5d9c79fa-7023-4519-aa57-4184ec6ef0dc.3ecff3a7-59b3-4679-b132-7a24559b158c",
            logoUrl = "https://store-images.s-microsoft.com/image/apps.43956.14561084620577041.5d9c79fa-7023-4519-aa57-4184ec6ef0dc.ca58f000-f0c3-4d44-be1f-824db21868cb",
            trailerUrl = "",
            screenshots = listOf("https://store-images.s-microsoft.com/image/apps.28424.14561084620577041.5d9c79fa-7023-4519-aa57-4184ec6ef0dc.3ecff3a7-59b3-4679-b132-7a24559b158c"),
            rating = "ESRB Mature 17+",
            capabilities = listOf("Xbox Series X|S Optimizado", "Audio Espacial 3D"),
            chameleonRgb = Triple(245, 158, 11)
        ),
        GameModel(
            id = "9P2N57MC619K",
            title = "Sea of Thieves",
            genre = "Action & Adventure",
            publisher = "Rare Ltd",
            description = "Sea of Thieves ofrece la experiencia de piratas definitiva, desde navegar y combatir hasta explorar y saquear.",
            posterUrl = "https://store-images.s-microsoft.com/image/apps.58572.13510798887493399.b9be0027-4a0b-4171-8bc4-a690bf034371.3c84be5e-aeec-4d7d-b586-3f1a268a0f9b",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.58572.13510798887493399.b9be0027-4a0b-4171-8bc4-a690bf034371.3c84be5e-aeec-4d7d-b586-3f1a268a0f9b",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.58572.13510798887493399.b9be0027-4a0b-4171-8bc4-a690bf034371.3c84be5e-aeec-4d7d-b586-3f1a268a0f9b",
            logoUrl = "https://store-images.s-microsoft.com/image/apps.58572.13510798887493399.b9be0027-4a0b-4171-8bc4-a690bf034371.3c84be5e-aeec-4d7d-b586-3f1a268a0f9b",
            trailerUrl = "",
            screenshots = emptyList(),
            rating = "ESRB Teen",
            capabilities = listOf("Control Táctil Nativo", "Cross-Play", "Multiplayer"),
            chameleonRgb = Triple(16, 185, 129)
        )
    )

    /**
     * Sincronizador de Arte Ultra-HD: Busca en Steam Store API para obtener carátulas 600x900_2x y SuperHeroArt 4K.
     */
    suspend fun searchStore(query: String): List<GameModel> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()
        try {
            val steamUrl = "https://store.steampowered.com/api/storesearch/?term=${java.net.URLEncoder.encode(query, "UTF-8")}&l=spanish&cc=CL"
            val req = Request.Builder().url(steamUrl).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()

            val json = JSONObject(resp.body?.string() ?: "{}")
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            val results = mutableListOf<GameModel>()

            for (i in 0 until minOf(items.length(), 10)) {
                val item = items.optJSONObject(i) ?: continue
                val appId = item.optInt("id")
                val name = item.optString("name")
                
                results.add(
                    GameModel(
                        id = "STEAM-$appId",
                        title = name,
                        publisher = "Sincronizado via Steam CDN",
                        description = "Portada 600x900_2x y Hero 4K obtenidos en tiempo real. AppID: $appId.",
                        posterUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900_2x.jpg",
                        heroUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$appId/library_hero.jpg",
                        superHeroUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$appId/library_hero.jpg",
                        logoUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg",
                        chameleonRgb = deriveColorFromTitle(name)
                    )
                )
            }
            return@withContext results
        } catch (e: Exception) {
            Log.w(TAG, "Steam search error: ${e.message}")
            return@withContext emptyList()
        }
    }

    suspend fun fetchLiveCatalog(
        authManager: XboxFullAuthManager? = null,
        gsToken: String? = null,
        regionUri: String? = null
    ): List<GameModel> = withContext(Dispatchers.IO) {
        try {
            // Case 1: Authenticated fetching (Directly from Xbox GSSV Titles API)
            if (authManager != null && !gsToken.isNullOrBlank() && !regionUri.isNullOrBlank()) {
                val titlesJson = authManager.getCloudTitles(gsToken, regionUri)
                val results = titlesJson.optJSONArray("results") ?: JSONArray()
                
                val productIds = mutableListOf<String>()
                for (i in 0 until results.length()) {
                    val title = results.optJSONObject(i) ?: continue
                    val productId = title.optJSONObject("details")?.optString("productId") ?: ""
                    if (productId.isNotBlank()) productIds.add(productId)
                }

                // Batch hydrate details from GamePass Catalog
                if (productIds.isNotEmpty()) {
                    val chunks = productIds.chunked(25)
                    val hydratedList = mutableListOf<GameModel>()
                    
                    for (chunk in chunks) {
                        try {
                            val detailsJson = authManager.getCloudTitleDetails(chunk)
                            for (pid in chunk) {
                                val prod = detailsJson.optJSONObject(pid) ?: continue
                                hydratedList.add(parseGamePassProduct(prod))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Chunk hydration error: ${e.message}")
                        }
                    }
                    
                    if (hydratedList.isNotEmpty()) {
                        // Merge with verified defaults to ensure critical games are always there
                        val final = hydratedList.toMutableList()
                        for (def in verifiedDefaultGames) {
                            if (final.none { it.id == def.id }) final.add(def)
                        }
                        return@withContext final
                    }
                }
            }

            // Case 2: Public fetching (GamePass SIGL)
            val siglUrl = "https://catalog.gamepass.com/sigls/v2?id=29a81209-df6f-41fd-a528-2ae6b91f719c&language=es-CL&market=CL"
            val req = Request.Builder().url(siglUrl).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext verifiedDefaultGames

            val siglArray = JSONArray(resp.body?.string() ?: "[]")
            val ids = mutableListOf<String>()
            for (i in 0 until minOf(siglArray.length(), 30)) {
                val item = siglArray.optJSONObject(i)
                val id = item?.optString("id")
                if (!id.isNullOrBlank()) ids.add(id)
            }

            if (ids.isEmpty()) return@withContext verifiedDefaultGames

            // Public display catalog fallback
            val displayCatalogUrl = "https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=${ids.joinToString(",")}&market=CL&languages=es-CL"
            val catReq = Request.Builder().url(displayCatalogUrl).build()
            val catResp = client.newCall(catReq).execute()
            if (!catResp.isSuccessful) return@withContext verifiedDefaultGames

            val json = JSONObject(catResp.body?.string() ?: "{}")
            val products = json.optJSONArray("Products") ?: return@withContext verifiedDefaultGames

            val hydratedGames = mutableListOf<GameModel>()
            for (i in 0 until products.length()) {
                val prod = products.optJSONObject(i) ?: continue
                val prodId = prod.optString("ProductId")
                val localizedProps = prod.optJSONArray("LocalizedProperties")?.optJSONObject(0)
                val title = localizedProps?.optString("ProductTitle") ?: "Xbox Title"
                val publisher = localizedProps?.optString("PublisherName") ?: "Xbox Game Studios"
                val desc = localizedProps?.optString("ProductDescription") ?: ""
                val images = localizedProps?.optJSONArray("Images") ?: JSONArray()

                var poster = ""
                var hero = ""
                var superHero = ""
                var logo = ""
                val screenshots = mutableListOf<String>()

                for (j in 0 until images.length()) {
                    val img = images.optJSONObject(j) ?: continue
                    val purpose = img.optString("ImagePurpose")
                    var uri = img.optString("Uri")
                    if (uri.startsWith("//")) uri = "https:$uri"

                    when (purpose) {
                        "Poster", "BoxArt" -> if (poster.isEmpty()) poster = uri
                        "TitledHeroArt", "HeroArt" -> if (hero.isEmpty()) hero = uri
                        "SuperHeroArt" -> superHero = uri
                        "Logo" -> logo = uri
                        "Screenshot" -> screenshots.add(uri)
                    }
                }

                if (poster.isBlank() && hero.isNotBlank()) poster = hero
                if (superHero.isBlank()) superHero = hero.ifBlank { poster }

                val properties = prod.optJSONArray("MarketProperties")?.optJSONObject(0)
                val ratingInfo = prod.optJSONArray("Properties")?.optJSONObject(0)?.optJSONArray("Ratings")?.optJSONObject(0)
                val ratingIcon = ratingInfo?.optString("RatingImageUri") ?: ""
                val contentRating = ratingInfo?.optString("RatingDescriptor") ?: ""

                hydratedGames.add(
                    GameModel(
                        id = prodId,
                        title = title,
                        publisher = publisher,
                        description = desc,
                        posterUrl = poster,
                        heroUrl = hero,
                        superHeroUrl = superHero,
                        logoUrl = logo,
                        screenshots = screenshots,
                        rating = contentRating,
                        ratingIconUrl = if (ratingIcon.startsWith("//")) "https:$ratingIcon" else ratingIcon,
                        isGamePass = true,
                        chameleonRgb = deriveColorFromTitle(title)
                    )
                )
            }

            return@withContext if (hydratedGames.isNotEmpty()) hydratedGames else verifiedDefaultGames
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching live catalog: ${e.message}")
            return@withContext verifiedDefaultGames
        }
    }

    private fun parseGamePassProduct(json: JSONObject): GameModel {
        val prodId = json.optString("StoreId")
        val title = json.optString("ProductTitle", "Unknown Game")
        val publisher = json.optString("PublisherName", "Xbox Game Studios")
        val desc = json.optString("ProductDescription", "")
        
        val poster = json.optJSONObject("Image_Poster")?.optString("URL", "")?.let { if (it.startsWith("//")) "https:$it" else it } ?: ""
        val hero = json.optJSONObject("Image_Hero")?.optString("URL", "")?.let { if (it.startsWith("//")) "https:$it" else it } ?: ""
        val tile = json.optJSONObject("Image_Tile")?.optString("URL", "")?.let { if (it.startsWith("//")) "https:$it" else it } ?: ""
        
        val screenshots = mutableListOf<String>()
        val ssArray = json.optJSONArray("Screenshots")
        if (ssArray != null) {
            for (i in 0 until ssArray.length()) {
                val ss = ssArray.optJSONObject(i)?.optString("URL")
                if (ss != null) screenshots.add(if (ss.startsWith("//")) "https:$ss" else ss)
            }
        }

        return GameModel(
            id = prodId,
            title = title,
            publisher = publisher,
            description = desc,
            posterUrl = if (poster.isNotBlank()) poster else tile,
            heroUrl = hero,
            superHeroUrl = hero,
            screenshots = screenshots,
            chameleonRgb = deriveColorFromTitle(title)
        )
    }

    private fun deriveColorFromTitle(title: String): Triple<Int, Int, Int> {
        val hash = title.hashCode()
        val r = (hash and 0xFF0000 shr 16).coerceIn(100, 240)
        val g = (hash and 0x00FF00 shr 8).coerceIn(100, 240)
        val b = (hash and 0x0000FF).coerceIn(100, 240)
        return Triple(r, g, b)
    }
}
