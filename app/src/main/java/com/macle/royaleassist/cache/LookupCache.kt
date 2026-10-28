package com.macle.royaleassist.cache

import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.LastBattle
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.lookup.RecentDeck
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** Room-backed cache for lookups; also the history source. */
class LookupCache(private val dao: CacheDao) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Cached(val entity: PlayerEntity, val stats: PlayerStats, val battle: LastBattle?, val decks: List<RecentDeck> = emptyList())

    fun history(): Flow<List<PlayerEntity>> = dao.history()

    fun decode(e: PlayerEntity): Cached? = runCatching {
        Cached(
            e,
            json.decodeFromString<PlayerStats>(e.statsJson),
            e.battleJson?.let { json.decodeFromString<LastBattle>(it) },
            e.decksJson?.let { json.decodeFromString<List<RecentDeck>>(it) }.orEmpty(),
        )
    }.onFailure { L.w("cache: cannot decode ${e.tag}: ${it.message}") }.getOrNull()

    suspend fun byTag(tag: String): Cached? = dao.player(tag)?.let(::decode)

    suspend fun byQuery(key: String): Cached? {
        val q = dao.query(key) ?: return null
        if (q.matcher != CachePolicy.MATCHER_VERSION) return null
        return byTag(q.tag)
    }

    suspend fun search(url: String, ttl: CacheTtl, now: Long): List<PlayerHit>? {
        val s = dao.search(url) ?: return null
        if (!CachePolicy.isFresh(s.fetchedAt, now, ttl)) return null
        return runCatching { json.decodeFromString<List<PlayerHit>>(s.rowsJson) }.getOrNull()
    }

    suspend fun putSearch(url: String, rows: List<PlayerHit>, now: Long) {
        dao.upsertSearch(SearchEntity(url, json.encodeToString(rows), now))
    }

    /** Saves a freshly fetched result (counts as one sighting). */
    suspend fun putResult(r: LookupResult, queryKey: String?, now: Long) {
        val stats = r.stats ?: return
        val tag = stats.tag
        val old = dao.player(tag)
        // Keep an older cached deck if this fetch did not get one.
        val battleJson = r.lastBattle?.let { json.encodeToString(it) } ?: old?.battleJson
        val decksJson = r.recentDecks.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) } ?: old?.decksJson
        dao.upsertPlayer(
            PlayerEntity(
                tag = tag,
                name = stats.name,
                clanName = stats.clanName,
                clanTag = stats.clanTag,
                trophies = stats.trophies,
                statsJson = json.encodeToString(stats),
                battleJson = battleJson,
                decksJson = decksJson,
                strategy = r.strategy,
                score = r.confidence,
                strongMatch = r.strongMatch,
                fetchedAt = now,
                lastSeenAt = now,
                seenCount = (old?.seenCount ?: 0) + 1,
            )
        )
        if (queryKey != null) dao.upsertQuery(QueryEntity(queryKey, tag, now, CachePolicy.MATCHER_VERSION))
    }

    suspend fun putQuery(key: String, tag: String, now: Long) {
        dao.upsertQuery(QueryEntity(key, tag, now, CachePolicy.MATCHER_VERSION))
    }

    /** Records another sighting of a cached player (cache hit). */
    suspend fun touch(tag: String, now: Long) {
        val old = dao.player(tag) ?: return
        dao.upsertPlayer(old.copy(lastSeenAt = now, seenCount = old.seenCount + 1))
    }

    suspend fun updateDecks(tag: String, battle: LastBattle, decks: List<RecentDeck>) {
        val old = dao.player(tag) ?: return
        dao.upsertPlayer(old.copy(battleJson = json.encodeToString(battle), decksJson = json.encodeToString(decks)))
    }

    suspend fun delete(tag: String) {
        dao.deletePlayer(tag)
        dao.deleteQueriesFor(tag)
    }

    suspend fun clear() {
        dao.clearPlayers()
        dao.clearQueries()
        dao.clearSearches()
    }
}

/** A history/cache entry as a lookup result, for re-showing it in the panel layout. */
fun LookupCache.Cached.toResult() = com.macle.royaleassist.lookup.LookupResult(
    query = entity.name,
    clanQuery = entity.clanName,
    hit = com.macle.royaleassist.lookup.PlayerHit(entity.name, entity.tag, entity.clanName, entity.clanTag),
    strategy = entity.strategy,
    confidence = entity.score,
    stats = stats,
    strongMatch = entity.strongMatch,
    lastBattle = battle,
    recentDecks = decks,
    cached = true,
    fetchedAt = entity.fetchedAt,
)
