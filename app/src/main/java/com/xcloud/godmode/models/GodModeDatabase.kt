package com.xcloud.godmode.models

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GodModeDao {
    // Telemetry
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetry(entry: TelemetryEntry)

    @Query("SELECT * FROM telemetry_logs ORDER BY timestamp DESC LIMIT 100")
    fun getAllTelemetry(): Flow<List<TelemetryEntry>>

    @Query("SELECT serverId FROM telemetry_logs GROUP BY serverId ORDER BY AVG(avgRtt) ASC LIMIT 1")
    suspend fun getBestServerByHistory(): String?

    // Game Cache
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cacheGame(entry: GameCacheEntry)

    @Query("SELECT * FROM game_cache WHERE titleId = :id")
    suspend fun getCachedGame(id: String): GameCacheEntry?

    @Query("SELECT * FROM game_cache ORDER BY lastUpdated DESC")
    fun getCachedCatalog(): Flow<List<GameCacheEntry>>
}

@Database(entities = [TelemetryEntry::class, GameCacheEntry::class], version = 1, exportSchema = false)
abstract class GodModeDatabase : RoomDatabase() {
    abstract fun dao(): GodModeDao

    companion object {
        @Volatile
        private var INSTANCE: GodModeDatabase? = null

        fun getDatabase(context: android.content.Context): GodModeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GodModeDatabase::class.java,
                    "god_mode_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
