package com.macle.royaleassist.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One looked-up player, keyed by tag. Stats and last-battle deck are stored as JSON of
 * [com.macle.royaleassist.lookup.PlayerStats] / [com.macle.royaleassist.lookup.LastBattle].
 * Also the source for the History screen.
 */
@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey val tag: String,
    val name: String,
    val clanName: String?,
    val clanTag: String?,
    val trophies: Int?,
    val statsJson: String,
    val battleJson: String?,
    val strategy: String?,
    val score: Double,
    val strongMatch: Boolean,
    /** When stats/deck were fetched from the network (epoch ms); TTL is measured from here. */
    val fetchedAt: Long,
    /** When the player was last shown (scan, cache hit or test lookup). */
    val lastSeenAt: Long,
    val seenCount: Int,
)

/** Normalized OCR'd (name, clan) -> tag, so a repeated scan skips the search entirely. */
@Entity(tableName = "queries")
data class QueryEntity(
    @PrimaryKey val key: String,
    val tag: String,
    val createdAt: Long,
)

/** Parsed search result rows (JSON list of PlayerHit) keyed by request URL. */
@Entity(tableName = "searches")
data class SearchEntity(
    @PrimaryKey val url: String,
    val rowsJson: String,
    val fetchedAt: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM players WHERE tag = :tag")
    suspend fun player(tag: String): PlayerEntity?

    @Upsert
    suspend fun upsertPlayer(p: PlayerEntity)

    @Query("SELECT * FROM players ORDER BY lastSeenAt DESC")
    fun history(): Flow<List<PlayerEntity>>

    @Query("DELETE FROM players WHERE tag = :tag")
    suspend fun deletePlayer(tag: String)

    @Query("DELETE FROM queries WHERE tag = :tag")
    suspend fun deleteQueriesFor(tag: String)

    @Query("SELECT * FROM queries WHERE `key` = :key")
    suspend fun query(key: String): QueryEntity?

    @Upsert
    suspend fun upsertQuery(q: QueryEntity)

    @Query("SELECT * FROM searches WHERE url = :url")
    suspend fun search(url: String): SearchEntity?

    @Upsert
    suspend fun upsertSearch(s: SearchEntity)

    @Query("DELETE FROM players")
    suspend fun clearPlayers()

    @Query("DELETE FROM queries")
    suspend fun clearQueries()

    @Query("DELETE FROM searches")
    suspend fun clearSearches()
}

@Database(entities = [PlayerEntity::class, QueryEntity::class, SearchEntity::class], version = 1, exportSchema = false)
abstract class CacheDb : RoomDatabase() {
    abstract fun dao(): CacheDao

    companion object {
        fun create(context: Context): CacheDb =
            Room.databaseBuilder(context, CacheDb::class.java, "lookup-cache.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
