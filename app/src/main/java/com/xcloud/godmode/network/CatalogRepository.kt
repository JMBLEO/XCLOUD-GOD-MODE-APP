package com.xcloud.godmode.network

import android.util.Log
import com.xcloud.godmode.models.GameModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CatalogRepository(
    private val db: com.xcloud.godmode.models.GodModeDatabase? = null,
    val client: OkHttpClient = OkHttpClient.Builder()
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
            posterUrl = "https://store-images.s-microsoft.com/image/apps.49800.13718773309227929.bebdcc0e-1ed5-4778-8732-f4ef65a2f445.9ac09d39-064d-466c-81ca-2f1b6f0b95c5",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.33953.13718773309227929.bebdcc0e-1ed5-4778-8732-f4ef65a2f445.9428b75f-2c08-4e70-9f95-281741b15341",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.33953.13718773309227929.bebdcc0e-1ed5-4778-8732-f4ef65a2f445.9428b75f-2c08-4e70-9f95-281741b15341",
            logoUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1551360/logo.png",
            trailerUrl = "https://assets.xboxservices.com/assets/79/53/79532585-78e7-49e0-8451-0eeea34e569c.mp4",
            screenshots = listOf(
                "https://store-images.s-microsoft.com/image/apps.33953.13718773309227929.bebdcc0e-1ed5-4778-8732-f4ef65a2f445.9428b75f-2c08-4e70-9f95-281741b15341"
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
            posterUrl = "https://store-images.s-microsoft.com/image/apps.3823.14330850369313893.a687698c-b891-44f6-9576-fe28978ce915.5aada4fa-d850-4eb5-9100-33a81a5cde09",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.47030.14330850369313893.a687698c-b891-44f6-9576-fe28978ce915.ce613c75-aac4-4721-9941-6a254d061488",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.47030.14330850369313893.a687698c-b891-44f6-9576-fe28978ce915.ce613c75-aac4-4721-9941-6a254d061488",
            logoUrl = "https://store-images.s-microsoft.com/image/apps.52504.14330850369313893.a687698c-b891-44f6-9576-fe28978ce915.95e011f8-5d23-40a4-abde-4ae00a4bb6f7",
            trailerUrl = "https://assets.xboxservices.com/assets/f4/bf/f4bfa556-9ff5-430c-b26a-9faeb65d7ca4.mp4",
            screenshots = listOf(
                "https://store-images.s-microsoft.com/image/apps.47030.14330850369313893.a687698c-b891-44f6-9576-fe28978ce915.ce613c75-aac4-4721-9941-6a254d061488"
            ),
            rating = "ESRB Teen",
            capabilities = listOf("Control Táctil Nativo", "Multijugador Online", "120 FPS", "Jugar con Teclado y Ratón"),
            languages = listOf("Español", "Inglés", "Portugués"),
            isOwned = true,
            chameleonRgb = Triple(56, 189, 248)
        ),
        GameModel(
            id = "C2KDNLT2H7DM",
            title = "Gears 5",
            genre = "Action & Adventure",
            publisher = "The Coalition",
            description = "De una de las sagas más aclamadas de los videojuegos, Gears 5 es más grande que nunca, con cinco modos emocionantes y la campaña más profunda hasta la fecha.",
            posterUrl = "https://store-images.s-microsoft.com/image/apps.10933.65494054591008504.4eae27ba-1cfe-4af2-950a-4af81f8cd22a.b5b2909d-8884-48d0-b25a-68d6518c9b73",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.65421.65494054591008504.4eae27ba-1cfe-4af2-950a-4af81f8cd22a.fd4fa6b5-431f-47bc-b9c8-3844383dfb20",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.65421.65494054591008504.4eae27ba-1cfe-4af2-950a-4af81f8cd22a.fd4fa6b5-431f-47bc-b9c8-3844383dfb20",
            logoUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1097840/logo.png",
            trailerUrl = "",
            screenshots = listOf("https://store-images.s-microsoft.com/image/apps.65421.65494054591008504.4eae27ba-1cfe-4af2-950a-4af81f8cd22a.fd4fa6b5-431f-47bc-b9c8-3844383dfb20"),
            rating = "ESRB Mature 17+",
            capabilities = listOf("Control Táctil Nativo", "Co-op Local", "HDR10"),
            languages = listOf("Español", "Inglés"),
            chameleonRgb = Triple(239, 68, 68)
        ),
        GameModel(
            id = "9NCGNJMRWB1Q",
            title = "Starfield",
            genre = "Role Playing",
            publisher = "Bethesda Game Studios",
            description = "Starfield es el primer universo nuevo en más de 25 años de Bethesda Game Studios, los galardonados creadores de The Elder Scrolls V: Skyrim y Fallout 4.",
            posterUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1716740/library_600x900_2x.jpg",
            heroUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1716740/library_hero.jpg",
            superHeroUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1716740/library_hero.jpg",
            logoUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1716740/logo.png",
            trailerUrl = "",
            screenshots = listOf("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1716740/library_hero.jpg"),
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
            posterUrl = "https://store-images.s-microsoft.com/image/apps.29206.14554784103656548.069efce3-9249-4074-a169-183b727043f8.03688f8c-edc0-416b-bebb-9d98a01c25f5",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.25532.14554784103656548.069efce3-9249-4074-a169-183b727043f8.c6e2146d-d417-4be0-bb6c-926f7cc67376",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.25532.14554784103656548.069efce3-9249-4074-a169-183b727043f8.c6e2146d-d417-4be0-bb6c-926f7cc67376",
            logoUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1172620/logo.png",
            trailerUrl = "",
            screenshots = listOf("https://store-images.s-microsoft.com/image/apps.25532.14554784103656548.069efce3-9249-4074-a169-183b727043f8.c6e2146d-d417-4be0-bb6c-926f7cc67376"),
            rating = "ESRB Teen",
            capabilities = listOf("Control Táctil Nativo", "Cross-Play", "Multiplayer"),
            chameleonRgb = Triple(16, 185, 129)
        ),
        GameModel(
            id = "GEARS-EDAY-STORE",
            title = "Gears of War: E-Day",
            genre = "Shooter • Acción y aventura",
            publisher = "Xbox Game Studios",
            description = "Gears of War: E-Day es la próxima entrega principal del universo de Gears of War. Catorce años antes de los eventos del juego original, los héroes de guerra Marcus Fenix y Dom Santiago regresan a casa para enfrentarse a una nueva pesadilla: la Horda Locust.",
            posterUrl = "https://store-images.s-microsoft.com/image/apps.20048.13978914104035031.46ab30c4-87b3-48d6-9e4a-ed44b301b17a.6e05624f-9f48-4169-ac52-04a7bbe5c7e8?h=1040&format=jpg",
            heroUrl = "https://store-images.s-microsoft.com/image/apps.20048.13978914104035031.46ab30c4-87b3-48d6-9e4a-ed44b301b17a.6e05624f-9f48-4169-ac52-04a7bbe5c7e8?h=1040&format=jpg",
            superHeroUrl = "https://store-images.s-microsoft.com/image/apps.20048.13978914104035031.46ab30c4-87b3-48d6-9e4a-ed44b301b17a.6e05624f-9f48-4169-ac52-04a7bbe5c7e8?h=1040&format=jpg",
            logoUrl = "https://store-images.microsoft.com/image/global.53975.image.32437939-2f27-4e79-8431-5f5866f32908.3dc74b18-4153-423c-b367-bdcfd34ac658",
            trailerUrl = "https://assets.xboxservices.com/assets/f4/bf/f4bfa556-9ff5-430c-b26a-9faeb65d7ca4.mp4",
            screenshots = listOf(
                "https://store-images.s-microsoft.com/image/apps.20048.13978914104035031.46ab30c4-87b3-48d6-9e4a-ed44b301b17a.6e05624f-9f48-4169-ac52-04a7bbe5c7e8?h=1040&format=jpg"
            ),
            rating = "Maduro 17+",
            contentDescriptors = listOf(
                "Derramamiento de sangre",
                "Lenguaje fuerte",
                "Violencia intensa",
                "Compras dentro del juego",
                "Interacción de usuarios"
            ),
            capabilities = listOf("Jugar con Mando", "Jugar con Teclado y Ratón", "Xbox Series X|S Optimizado"),
            languages = listOf("22 idiomas compatibles"),
            accessibilityFeatures = listOf("20 funciones de accesibilidad"),
            isGamePass = true,
            isOwned = false,
            chameleonRgb = Triple(185, 28, 28)
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
                        chameleonRgb = deriveColorFromTitle(name),
                        editionType = getEditionType(name),
                        rating = resolveESRBFromTitle(name)
                    )
                )
            }
            return@withContext results
        } catch (e: Exception) {
            Log.w(TAG, "Steam search error: ${e.message}")
            return@withContext emptyList()
        }
    }

    private fun resolveESRBFromTitle(title: String): String {
        val lower = title.lowercase()
        return when {
            // Mature 17+ Keywords
            lower.contains("doom") || lower.contains("gears") || lower.contains("fallout") || 
            lower.contains("starfield") || lower.contains("resident") || lower.contains("evil") || 
            lower.contains("cyberpunk") || lower.contains("mortal") || lower.contains("dead") || 
            lower.contains("killer") || lower.contains("witcher") || lower.contains("yakuza") || 
            lower.contains("gta") || lower.contains("wolfenstein") || lower.contains("diablo") || 
            lower.contains("remnant") || lower.contains("scorn") || lower.contains("call of duty") ||
            lower.contains("atomic heart") || lower.contains("lies of p") || lower.contains("hellblade") ||
            lower.contains("sniper") || lower.contains("payday") || lower.contains("hitman") ||
            lower.contains("conan") || lower.contains("warhammer") || lower.contains("mass effect") ||
            lower.contains("valor mortis") -> "ESRB Mature 17+"

            // Teen Keywords
            lower.contains("halo") || lower.contains("sea of thieves") || lower.contains("destiny") || 
            lower.contains("battlefield") || lower.contains("street fighter") || lower.contains("tekken") || 
            lower.contains("dragon ball") || lower.contains("naruto") || lower.contains("one piece") || 
            lower.contains("persona") || lower.contains("monster hunter") || lower.contains("injustice") ||
            lower.contains("rainbow 6") || lower.contains("siege") ||
            lower.contains("ark") || lower.contains("valheim") || lower.contains("overwatch") ||
            lower.contains("fortnite") || lower.contains("apex") || lower.contains("warframe") -> "ESRB Teen"

            // Everyone Keywords
            lower.contains("minecraft") || lower.contains("forza") || lower.contains("flight") || 
            lower.contains("fifa") || lower.contains("madden") || lower.contains("lego") || 
            lower.contains("ori") || lower.contains("psychonauts") || lower.contains("paw patrol") || 
            lower.contains("peppa") || lower.contains("bluey") || lower.contains("rush") ||
            lower.contains("f1") || lower.contains("powerwash") || lower.contains("goat") ||
            lower.contains("unravel") || lower.contains("stardew") || lower.contains("cities") ||
            lower.contains("slime rancher") || lower.contains("disney") || lower.contains("cars") -> "ESRB Everyone"
            
            else -> "ESRB Everyone 10+"
        }
    }

    suspend fun fetchLiveCatalog(
        authManager: XboxFullAuthManager? = null,
        gsToken: String? = null,
        regionUri: String? = null
    ): List<GameModel> = withContext(Dispatchers.IO) {
        try {
            var hydratedGames = mutableListOf<GameModel>()

            // Case 1: Authenticated fetching
            if (authManager != null && !gsToken.isNullOrBlank() && !regionUri.isNullOrBlank()) {
                val titlesJson = authManager.getCloudTitles(gsToken, regionUri)
                val results = titlesJson.optJSONArray("results") ?: JSONArray()
                
                val productIds = mutableListOf<String>()
                for (i in 0 until results.length()) {
                    val title = results.optJSONObject(i) ?: continue
                    val productId = title.optJSONObject("details")?.optString("productId") ?: ""
                    if (productId.isNotBlank()) productIds.add(productId)
                }

                if (productIds.isNotEmpty()) {
                    val chunks = productIds.chunked(25)
                    for (chunk in chunks) {
                        try {
                            val detailsJson = authManager.getCloudTitleDetails(chunk)
                            for (pid in chunk) {
                                val prod = detailsJson.optJSONObject(pid) ?: continue
                                hydratedGames.add(parseGamePassProduct(prod))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Chunk hydration notice: ${e.message}")
                        }
                    }
                }
            }

            // Case 2: Public fetching (fallback / full catalog fetching)
            if (hydratedGames.isEmpty()) {
                val collections = listOf(
                    "29a81209-df6f-41fd-a528-2ae6b91f719c", // All Game Pass Cloud Games
                    "6a589fa0-d493-472b-8e20-3813699d7056", // Most popular on cloud
                    "06323672-b8c8-43cc-b0de-32d5a9834749", // Recently added
                    "e78d9a61-5ef4-43af-b400-edba1250b18e", // Stream Your Own Game (BYOG)
                    "8fa264dd-124f-4af3-97e8-596fcdf4b486"  // Native Mouse & Keyboard
                )
                val allSiglIds = mutableSetOf<String>()
                for (colId in collections) {
                    val siglUrl = "https://catalog.gamepass.com/sigls/v2?id=$colId&language=es-CL&market=CL"
                    val req = Request.Builder().url(siglUrl).build()
                    try {
                        val resp = client.newCall(req).execute()
                        if (resp.isSuccessful) {
                            val siglArray = JSONArray(resp.body?.string() ?: "[]")
                            for (i in 0 until siglArray.length()) {
                                val item = siglArray.optJSONObject(i)
                                val id = item?.optString("id")
                                if (!id.isNullOrBlank() && id.length > 4) {
                                    allSiglIds.add(id)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error fetching collection $colId: ${e.message}")
                    }
                }

                if (allSiglIds.isNotEmpty()) {
                    val chunks = allSiglIds.toList().chunked(25)
                    for (chunk in chunks) {
                        val displayCatalogUrl = "https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=${chunk.joinToString(",")}&market=CL&languages=es-CL"
                        val catReq = Request.Builder().url(displayCatalogUrl).build()
                        try {
                            val catResp = client.newCall(catReq).execute()
                            if (catResp.isSuccessful) {
                                val json = JSONObject(catResp.body?.string() ?: "{}")
                                val products = json.optJSONArray("Products") ?: JSONArray()
                                for (i in 0 until products.length()) {
                                    val prod = products.optJSONObject(i) ?: continue
                                    hydratedGames.add(parseDisplayCatalogProduct(prod))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Chunk display catalog error: ${e.message}")
                        }
                    }
                }
            }

            // Case 3: Dynamic Catalog Collections Integration
            try {
                val dynamicRepo = com.xcloud.godmode.data.DynamicXboxCatalogRepository(client)
                val shelves = dynamicRepo.loadDynamicHomeShelves(market = "CL", language = "es-CL")
                val dynamicTiles = shelves.values.flatten().distinctBy { it.productId }
                for (tile in dynamicTiles) {
                    if (hydratedGames.none { it.id == tile.productId }) {
                        hydratedGames.add(
                            GameModel(
                                id = tile.productId,
                                title = tile.title,
                                genre = tile.genre,
                                rating = resolveESRBFromTitle(tile.title),
                                publisher = tile.publisher,
                                description = tile.description,
                                posterUrl = tile.posterUrl,
                                heroUrl = tile.heroUrl,
                                superHeroUrl = tile.heroUrl,
                                capabilities = if (tile.supportsMkb) listOf("Jugar con Teclado y Ratón", "Control Táctil Nativo", "Xbox Series X|S") else listOf("Control Táctil Nativo", "Xbox Series X|S"),
                                isGamePass = true,
                                chameleonRgb = deriveColorFromTitle(tile.title),
                                editionType = getEditionType(tile.title),
                                subscriptionTier = getSubscriptionTier(tile.title)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Dynamic catalog shelves integration: ${e.message}")
            }

            // Merge with defaults
            val final = hydratedGames.toMutableList()
            for (def in verifiedDefaultGames) {
                if (final.none { it.id == def.id }) final.add(def)
            }
            return@withContext final

        } catch (e: Exception) {
            Log.w(TAG, "Error fetching live catalog: ${e.message}")
            return@withContext verifiedDefaultGames
        }
    }

    private fun parseDisplayCatalogProduct(prod: JSONObject): GameModel {
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

        val properties = prod.optJSONArray("Properties")?.optJSONObject(0)
        val categories = properties?.optJSONArray("Categories")
        val category = if (categories != null && categories.length() > 0) {
            categories.optString(0)
        } else {
            properties?.optString("Category") ?: ""
        }

        val ratingInfo = properties?.optJSONArray("Ratings")?.optJSONObject(0)
        val ratingIcon = ratingInfo?.optString("RatingImageUri") ?: ""
        val contentRating = ratingInfo?.optString("RatingDescriptor") ?: ""

        val (genreResolved, ratingResolved, descResolved) = resolveCatalogGameDetails(title, category, contentRating, desc)

        return GameModel(
            id = prodId,
            title = title,
            genre = genreResolved,
            publisher = publisher,
            description = descResolved,
            posterUrl = poster,
            heroUrl = hero,
            superHeroUrl = superHero,
            logoUrl = logo,
            screenshots = screenshots,
            rating = ratingResolved,
            ratingIconUrl = if (ratingIcon.startsWith("//")) "https:$ratingIcon" else ratingIcon,
            chameleonRgb = deriveColorFromTitle(title),
            editionType = getEditionType(title),
            subscriptionTier = getSubscriptionTier(title),
            supportsTouch = title.lowercase().contains("halo") || title.lowercase().contains("gears") || title.lowercase().contains("forza") || title.lowercase().contains("sea of thieves") || title.lowercase().contains("minecraft") || title.lowercase().contains("dodo"),
            supportsMkb = title.lowercase().contains("halo") || title.lowercase().contains("flight") || title.lowercase().contains("age of") || title.lowercase().contains("cities") || title.lowercase().contains("starfield"),
            isSeriesOptimized = true // Most cloud games are optimized
        )
    }

    private fun getSubscriptionTier(title: String): String {
        val lower = title.lowercase()
        return when {
            lower.contains("ea play") -> "EA PLAY · ULTIMATE"
            lower.contains("ubisoft") -> "UBISOFT+ · ULTIMATE"
            lower.contains("pc") || lower.contains("windows") -> "ULTIMATE · PC"
            else -> "ULTIMATE · PREMIUM"
        }
    }

    private fun getEditionType(title: String): String? {
        val lower = title.lowercase()
        
        // Anti-Double Suffix Logic
        if (lower.contains("edition") || lower.contains("versión") || lower.contains("ultimate") || 
            lower.contains("premium") || lower.contains("deluxe") || lower.contains("goty") || 
            lower.contains("complete") || lower.contains("definitive") || lower.contains("gold") ||
            lower.contains("master") || lower.contains("collection")) return null

        return when {
            lower.contains("forza") || lower.contains("horizon") || lower.contains("halo") || 
            lower.contains("gears") || lower.contains("sea of thieves") || lower.contains("state of decay") -> "Ultimate"
            
            lower.contains("witcher") || lower.contains("cyberpunk") || lower.contains("elden") || 
            lower.contains("souls") || lower.contains("sekiro") || lower.contains("resident evil") -> "Game of the Year"
            
            lower.contains("starfield") || lower.contains("fallout") || lower.contains("scrolls") || 
            lower.contains("doom") || lower.contains("wolfenstein") || lower.contains("atomic heart") -> "Premium"
            
            lower.contains("mortal kombat") || lower.contains("street fighter") || lower.contains("tekken") || 
            lower.contains("control") || lower.contains("hitman") || lower.contains("remedy") -> "Definitive"
            
            lower.contains("ark") || lower.contains("valheim") || lower.contains("survival") || 
            lower.contains("conan") || lower.contains("atlas") -> "Ultimate Survivor"
            
            lower.contains("siege") || lower.contains("rainbow") || lower.contains("battlefield") || 
            lower.contains("division") || lower.contains("ghost recon") -> "Gold"
            
            lower.contains("persona") || lower.contains("yakuza") || lower.contains("dragon") || 
            lower.contains("monster hunter") -> "Deluxe"
            
            lower.contains("minecraft") || lower.contains("lego") -> "Master Collection"
            
            lower.contains("fifa") || lower.contains("madden") || lower.contains("football") || 
            lower.contains("fc 2") -> "Ultimate Team"
            
            else -> null 
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

        val (genreResolved, ratingResolved, descResolved) = resolveCatalogGameDetails(title, "", "", desc)

        return GameModel(
            id = prodId,
            title = title,
            genre = genreResolved,
            rating = ratingResolved,
            publisher = publisher,
            description = descResolved,
            posterUrl = if (poster.isNotBlank()) poster else tile,
            heroUrl = hero,
            superHeroUrl = hero,
            screenshots = screenshots,
            chameleonRgb = deriveColorFromTitle(title),
            editionType = getEditionType(title),
            subscriptionTier = getSubscriptionTier(title),
            supportsTouch = title.lowercase().contains("halo") || title.lowercase().contains("gears") || title.lowercase().contains("forza") || title.lowercase().contains("sea of thieves") || title.lowercase().contains("minecraft") || title.lowercase().contains("dodo"),
            supportsMkb = title.lowercase().contains("halo") || title.lowercase().contains("flight") || title.lowercase().contains("age of") || title.lowercase().contains("cities") || title.lowercase().contains("starfield"),
            isSeriesOptimized = true
        )
    }

    private fun resolveCatalogGameDetails(title: String, rawCategory: String, rawRating: String, rawDesc: String): Triple<String, String, String> {
        val lower = title.lowercase()
        val genre = when {
            rawCategory.isNotBlank() && !rawCategory.equals("Games", ignoreCase = true) -> rawCategory
            lower.contains("forza") || lower.contains("racing") || lower.contains("f1") || lower.contains("grid") || lower.contains("dirt") || lower.contains("speed") -> "Carreras • Simulación"
            lower.contains("halo") || lower.contains("gears") || lower.contains("doom") || lower.contains("shooter") || lower.contains("battlefield") || lower.contains("wolfenstein") -> "Shooter • Acción"
            lower.contains("starfield") || lower.contains("fallout") || lower.contains("elder scrolls") || lower.contains("skyrim") || lower.contains("persona") || lower.contains("yakuza") -> "Rol • Aventura"
            lower.contains("minecraft") || lower.contains("lego") || lower.contains("ori") || lower.contains("dodo") || lower.contains("duckie") || lower.contains("party") || lower.contains("psychonauts") -> "Plataformas • Familiar"
            lower.contains("fifa") || lower.contains("madden") || lower.contains("nba") || lower.contains("sports") || lower.contains("football") -> "Deportes • Competitivo"
            lower.contains("flight") || lower.contains("simulator") || lower.contains("empires") || lower.contains("cities") -> "Estrategia • Simulación"
            else -> "Acción y Aventura"
        }

        val rating = resolveESRBFromTitle(title)

        val cleanDesc = rawDesc.replace(Regex("<[^>]*>"), "").trim()
        val desc = when {
            cleanDesc.length >= 25 -> cleanDesc
            lower.contains("forza") -> "Explora paisajes abiertos vibrantes y conduce cientos de los mejores automóviles del mundo en una experiencia automovilística sin precedentes."
            lower.contains("starfield") -> "En este juego de rol de nueva generación ambientado entre las estrellas, crea el personaje que desees y explora con una libertad sin igual."
            lower.contains("halo") -> "Ponte la armadura del mayor héroe de la humanidad para vivir una aventura épica y explorar la colosal escala del anillo de Halo."
            lower.contains("sea of thieves") -> "Surca los mares y forja tu propia leyenda pirata en un mundo compartido repleto de tesoros ocultos, combates navales y monstruos legendarios."
            lower.contains("gears") -> "Una de las franquicias más aclamadas de los videojuegos ofrece una campaña colosal y frenético combate cooperativo y multijugador."
            lower.contains("dodo") || lower.contains("duckie") -> "Acompaña a Dodo Duckie en una colorida y divertida aventura de plataformas llena de puzles, saltos y desafíos para todas las edades."
            lower.contains("minecraft") -> "Construye cualquier cosa que imagines, aventúrate en biomas infinitos y desafía a criaturas misteriosas en una experiencia sandbox legendaria."
            lower.contains("flight") -> "Surca los cielos de todo el planeta Tierra con aviones sumamente detallados en condiciones meteorológicas realistas en tiempo real."
            else -> "Disfruta de este aclamado título en la nube optimizado a 60 FPS con controles táctiles nativos, baja latencia y audio espacial inmersivo."
        }

        return Triple(genre, rating, desc)
    }

    private fun deriveColorFromTitle(title: String): Triple<Int, Int, Int> {
        val hash = title.hashCode()
        val r = (hash and 0xFF0000 shr 16).coerceIn(100, 240)
        val g = (hash and 0x00FF00 shr 8).coerceIn(100, 240)
        val b = (hash and 0x0000FF).coerceIn(100, 240)
        return Triple(r, g, b)
    }
}
