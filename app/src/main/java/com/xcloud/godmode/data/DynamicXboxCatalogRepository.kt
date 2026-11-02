package com.xcloud.godmode.data

import android.util.Log
import com.xcloud.godmode.models.CatalogEntity
import com.xcloud.godmode.models.GameModel
import com.xcloud.godmode.models.GodModeDao
import com.xcloud.godmode.network.XboxFullAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class GameTileEntity(
    val productId: String,
    val titleId: String?,
    val title: String,
    val posterUrl: String,
    val heroUrl: String,
    val editionBadge: String?,
    val supportsMkb: Boolean = false,
    val description: String = "",
    val genre: String = "Acción y Aventura",
    val rating: String = "ESRB Teen",
    val publisher: String = "Xbox Cloud Gaming"
)

class DynamicXboxCatalogRepository(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DynamicXboxCatalogRepo"

        val STORE_COLLECTIONS = listOf(
            "6a589fa0-d493-472b-8e20-3813699d7056", // Most popular on cloud
            "06323672-b8c8-43cc-b0de-32d5a9834749", // Recently added
            "29a81209-df6f-41fd-a528-2ae6b91f719c", // All Game Pass Cloud Games
            "fdd9e2a7-0fee-49f6-ad69-4354098401ff", // Cloud Favorites
            "66ec875c-a391-44f5-9a54-a28bd6f976ce", // Ubisoft+ Classics
            "3aa7a358-f15b-476b-af7e-134a250c08a0"  // Play with mouse and keyboard
        )
        const val SYOG_COLLECTION = "e78d9a61-5ef4-43af-b400-edba1250b18e"         // Stream Your Own Game
        const val LEAVING_SOON_COLLECTION = "393f05bf-e596-4ef6-9487-6d4fa0eab987" // Leaving Soon
        const val NATIVE_MKB_COLLECTION = "8fa264dd-124f-4af3-97e8-596fcdf4b486"   // Native Mouse & Keyboard

        private val EDITION_REGEX = Regex(
            """\s*(?:[-–—:]\s*)?(?:for\s+)?\(?\s*Xbox\s+(One|Series\s*X\s*\|\s*S|Series\s*S\s*\|\s*X)\s*\)?(?:\s+(?:Edition|Version))?\s*$""",
            RegexOption.IGNORE_CASE
        )
    }

    suspend fun loadDynamicHomeShelves(
        market: String = "CL",
        language: String = "es-CL"
    ): Map<String, List<GameTileEntity>> = withContext(Dispatchers.IO) {
        val mkbSet = fetchCollectionProductIds(NATIVE_MKB_COLLECTION, market, language).toSet()

        val deferredShelves = listOf(
            async { "Populares en la Nube" to fetchShelf(STORE_COLLECTIONS[0], 30, market, language, mkbSet) },
            async { "Recién Agregados" to fetchShelf(STORE_COLLECTIONS[1], 30, market, language, mkbSet) },
            async { "Catálogo Completo Cloud" to fetchShelf(STORE_COLLECTIONS[2], 40, market, language, mkbSet) },
            async { "Favoritos de la Comunidad" to fetchShelf(STORE_COLLECTIONS[3], 24, market, language, mkbSet) },
            async { "Juega tus Propios Juegos (BYOG)" to fetchShelf(SYOG_COLLECTION, 24, market, language, mkbSet) },
            async { "Salen Pronto de Game Pass" to fetchShelf(LEAVING_SOON_COLLECTION, 16, market, language, mkbSet) }
        )

        val map = deferredShelves.awaitAll().toMap().toMutableMap()
        val allLoaded = map.values.flatten().distinctBy { it.productId }.shuffled()
        map["Destacados Dinámicos"] = allLoaded.take(20)
        map
    }

    private suspend fun fetchShelf(
        collectionId: String,
        limit: Int,
        market: String,
        language: String,
        mkbSet: Set<String>
    ): List<GameTileEntity> {
        val ids = fetchCollectionProductIds(collectionId, market, language).take(limit)
        if (ids.isEmpty()) return emptyList()
        val rawEntities = hydrateChunk(ids, market, language, mkbSet, emptySet())
        return rawEntities.map { entity ->
            GameTileEntity(
                productId = entity.productId,
                titleId = entity.titleId,
                title = entity.title,
                posterUrl = entity.posterUrl,
                heroUrl = entity.heroUrl,
                editionBadge = if (entity.isSeriesOptimized) "Xbox Series X|S" else null,
                supportsMkb = entity.supportsMkb,
                description = entity.description,
                genre = entity.genre,
                rating = entity.rating,
                publisher = entity.publisher
            )
        }
    }

    /**
     * Crawls official Xbox catalog APIs (GamePass SIGLS, DisplayCatalog, BYOG collections, TitleHub)
     * across multiple markets and hydrates items into Room Database for instant local FTS4 search.
     */
    suspend fun syncFullCatalogToRoom(
        dao: GodModeDao,
        session: XboxFullAuthManager.XboxReadySession? = null
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting full Xbox catalog sync into Room DB...")

            val markets = listOf("CL", "MX", "US", "UK")
            val allProductIds = mutableSetOf<String>()
            val byogProductIds = mutableSetOf<String>()
            val mkbSet = mutableSetOf<String>()

            // 1. Fetch Mouse & Keyboard list
            val mkbIds = fetchCollectionProductIds(NATIVE_MKB_COLLECTION, "US", "en-US")
            mkbSet.addAll(mkbIds)

            // 2. Fetch BYOG Collection
            val byogIds = fetchCollectionProductIds(SYOG_COLLECTION, "US", "en-US")
            byogProductIds.addAll(byogIds)

            // 3. Multi-Market Collection Crawl
            for (m in markets) {
                val lang = when (m) {
                    "CL" -> "es-CL"
                    "MX" -> "es-MX"
                    "UK" -> "en-GB"
                    else -> "en-US"
                }
                for (col in STORE_COLLECTIONS) {
                    val ids = fetchCollectionProductIds(col, m, lang)
                    allProductIds.addAll(ids)
                }
            }

            allProductIds.addAll(byogProductIds)
            Log.d(TAG, "Discovered ${allProductIds.size} unique product IDs across markets.")

            if (allProductIds.isEmpty()) return@withContext

            // 4. Chunk hydration by 20 items to avoid payload limits
            val catalogEntities = mutableListOf<CatalogEntity>()
            val chunkedIds = allProductIds.chunked(20)

            for (chunk in chunkedIds) {
                val hydrated = hydrateChunk(chunk, "MX", "es-MX", mkbSet, byogProductIds)
                catalogEntities.addAll(hydrated)
            }

            // 5. If user session is active, fetch recent history from TitleHub
            if (session != null && session.webXstsToken.isNotBlank()) {
                fetchAndMergeRecentHistoryFromTitleHub(session, catalogEntities)
                fetchAndMergeUserEntitlements(session, catalogEntities)
            }

            // 6. Insert all entities into Room DB
            dao.insertCatalogGames(catalogEntities)
            Log.d(TAG, "Successfully inserted ${catalogEntities.size} Xbox games into Room DB!")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing catalog to Room: ${e.message}", e)
        }
    }

    private fun fetchCollectionProductIds(collectionId: String, market: String, language: String): List<String> {
        fun buildUrl(m: String, l: String) =
            "https://catalog.gamepass.com/sigls/v3?id=$collectionId&market=$m&language=$l&subscriptionContext=none&platformContext=Cloud%3AXGPUWEB"

        var ids = executeSiglCall(buildUrl(market, language))
        if (ids.isEmpty() && (market != "US" || language != "en-US")) {
            ids = executeSiglCall(buildUrl("US", "en-US"))
        }
        return ids
    }

    private fun executeSiglCall(url: String): List<String> {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("calling-app-name", "Xbox Cloud Gaming Web")
            .header("calling-app-version", "29.19.17")
            .header("ms-cv", "${UUID.randomUUID().toString().replace("-", "").take(22)}.1")
            .header("Referer", "https://www.xbox.com/")
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val txt = res.body?.string() ?: "[]"
                val arr = if (txt.trim().startsWith("[")) JSONArray(txt)
                else JSONObject(txt).optJSONArray("items") ?: JSONArray()

                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    if (id.length > 4) out.add(id)
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hydrateChunk(
        productIds: List<String>,
        market: String,
        language: String,
        mkbSet: Set<String>,
        byogSet: Set<String>
    ): List<CatalogEntity> {
        val url = "https://catalog.gamepass.com/v3/products?market=$market&language=$language&hydration=RemoteHighSapphire0"
        val bodyJson = JSONObject().put("Products", JSONArray(productIds)).toString()
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("calling-app-name", "Xbox Cloud Gaming Web")
            .header("calling-app-version", "29.19.17")
            .header("ms-cv", "${UUID.randomUUID().toString().replace("-", "").take(22)}.2")
            .header("Referer", "https://www.xbox.com/")
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val root = JSONObject(res.body?.string() ?: "{}")
                val productsObj = root.optJSONObject("Products") ?: return emptyList()
                val entities = mutableListOf<CatalogEntity>()

                val keys = productsObj.keys()
                while (keys.hasNext()) {
                    val pid = keys.next()
                    val p = productsObj.optJSONObject(pid) ?: continue
                    val title = p.optString("ProductTitle").ifEmpty {
                        p.optJSONArray("LocalizedProperties")?.optJSONObject(0)?.optString("ProductTitle") ?: ""
                    }
                    if (title.isEmpty()) continue

                    val poster = normalizeUrl(
                        p.optJSONObject("Image_Poster")?.optString("URL")
                            ?: p.optJSONObject("Image_Tile")?.optString("URL")
                    )
                    val hero = normalizeUrl(
                        p.optJSONObject("Image_TitledHero")?.optString("URL")
                            ?: p.optJSONObject("Image_Hero")?.optString("URL")
                            ?: poster
                    )
                    val superHero = normalizeUrl(
                        p.optJSONObject("Image_SuperHeroArt")?.optString("URL")
                            ?: p.optJSONObject("Image_TitledHero")?.optString("URL")
                            ?: hero
                    )
                    if (poster.isEmpty() && hero.isEmpty()) continue

                    val rawDesc = p.optString("ProductDescription").ifEmpty {
                        p.optString("ShortDescription").ifEmpty {
                            p.optJSONArray("LocalizedProperties")?.optJSONObject(0)?.optString("ProductDescription") ?: ""
                        }
                    }

                    val categoriesArr = p.optJSONArray("Categories")
                    val rawCategory = if (categoriesArr != null && categoriesArr.length() > 0) {
                        categoriesArr.optString(0)
                    } else {
                        p.optString("Category").ifEmpty {
                            p.optJSONArray("LocalizedProperties")?.optJSONObject(0)?.optString("Category") ?: ""
                        }
                    }

                    val rawRating = p.optString("Rating").ifEmpty {
                        p.optString("ParentalRating").ifEmpty {
                            p.optJSONArray("Properties")?.optJSONObject(0)?.optJSONArray("Ratings")?.optJSONObject(0)?.optString("RatingDescriptor") ?: ""
                        }
                    }

                    val pub = p.optString("PublisherName").ifEmpty { "Xbox Cloud Gaming" }
                    val (genreResolved, ratingResolved, descResolved) = resolveGameDetails(title, rawCategory, rawRating, rawDesc)

                    val isByog = byogSet.contains(pid)
                    val isMkb = mkbSet.contains(pid)
                    val titleId = p.optString("XCloudTitleId").takeIf { it.isNotEmpty() }

                    entities.add(
                        CatalogEntity(
                            productId = pid,
                            titleId = titleId,
                            title = title,
                            publisher = pub,
                            description = descResolved,
                            posterUrl = poster.ifEmpty { hero },
                            heroUrl = hero.ifEmpty { poster },
                            superHeroUrl = superHero.ifEmpty { hero },
                            genre = genreResolved,
                            rating = ratingResolved,
                            isCloudEnabled = true,
                            isSeriesOptimized = true,
                            isByog = isByog,
                            supportsMkb = isMkb,
                            marketRegion = if (isByog) "WORLD" else "LATAM",
                            lastPlayedEpochMs = 0L
                        )
                    )
                }
                entities
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchAndMergeRecentHistoryFromTitleHub(
        session: XboxFullAuthManager.XboxReadySession,
        entities: MutableList<CatalogEntity>
    ) {
        val url = "https://titlehub.xboxlive.com/users/xuid(${session.uhs})/titles/titlehistory/decoration/detail"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "XBL3.0 x=${session.uhs};${session.webXstsToken}")
            .header("x-xbl-contract-version", "2")
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return
                val root = JSONObject(res.body?.string() ?: "{}")
                val titlesArr = root.optJSONArray("titles") ?: return
                val now = System.currentTimeMillis()

                for (i in 0 until titlesArr.length()) {
                    val t = titlesArr.optJSONObject(i) ?: continue
                    val titleId = t.optString("titleId")
                    val name = t.optString("name")
                    val pfn = t.optString("pfn")

                    if (titleId.isBlank()) continue

                    val index = entities.indexOfFirst { it.titleId == titleId || it.title.equals(name, ignoreCase = true) }
                    val playedEpoch = now - (i * 3600000L)

                    if (index >= 0) {
                        entities[index] = entities[index].copy(lastPlayedEpochMs = playedEpoch)
                    } else if (name.isNotBlank()) {
                        entities.add(
                            CatalogEntity(
                                productId = "title_$titleId",
                                titleId = titleId,
                                title = name,
                                publisher = "Xbox Cloud Gaming",
                                description = "Jugado recientemente en Xbox Live",
                                posterUrl = "https://images-eds-ssl.xboxlive.com/image?url=471_3kkEig9M9_Uu_w67FzN7Uj2z...&$pfn",
                                genre = "Reciente",
                                isCloudEnabled = true,
                                lastPlayedEpochMs = playedEpoch
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun fetchAndMergeUserEntitlements(
        session: XboxFullAuthManager.XboxReadySession,
        entities: MutableList<CatalogEntity>
    ) {
        val url = "https://entitlements.auth.xboxlive.com/api/v1/entitlements"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "XBL3.0 x=${session.uhs};${session.webXstsToken}")
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return
                val root = JSONObject(res.body?.string() ?: "{}")
                val entitlements = root.optJSONArray("items") ?: return

                for (i in 0 until entitlements.length()) {
                    val item = entitlements.optJSONObject(i) ?: continue
                    val pid = item.optString("productId")
                    if (pid.isNotBlank()) {
                        val index = entities.indexOfFirst { it.productId == pid }
                        if (index >= 0) {
                            entities[index] = entities[index].copy(isByog = true)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun resolveGameDetails(title: String, rawCategory: String, rawRating: String, rawDesc: String): Triple<String, String, String> {
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

        val rating = when {
            rawRating.isNotBlank() -> rawRating
            lower.contains("doom") || lower.contains("gears") || lower.contains("fallout") || lower.contains("starfield") || lower.contains("witcher") || lower.contains("yakuza") -> "ESRB Mature 17+"
            lower.contains("halo") || lower.contains("sea of thieves") || lower.contains("persona") || lower.contains("battlefield") -> "ESRB Teen"
            lower.contains("minecraft") || lower.contains("forza") || lower.contains("dodo") || lower.contains("duckie") || lower.contains("lego") || lower.contains("flight") || lower.contains("fifa") -> "ESRB Everyone"
            else -> "ESRB Teen"
        }

        val cleanDesc = rawDesc.replace(Regex("<[^>]*>"), "").trim()
        val desc = when {
            cleanDesc.length >= 25 -> cleanDesc
            lower.contains("forza") -> "Explora paisajes abiertos vibrantes y conduce cientos de los mejores automóviles del mundo en una experiencia automovilística sin precedentes."
            lower.contains("starfield") -> "En este juego de rol de nueva generación ambientado entre las estrellas, crea el personaje que desees y explora con una libertad sin igual."
            lower.contains("halo") -> "Ponte la armadura del mayor héroe de la humanidad para vivir una aventura épica y explorar la colosal escala del anillo de Halo."
            lower.contains("sea of thieves") -> "Surca los mares y forja tu propia leyenda pirata en un mundo compartido repleto de tesoros ocultos, combates navales y monstruos legendarios."
            lower.contains("gears") -> "Una de las franquicias más aclamadas de los videojuegos ofrece una campaña colosal y frenético combate cooperativo y multijugador."
            lower.contains("minecraft") -> "Construye cualquier cosa que imagines, aventúrate en biomas infinitos y desafía a criaturas misteriosas en una experiencia sandbox legendaria."
            lower.contains("flight") -> "Surca los cielos de todo el planeta Tierra con aviones sumamente detallados en condiciones meteorológicas realistas en tiempo real."
            else -> "Disfruta de este aclamado título en la nube optimizado a 60 FPS con controles táctiles nativos, baja latencia y audio espacial inmersivo."
        }

        return Triple(genre, rating, desc)
    }

    private fun normalizeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return if (raw.startsWith("//")) "https:$raw" else raw
    }
}
