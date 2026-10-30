package com.aistudio.xcloudgodmode.ai.services

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-game configuration profile (LocalDB persistence architecture).
 */
data class GameProfile(
    val titleId: String,
    val gameTitle: String = "",
    val upscalerEngine: String = "AMD FSR 3.1 RCAS WebGL2",
    val fsrSharpness: Float = 0.6f,
    val targetResProfile: String = "1080p",
    val controllerProfile: String = "Standard", // "Standard", "Pro", "DualSense", "Inverted RPG"
    val stickDeadzoneLeft: Float = 0.08f,
    val stickDeadzoneRight: Float = 0.08f,
    val stickResponseCurve: String = "Linear", // "Linear", "Exponential", "Smooth"
    val vibrationEnabled: Boolean = true,
    val vibrationIntensity: Int = 100,
    val mkbEnabled: Boolean = false,
    val mouseSensitivity: Float = 1.0f,
    val volumeBoostPercent: Int = 120,
    val customKeyBindings: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("titleId", titleId)
        json.put("gameTitle", gameTitle)
        json.put("upscalerEngine", upscalerEngine)
        json.put("fsrSharpness", fsrSharpness.toDouble())
        json.put("targetResProfile", targetResProfile)
        json.put("controllerProfile", controllerProfile)
        json.put("stickDeadzoneLeft", stickDeadzoneLeft.toDouble())
        json.put("stickDeadzoneRight", stickDeadzoneRight.toDouble())
        json.put("stickResponseCurve", stickResponseCurve)
        json.put("vibrationEnabled", vibrationEnabled)
        json.put("vibrationIntensity", vibrationIntensity)
        json.put("mkbEnabled", mkbEnabled)
        json.put("mouseSensitivity", mouseSensitivity.toDouble())
        json.put("volumeBoostPercent", volumeBoostPercent)
        
        val bindingsObj = JSONObject()
        customKeyBindings.forEach { (k, v) -> bindingsObj.put(k, v) }
        json.put("customKeyBindings", bindingsObj)
        return json.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): GameProfile? {
            return try {
                val json = JSONObject(jsonStr)
                val bindings = mutableMapOf<String, String>()
                val bindingsObj = json.optJSONObject("customKeyBindings")
                bindingsObj?.keys()?.forEach { k ->
                    bindings[k] = bindingsObj.optString(k)
                }

                GameProfile(
                    titleId = json.optString("titleId", ""),
                    gameTitle = json.optString("gameTitle", ""),
                    upscalerEngine = json.optString("upscalerEngine", "AMD FSR 3.1 RCAS WebGL2"),
                    fsrSharpness = json.optDouble("fsrSharpness", 0.6).toFloat(),
                    targetResProfile = json.optString("targetResProfile", "1080p"),
                    controllerProfile = json.optString("controllerProfile", "Standard"),
                    stickDeadzoneLeft = json.optDouble("stickDeadzoneLeft", 0.08).toFloat(),
                    stickDeadzoneRight = json.optDouble("stickDeadzoneRight", 0.08).toFloat(),
                    stickResponseCurve = json.optString("stickResponseCurve", "Linear"),
                    vibrationEnabled = json.optBoolean("vibrationEnabled", true),
                    vibrationIntensity = json.optInt("vibrationIntensity", 100),
                    mkbEnabled = json.optBoolean("mkbEnabled", false),
                    mouseSensitivity = json.optDouble("mouseSensitivity", 1.0).toFloat(),
                    volumeBoostPercent = json.optInt("volumeBoostPercent", 120),
                    customKeyBindings = bindings
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * GameProfileManager manages local persistence of per-game configurations (LocalDB).
 */
class GameProfileManager(private val context: Context) {
    private val TAG = "GameProfileManager"
    private val memoryCache = ConcurrentHashMap<String, GameProfile>()
    private val profilesDir: File by lazy {
        File(context.filesDir, "game_profiles").apply { if (!exists()) mkdirs() }
    }

    init {
        loadMarqueeDefaults()
    }

    private fun loadMarqueeDefaults() {
        // Pre-configure marquee title optimizations
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9NKX70BBCDRN", // Forza Horizon 5
                gameTitle = "Forza Horizon 5",
                upscalerEngine = "AMD FSR 3.1 RCAS WebGL2",
                fsrSharpness = 0.75f,
                targetResProfile = "1440p",
                controllerProfile = "Pro Racing",
                stickDeadzoneLeft = 0.04f,
                stickDeadzoneRight = 0.05f,
                stickResponseCurve = "Smooth",
                vibrationIntensity = 130,
                volumeBoostPercent = 140
            )
        )
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9NP1P1WFS0LB", // Halo Infinite
                gameTitle = "Halo Infinite",
                upscalerEngine = "AMD FSR 3.1 RCAS WebGL2",
                fsrSharpness = 0.7f,
                targetResProfile = "1080p",
                controllerProfile = "Competitive FPS",
                stickDeadzoneLeft = 0.05f,
                stickDeadzoneRight = 0.04f,
                stickResponseCurve = "Exponential",
                mkbEnabled = true,
                mouseSensitivity = 1.2f,
                volumeBoostPercent = 130
            )
        )
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9NCJSXWZTP88", // Starfield
                gameTitle = "Starfield",
                upscalerEngine = "Real-ESRGAN SRVGG",
                fsrSharpness = 0.8f,
                targetResProfile = "1440p",
                controllerProfile = "Inverted RPG",
                stickDeadzoneLeft = 0.08f,
                stickDeadzoneRight = 0.08f,
                volumeBoostPercent = 125
            )
        )
    }

    private fun putDefaultIfAbsent(profile: GameProfile) {
        if (!memoryCache.containsKey(profile.titleId)) {
            val file = File(profilesDir, "${profile.titleId}.json")
            if (file.exists()) {
                val loaded = GameProfile.fromJson(file.readText())
                if (loaded != null) {
                    memoryCache[profile.titleId] = loaded
                    return
                }
            }
            memoryCache[profile.titleId] = profile
        }
    }

    /**
     * Gets or creates a GameProfile for the given title ID.
     */
    fun getProfile(titleId: String, titleName: String = ""): GameProfile {
        if (titleId.isBlank()) {
            return GameProfile(titleId = "default", gameTitle = titleName)
        }

        memoryCache[titleId]?.let { return it }

        val file = File(profilesDir, "$titleId.json")
        if (file.exists()) {
            try {
                val content = file.readText()
                val loaded = GameProfile.fromJson(content)
                if (loaded != null) {
                    memoryCache[titleId] = loaded
                    return loaded
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading profile for $titleId: ${e.message}")
            }
        }

        val fresh = GameProfile(titleId = titleId, gameTitle = titleName)
        memoryCache[titleId] = fresh
        return fresh
    }

    /**
     * Saves a GameProfile both in memory and to disk (LocalDB).
     */
    fun saveProfile(profile: GameProfile) {
        memoryCache[profile.titleId] = profile
        try {
            val file = File(profilesDir, "${profile.titleId}.json")
            file.writeText(profile.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving profile for ${profile.titleId}: ${e.message}")
        }
    }
}
