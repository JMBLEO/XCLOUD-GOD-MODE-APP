package com.aistudio.xcloudgodmode.ai.services

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Configuration for an individual virtual touch button.
 * Coordinates are normalized percentages (0.0f .. 1.0f) for fluid multi-aspect screen scaling.
 */
data class TouchButtonConfig(
    val id: String,
    val label: String,
    val xPercent: Float,
    val yPercent: Float,
    val scale: Float = 1.0f,
    val opacity: Float = 0.8f,
    val isCircle: Boolean = true,
    val colorHex: String = "#107C10"
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("label", label)
        json.put("xPercent", xPercent.toDouble())
        json.put("yPercent", yPercent.toDouble())
        json.put("scale", scale.toDouble())
        json.put("opacity", opacity.toDouble())
        json.put("isCircle", isCircle)
        json.put("colorHex", colorHex)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): TouchButtonConfig {
            return TouchButtonConfig(
                id = json.optString("id", "btn"),
                label = json.optString("label", "A"),
                xPercent = json.optDouble("xPercent", 0.5).toFloat(),
                yPercent = json.optDouble("yPercent", 0.5).toFloat(),
                scale = json.optDouble("scale", 1.0).toFloat(),
                opacity = json.optDouble("opacity", 0.8).toFloat(),
                isCircle = json.optBoolean("isCircle", true),
                colorHex = json.optString("colorHex", "#107C10")
            )
        }
    }
}

/**
 * Touch layout profile persisted per game TitleID (IndexedDB / LocalDB architecture).
 */
data class TouchLayoutProfile(
    val titleId: String,
    val gameTitle: String = "",
    val buttons: List<TouchButtonConfig>,
    val globalOpacity: Float = 0.8f,
    val snapToGrid: Boolean = false,
    val hapticsEnabled: Boolean = true
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("titleId", titleId)
        json.put("gameTitle", gameTitle)
        json.put("globalOpacity", globalOpacity.toDouble())
        json.put("snapToGrid", snapToGrid)
        json.put("hapticsEnabled", hapticsEnabled)

        val array = JSONArray()
        buttons.forEach { button ->
            array.put(button.toJson())
        }
        json.put("buttons", array)
        return json.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): TouchLayoutProfile? {
            return try {
                val json = JSONObject(jsonStr)
                val buttonsList = mutableListOf<TouchButtonConfig>()
                val array = json.optJSONArray("buttons")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i)
                        if (obj != null) {
                            buttonsList.add(TouchButtonConfig.fromJson(obj))
                        }
                    }
                }
                TouchLayoutProfile(
                    titleId = json.optString("titleId", "DEFAULT"),
                    gameTitle = json.optString("gameTitle", ""),
                    buttons = buttonsList,
                    globalOpacity = json.optDouble("globalOpacity", 0.8).toFloat(),
                    snapToGrid = json.optBoolean("snapToGrid", false),
                    hapticsEnabled = json.optBoolean("hapticsEnabled", true)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Local persistence manager for touch layout profiles indexed by TitleID.
 */
class TouchLayoutStorage(private val context: Context) {
    private val TAG = "TouchLayoutStorage"
    private val memoryCache = ConcurrentHashMap<String, TouchLayoutProfile>()
    private val layoutsDir: File by lazy {
        File(context.filesDir, "touch_layouts").apply { if (!exists()) mkdirs() }
    }

    /**
     * Get or build the touch layout profile for a specific titleId.
     */
    fun getLayout(titleId: String, gameTitle: String = ""): TouchLayoutProfile {
        val safeKey = sanitizeFilename(titleId)
        memoryCache[safeKey]?.let { return it }

        val file = File(layoutsDir, "$safeKey.json")
        if (file.exists()) {
            try {
                val content = file.readText()
                val profile = TouchLayoutProfile.fromJson(content)
                if (profile != null) {
                    memoryCache[safeKey] = profile
                    return profile
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading touch layout profile for $titleId: ${e.message}")
            }
        }

        // Generate standard Xbox default layout
        val defaultProfile = TouchLayoutProfile(
            titleId = titleId,
            gameTitle = gameTitle,
            buttons = getDefaultXboxButtons()
        )
        memoryCache[safeKey] = defaultProfile
        return defaultProfile
    }

    /**
     * Save the touch layout profile to local disk by TitleID.
     */
    fun saveLayout(profile: TouchLayoutProfile) {
        val safeKey = sanitizeFilename(profile.titleId)
        memoryCache[safeKey] = profile
        try {
            val file = File(layoutsDir, "$safeKey.json")
            file.writeText(profile.toJson())
            Log.d(TAG, "Saved touch layout for ${profile.titleId} (${profile.buttons.size} buttons)")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing touch layout file: ${e.message}")
        }
    }

    /**
     * Reset the layout for a titleId back to factory Xbox default.
     */
    fun resetToDefault(titleId: String, gameTitle: String = ""): TouchLayoutProfile {
        val defaultProfile = TouchLayoutProfile(
            titleId = titleId,
            gameTitle = gameTitle,
            buttons = getDefaultXboxButtons()
        )
        saveLayout(defaultProfile)
        return defaultProfile
    }

    /**
     * Standard ergonomic default positions for Xbox controller touch buttons.
     */
    fun getDefaultXboxButtons(): List<TouchButtonConfig> {
        return listOf(
            // Left Stick (LS)
            TouchButtonConfig(
                id = "stick_left",
                label = "LS",
                xPercent = 0.14f,
                yPercent = 0.64f,
                scale = 1.3f,
                opacity = 0.85f,
                isCircle = true,
                colorHex = "#22C55E"
            ),
            // D-Pad (Cruceta)
            TouchButtonConfig(
                id = "dpad",
                label = "DPAD",
                xPercent = 0.28f,
                yPercent = 0.80f,
                scale = 1.15f,
                opacity = 0.80f,
                isCircle = false,
                colorHex = "#38BDF8"
            ),
            // Bumpers & Triggers Izquierdos
            TouchButtonConfig(
                id = "btn_lt",
                label = "LT",
                xPercent = 0.12f,
                yPercent = 0.14f,
                scale = 1.0f,
                opacity = 0.80f,
                isCircle = false,
                colorHex = "#475569"
            ),
            TouchButtonConfig(
                id = "btn_lb",
                label = "LB",
                xPercent = 0.12f,
                yPercent = 0.26f,
                scale = 1.0f,
                opacity = 0.80f,
                isCircle = false,
                colorHex = "#64748B"
            ),
            // Right Stick (RS)
            TouchButtonConfig(
                id = "stick_right",
                label = "RS",
                xPercent = 0.72f,
                yPercent = 0.78f,
                scale = 1.3f,
                opacity = 0.85f,
                isCircle = true,
                colorHex = "#22C55E"
            ),
            // ABXY Action Diamond (Xbox standard colors)
            TouchButtonConfig(
                id = "btn_a",
                label = "A",
                xPercent = 0.86f,
                yPercent = 0.68f,
                scale = 1.1f,
                opacity = 0.90f,
                isCircle = true,
                colorHex = "#107C10" // Xbox Green
            ),
            TouchButtonConfig(
                id = "btn_b",
                label = "B",
                xPercent = 0.93f,
                yPercent = 0.55f,
                scale = 1.1f,
                opacity = 0.90f,
                isCircle = true,
                colorHex = "#EF4444" // Xbox Red
            ),
            TouchButtonConfig(
                id = "btn_x",
                label = "X",
                xPercent = 0.79f,
                yPercent = 0.55f,
                scale = 1.1f,
                opacity = 0.90f,
                isCircle = true,
                colorHex = "#3B82F6" // Xbox Blue
            ),
            TouchButtonConfig(
                id = "btn_y",
                label = "Y",
                xPercent = 0.86f,
                yPercent = 0.42f,
                scale = 1.1f,
                opacity = 0.90f,
                isCircle = true,
                colorHex = "#F59E0B" // Xbox Yellow
            ),
            // Bumpers & Triggers Derechos
            TouchButtonConfig(
                id = "btn_rt",
                label = "RT",
                xPercent = 0.88f,
                yPercent = 0.14f,
                scale = 1.0f,
                opacity = 0.80f,
                isCircle = false,
                colorHex = "#475569"
            ),
            TouchButtonConfig(
                id = "btn_rb",
                label = "RB",
                xPercent = 0.88f,
                yPercent = 0.26f,
                scale = 1.0f,
                opacity = 0.80f,
                isCircle = false,
                colorHex = "#64748B"
            ),
            // Botones de Sistema (Guía Nexus, View, Menu)
            TouchButtonConfig(
                id = "btn_nexus",
                label = "XBOX",
                xPercent = 0.50f,
                yPercent = 0.10f,
                scale = 1.15f,
                opacity = 0.90f,
                isCircle = true,
                colorHex = "#107C10"
            ),
            TouchButtonConfig(
                id = "btn_view",
                label = "VIEW",
                xPercent = 0.42f,
                yPercent = 0.10f,
                scale = 0.85f,
                opacity = 0.70f,
                isCircle = false,
                colorHex = "#334155"
            ),
            TouchButtonConfig(
                id = "btn_menu",
                label = "MENU",
                xPercent = 0.58f,
                yPercent = 0.10f,
                scale = 0.85f,
                opacity = 0.70f,
                isCircle = false,
                colorHex = "#334155"
            )
        )
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
