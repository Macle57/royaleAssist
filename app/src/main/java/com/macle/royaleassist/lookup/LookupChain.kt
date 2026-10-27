package com.macle.royaleassist.lookup

import com.macle.royaleassist.L
import com.macle.royaleassist.cache.CachePolicy
import com.macle.royaleassist.cache.CacheTtl
import com.macle.royaleassist.cache.LookupCache
import com.macle.royaleassist.lookup.OfficialApi.Companion.toStats
import com.macle.royaleassist.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * Turns an OCR'd player name (+ optional clan name) into a player tag and basic stats by trying a
 * chain of strategies. Every strategy yields candidate players; each candidate is scored by name
 * similarity and (when a clan was read) clan similarity. The chain stops at the first *strong*
 * candidate (name and clan both match); otherwise it keeps going and finally returns the best
 * acceptable candidate. Each step logs to logcat (tag RoyaleAssist) and into [LookupResult.log].
 *
 *  L1   RoyaleAPI website, "exact" name search (case-sensitive; the site still returns prefixes).
 *  L2   RoyaleAPI website, fuzzy name search.
 *  L2v  Fuzzy search again with typical OCR confusions fixed (v->y, rn->m, 0->O, ...).
 *  L2c  RoyaleAPI website clan search by the OCR'd clan name, then that clan's member list.
 *  L3   Official API (token only): clan search, then member list.
 *
 * The game font draws lowercase as small capitals, so OCR case is unreliable ("TRaiNeR fart");
 * all comparisons are case-insensitive and the exact search is only the cheap first try.
 *
 * Stats come from the official API when a token is configured, else from the RoyaleAPI profile
 * page, else just the search hit.
 */
class LookupChain(
    private val http: HttpClients,
    private val settings: Settings,
    private val cache: LookupCache? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val api = OfficialApi(http)

    data class Scored(val hit: PlayerHit, val nameSim: Double, val clanSim: Double, val score: Double)

    private class Ctx(
        val name: String,
        val clan: String?,
        val token: String?,
        val wantDeck: Boolean = false,
        val ttl: CacheTtl = CacheTtl.DEFAULT,
        val bypassCache: Boolean = false,
        val queryKey: String? = null,
    ) {
        val log = mutableListOf<String>()
        var cloudflareBlocked = false
        val candidates = LinkedHashMap<String, Scored>()
        fun log(msg: String) { L.i("lookup: $msg"); log += msg }
    }

    private class Strategy(val id: String, val web: Boolean, val run: suspend (Ctx) -> List<PlayerHit>)

    private val strategies = listOf(
        Strategy("L1 RoyaleAPI exact search", web = true) { ctx ->
            searchRows(ctx, RoyaleApiWeb.searchUrl(ctx.name, exact = true))
        },
        Strategy("L2 RoyaleAPI fuzzy search", web = true) { ctx ->
            searchRows(ctx, RoyaleApiWeb.searchUrl(ctx.name, exact = false))
        },
        Strategy("L2v RoyaleAPI search with OCR fixes", web = true) { ctx ->
            val out = mutableListOf<PlayerHit>()
            for (v in ocrVariants(ctx.name).take(4)) {
                val rows = searchRows(ctx, RoyaleApiWeb.searchUrl(v, exact = false))
                ctx.log("  variant '$v': ${rows.size} rows")
                out += rows
                if (rows.any { isStrong(score(it, ctx.name, ctx.clan), ctx.clan) || isStrong(score(it, v, ctx.clan), ctx.clan) }) break
            }
            out
        },
        Strategy("L2c RoyaleAPI clan members", web = true) { ctx ->
            val clan = ctx.clan
            if (clan == null || StringSim.normalize(clan).length < 2) {
                emptyList()
            } else {
                // Clans already seen on earlier search hits come first (they carry the exact clan tag),
                // then the clan search results.
                val fromHits = ctx.candidates.values
                    .filter { it.hit.clanTag != null && it.clanSim >= 0.8 }
                    .sortedByDescending { it.clanSim }
                    .map { it.hit.clanTag!! to (it.hit.clanName ?: clan) }
                val clans = RoyaleApiWeb.parseClanSearch(http.getHtml(RoyaleApiWeb.clanSearchUrl(clan)))
                val ranked = (fromHits + rankClans(clans, clan)).distinctBy { it.first }.take(MAX_CLAN_PAGES)
                ctx.log("  ${clans.size} clans found by name, ${fromHits.map { it.first }.distinct().size} from search hits, checking ${ranked.size}")
                val out = mutableListOf<PlayerHit>()
                for ((tag, cName) in ranked) {
                    val members = RoyaleApiWeb.parseClanMembers(http.getHtml(RoyaleApiWeb.clanUrl(tag)), tag, cName)
                    out += members
                    if (members.any { isStrong(score(it, ctx.name, ctx.clan), ctx.clan) }) break
                }
                out
            }
        },
        Strategy("L3 official API clan members", web = false) { ctx ->
            val token = ctx.token
            val clan = ctx.clan
            when {
                token == null -> { ctx.log("  skipped (no API token)"); emptyList() }
                clan == null || clan.trim().length < 3 -> { ctx.log("  skipped (no usable clan name)"); emptyList() }
                else -> {
                    val clans = rankClans(api.searchClans(clan.trim(), token).map { it.tag to it.name }, clan).take(MAX_CLAN_PAGES)
                    val out = mutableListOf<PlayerHit>()
                    for ((tag, cName) in clans) {
                        val members = api.clanMembers(tag, token).map { PlayerHit(it.name, tagNoHash(it.tag), cName, tagNoHash(tag)) }
                        out += members
                        if (members.any { isStrong(score(it, ctx.name, ctx.clan), ctx.clan) }) break
                    }
                    out
                }
            }
        },
    )

    /** Search page rows, from the cache when fresh. */
    private suspend fun searchRows(ctx: Ctx, url: String): List<PlayerHit> {
        if (cache != null && !ctx.bypassCache) {
            cache.search(url, ctx.ttl, clock())?.let {
                ctx.log("  cached search ${url.substringAfter("?")}: ${it.size} rows")
                return it
            }
        }
        val rows = RoyaleApiWeb.parseSearch(http.getHtml(url))
        cache?.putSearch(url, rows, clock())
        return rows
    }

    /**
     * Main entry: OCR'd name + optional clan -> result. A repeated (name, clan) within the cache
     * TTL is answered from the local cache without any network. Never throws (except cancellation).
     */
    suspend fun lookup(name: String, clan: String?, bypassCache: Boolean = false): LookupResult {
        val cleanName = cleanOcrName(name)
        val cleanClan = clan?.let(::cleanOcrName)?.ifBlank { null }
        val key = CachePolicy.queryKey(cleanName, cleanClan)
        val ctx = Ctx(
            cleanName, cleanClan, settings.currentToken(), settings.overlayPrefs.first().deckOnly,
            settings.cacheTtl.first(), bypassCache, key,
        )
        ctx.log("query name='$cleanName' clan='${cleanClan ?: "-"}' token=${if (ctx.token != null) "yes" else "no"}")
        if (cleanName.isBlank()) return LookupResult(name, clan, null, null, 0.0, null, log = ctx.log, error = "Empty name")

        if (cache != null && !bypassCache) {
            val hit = cache.byQuery(key)
            if (hit != null && CachePolicy.isFresh(hit.entity.fetchedAt, clock(), ctx.ttl)) {
                ctx.log("cache hit for '$key' -> #${hit.entity.tag} (fetched ${CachePolicy.age(hit.entity.fetchedAt, clock())}, no network)")
                return fromCache(ctx, hit, "cache")
            }
            if (hit != null) ctx.log("cache entry for '$key' is stale (${CachePolicy.age(hit.entity.fetchedAt, clock())})")
        }

        // A tag typed directly ("#98VQRGCQ") skips the search.
        if (TAG_RE.matches(cleanName)) {
            return finish(ctx, Scored(PlayerHit(cleanName, tagNoHash(cleanName)), 1.0, 0.0, 1.0), "direct tag", emptyList(), strong = true)
        }

        var bestFrom: String? = null
        for (s in strategies) {
            if (s.web && ctx.cloudflareBlocked) continue
            val hits = try {
                s.run(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareBlockedException) {
                ctx.cloudflareBlocked = true
                ctx.log("${s.id}: blocked by Cloudflare (open the verifier once)")
                emptyList()
            } catch (e: Exception) {
                ctx.log("${s.id}: failed: ${e.message}")
                emptyList()
            }
            if (hits.isEmpty()) { ctx.log("${s.id}: no candidates"); continue }
            val scored = hits.map { score(it, ctx.name, ctx.clan) }
            val prevBest = ctx.candidates.values.maxByOrNull { it.score }
            for (c in scored) {
                val old = ctx.candidates[c.hit.tag]
                if (old == null || c.score > old.score) ctx.candidates[c.hit.tag] = c
            }
            val top = scored.maxBy { it.score }
            ctx.log("${s.id}: ${hits.size} candidates, best ${describe(top)}")
            if (prevBest == null || top.score > prevBest.score) bestFrom = s.id
            if (isStrong(top, ctx.clan)) return finish(ctx, top, s.id, alternatives(ctx, top), strong = true)
        }
        val best = ctx.candidates.values.maxByOrNull { it.score }
        if (best != null && best.nameSim >= MIN_NAME_SIM) {
            ctx.log("no strong match; using best weak candidate ${describe(best)}")
            return finish(ctx, best, bestFrom ?: "best weak candidate", alternatives(ctx, best), strong = false)
        }
        ctx.log("no strategy found a player")
        return LookupResult(
            name, clan, null, null, 0.0, null,
            alternatives = ctx.candidates.values.sortedByDescending { it.score }.take(5).map { it.hit },
            needsVerification = ctx.cloudflareBlocked, log = ctx.log,
            error = if (ctx.cloudflareBlocked) "RoyaleAPI needs verification" else "Player not found",
        )
    }

    private fun alternatives(ctx: Ctx, chosen: Scored) =
        ctx.candidates.values.filter { it.hit.tag != chosen.hit.tag }.sortedByDescending { it.score }.take(5).map { it.hit }

    /** Re-fetches stats and deck for a known tag, ignoring the cache (the panel's Refresh). */
    suspend fun refresh(tag: String): LookupResult {
        val t = tagNoHash(tag)
        val old = cache?.byTag(t)
        val ctx = Ctx(
            old?.entity?.name ?: "#$t", old?.entity?.clanName, settings.currentToken(), settings.overlayPrefs.first().deckOnly,
            settings.cacheTtl.first(), bypassCache = true, queryKey = null,
        )
        ctx.log("refresh #$t (cache bypassed)")
        val hit = PlayerHit(old?.entity?.name ?: "#$t", t, old?.entity?.clanName, old?.entity?.clanTag)
        return finish(
            ctx, Scored(hit, 1.0, 1.0, old?.entity?.score ?: 1.0), old?.entity?.strategy ?: "direct tag", emptyList(),
            strong = old?.entity?.strongMatch ?: true,
        )
    }

    /** Builds a result from a cache entry; fetches only a missing deck when the deck view needs it. */
    private suspend fun fromCache(ctx: Ctx, c: LookupCache.Cached, strategy: String?): LookupResult {
        var battle = c.battle
        var battleError: String? = null
        if (ctx.wantDeck && battle == null) {
            ctx.log("cache has no deck; fetching the battle log")
            val r = fetchLastBattle(ctx, c.entity.tag)
            battle = r.getOrNull()
            battleError = r.exceptionOrNull()?.message
            battle?.let { cache?.updateBattle(c.entity.tag, it) }
        }
        cache?.touch(c.entity.tag, clock())
        return LookupResult(
            query = ctx.name,
            clanQuery = ctx.clan,
            hit = PlayerHit(c.entity.name, c.entity.tag, c.entity.clanName, c.entity.clanTag),
            strategy = c.entity.strategy ?: strategy,
            confidence = c.entity.score,
            stats = c.stats,
            strongMatch = c.entity.strongMatch,
            lastBattle = battle,
            lastBattleError = battleError,
            cached = true,
            fetchedAt = c.entity.fetchedAt,
            log = ctx.log,
        )
    }

    private suspend fun finish(ctx: Ctx, found: Scored, strategy: String, alternatives: List<PlayerHit>, strong: Boolean): LookupResult {
        ctx.log("$strategy: picked ${describe(found)}")
        // The search found a player whose stats are already cached (e.g. a different OCR reading).
        if (cache != null && !ctx.bypassCache) {
            val c = cache.byTag(found.hit.tag)
            if (c != null && CachePolicy.isFresh(c.entity.fetchedAt, clock(), ctx.ttl)) {
                ctx.log("stats for #${found.hit.tag} served from cache")
                ctx.queryKey?.let { cache.putQuery(it, found.hit.tag, clock()) }
                return fromCache(ctx, c, strategy).copy(alternatives = alternatives, needsVerification = ctx.cloudflareBlocked)
            }
        }
        // Stats and (when the deck view is on) the last battle are fetched in parallel.
        val (stats, battle) = coroutineScope {
            val s = async { fetchStats(ctx, found.hit) }
            val b = async { if (ctx.wantDeck) fetchLastBattle(ctx, found.hit.tag) else null }
            s.await() to b.await()
        }
        return LookupResult(
            query = ctx.name,
            clanQuery = ctx.clan,
            hit = found.hit,
            strategy = strategy,
            confidence = found.score,
            stats = stats,
            alternatives = alternatives,
            needsVerification = ctx.cloudflareBlocked,
            strongMatch = strong,
            lastBattle = battle?.getOrNull(),
            lastBattleError = battle?.exceptionOrNull()?.message,
            fetchedAt = clock(),
            log = ctx.log,
        ).also { r ->
            // Only real stats are cached; a fallback (network failed) must not replace a good entry.
            if (r.stats != null && !r.stats.source.startsWith(FALLBACK_SOURCE)) cache?.putResult(r, ctx.queryKey, clock())
            else if (r.stats != null) ctx.log("not caching: stats came from the search result only")
        }
    }

    /** Last battle deck: official battle log with a token, else the RoyaleAPI battles page. */
    private suspend fun fetchLastBattle(ctx: Ctx, tag: String): Result<LastBattle> {
        ctx.token?.let { token ->
            try {
                BattleLog.fromApi(api.battleLog(tag, token), tag)?.let {
                    ctx.log("battle: official API ok (${it.mode})")
                    return Result.success(it)
                }
                ctx.log("battle: official API battle log empty")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.log("battle: official API failed: ${e.message}")
            }
        }
        return try {
            val b = BattleLog.parseRoyaleApi(http.getHtml(RoyaleApiWeb.battlesUrl(tag)), tag)
            if (b != null) {
                ctx.log("battle: royaleapi.com battles ok (${b.mode}, ${b.cards.size} cards)")
                Result.success(b)
            } else {
                ctx.log("battle: no battles on royaleapi.com")
                Result.failure(IllegalStateException("No recent battles found"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareBlockedException) {
            ctx.log("battle: blocked by Cloudflare")
            Result.failure(IllegalStateException("RoyaleAPI needs verification"))
        } catch (e: Exception) {
            ctx.log("battle: failed: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun fetchStats(ctx: Ctx, hit: PlayerHit): PlayerStats {
        ctx.token?.let { token ->
            try {
                return api.player(hit.tag, token).toStats().also { ctx.log("stats: official API ok") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.log("stats: official API failed: ${e.message}")
            }
        }
        if (!ctx.cloudflareBlocked) {
            try {
                val p = RoyaleApiWeb.parseProfile(http.getHtml(RoyaleApiWeb.playerUrl(hit.tag)), hit.tag)
                if (p != null) {
                    ctx.log("stats: royaleapi.com profile ok")
                    return p
                }
                ctx.log("stats: profile page had no player header")
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareBlockedException) {
                ctx.cloudflareBlocked = true
                ctx.log("stats: profile blocked by Cloudflare")
            } catch (e: Exception) {
                ctx.log("stats: profile failed: ${e.message}")
            }
        }
        return PlayerStats(
            name = hit.name, tag = hit.tag, clanName = hit.clanName, clanTag = hit.clanTag,
            source = "$FALLBACK_SOURCE (add an API token for full stats)",
        )
    }

    companion object {
        const val FALLBACK_SOURCE = "search result only"
        const val STRONG_NAME_SIM = 0.85
        const val STRONG_CLAN_SIM = 0.75
        const val MIN_NAME_SIM = 0.72
        const val MAX_CLAN_PAGES = 6
        private val TAG_RE = Regex("#[0289PYLQGRJCUV]{3,14}", RegexOption.IGNORE_CASE)

        /** Strip OCR noise around a name: surrounding quotes/stars/pipes and extra spaces. */
        fun cleanOcrName(s: String): String =
            s.replace(Regex("\\s+"), " ")
                .trim()
                .trim('|', '*', '★', '☆', '•', '·', '_', '-', '~', '"', '\'', '“', '”')
                .trim()

        fun score(hit: PlayerHit, name: String, clan: String?): Scored {
            // Also try without a possible junk first letter from the clan badge, at a small penalty.
            val ns = maxOf(
                StringSim.similarity(hit.name, name),
                if (name.length >= 4) StringSim.similarity(hit.name, name.substring(1)) * 0.9 else 0.0,
            )
            val cs = if (clan != null) StringSim.similarity(hit.clanName, clan) else 0.0
            val score = if (clan != null) ns * 0.6 + cs * 0.4 else ns
            return Scored(hit, ns, cs, score)
        }

        fun isStrong(s: Scored, clan: String?) =
            s.nameSim >= STRONG_NAME_SIM && (clan == null || s.clanSim >= STRONG_CLAN_SIM)

        /** Best candidate among [rows] for a name/clan; used by tests and the chain. */
        fun best(rows: List<PlayerHit>, name: String, clan: String?): Scored? =
            rows.map { score(it, name, clan) }.maxByOrNull { it.score }

        /** Clans whose name matches the OCR'd clan, exact (case-sensitive) matches first. */
        fun rankClans(clans: List<Pair<String, String>>, clan: String): List<Pair<String, String>> =
            clans.map { it to StringSim.similarity(it.second, clan) }
                .filter { it.second >= 0.8 }
                .sortedWith(compareByDescending<Pair<Pair<String, String>, Double>> { it.first.second.trim() == clan.trim() }.thenByDescending { it.second })
                .map { it.first }

        /** Name variants that undo typical ML Kit misreads of the game font. */
        fun ocrVariants(name: String): List<String> {
            val subs = listOf("v" to "y", "V" to "Y", "rn" to "m", "0" to "O", "1" to "l", "I" to "l", "l" to "I", "5" to "S", "u" to "v", "c" to "e")
            val fixes = subs.mapNotNull { (a, b) -> if (name.contains(a)) name.replace(a, b) else null }
            // The clan badge left of the name is often read as an extra first letter ("CROYale King").
            val dropFirst = if (name.length >= 4 && !name[1].isWhitespace()) listOf(name.substring(1)) else emptyList()
            return (fixes.take(2) + dropFirst + fixes.drop(2))
                .filter { it != name }
                .distinct()
        }

        private fun describe(s: Scored) =
            "'${s.hit.name}' #${s.hit.tag} (${s.hit.clanName ?: "no clan"}) name=${"%.2f".format(s.nameSim)} clan=${"%.2f".format(s.clanSim)} score=${"%.2f".format(s.score)}"
    }
}
