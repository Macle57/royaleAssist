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
        Strategy("L2s RoyaleAPI search without symbols", web = true) { ctx ->
            // OCR turns ⚡ ★ ❤ ™ into stray characters; the site also finds names with the symbols
            // removed, and mixed Latin+CJK names when the scripts are separated by a space.
            val out = mutableListOf<PlayerHit>()
            for (q in symbolVariants(ctx.name)) {
                val rows = searchRows(ctx, RoyaleApiWeb.searchUrl(q, exact = false))
                ctx.log("  without symbols '$q': ${rows.size} rows")
                out += rows
                if (rows.any { isStrong(score(it, ctx.name, ctx.clan), ctx.clan) }) break
            }
            out
        },
        Strategy("L2p RoyaleAPI prefix search", web = true) { ctx ->
            // RoyaleAPI matches word prefixes (not substrings), and long or misread names return
            // nothing. Shorter prefixes of the OCR reading still reach the player; candidates are
            // then ranked by (jamo-aware) similarity and clan.
            val prefixes = progressivePrefixes(ctx.name)
            if (ctx.candidates.isNotEmpty() || prefixes.isEmpty()) {
                emptyList()
            } else {
                val out = mutableListOf<PlayerHit>()
                for (q in prefixes) {
                    val rows = searchRows(ctx, RoyaleApiWeb.searchUrl(q, exact = false))
                    ctx.log("  prefix '$q': ${rows.size} rows")
                    out += rows
                    if (rows.any { isStrong(score(it, ctx.name, ctx.clan), ctx.clan) }) break
                }
                out
            }
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
                // The clan slot sometimes shows a tag ("Klan: #GORGQPCJ"): use it directly.
                val tagged = CLAN_TAG_RE.find(clan)?.groupValues?.get(1)?.uppercase()?.let { listOf(it to clan) }.orEmpty()
                val clans = if (tagged.isNotEmpty()) emptyList() else RoyaleApiWeb.parseClanSearch(http.getHtml(RoyaleApiWeb.clanSearchUrl(clan)))
                val ranked = (tagged + fromHits + rankClans(clans, clan)).distinctBy { it.first }.take(MAX_CLAN_PAGES)
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
        // A clan reading that is really a number (tower HP, level badge) is worse than no clan.
        val cleanClan = clan?.let(::cleanOcrName)?.ifBlank { null }
            ?.takeIf { isPlausibleText(it) && !com.macle.royaleassist.ocr.ClanText.isNoClanPlaceholder(it) }
        val key = CachePolicy.queryKey(cleanName, cleanClan)
        val ctx = Ctx(
            cleanName, cleanClan, settings.currentToken(), settings.overlayPrefs.first().deckOnly,
            settings.cacheTtl.first(), bypassCache, key,
        )
        ctx.log("query name='$cleanName' clan='${cleanClan ?: "-"}' token=${if (ctx.token != null) "yes" else "no"}")
        if (cleanName.isBlank()) return LookupResult(name, clan, null, null, 0.0, null, log = ctx.log, error = "Empty name")
        if (!TAG_RE.matches(cleanName) && !isPlausibleText(cleanName)) {
            ctx.log("rejected query '$cleanName': not a name (numbers or too few letters), no search sent")
            return LookupResult(name, clan, null, null, 0.0, null, log = ctx.log, error = "No name found")
        }

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
        var decks = c.decks
        var battleError: String? = null
        if (ctx.wantDeck && (battle == null || decks.isEmpty())) {
            ctx.log("cache has no recent decks; fetching the battle log")
            val r = fetchDecks(ctx, c.entity.tag)
            r.getOrNull()?.let { info ->
                battle = info.last
                decks = info.decks
                cache?.updateDecks(c.entity.tag, info.last, info.decks)
            }
            battleError = r.exceptionOrNull()?.message
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
            recentDecks = decks,
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
            val b = async { if (ctx.wantDeck) fetchDecks(ctx, found.hit.tag) else null }
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
            lastBattle = battle?.getOrNull()?.last,
            recentDecks = battle?.getOrNull()?.decks.orEmpty(),
            lastBattleError = battle?.exceptionOrNull()?.message,
            fetchedAt = clock(),
            log = ctx.log,
        ).also { r ->
            // Only real stats are cached; a fallback (network failed) must not replace a good entry.
            if (r.stats != null && !r.stats.source.startsWith(FALLBACK_SOURCE)) cache?.putResult(r, ctx.queryKey, clock())
            else if (r.stats != null) ctx.log("not caching: stats came from the search result only")
        }
    }

    /** Decks the deck view needs: the newest battle plus up to [MAX_DECKS] unique recent decks. */
    data class DeckInfo(val last: LastBattle, val decks: List<RecentDeck>)

    /**
     * Official battle log (25 battles) with a token; otherwise the royaleapi.com battles page (newest
     * 5 battles), topped up from the player's Decks tab when that gives fewer than [MAX_DECKS].
     */
    private suspend fun fetchDecks(ctx: Ctx, tag: String): Result<DeckInfo> {
        ctx.token?.let { token ->
            try {
                val battles = BattleLog.fromApiAll(api.battleLog(tag, token), tag)
                if (battles.isNotEmpty()) {
                    val decks = BattleLog.uniqueDecks(battles, MAX_DECKS)
                    ctx.log("battle: official API ok (${battles.size} battles, ${decks.size} unique decks)")
                    return Result.success(DeckInfo(battles.first(), decks))
                }
                ctx.log("battle: official API battle log empty")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.log("battle: official API failed: ${e.message}")
            }
        }
        return try {
            val battles = BattleLog.parseRoyaleApiBattles(http.getHtml(RoyaleApiWeb.battlesUrl(tag)), tag)
            if (battles.isEmpty()) {
                ctx.log("battle: no battles on royaleapi.com")
                return Result.failure(IllegalStateException("No recent battles found"))
            }
            var decks = BattleLog.uniqueDecks(battles, MAX_DECKS)
            if (decks.size < MAX_DECKS) {
                try {
                    val tab = BattleLog.parseRoyaleApiDecksTab(http.getHtml(RoyaleApiWeb.decksUrl(tag)))
                    decks = BattleLog.uniqueDecks(battles, MAX_DECKS, tab)
                    ctx.log("battle: decks tab added ${tab.size} decks")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ctx.log("battle: decks tab failed: ${e.message}")
                }
            }
            ctx.log("battle: royaleapi.com battles ok (${battles.size} battles, ${decks.size} unique decks, newest ${battles.first().mode})")
            Result.success(DeckInfo(battles.first(), decks))
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
        const val MAX_DECKS = 3
        const val FALLBACK_SOURCE = "search result only"
        const val STRONG_NAME_SIM = 0.85
        const val STRONG_CLAN_SIM = 0.75
        const val MIN_NAME_SIM = 0.72
        const val MAX_CLAN_PAGES = 6
        /** A clan tag written in the clan slot, e.g. "Klan: #GORGQPCJ" or "Clan #GVGVVGUO". */
        private val CLAN_TAG_RE = Regex("#\\s*([0289PYLQGRJCUVO]{6,10})\\b", RegexOption.IGNORE_CASE)

        /**
         * Search variants for names with symbols or mixed scripts: symbols replaced by spaces, and
         * a space inserted between Latin and CJK/Hangul/kana runs ("Zodiac™是传奇" -> "Zodiac 是传奇").
         */
        fun symbolVariants(name: String): List<String> {
            val base = name.trim()
            val spaced = base.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }.joinToString("")
                .replace(Regex("\\s+"), " ").trim()
            val sb = StringBuilder()
            for (ch in spaced) {
                if (sb.isNotEmpty() && sb.last() != ' ' && ch != ' ' && isLatin(sb.last()) != isLatin(ch) &&
                    (sb.last().isLetter() && ch.isLetter())
                ) sb.append(' ')
                sb.append(ch)
            }
            val split = sb.toString()
            return listOf(spaced, split).filter { it != base && isPlausibleText(it) }.distinct()
        }

        private fun isLatin(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

        private val TAG_RE = Regex("#[0289PYLQGRJCUV]{3,14}", RegexOption.IGNORE_CASE)

        /**
         * Search prefixes for names RoyaleAPI did not find: Hangul/CJK and other non-Latin names
         * 8, 5 and 3 characters; Latin names longer than 10 characters 10, 7 and 5. Spaces and
         * decorations are dropped first (the game shows "동덕여대…황나☠", OCR may read "… 문지기…").
         */
        fun progressivePrefixes(name: String): List<String> {
            val s = StringSim.searchable(name)
            // Mixed names count as non-Latin as soon as they contain CJK/Hangul/kana.
            val latin = s.none { isCjkLike(it) } &&
                s.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } * 2 >= s.length
            val lengths = when {
                !latin && s.length >= 4 -> listOf(8, 5, 3)
                latin && s.length > 10 -> listOf(10, 7, 5)
                else -> emptyList()
            }
            return lengths.filter { it < s.length }.map { s.take(it) }.filter { isPlausibleText(it) }.distinct()
        }

        private fun isCjkLike(c: Char) = when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA -> true
            else -> false
        }

        /** At least two letters and not mostly digits ("4858", "E4858", "的4858-" are rejected). */
        fun isPlausibleText(s: String): Boolean {
            val letters = s.count { it.isLetter() }
            val digits = s.count { it.isDigit() }
            return letters >= 2 && digits < letters
        }

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
                .filter { it != name && isPlausibleText(it) }
                .distinct()
        }

        private fun describe(s: Scored) =
            "'${s.hit.name}' #${s.hit.tag} (${s.hit.clanName ?: "no clan"}) name=${"%.2f".format(s.nameSim)} clan=${"%.2f".format(s.clanSim)} score=${"%.2f".format(s.score)}"
    }
}
