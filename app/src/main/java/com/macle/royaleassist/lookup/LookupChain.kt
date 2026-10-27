package com.macle.royaleassist.lookup

import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.OfficialApi.Companion.toStats
import com.macle.royaleassist.settings.Settings
import kotlinx.coroutines.CancellationException

/**
 * Turns an OCR'd player name (+ optional clan name) into a player tag and basic stats by trying a
 * chain of strategies in order. Each step logs to logcat (tag RoyaleAssist) and into [LookupResult.log].
 *
 *  L1  RoyaleAPI website, exact case-sensitive name search; disambiguate by clan similarity.
 *  L2  RoyaleAPI website, fuzzy name search; rank by name + clan similarity.
 *  L3  Official API (token only): search clans by OCR'd clan name, then fuzzy-match the member list.
 *
 * Stats come from the official API when a token is configured, else from the RoyaleAPI profile page,
 * else just the search hit.
 */
class LookupChain(
    private val http: HttpClients,
    private val settings: Settings,
) {
    private val api = OfficialApi(http)

    data class Found(val hit: PlayerHit, val confidence: Double, val alternatives: List<PlayerHit> = emptyList())

    private class Ctx(val name: String, val clan: String?, val token: String?) {
        val log = mutableListOf<String>()
        var cloudflareBlocked = false
        fun log(msg: String) { L.i("lookup: $msg"); log += msg }
    }

    private interface Strategy {
        val id: String
        suspend fun find(ctx: Ctx): Found?
    }

    private val strategies: List<Strategy> = listOf(
        object : Strategy {
            override val id = "L1 RoyaleAPI exact search"
            override suspend fun find(ctx: Ctx): Found? {
                if (ctx.cloudflareBlocked) return null
                val rows = RoyaleApiWeb.parseSearch(http.getHtml(RoyaleApiWeb.searchUrl(ctx.name, exact = true)))
                ctx.log("$id: ${rows.size} rows")
                return pickByClan(rows, ctx.clan, base = 0.75, log = ctx::log)
            }
        },
        object : Strategy {
            override val id = "L2 RoyaleAPI fuzzy search"
            override suspend fun find(ctx: Ctx): Found? {
                if (ctx.cloudflareBlocked) return null
                val rows = RoyaleApiWeb.parseSearch(http.getHtml(RoyaleApiWeb.searchUrl(ctx.name, exact = false)))
                ctx.log("$id: ${rows.size} rows")
                return pickFuzzy(rows, ctx.name, ctx.clan, log = ctx::log)
            }
        },
        object : Strategy {
            override val id = "L3 official API clan members"
            override suspend fun find(ctx: Ctx): Found? {
                val token = ctx.token
                val clan = ctx.clan
                if (token == null) { ctx.log("$id: skipped (no API token)"); return null }
                if (clan == null || clan.trim().length < 3) { ctx.log("$id: skipped (no usable clan name)"); return null }
                val clans = api.searchClans(clan.trim(), token)
                    .map { it to StringSim.similarity(it.name, clan) }
                    .filter { it.second >= 0.6 }
                    .sortedByDescending { it.second }
                    .take(5)
                ctx.log("$id: ${clans.size} candidate clans for '$clan'")
                var best: Pair<PlayerHit, Double>? = null
                for ((c, clanSim) in clans) {
                    val members = api.clanMembers(c.tag, token)
                    for (m in members) {
                        val s = StringSim.similarity(m.name, ctx.name) * 0.8 + clanSim * 0.2
                        if (best == null || s > best.second) {
                            best = PlayerHit(m.name, tagNoHash(m.tag), c.name, tagNoHash(c.tag)) to s
                        }
                    }
                }
                val b = best ?: return null
                ctx.log("$id: best member ${b.first.name} #${b.first.tag} score=${"%.2f".format(b.second)}")
                return if (b.second >= 0.7) Found(b.first, b.second) else null
            }
        },
    )

    /** Main entry: OCR'd name + optional clan -> result. Never throws (except cancellation). */
    suspend fun lookup(name: String, clan: String?): LookupResult {
        val cleanName = cleanOcrName(name)
        val cleanClan = clan?.let(::cleanOcrName)?.ifBlank { null }
        val ctx = Ctx(cleanName, cleanClan, settings.currentToken())
        ctx.log("query name='$cleanName' clan='${cleanClan ?: "-"}' token=${if (ctx.token != null) "yes" else "no"}")
        if (cleanName.isBlank()) return LookupResult(name, clan, null, null, 0.0, null, log = ctx.log, error = "Empty name")

        // A tag typed directly ("#98VQRGCQ") skips the search.
        if (cleanName.startsWith("#") && cleanName.length in 4..15) {
            val tag = tagNoHash(cleanName)
            return finish(ctx, Found(PlayerHit(cleanName, tag), 1.0), "direct tag")
        }

        for (s in strategies) {
            val found = try {
                s.find(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareBlockedException) {
                ctx.cloudflareBlocked = true
                ctx.log("${s.id}: blocked by Cloudflare (open the verifier once)")
                null
            } catch (e: Exception) {
                ctx.log("${s.id}: failed: ${e.message}")
                null
            }
            if (found != null) {
                ctx.log("${s.id}: picked ${found.hit.name} #${found.hit.tag} (${found.hit.clanName ?: "no clan"}) conf=${"%.2f".format(found.confidence)}")
                return finish(ctx, found, s.id)
            }
        }
        ctx.log("no strategy found a player")
        return LookupResult(
            name, clan, null, null, 0.0, null,
            needsVerification = ctx.cloudflareBlocked, log = ctx.log,
            error = if (ctx.cloudflareBlocked) "RoyaleAPI needs verification" else "Player not found",
        )
    }

    private suspend fun finish(ctx: Ctx, found: Found, strategy: String): LookupResult {
        val stats = fetchStats(ctx, found.hit)
        return LookupResult(
            query = ctx.name,
            clanQuery = ctx.clan,
            hit = found.hit,
            strategy = strategy,
            confidence = found.confidence,
            stats = stats,
            alternatives = found.alternatives,
            needsVerification = ctx.cloudflareBlocked,
            log = ctx.log,
        )
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
            source = "search result only (add an API token for full stats)",
        )
    }

    companion object {
        /** Strip OCR noise around a name: surrounding quotes/stars/pipes and extra spaces. */
        fun cleanOcrName(s: String): String =
            s.replace(Regex("\\s+"), " ")
                .trim()
                .trim('|', '*', '★', '☆', '•', '·', '_', '-', '~')
                .trim()

        fun pickByClan(rows: List<PlayerHit>, clan: String?, base: Double, log: (String) -> Unit = {}): Found? {
            if (rows.isEmpty()) return null
            if (rows.size == 1) {
                val clanSim = if (clan != null) StringSim.similarity(rows[0].clanName, clan) else 0.0
                return Found(rows[0], (base + 0.15 + 0.1 * clanSim).coerceAtMost(1.0))
            }
            if (clan == null) return Found(rows[0], base - 0.25, rows.drop(1).take(5))
            val scored = rows.map { it to StringSim.similarity(it.clanName, clan) }.sortedByDescending { it.second }
            val (best, sim) = scored.first()
            log("clan disambiguation: best '${best.clanName}' sim=${"%.2f".format(sim)} of ${rows.size}")
            val conf = if (sim >= 0.8) base + 0.2 else if (sim >= 0.6) base else base - 0.3
            return Found(best, conf.coerceIn(0.0, 1.0), scored.drop(1).take(5).map { it.first })
        }

        fun pickFuzzy(rows: List<PlayerHit>, name: String, clan: String?, log: (String) -> Unit = {}): Found? {
            if (rows.isEmpty()) return null
            val scored = rows.map { r ->
                val ns = StringSim.similarity(r.name, name)
                val cs = if (clan != null) StringSim.similarity(r.clanName, clan) else 0.0
                Triple(r, ns, if (clan != null) ns * 0.65 + cs * 0.35 else ns)
            }.sortedByDescending { it.third }
            val (best, ns, score) = scored.first()
            log("fuzzy best '${best.name}' (${best.clanName}) nameSim=${"%.2f".format(ns)} score=${"%.2f".format(score)}")
            if (ns < 0.72) return null
            return Found(best, score.coerceIn(0.0, 1.0), scored.drop(1).take(5).map { it.first })
        }
    }
}
