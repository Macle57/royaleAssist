package com.macle.royaleassist.lookup

import com.fleeksoft.ksoup.Ksoup
import com.macle.royaleassist.util.nowMillis
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * The opponent's deck from their most recent battle.
 *
 * Web source (`royaleapi.com/player/TAG/battles`, structure verified 2026-10-27, fixtures in
 * app/src/test/resources/royaleapi/battles_*.html): each battle is
 * `div.battle_list_battle[data-timestamp][data-battle-type]` with `h4.game_mode_header` (mode
 * name) and two `div.team-segment`s. The first is the player whose page it is; it holds
 * `a.player_name_header[href=/player/TAG/battles]` and eight `img.deck_card[alt][data-card-key][src]`,
 * each followed by `div.card-level` ("Lvl 11"). Card art comes from RoyaleAPI's CDN
 * (cdns3.royaleapi.com/.../static/img/cards/<version>/<key>.png), which loads without cookies.
 *
 * API source (`/v1/players/%23TAG/battlelog`): newest battle first; `team[0]` is the player.
 */
object BattleLog {

    fun parseRoyaleApi(html: String, tag: String): LastBattle? = parseRoyaleApiBattles(html, tag).firstOrNull()

    /** Every battle on the battles page (the site renders the newest 5), newest first. */
    fun parseRoyaleApiBattles(html: String, tag: String): List<LastBattle> {
        val doc = Ksoup.parse(html, RoyaleApiWeb.BASE)
        val wanted = tagNoHash(tag)
        return doc.select("div.battle_list_battle").mapNotNull { battle ->
            val teams = battle.select("div.team-segment")
            val team = teams.firstOrNull { seg ->
                seg.selectFirst("a.player_name_header")?.attr("href")?.substringAfter("/player/")?.substringBefore('/')
                    ?.equals(wanted, ignoreCase = true) == true
            } ?: teams.firstOrNull() ?: return@mapNotNull null
            val cards = team.select("img.deck_card").map { img ->
                val level = img.parent()?.parent()?.selectFirst(".card-level")?.text()
                    ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                DeckCard(
                    name = img.attr("alt").ifBlank { img.attr("data-card-key") },
                    level = level,
                    iconUrl = img.absUrl("src").ifBlank { null },
                    key = img.attr("data-card-key").ifBlank { null },
                )
            }.take(8)
            if (cards.isEmpty()) return@mapNotNull null
            val mode = battle.selectFirst("h4.game_mode_header")?.text()?.trim()?.ifBlank { null }
                ?: modeLabel(battle.attr("data-battle-type"))
            val time = battle.attr("data-timestamp").toDoubleOrNull()?.toLong()
            LastBattle(mode, time, cards, "royaleapi.com battles")
        }
    }

    /** A deck from the player's "Decks" tab with its games in the last 7 days. */
    data class TabDeck(val cards: List<DeckCard>, val games: Int?, val total: Int?)

    /**
     * The "Decks" tab (`/player/TAG/decks`): `div.deck_segment` blocks, each with 8
     * `img.deck_card[data-card-key]` and "NN% (30 / 47 games)" for the last 7 days.
     */
    fun parseRoyaleApiDecksTab(html: String): List<TabDeck> {
        val doc = Ksoup.parse(html, RoyaleApiWeb.BASE)
        return doc.select("div.deck_segment").mapNotNull { seg ->
            val cards = seg.select("img.deck_card").map { img ->
                DeckCard(
                    name = img.attr("alt").ifBlank { img.attr("data-card-key") },
                    level = null,
                    iconUrl = img.absUrl("src").ifBlank { null },
                    key = img.attr("data-card-key").ifBlank { null },
                )
            }.take(8)
            if (cards.size < 8) return@mapNotNull null
            val m = Regex("\\((\\d+)\\s*/\\s*(\\d+)\\s*games\\)").find(seg.text())
            TabDeck(cards, m?.groupValues?.get(1)?.toIntOrNull(), m?.groupValues?.get(2)?.toIntOrNull())
        }
    }

    fun fromApi(battles: List<ApiBattle>, tag: String): LastBattle? = fromApiAll(battles, tag).firstOrNull()

    /** Every battle of the official battle log (up to 25), newest first. */
    fun fromApiAll(battles: List<ApiBattle>, tag: String): List<LastBattle> {
        val wanted = tagNoHash(tag)
        return battles.mapNotNull { b ->
            val me = (b.team + b.opponent).firstOrNull { tagNoHash(it.tag) == wanted } ?: b.team.firstOrNull() ?: return@mapNotNull null
            val cards = me.cards.take(8).map { c ->
                val evo = (c.evolutionLevel ?: 0) > 0
                val icon = if (evo) c.iconUrls?.evolutionMedium ?: c.iconUrls?.medium else c.iconUrls?.medium
                DeckCard(c.name, apiDisplayLevel(c), icon, key = c.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
            }
            if (cards.isEmpty()) return@mapNotNull null
            val mode = b.gameMode?.name?.takeIf { b.type != "PvP" && it.isNotBlank() } ?: modeLabel(b.type)
            LastBattle(mode, parseApiTime(b.battleTime), cards, "Clash Royale API battle log")
        }
    }

    /** Base card key: evolution ("-ev1") and hero ("-hero") variants count as the same card. */
    fun baseKey(c: DeckCard): String {
        val k = c.key ?: c.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return k.replace(Regex("-ev\\d+$"), "").removeSuffix("-hero")
    }

    /** Order-insensitive identity of a deck. */
    fun deckKey(cards: List<DeckCard>): String = cards.map(::baseKey).sorted().joinToString(",")

    /**
     * Up to [n] unique decks, newest first: walk [battles] (newest first) and keep each new card
     * set with how often it was used; if that gives fewer than [n], add decks from [tab]. Counts
     * from the tab are attached to decks already found in battles too.
     */
    fun uniqueDecks(battles: List<LastBattle>, n: Int, tab: List<TabDeck> = emptyList()): List<RecentDeck> {
        val byKey = LinkedHashMap<String, RecentDeck>()
        for (b in battles) {
            val k = deckKey(b.cards)
            val cur = byKey[k]
            byKey[k] = cur?.copy(battleUses = cur.battleUses + 1)
                ?: RecentDeck(b.cards, b.mode, b.time, battleUses = 1)
        }
        val seen = battles.size
        var out = byKey.mapValues { (_, d) -> d.copy(battlesSeen = seen) }
        val tabByKey = tab.associateBy { deckKey(it.cards) }
        out = out.mapValues { (k, d) -> tabByKey[k]?.let { t -> d.copy(weekGames = t.games, weekTotal = t.total) } ?: d }
        val result = out.values.toMutableList()
        for (t in tab) {
            if (result.size >= n) break
            if (deckKey(t.cards) in out.keys) continue
            result += RecentDeck(t.cards, battlesSeen = seen, weekGames = t.games, weekTotal = t.total)
        }
        return result.take(n)
    }

    /** "Ladder, 3 h ago · 4 of last 10" / "30 of 47 games in 7 days". */
    fun usageLine(d: RecentDeck, nowSec: Long = nowMillis() / 1000): String {
        val parts = mutableListOf<String>()
        if (d.lastMode != null || d.lastTime != null) {
            parts += listOfNotNull(d.lastMode, relative(d.lastTime, nowSec)).joinToString(", ")
        }
        if (d.battleUses > 0) parts += "${d.battleUses} of last ${d.battlesSeen}"
        if (d.weekGames != null && d.weekTotal != null) parts += "${d.weekGames} of ${d.weekTotal} games in 7 days"
        return parts.joinToString(" · ")
    }

    /**
     * The API reports levels on the old per-rarity scale with a `maxLevel`; the in-game level is
     * level + (14 - maxLevel). Not verified against live data (no token was available).
     */
    fun apiDisplayLevel(c: ApiCard): Int? {
        val level = c.level ?: return null
        val max = c.maxLevel ?: return level
        return level + (14 - max)
    }

    fun modeLabel(type: String?): String = when (type?.lowercase()) {
        null, "" -> "Battle"
        "pvp", "ladder" -> "Ladder"
        "pathoflegend" -> "Path of Legends"
        "trail", "challenge" -> "Challenge"
        "clanwarwarday", "riverracepvp", "riverraceduel", "riverraceduelcolosseum", "boatbattle" -> "Clan war"
        "tournament" -> "Tournament"
        "friendly", "clanmate" -> "Friendly"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /** "20261027T104512.000Z" (UTC) -> epoch seconds. */
    fun parseApiTime(s: String?): Long? {
        val m = s?.let { API_TIME.matchAt(it.trim(), 0) } ?: return null
        return runCatching {
            val (y, mo, d, h, mi, sec) = m.destructured
            LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), sec.toInt())
                .toInstant(TimeZone.UTC).epochSeconds
        }.getOrNull()
    }

    private val API_TIME = Regex("""(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(?:\.\d{1,3})?Z""")

    /** "just now", "12 min ago", "3 h ago", "2 d ago". */
    fun relative(epochSec: Long?, nowSec: Long = nowMillis() / 1000): String? {
        if (epochSec == null) return null
        val d = (nowSec - epochSec).coerceAtLeast(0)
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86400 -> "${d / 3600} h ago"
            else -> "${d / 86400} d ago"
        }
    }
}
