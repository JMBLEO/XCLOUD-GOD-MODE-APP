package com.aistudio.xcloudgodmode.ai

import com.aistudio.xcloudgodmode.ai.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object GeminiAiAssistant {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    /**
     * Mode:
     * - "low_latency": uses gemini-3.1-flash-lite for instant, ultra-fast responses (<1s)
     * - "thinking": uses gemini-4-argon with thinkingLevel = "high" (do not set maxOutputTokens)
     */
    suspend fun query(prompt: String, mode: String = "low_latency"): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Para activar el Copiloto IA de God Mode, agrega tu GEMINI_API_KEY en el panel de Secretos de AI Studio."
        }

        val model = if (mode == "thinking") {
            "gemini-4-argon"
        } else {
            "gemini-3.1-flash-lite"
        }

        val requestJson = JSONObject().apply {
            val contentsArr = JSONArray().apply {
                val turn = JSONObject().apply {
                    val partsArr = JSONArray().apply {
                        val part = JSONObject().apply {
                            put("text", prompt)
                        }
                        put(part)
                    }
                    put("parts", partsArr)
                }
                put(turn)
            }
            put("contents", contentsArr)

            val genConfig = JSONObject()
            if (mode == "thinking") {
                val thinkingObj = JSONObject().apply {
                    put("thinkingLevel", "high")
                }
                genConfig.put("thinkingConfig", thinkingObj)
                // Note: Do not set maxOutputTokens for gemini-4-argon as mandated
            } else {
                genConfig.put("temperature", 0.4)
            }
            put("generationConfig", genConfig)

            val systemInstruction = JSONObject().apply {
                val partsArr = JSONArray().apply {
                    val p = JSONObject().apply {
                        put("text", "Eres God Mode AI Gaming Copilot, un asistente experto en optimización de Xbox Cloud Gaming, Remote Play, análisis de baja latencia WebRTC, shaders (Real-ESRGAN, SeedVR2), tasas de refresco 120Hz, y tácticas de juego. Responde de forma clara, directa y estructurada en español.")
                    }
                    put(p)
                }
                put("parts", partsArr)
            }
            put("systemInstruction", systemInstruction)
        }

        val url = "$BASE_URL$model:generateContent?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext "Error del servicio IA (${response.code}): ${response.message}"
                }

                val json = JSONObject(responseBody)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                sb.append(part.getString("text"))
                            }
                        }
                        return@withContext sb.toString().trim()
                    }
                }
                return@withContext "No se obtuvo respuesta del modelo."
            }
        } catch (e: Exception) {
            return@withContext "Error de conexión con el Copiloto IA: ${e.localizedMessage ?: e.message}"
        }
    }
}
