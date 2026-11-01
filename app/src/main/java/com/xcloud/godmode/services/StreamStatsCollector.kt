package com.xcloud.godmode.services

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.statsDataStore by preferencesDataStore(name = "stream_telemetry")

data class HistoricalStat(
    val timestamp: Long,
    val server: String,
    val avgPing: Long,
    val jitter: Double,
    val packetLoss: Double
)

/**
 * StreamStatsCollector: Azure Benchmark DB.
 * Collects and persists historical stream telemetry to suggest the best server over time.
 */
class StreamStatsCollector(private val context: Context) {
    private val HISTORY_KEY = stringPreferencesKey("historical_stats")
    private val gson = Gson()

    val history: Flow<List<HistoricalStat>> = context.statsDataStore.data.map { prefs ->
        val json = prefs[HISTORY_KEY] ?: "[]"
        val type = object : TypeToken<List<HistoricalStat>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun recordSession(stat: HistoricalStat) {
        context.statsDataStore.edit { prefs ->
            val json = prefs[HISTORY_KEY] ?: "[]"
            val type = object : TypeToken<MutableList<HistoricalStat>>() {}.type
            val list: MutableList<HistoricalStat> = gson.fromJson(json, type) ?: mutableListOf()
            
            list.add(stat)
            
            // Keep last 100 sessions
            if (list.size > 100) list.removeAt(0)
            
            prefs[HISTORY_KEY] = gson.toJson(list)
        }
    }
}
