package com.macle.royaleassist.cache

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
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
    /** JSON list of RecentDeck (up to 3 unique decks); added in schema 2. */
    val decksJson: String? = null,
    val strategy: String?,
    val score: Double,
    val strongMatch: Boolean,
    /** When stats/deck were fetched from the network (epoch ms); TTL is measured from here. */
    val fetchedAt: Long,
    /** When the player was last shown (scan, cache hit or test lookup). */
    val lastSeenAt: Long,
    val seenCount: Int,
    /** How the player was last shown: [VIA_LOOKUP], [VIA_CACHE] or [VIA_SCREENSHOT] (schema 4). */
    val lastVia: String? = null,
) {
    companion object {
        const val VIA_LOOKUP = "lookup"
        const val VIA_CACHE = "cache"
        /** A repeated tap on the same screen answered from [ScreenKeyEntity] (no OCR, no network). */
        const val VIA_SCREENSHOT = "screenshot"
    }
}

/** Normalized OCR'd (name, clan) -> tag, so a repeated scan skips the search entirely. */
@Entity(tableName = "queries")
data class QueryEntity(
    @PrimaryKey val key: String,
    val tag: String,
    val createdAt: Long,
    /** [CachePolicy.MATCHER_VERSION] that made this decision (schema 3). */
    val matcher: Int = 0,
)

/**
 * A screen already answered (schema 4): the frame size, the padded name/clan boxes OCR found and
 * their [ScreenHash] fingerprints, and the full result as JSON. A capture whose boxes hash the same
 * is answered from here at once. Entries expire at [expiresAt].
 */
@Entity(tableName = "screen_keys")
data class ScreenKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val frameWidth: Int,
    val frameHeight: Int,
    val nameLeft: Int,
    val nameTop: Int,
    val nameRight: Int,
    val nameBottom: Int,
    val nameHash: String,
    val nameText: String,
    val clanLeft: Int? = null,
    val clanTop: Int? = null,
    val clanRight: Int? = null,
    val clanBottom: Int? = null,
    val clanHash: String? = null,
    val clanText: String? = null,
    val tag: String,
    /** The [com.macle.royaleassist.lookup.LookupResult] shown for this screen, as JSON (log dropped). */
    val resultJson: String,
    val createdAt: Long,
    val expiresAt: Long,
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

    @Query("SELECT * FROM screen_keys WHERE expiresAt > :now ORDER BY createdAt DESC")
    suspend fun screenKeys(now: Long): List<ScreenKeyEntity>

    @Insert
    suspend fun insertScreenKey(k: ScreenKeyEntity): Long

    @Query("DELETE FROM screen_keys WHERE expiresAt <= :now")
    suspend fun deleteExpiredScreenKeys(now: Long)

    @Query("DELETE FROM screen_keys WHERE id IN (:ids)")
    suspend fun deleteScreenKeys(ids: List<Long>)

    @Query("DELETE FROM screen_keys WHERE tag = :tag")
    suspend fun deleteScreenKeysFor(tag: String)

    @Query("DELETE FROM screen_keys")
    suspend fun clearScreenKeys()
}

@Database(entities = [PlayerEntity::class, QueryEntity::class, SearchEntity::class, ScreenKeyEntity::class], version = 4, exportSchema = false)
@ConstructedBy(CacheDbConstructor::class)
abstract class CacheDb : RoomDatabase() {
    abstract fun dao(): CacheDao

    companion object {
        /** File name of the database (Android: in the app's databases folder). */
        const val NAME = "lookup-cache.db"

        /** Schema 2: recent unique decks per player. Keeps existing history. */
        val MIGRATION_1_2 = Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE players ADD COLUMN decksJson TEXT")
        }

        /** Schema 3: matcher version on query decisions (old ones then count as expired). */
        val MIGRATION_2_3 = Migration(2, 3) { db ->
            db.execSQL("ALTER TABLE queries ADD COLUMN matcher INTEGER NOT NULL DEFAULT 0")
        }

        /** Schema 4: how each player was last shown, and the screenshot-keyed instant cache. */
        val MIGRATION_3_4 = Migration(3, 4) { db ->
            db.execSQL("ALTER TABLE players ADD COLUMN lastVia TEXT")
            db.execSQL(SCREEN_KEYS_SQL)
        }

        /** Same statement Room generates for [ScreenKeyEntity] (checked against CacheDb_Impl). */
        const val SCREEN_KEYS_SQL = "CREATE TABLE IF NOT EXISTS `screen_keys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`frameWidth` INTEGER NOT NULL, `frameHeight` INTEGER NOT NULL, `nameLeft` INTEGER NOT NULL, `nameTop` INTEGER NOT NULL, " +
            "`nameRight` INTEGER NOT NULL, `nameBottom` INTEGER NOT NULL, `nameHash` TEXT NOT NULL, `nameText` TEXT NOT NULL, " +
            "`clanLeft` INTEGER, `clanTop` INTEGER, `clanRight` INTEGER, `clanBottom` INTEGER, `clanHash` TEXT, `clanText` TEXT, " +
            "`tag` TEXT NOT NULL, `resultJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL)"
    }
}

/** Room generates the actual objects (one per platform) with KSP. */
@Suppress("KotlinNoActualForExpect")
expect object CacheDbConstructor : RoomDatabaseConstructor<CacheDb> {
    override fun initialize(): CacheDb
}

/** The options every platform builds the database with: the 1-2-3-4 migrations, else start empty. */
internal fun RoomDatabase.Builder<CacheDb>.withCacheOptions(): RoomDatabase.Builder<CacheDb> =
    addMigrations(CacheDb.MIGRATION_1_2, CacheDb.MIGRATION_2_3, CacheDb.MIGRATION_3_4)
        .fallbackToDestructiveMigration(true)
