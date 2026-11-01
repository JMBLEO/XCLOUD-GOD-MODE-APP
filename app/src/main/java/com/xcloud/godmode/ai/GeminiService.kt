package com.xcloud.godmode.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.xcloud.godmode.BuildConfig

/**
 * GeminiService handles AI-driven optimization recommendations for games.
 */
class GeminiService(private val okHttpClient: OkHttpClient = OkHttpClient()) {
    private val TAG = "GeminiService"
    private val API_KEY = BuildConfig.GEMINI_API_KEY

    suspend fun getOptimizationRecommendation(gameTitle: String, networkType: String): String = withContext(Dispatchers.IO) {
        if (API_KEY.isEmpty()) {
            return@withContext "AI Optimization is currently unavailable (API Key missing)."
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$API_KEY"
        
        val prompt = """
            As an Xbox Cloud Gaming expert assistant, provide a short (max 2 sentences) performance optimization recommendation for:
            Game: $gameTitle
            Network: $networkType
            
            Focus on settings like 'God Mode', 'Balanced', or 'Battery Saver' and explain why.
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Recommended Profile: God Mode (Default AI recommendation for high performance)."
                
                val responseJson = JSONObject(response.body?.string() ?: "")
                val candidate = responseJson.getJSONArray("candidates").getJSONObject(0)
                val text = candidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                return@withContext text.trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed", e)
            return@withContext "Recommended Profile: God Mode. Enjoy low latency and high quality on your $networkType connection."
        }
    }
}
