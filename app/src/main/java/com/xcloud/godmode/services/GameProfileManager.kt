package com.xcloud.godmode.services

import android.content.Context
import android.util.Log
import com.xcloud.godmode.models.GameProfileEntity
import com.xcloud.godmode.models.GodModeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * GameProfile: Per-game native configuration profile.
 * Stores specific shader settings, resolution overrides, preferred rendering engine,
 * and optimization presets per GameID to be loaded automatically.
 */
data class GameProfile(
    val titleId: String,
    val gameTitle: String = "",
    val resolutionOverride: String = "1440p", // "1080p", "1440p", "720p", "4K"
    val targetFps: Int = 60,                 // 60, 120
    val targetBitrateKbps: Int = 35000,      // 15000..50000
    val preferredEngineId: Int = 3,          // 1..6 (StreamEngineType)
    val shaderPreset: String = "CAS Adaptive", // "Laplacian AI", "CAS Adaptive", "Bilateral Ultra", "Vibrance Boost", "None"
    val sharpeningStrength: Float = 0.65f,   // 0.0f..1.0f
    val contrastBoost: Float = 1.05f,        // 0.8f..1.4f
    val saturationBoost: Float = 1.10f,      // 0.8f..1.4f
    val bbrUltraPacing: Boolean = true,
    val enableAiUpscale: Boolean = true,
    val lowLatencyVsyncOff: Boolean = true,
    val kalmanMouseSmoothing: Boolean = false,
    val aiOptimizationPreset: String = "Ultra Low Latency + CAS", // "Aggressive Low Latency", "Ultra Visual Fidelity", "Balanced Esports", "Battery Saver NPU"
    val audioPtsOffsetUs: Long = 0L,         // -125000L for BT audio, 0L for wired
    val controllerProfile: String = "Standard", // "Standard", "Pro Racing", "Competitive FPS", "Inverted RPG"
    val stickDeadzoneLeft: Float = 0.06f,
    val stickDeadzoneRight: Float = 0.06f,
    val stickResponseCurve: String = "Linear", // "Linear", "Exponential", "Smooth"
    val vibrationEnabled: Boolean = true,
    val vibrationIntensity: Int = 100,
    val mkbEnabled: Boolean = false,
    val mouseSensitivity: Float = 1.0f,
    val volumeBoostPercent: Int = 120,
    val customKeyBindings: Map<String, String> = emptyMap(),
    val isCustom: Boolean = false
) {
    // Backward compatibility aliases
    val upscalerEngine: String get() = shaderPreset
    val fsrSharpness: Float get() = sharpeningStrength
    val targetResProfile: String get() = resolutionOverride

    fun toEntity(): GameProfileEntity = GameProfileEntity.fromGameProfile(this)

    fun toJson(): String {
        val json = JSONObject()
        json.put("titleId", titleId)
        json.put("gameTitle", gameTitle)
        json.put("resolutionOverride", resolutionOverride)
        json.put("targetResProfile", resolutionOverride)
        json.put("targetFps", targetFps)
        json.put("targetBitrateKbps", targetBitrateKbps)
        json.put("preferredEngineId", preferredEngineId)
        json.put("shaderPreset", shaderPreset)
        json.put("upscalerEngine", shaderPreset)
        json.put("sharpeningStrength", sharpeningStrength.toDouble())
        json.put("fsrSharpness", sharpeningStrength.toDouble())
        json.put("contrastBoost", contrastBoost.toDouble())
        json.put("saturationBoost", saturationBoost.toDouble())
        json.put("bbrUltraPacing", bbrUltraPacing)
        json.put("enableAiUpscale", enableAiUpscale)
        json.put("lowLatencyVsyncOff", lowLatencyVsyncOff)
        json.put("kalmanMouseSmoothing", kalmanMouseSmoothing)
        json.put("aiOptimizationPreset", aiOptimizationPreset)
        json.put("audioPtsOffsetUs", audioPtsOffsetUs)
        json.put("controllerProfile", controllerProfile)
        json.put("stickDeadzoneLeft", stickDeadzoneLeft.toDouble())
        json.put("stickDeadzoneRight", stickDeadzoneRight.toDouble())
        json.put("stickResponseCurve", stickResponseCurve)
        json.put("vibrationEnabled", vibrationEnabled)
        json.put("vibrationIntensity", vibrationIntensity)
        json.put("mkbEnabled", mkbEnabled)
        json.put("mouseSensitivity", mouseSensitivity.toDouble())
        json.put("volumeBoostPercent", volumeBoostPercent)
        json.put("isCustom", isCustom)

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

                val res = json.optString("resolutionOverride", json.optString("targetResProfile", "1440p"))
                val shader = json.optString("shaderPreset", json.optString("upscalerEngine", "CAS Adaptive"))
                val sharpness = json.optDouble("sharpeningStrength", json.optDouble("fsrSharpness", 0.65)).toFloat()

                GameProfile(
                    titleId = json.optString("titleId", ""),
                    gameTitle = json.optString("gameTitle", ""),
                    resolutionOverride = res,
                    targetFps = json.optInt("targetFps", 60),
                    targetBitrateKbps = json.optInt("targetBitrateKbps", 35000),
                    preferredEngineId = json.optInt("preferredEngineId", 3),
                    shaderPreset = shader,
                    sharpeningStrength = sharpness,
                    contrastBoost = json.optDouble("contrastBoost", 1.05).toFloat(),
                    saturationBoost = json.optDouble("saturationBoost", 1.10).toFloat(),
                    bbrUltraPacing = json.optBoolean("bbrUltraPacing", true),
                    enableAiUpscale = json.optBoolean("enableAiUpscale", true),
                    lowLatencyVsyncOff = json.optBoolean("lowLatencyVsyncOff", true),
                    kalmanMouseSmoothing = json.optBoolean("kalmanMouseSmoothing", false),
                    aiOptimizationPreset = json.optString("aiOptimizationPreset", "Ultra Low Latency + CAS"),
                    audioPtsOffsetUs = json.optLong("audioPtsOffsetUs", 0L),
                    controllerProfile = json.optString("controllerProfile", "Standard"),
                    stickDeadzoneLeft = json.optDouble("stickDeadzoneLeft", 0.06).toFloat(),
                    stickDeadzoneRight = json.optDouble("stickDeadzoneRight", 0.06).toFloat(),
                    stickResponseCurve = json.optString("stickResponseCurve", "Linear"),
                    vibrationEnabled = json.optBoolean("vibrationEnabled", true),
                    vibrationIntensity = json.optInt("vibrationIntensity", 100),
                    mkbEnabled = json.optBoolean("mkbEnabled", false),
                    mouseSensitivity = json.optDouble("mouseSensitivity", 1.0).toFloat(),
                    volumeBoostPercent = json.optInt("volumeBoostPercent", 120),
                    customKeyBindings = bindings,
                    isCustom = json.optBoolean("isCustom", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * GameProfileManager: Manages local persistence and automatic loading of per-game configurations.
 */
class GameProfileManager(private val context: Context) {
    private val TAG = "GameProfileManager"
    private val memoryCache = ConcurrentHashMap<String, GameProfile>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dao by lazy { GodModeDatabase.getDatabase(context).dao() }
    private val profilesDir: File by lazy {
        File(context.filesDir, "game_profiles").apply { if (!exists()) mkdirs() }
    }

    init {
        loadMarqueeDefaults()
        preloadFromRoom()
    }

    private fun preloadFromRoom() {
        scope.launch {
            try {
                dao.getAllGameProfiles().collect { list ->
                    list.forEach { entity ->
                        memoryCache[entity.gameId] = entity.toGameProfile()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error preloading game profiles from Room: ${e.message}")
            }
        }
    }

    private fun loadMarqueeDefaults() {
        // 1. Forza Horizon 5: 1440p 120Hz / Motor 2 (Vulkan ASIC Direct) / Laplacian AI / 45Mbps
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9NKX70BBCDRN",
                gameTitle = "Forza Horizon 5",
                resolutionOverride = "1440p",
                targetFps = 120,
                targetBitrateKbps = 45000,
                preferredEngineId = 2, // Motor 2: Vulkan Video ASIC Direct
                shaderPreset = "Laplacian AI",
                sharpeningStrength = 0.55f,
                contrastBoost = 1.05f,
                saturationBoost = 1.15f,
                bbrUltraPacing = true,
                lowLatencyVsyncOff = true,
                aiOptimizationPreset = "Ultra Visual Fidelity",
                controllerProfile = "Pro Racing",
                stickDeadzoneLeft = 0.04f,
                stickDeadzoneRight = 0.04f,
                stickResponseCurve = "Smooth",
                vibrationIntensity = 135,
                volumeBoostPercent = 130
            )
        )

        // 2. Halo Infinite: 1080p 120Hz / Motor 4 (HWC2 Direct Overlay AI) / CAS Adaptive / 35Mbps / Kalman Mouse
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9NP1P1WFS0LB",
                gameTitle = "Halo Infinite",
                resolutionOverride = "1080p",
                targetFps = 120,
                targetBitrateKbps = 35000,
                preferredEngineId = 4, // Motor 4: HWC2 Direct Overlay AI
                shaderPreset = "CAS Adaptive",
                sharpeningStrength = 0.70f,
                contrastBoost = 1.10f,
                saturationBoost = 1.05f,
                bbrUltraPacing = true,
                lowLatencyVsyncOff = true,
                kalmanMouseSmoothing = true,
                aiOptimizationPreset = "Aggressive Low Latency",
                controllerProfile = "Competitive FPS",
                stickDeadzoneLeft = 0.03f,
                stickDeadzoneRight = 0.03f,
                stickResponseCurve = "Exponential",
                mkbEnabled = true,
                mouseSensitivity = 1.25f,
                volumeBoostPercent = 125
            )
        )

        // 3. Starfield: 1440p 60Hz / Motor 3 (AMediaCodec + NPU AI) / CAS Adaptive / 35Mbps
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9NCJSXWZTP88",
                gameTitle = "Starfield",
                resolutionOverride = "1440p",
                targetFps = 60,
                targetBitrateKbps = 35000,
                preferredEngineId = 3, // Motor 3: AMediaCodec + NPU AI
                shaderPreset = "CAS Adaptive",
                sharpeningStrength = 0.75f,
                contrastBoost = 1.10f,
                saturationBoost = 1.20f,
                bbrUltraPacing = true,
                aiOptimizationPreset = "Balanced Esports",
                controllerProfile = "Inverted RPG",
                stickDeadzoneLeft = 0.06f,
                stickDeadzoneRight = 0.06f,
                volumeBoostPercent = 130
            )
        )

        // 4. Cyberpunk 2077: 1440p 60Hz / Motor 5 (AMediaCodec + NNAPI AI) / Vibrance Boost
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9P688K8Q0D8D",
                gameTitle = "Cyberpunk 2077",
                resolutionOverride = "1440p",
                targetFps = 60,
                targetBitrateKbps = 35000,
                preferredEngineId = 5, // Motor 5: AMediaCodec + NNAPI AI
                shaderPreset = "Vibrance Boost",
                sharpeningStrength = 0.65f,
                contrastBoost = 1.15f,
                saturationBoost = 1.25f,
                bbrUltraPacing = true,
                aiOptimizationPreset = "Ultra Visual Fidelity",
                controllerProfile = "Standard",
                volumeBoostPercent = 130
            )
        )

        // 5. Microsoft Flight Simulator: 1440p 60Hz / Motor 6 (EGLImage OES AI) / Laplacian AI
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9NRX046J7S98",
                gameTitle = "Microsoft Flight Simulator",
                resolutionOverride = "1440p",
                targetFps = 60,
                targetBitrateKbps = 40000,
                preferredEngineId = 6, // Motor 6: EGLImage Zero-Copy OES AI
                shaderPreset = "Laplacian AI",
                sharpeningStrength = 0.60f,
                contrastBoost = 1.05f,
                saturationBoost = 1.10f,
                bbrUltraPacing = true,
                aiOptimizationPreset = "Ultra Visual Fidelity",
                volumeBoostPercent = 120
            )
        )

        // 6. Gears 5: 1440p 120Hz / Motor 1 (Rust Secure + Vulkan) / CAS Adaptive
        putDefaultIfAbsent(
            GameProfile(
                titleId = "9P2N57MC619K",
                gameTitle = "Gears 5",
                resolutionOverride = "1440p",
                targetFps = 120,
                targetBitrateKbps = 35000,
                preferredEngineId = 1, // Motor 1: Rust Secure + Vulkan
                shaderPreset = "CAS Adaptive",
                sharpeningStrength = 0.65f,
                contrastBoost = 1.10f,
                saturationBoost = 1.10f,
                bbrUltraPacing = true,
                lowLatencyVsyncOff = true,
                aiOptimizationPreset = "Aggressive Low Latency",
                volumeBoostPercent = 130
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

            // Synchronize marquee defaults to Room DB
            scope.launch {
                try {
                    val existing = dao.getGameProfile(profile.titleId)
                    if (existing == null) {
                        dao.insertGameProfile(profile.toEntity())
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Default sync to Room: ${e.message}")
                }
            }
        }
    }

    /**
     * Automatically retrieves or creates an optimized GameProfile for the given TitleID or Game Title.
     * Evaluates memory cache, then queries Room DB, then disk JSON, and falls back to a clean 1440p preset.
     */
    fun getProfile(titleId: String, titleName: String = ""): GameProfile {
        if (titleId.isBlank() && titleName.isBlank()) {
            return defaultProfile()
        }

        // 1. Direct TitleID hit in memory cache
        if (titleId.isNotBlank() && memoryCache.containsKey(titleId)) {
            val cached = memoryCache[titleId]!!
            return if (titleName.isNotBlank() && cached.gameTitle.isBlank()) {
                cached.copy(gameTitle = titleName)
            } else cached
        }

        // 2. Search by Title Name in cache
        if (titleName.isNotBlank()) {
            val match = memoryCache.values.firstOrNull {
                it.gameTitle.contains(titleName, ignoreCase = true) ||
                titleName.contains(it.gameTitle, ignoreCase = true)
            }
            if (match != null) return match
        }

        // 3. Query Room Database synchronously with short timeout
        if (titleId.isNotBlank()) {
            try {
                val fromRoom = runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(300L) {
                        dao.getGameProfile(titleId)?.toGameProfile()
                    }
                }
                if (fromRoom != null) {
                    memoryCache[titleId] = fromRoom
                    return fromRoom
                }
            } catch (e: Exception) {
                Log.d(TAG, "Room sync query skipped: ${e.message}")
            }
        }

        // 4. Check on disk JSON fallback
        if (titleId.isNotBlank()) {
            val file = File(profilesDir, "$titleId.json")
            if (file.exists()) {
                try {
                    val loaded = GameProfile.fromJson(file.readText())
                    if (loaded != null) {
                        memoryCache[titleId] = loaded
                        scope.launch { dao.insertGameProfile(loaded.toEntity()) }
                        return loaded
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading profile file for $titleId: ${e.message}")
                }
            }
        }

        // 5. Generic fallback profile (1440p default with CAS Adaptive and BBR Pacing)
        val fresh = GameProfile(
            titleId = titleId.ifBlank { "custom_" + titleName.hashCode() },
            gameTitle = titleName.ifBlank { "Custom Game" },
            resolutionOverride = "1440p",
            targetFps = 60,
            targetBitrateKbps = 35000,
            preferredEngineId = 3,
            shaderPreset = "CAS Adaptive",
            sharpeningStrength = 0.65f,
            bbrUltraPacing = true,
            aiOptimizationPreset = "Ultra Low Latency + CAS"
        )
        memoryCache[fresh.titleId] = fresh
        scope.launch {
            try { dao.insertGameProfile(fresh.toEntity()) } catch (_: Exception) {}
        }
        return fresh
    }

    /**
     * Asynchronous retrieval from Room Database with coroutine flow.
     */
    suspend fun getProfileAsync(titleId: String, titleName: String = ""): GameProfile {
        if (titleId.isBlank() && titleName.isBlank()) return defaultProfile()
        if (titleId.isNotBlank() && memoryCache.containsKey(titleId)) {
            return memoryCache[titleId]!!
        }
        val entity = dao.getGameProfile(titleId)
        if (entity != null) {
            val profile = entity.toGameProfile()
            memoryCache[titleId] = profile
            return profile
        }
        return getProfile(titleId, titleName)
    }

    fun defaultProfile(): GameProfile {
        return GameProfile(
            titleId = "default_profile",
            gameTitle = "Predeterminado Ultra",
            resolutionOverride = "1440p",
            targetFps = 60,
            targetBitrateKbps = 35000,
            preferredEngineId = 3,
            shaderPreset = "CAS Adaptive",
            sharpeningStrength = 0.65f,
            aiOptimizationPreset = "Ultra Low Latency + CAS"
        )
    }

    fun getAllProfiles(): List<GameProfile> {
        return memoryCache.values.toList().sortedBy { it.gameTitle }
    }

    /**
     * Saves a GameProfile both in memory, to disk JSON, and to Room Database (LocalDB).
     */
    fun saveProfile(profile: GameProfile) {
        val updated = profile.copy(isCustom = true)
        memoryCache[updated.titleId] = updated
        try {
            val file = File(profilesDir, "${updated.titleId}.json")
            file.writeText(updated.toJson())
            Log.i(TAG, "Perfil de juego guardado en disco para ${updated.gameTitle} (${updated.titleId})")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando perfil en disco para ${updated.titleId}: ${e.message}")
        }

        // Persist to Room Database
        scope.launch {
            try {
                dao.insertGameProfile(updated.toEntity())
                Log.i(TAG, "Perfil de juego persistido en Room Database para GameID: ${updated.titleId}")
            } catch (e: Exception) {
                Log.e(TAG, "Error persistiendo en Room DB: ${e.message}")
            }
        }
    }
}
