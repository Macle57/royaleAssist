package com.macle.royaleassist.lookup

import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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

    fun parseRoyaleApi(html: String, tag: String): LastBattle? {
        val doc = Jsoup.parse(html, RoyaleApiWeb.BASE)
        val battle = doc.selectFirst("div.battle_list_battle") ?: return null
        val wanted = tagNoHash(tag)
        val teams = battle.select("div.team-segment")
        val team = teams.firstOrNull { seg ->
            seg.selectFirst("a.player_name_header")?.attr("href")?.substringAfter("/player/")?.substringBefore('/')
                ?.equals(wanted, ignoreCase = true) == true
        } ?: teams.firstOrNull() ?: return null
        val cards = team.select("img.deck_card").map { img ->
            val level = img.parent()?.parent()?.selectFirst(".card-level")?.text()
                ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            DeckCard(
                name = img.attr("alt").ifBlank { img.attr("data-card-key") },
                level = level,
                iconUrl = img.absUrl("src").ifBlank { null },
            )
        }.take(8)
        if (cards.isEmpty()) return null
        val mode = battle.selectFirst("h4.game_mode_header")?.text()?.trim()?.ifBlank { null }
            ?: modeLabel(battle.attr("data-battle-type"))
        val time = battle.attr("data-timestamp").toDoubleOrNull()?.toLong()
        return LastBattle(mode, time, cards, "royaleapi.com battles")
    }

    fun fromApi(battles: List<ApiBattle>, tag: String): LastBattle? {
        val b = battles.firstOrNull() ?: return null
        val wanted = tagNoHash(tag)
        val me = (b.team + b.opponent).firstOrNull { tagNoHash(it.tag) == wanted } ?: b.team.firstOrNull() ?: return null
        val cards = me.cards.take(8).map { c ->
            val icon = if ((c.evolutionLevel ?: 0) > 0) c.iconUrls?.evolutionMedium ?: c.iconUrls?.medium else c.iconUrls?.medium
            DeckCard(c.name, apiDisplayLevel(c), icon)
        }
        if (cards.isEmpty()) return null
        val mode = b.gameMode?.name?.takeIf { b.type != "PvP" && it.isNotBlank() } ?: modeLabel(b.type)
        return LastBattle(mode, parseApiTime(b.battleTime), cards, "Clash Royale API battle log")
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

    fun parseApiTime(s: String?): Long? = s?.let {
        runCatching {
            SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(it)?.time?.div(1000)
        }.getOrNull()
    }

    /** "just now", "12 min ago", "3 h ago", "2 d ago". */
    fun relative(epochSec: Long?, nowSec: Long = System.currentTimeMillis() / 1000): String? {
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
