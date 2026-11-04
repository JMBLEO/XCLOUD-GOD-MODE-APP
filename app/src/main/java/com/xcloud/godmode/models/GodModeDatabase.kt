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

    // Full Catalog & FTS4 Queries
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCatalogGames(games: List<CatalogEntity>)

    @Query("SELECT * FROM catalog_games ORDER BY title ASC")
    fun getAllCatalogGames(): Flow<List<CatalogEntity>>

    @Query("SELECT * FROM catalog_games WHERE isByog = 1 ORDER BY title ASC")
    fun getByogGames(): Flow<List<CatalogEntity>>

    @Query("SELECT * FROM catalog_games WHERE lastPlayedEpochMs > 0 ORDER BY lastPlayedEpochMs DESC")
    fun getRecentGames(): Flow<List<CatalogEntity>>

    @Query("SELECT * FROM catalog_games WHERE isSeriesOptimized = 1 ORDER BY title ASC")
    fun getSeriesOptimizedGames(): Flow<List<CatalogEntity>>

    @Query("SELECT * FROM catalog_games WHERE marketRegion = 'CL' OR marketRegion = 'WORLD' ORDER BY title ASC")
    fun getChileCatalogGames(): Flow<List<CatalogEntity>>

    @Query("SELECT * FROM catalog_games WHERE marketRegion IN ('CL', 'MX', 'LATAM', 'WORLD') ORDER BY title ASC")
    fun getLatamCatalogGames(): Flow<List<CatalogEntity>>

    @Query("SELECT * FROM catalog_games WHERE title LIKE '%' || :query || '%' OR publisher LIKE '%' || :query || '%' OR genre LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchCatalogGames(query: String): Flow<List<CatalogEntity>>

    @Query("UPDATE catalog_games SET lastPlayedEpochMs = :epochMs WHERE productId = :productId OR titleId = :productId")
    suspend fun updateLastPlayed(productId: String, epochMs: Long = System.currentTimeMillis())

    // Game Profiles (Per-Game Configuration)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGameProfile(profile: GameProfileEntity)

    @Query("SELECT * FROM game_profiles WHERE gameId = :gameId")
    suspend fun getGameProfile(gameId: String): GameProfileEntity?

    @Query("SELECT * FROM game_profiles WHERE gameId = :gameId")
    fun getGameProfileFlow(gameId: String): Flow<GameProfileEntity?>

    @Query("SELECT * FROM game_profiles ORDER BY lastUpdatedEpochMs DESC")
    fun getAllGameProfiles(): Flow<List<GameProfileEntity>>

    @Query("DELETE FROM game_profiles WHERE gameId = :gameId")
    suspend fun deleteGameProfile(gameId: String)
}

@Database(
    entities = [TelemetryEntry::class, GameCacheEntry::class, CatalogEntity::class, CatalogFtsEntity::class, GameProfileEntity::class],
    version = 3,
    exportSchema = false
)
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
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

