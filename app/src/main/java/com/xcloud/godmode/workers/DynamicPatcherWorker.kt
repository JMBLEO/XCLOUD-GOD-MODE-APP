package com.xcloud.godmode.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Layer 5: Dynamic Patcher
 * Periodically fetches remote configuration overrides (JSON) to patch XboxEndpoints dynamically.
 * This allows the app to bypass regional blocks or update API endpoints without a full app update.
 */
class DynamicPatcherWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val TAG = "DynamicPatcher"
    private val client = OkHttpClient()

    override suspend fun doWork(): Result {
        Log.d(TAG, "Dynamic Patcher: Fetching remote endpoint overrides...")

        try {
            // URL pointing to a secure remote config (GitHub Gist or similar)
            // Note: This is an OTA update channel for dynamic regional bypasses.
            val remoteUrl = "https://gist.githubusercontent.com/xcloud-godmode/overrides/raw/endpoints.json"
            
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Cache-Control", "no-cache")
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: "{}"
                if (jsonStr.trim().startsWith("{")) {
                    val json = JSONObject(jsonStr)
                    
                    // Patch XboxEndpoints (Layer 5)
                    applyOverrides(json)
                    
                    Log.d(TAG, "Dynamic Patcher: Endpoints successfully patched via OTA.")
                    return Result.success()
                } else {
                    Log.w(TAG, "Dynamic Patcher: Received invalid payload (not JSON).")
                }
            } else {
                Log.w(TAG, "Dynamic Patcher: Server returned error ${response.code}.")
            }
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "Dynamic Patcher: DNS Resolution failed. Device might be offline or domain is blocked.")
            return Result.retry() // Retry later when network returns
        } catch (e: Exception) {
            Log.e(TAG, "Dynamic Patcher: Unexpected failure: ${e.message}")
        }

        return Result.retry()
    }

    private fun applyOverrides(json: JSONObject) {
        // Here we would store the overrides in DataStore or SharedPreferences
        // which the XboxEndpoints class would then read at runtime.
        val prefs = applicationContext.getSharedPreferences("god_mode_patcher", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        json.keys().forEach { key ->
            editor.putString(key, json.getString(key))
        }
        editor.apply()
    }
}
