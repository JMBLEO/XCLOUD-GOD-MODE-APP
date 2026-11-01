package com.xcloud.godmode.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telemetry_logs")
data class TelemetryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val serverId: String,
    val avgRtt: Int,
    val avgJitter: Double,
    val packetLoss: Double,
    val resolution: String,
    val motor: String,
    val networkType: String
)

@Entity(tableName = "game_cache")
data class GameCacheEntry(
    @PrimaryKey val titleId: String,
    val title: String,
    val posterUrl: String,
    val heroUrl: String,
    val description: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
