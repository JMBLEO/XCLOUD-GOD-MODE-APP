package com.xcloud.godmode.models

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_games")
data class CatalogEntity(
    @PrimaryKey val productId: String,
    val titleId: String? = null,
    val title: String,
    val publisher: String = "Xbox Cloud Gaming",
    val description: String = "",
    val posterUrl: String = "",
    val heroUrl: String = "",
    val superHeroUrl: String = "",
    val genre: String = "Acción y Aventura",
    val rating: String = "ESRB Teen",
    val isCloudEnabled: Boolean = true,
    val isSeriesOptimized: Boolean = true,
    val isByog: Boolean = false,
    val supportsMkb: Boolean = false,
    val marketRegion: String = "WORLD", // "US", "CL", "LATAM", "WORLD"
    val lastPlayedEpochMs: Long = 0L
)

@Fts4(contentEntity = CatalogEntity::class)
@Entity(tableName = "catalog_fts")
data class CatalogFtsEntity(
    val title: String,
    val publisher: String,
    val description: String,
    val genre: String
)
