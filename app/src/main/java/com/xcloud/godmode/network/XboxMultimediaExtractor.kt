package com.xcloud.godmode.network

import android.util.Log
import com.xcloud.godmode.network.XboxEndpoints
import com.xcloud.godmode.models.GameModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class XboxSocialProfile(
    val xuid: String,
    val gamertag: String,
    val avatarUrl: String,
    val preferredColors: Triple<String, String, String>, // primary, secondary, tertiary hex
    val presenceState: String, // Online, Offline, InGame
    val presenceText: String
)

/**
 * XboxMultimediaExtractor: Handles rich media, game pass eligibility, trailers,
 * social presence, and GSSV Chile Central session requests across all 14 Xbox domains.
 */
class XboxMultimediaExtractor(
    private val uhs: String = "",
    private val webToken: String = "",
    private val gssvToken: String = "",
    private val marketLocale: String = "es-CL",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "XboxMultimediaExtractor"
    private val authHeader = if (uhs.isNotBlank() && webToken.isNotBlank()) "XBL3.0 x=$uhs;$webToken" else ""

    private fun getStandardHeaders(contractVersion: String = "3"): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
            .add("x-xbl-contract-version", contractVersion)
            .add("x-ms-api-version", "1.0")
            .add("Accept", "application/json")
            .add("Accept-Language", "$marketLocale,es;q=0.9,en-US;q=0.8")
            .add("Origin", "https://www.xbox.com")
            .add("Referer", "https://www.xbox.com/")

        if (authHeader.isNotBlank()) {
            builder.add("Authorization", authHeader)
        }
        return builder.build()
    }

    /**
     * 1. Extrae Imágenes (BoxArt, Hero, Capturas, Logos) y Videos (Trailers MP4/HLS)
     * combinando emerald.xboxservices.com y store-images.s-microsoft.com
     */
    suspend fun fetchRichMediaForProducts(productIds: List<String>): List<GameModel> = withContext(Dispatchers.IO) {
        if (productIds.isEmpty()) return@withContext emptyList()
        try {
            val idsParam = productIds.joinToString(",")
            val url = "${XboxEndpoints.EMERALD_CATALOG}/xboxcomfd/products/$idsParam?locale=$marketLocale"

            val request = Request.Builder()
                .url(url)
                .headers(getStandardHeaders("2"))
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Emerald Catalog error ${response.code}")
                return@withContext emptyList()
            }

            val bodyStr = response.body?.string() ?: "{}"
            val json = JSONObject(bodyStr)
            val products = json.optJSONArray("Products") ?: json.optJSONArray("products") ?: JSONArray()

            val hydratedGames = mutableListOf<GameModel>()
            for (i in 0 until products.length()) {
                val prod = products.optJSONObject(i) ?: continue
                val productId = prod.optString("ProductId", prod.optString("productId"))
                val title = prod.optString("ProductTitle", prod.optString("title", "Xbox Game"))
                val publisher = prod.optString("PublisherName", prod.optString("developerName", "Xbox Game Studios"))
                val description = prod.optString("ProductDescription", prod.optString("description", ""))

                val images = prod.optJSONArray("Images") ?: prod.optJSONArray("images") ?: JSONArray()
                val videos = prod.optJSONArray("Videos") ?: prod.optJSONArray("CMSVideos") ?: prod.optJSONArray("videos") ?: JSONArray()

                var poster = ""
                var hero = ""
                var superHero = ""
                var logo = ""
                val screenshots = mutableListOf<String>()

                for (j in 0 until images.length()) {
                    val img = images.optJSONObject(j) ?: continue
                    val purpose = (img.optString("ImagePurpose", img.optString("imagePurpose"))).lowercase()
                    val rawUri = img.optString("Uri", img.optString("url"))
                    val uri = XboxEndpoints.normalizeImageUrl(rawUri, 1920, 1080)

                    when (purpose) {
                        "poster" -> if (poster.isEmpty()) poster = XboxEndpoints.normalizeImageUrl(rawUri, 600, 900)
                        "boxart" -> if (poster.isEmpty()) poster = XboxEndpoints.normalizeImageUrl(rawUri, 600, 600)
                        "superheroart" -> superHero = uri
                        "titledheroart", "heroart" -> if (hero.isEmpty()) hero = uri
                        "logo" -> logo = XboxEndpoints.normalizeImageUrl(rawUri, 600, 300)
                        "screenshot" -> screenshots.add(uri)
                    }
                }

                if (hero.isBlank() && superHero.isNotBlank()) hero = superHero
                if (superHero.isBlank()) superHero = hero.ifBlank { poster }

                // Video trailers (MP4 or HLS)
                var trailerUrl = ""
                for (v in 0 until videos.length()) {
                    val vid = videos.optJSONObject(v) ?: continue
                    val mp4 = vid.optString("Uri", vid.optString("mp4Url"))
                    val hls = vid.optString("HLS", vid.optString("hlsUrl"))
                    if (mp4.isNotBlank()) {
                        trailerUrl = mp4
                        break
                    } else if (hls.isNotBlank() && trailerUrl.isBlank()) {
                        trailerUrl = hls
                    }
                }

                // Rating & Descriptors
                val properties = prod.optJSONArray("Properties")?.optJSONObject(0)
                val ratingInfo = properties?.optJSONArray("Ratings")?.optJSONObject(0)
                val ratingImageUri = ratingInfo?.optString("RatingImageUri", "") ?: ""
                val ratingDescriptor = ratingInfo?.optString("RatingDescriptor", "ESRB Everyone") ?: "ESRB Everyone"
                val contentDescArray = ratingInfo?.optJSONArray("RatingDisclaimers") ?: JSONArray()
                val contentDescriptors = mutableListOf<String>()
                for (d in 0 until contentDescArray.length()) {
                    val item = contentDescArray.optString(d)
                    if (item.isNotBlank()) contentDescriptors.add(item)
                }
                if (contentDescriptors.isEmpty()) {
                    val rawDesc = ratingInfo?.optString("RatingSystem", "") ?: ""
                    if (rawDesc.isNotBlank()) contentDescriptors.add(rawDesc)
                }

                // Attributes & Capabilities
                val capabilities = mutableListOf("Cloud Enabled", "Xbox Series X|S")
                val isAdTrial = prod.optBoolean("IsAdSupportedTrial", prod.optBoolean("isTrial", false))
                if (isAdTrial) capabilities.add("Prueba con Anuncios")

                hydratedGames.add(
                    GameModel(
                        id = productId,
                        title = title,
                        publisher = publisher,
                        description = description,
                        posterUrl = poster,
                        heroUrl = hero,
                        superHeroUrl = superHero,
                        logoUrl = logo,
                        trailerUrl = trailerUrl,
                        screenshots = screenshots,
                        rating = ratingDescriptor,
                        ratingIconUrl = XboxEndpoints.normalizeImageUrl(ratingImageUri, 120, 120),
                        contentDescriptors = contentDescriptors,
                        capabilities = capabilities,
                        languages = listOf("Español (Latinoamérica)", "Inglés", "Portugués", "Francés", "Alemán"),
                        accessibilityFeatures = listOf("Subtítulos personalizables", "Compatibilidad con Lector de pantalla", "Remapeo de controles"),
                        isGamePass = true,
                        chameleonRgb = deriveColor(title)
                    )
                )
            }
            hydratedGames
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchRichMediaForProducts: ${e.message}")
            emptyList()
        }
    }

    /**
     * 2. Verifica elegibilidad de licencias y acceso a contenido en contentaccess.exp.xboxservices.com
     */
    suspend fun checkContentAccessEntitlements(productId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${XboxEndpoints.CONTENT_ACCESS}/v1/contentaccess?productId=${productId}"
            val req = Request.Builder()
                .url(url)
                .headers(getStandardHeaders("2"))
                .get()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    /**
     * 3. Extrae Avatares HD, Presencia y Colores de Perfil desde peoplehub.xboxlive.com
     */
    suspend fun fetchFriendsAndPresence(): List<XboxSocialProfile> = withContext(Dispatchers.IO) {
        if (authHeader.isBlank()) return@withContext emptyList()
        try {
            val url = "${XboxEndpoints.PEOPLE_HUB}/users/me/people/social/decoration/detail,preferredColor,presenceDetail,multiplayerSummary"
            val req = Request.Builder()
                .url(url)
                .headers(getStandardHeaders("5"))
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val people = json.optJSONArray("people") ?: JSONArray()
                val profiles = mutableListOf<XboxSocialProfile>()

                for (i in 0 until people.length()) {
                    val p = people.optJSONObject(i) ?: continue
                    val xuid = p.optString("xuid", "")
                    val gamertag = p.optString("gamertag", p.optString("modernGamertag", "Xbox Friend"))
                    val avatarRaw = p.optString("displayPicRaw", "")
                    val avatarUrl = XboxEndpoints.normalizeImageUrl(avatarRaw, 512, 512, 95)

                    val colorObj = p.optJSONObject("preferredColor")
                    val primary = "#${colorObj?.optString("primaryColor", "107c10")}"
                    val secondary = "#${colorObj?.optString("secondaryColor", "054b16")}"
                    val tertiary = "#${colorObj?.optString("tertiaryColor", "0a1f0a")}"

                    val presenceState = p.optString("presenceState", "Offline")
                    val presenceText = p.optString("presenceText", "")

                    profiles.add(
                        XboxSocialProfile(
                            xuid = xuid,
                            gamertag = gamertag,
                            avatarUrl = avatarUrl,
                            preferredColors = Triple(primary, secondary, tertiary),
                            presenceState = presenceState,
                            presenceText = presenceText
                        )
                    )
                }
                profiles
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching friends & presence: ${e.message}")
            emptyList()
        }
    }

    /**
     * 4. Obtiene notificaciones de logros y alertas de Xbox Cloud
     */
    suspend fun fetchNotificationsInbox(): List<JSONObject> = withContext(Dispatchers.IO) {
        if (authHeader.isBlank()) return@withContext emptyList()
        try {
            val url = "${XboxEndpoints.NOTIFICATION_INBOX}/users/me/inbox"
            val req = Request.Builder().url(url).headers(getStandardHeaders("2")).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val notifications = json.optJSONArray("notifications") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until notifications.length()) {
                    notifications.optJSONObject(i)?.let { list.add(it) }
                }
                list
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 5. Obtiene conversaciones y mensajes multimedia
     */
    suspend fun fetchMessagingConversations(): List<JSONObject> = withContext(Dispatchers.IO) {
        if (authHeader.isBlank()) return@withContext emptyList()
        try {
            val url = "${XboxEndpoints.XBL_MESSAGING}/network/xbox/users/me/conversations"
            val req = Request.Builder().url(url).headers(getStandardHeaders("1")).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val convs = json.optJSONArray("conversations") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until convs.length()) {
                    convs.optJSONObject(i)?.let { list.add(it) }
                }
                list
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 6. Inicia sesión de juego en el cluster regional de Chile Central (clc.core.gssv-play-prod.xboxlive.com)
     */
    suspend fun startCloudSessionChileCentral(titleId: String, deviceInfoJson: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val url = "${XboxEndpoints.GSSV_CHILE_CENTRAL}/v5/sessions/cloud/play"
        val payload = JSONObject().apply {
            put("titleId", titleId)
            put("systemUpdateGroup", "")
            put("clientSessionId", UUID.randomUUID().toString())
            put("settings", JSONObject().apply {
                put("nanoVersion", "V3;WebrtcTransport.dll")
                put("enableOptionalDataCollection", false)
                put("enableTextToSpeech", false)
                put("locale", marketLocale)
            })
        }
        val mediaType = "application/json".toMediaType()
        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $gssvToken")
            .post(payload.toString().toRequestBody(mediaType))

        if (deviceInfoJson.isNotBlank()) {
            reqBuilder.addHeader("x-ms-device-info", deviceInfoJson)
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun deriveColor(title: String): Triple<Int, Int, Int> {
        val hash = title.hashCode()
        val r = (hash and 0xFF0000 shr 16).coerceIn(100, 240)
        val g = (hash and 0x00FF00 shr 8).coerceIn(100, 240)
        val b = (hash and 0x0000FF).coerceIn(100, 240)
        return Triple(r, g, b)
    }
}
