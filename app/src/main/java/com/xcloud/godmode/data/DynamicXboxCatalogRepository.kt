package com.xcloud.godmode.data

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
    val genre: String = "Action & Adventure",
    val rating: String = "ESRB Everyone",
    val publisher: String = "Xbox Cloud Gaming"
)

class DynamicXboxCatalogRepository(
    private val httpClient: OkHttpClient
) {
    companion object {
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
        val rawTiles = hydrateProducts(ids, market, language, mkbSet)
        return groupEditions(rawTiles)
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

    private fun hydrateProducts(
        productIds: List<String>,
        market: String,
        language: String,
        mkbSet: Set<String>
    ): List<GameTileEntity> {
        fun callHydration(m: String, l: String): List<GameTileEntity> {
            val url = "https://catalog.gamepass.com/v3/products?market=$m&language=$l&hydration=RemoteHighSapphire0"
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
                    val tiles = mutableListOf<GameTileEntity>()

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

                        tiles.add(
                            GameTileEntity(
                                productId = pid,
                                titleId = p.optString("XCloudTitleId").takeIf { it.isNotEmpty() },
                                title = title,
                                posterUrl = poster.ifEmpty { hero },
                                heroUrl = hero.ifEmpty { poster },
                                editionBadge = null,
                                supportsMkb = mkbSet.contains(pid),
                                description = descResolved,
                                genre = genreResolved,
                                rating = ratingResolved,
                                publisher = pub
                            )
                        )
                    }
                    tiles
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        val primary = callHydration(market, language)
        return if (primary.isNotEmpty() || (market == "US" && language == "en-US")) primary
        else callHydration("US", "en-US")
    }

    private fun resolveGameDetails(title: String, rawCategory: String, rawRating: String, rawDesc: String): Triple<String, String, String> {
        val lower = title.lowercase()
        val genre = when {
            rawCategory.isNotBlank() && !rawCategory.equals("Games", ignoreCase = true) -> rawCategory
            lower.contains("forza") || lower.contains("racing") || lower.contains("f1") || lower.contains("grid") || lower.contains("dirt") || lower.contains("speed") -> "Carreras • Simulación"
            lower.contains("halo") || lower.contains("gears") || lower.contains("doom") || lower.contains("shooter") || lower.contains("battlefield") || lower.contains("wolfenstein") || lower.contains("duty") -> "Shooter • Acción"
            lower.contains("starfield") || lower.contains("fallout") || lower.contains("elder scrolls") || lower.contains("skyrim") || lower.contains("persona") || lower.contains("yakuza") || lower.contains("witcher") -> "Rol • Aventura"
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
            lower.contains("dodo") || lower.contains("duckie") -> "Acompaña a Dodo Duckie en una colorida y divertida aventura de plataformas llena de puzles, saltos y desafíos para todas las edades."
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

    private fun groupEditions(tiles: List<GameTileEntity>): List<GameTileEntity> {
        val grouped = LinkedHashMap<String, MutableList<GameTileEntity>>()
        for (t in tiles) {
            val match = EDITION_REGEX.find(t.title)
            val baseTitle = if (match != null) t.title.substring(0, match.range.first).trim() else t.title.trim()
            val edition = if (match != null) {
                if (match.groupValues[1].contains("One", ignoreCase = true)) "Xbox One" else "Xbox Series X|S"
            } else null

            val key = baseTitle.lowercase()
            grouped.getOrPut(key) { mutableListOf() }.add(
                t.copy(title = baseTitle.ifEmpty { t.title }, editionBadge = edition)
            )
        }
        return grouped.values.map { group ->
            group.sortedBy { if (it.editionBadge == "Xbox One") 1 else 0 }.first()
        }
    }
}
