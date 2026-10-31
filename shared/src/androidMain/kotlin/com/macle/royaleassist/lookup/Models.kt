package com.macle.royaleassist.lookup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row from RoyaleAPI's player search page, or one clan member from the official API. */
@Serializable
data class PlayerHit(
    val name: String,
    val tag: String,          // without '#'
    val clanName: String? = null,
    val clanTag: String? = null,
)

/** Basic stats shown in the panel. Every field is optional because the sources differ. */
@Serializable
data class PlayerStats(
    val name: String,
    val tag: String,
    val clanName: String? = null,
    val clanTag: String? = null,
    val clanRole: String? = null,
    val expLevel: Int? = null,
    val trophies: Int? = null,
    val bestTrophies: Int? = null,
    val wins: Int? = null,
    val losses: Int? = null,
    val threeCrownWins: Int? = null,
    val battleCount: Int? = null,
    val arena: String? = null,
    /** Ranked league of the current season ("League 5" -> 5), from the royaleapi.com profile. */
    val league: Int? = null,
    val deck: List<String> = emptyList(),
    /** Extra label/value pairs scraped from the RoyaleAPI profile page. */
    val extra: List<Pair<String, String>> = emptyList(),
    val source: String,
)

/**
 * What the screen showed besides the name. The clan is a tri-state: a clan name, "No Clan"
 * ([noClan], the placeholder was on screen), or unknown (neither). [league] is the ranked league
 * ("League 5") and [trophies] the trophy count some screens show instead.
 */
@Serializable
data class ScreenHint(
    val clan: String? = null,
    val noClan: Boolean = false,
    val league: Int? = null,
    val trophies: Int? = null,
    /** The name probably has emoji/symbols OCR did not read (text starts far right of the clan). */
    val decorated: Boolean = false,
) {
    fun describe() = listOfNotNull(
        clan?.let { "clan '$it'" } ?: if (noClan) "no clan" else "clan unknown",
        league?.let { "League $it" },
        trophies?.let { "$it trophies" },
    ).joinToString(", ")
}

@Serializable
data class LookupResult(
    val query: String,
    val clanQuery: String?,
    val hit: PlayerHit?,
    val strategy: String?,
    val confidence: Double,
    val stats: PlayerStats?,
    val alternatives: List<PlayerHit> = emptyList(),
    /** True when RoyaleAPI answered with a Cloudflare challenge and the user should open the verifier. */
    val needsVerification: Boolean = false,
    /** False when the pick is only the best weak candidate (name or clan did not fully match). */
    val strongMatch: Boolean = false,
    /** The player's deck in their most recent battle (only fetched when the deck view is on). */
    val lastBattle: LastBattle? = null,
    /** Up to three unique recent decks, newest first (deck view). */
    val recentDecks: List<RecentDeck> = emptyList(),
    /** Why [lastBattle] is missing although it was requested. */
    val lastBattleError: String? = null,
    /** The screen information the lookup used (for Refresh and the pinned card). */
    val hint: ScreenHint = ScreenHint(),
    /** Extra explanation for the card, e.g. why the match is only a best guess. */
    val note: String? = null,
    /** Served from the local cache; [fetchedAt] is when the data was fetched (epoch ms). */
    val cached: Boolean = false,
    val fetchedAt: Long? = null,
    val log: List<String> = emptyList(),
    val error: String? = null,
    /** Answered from the screenshot cache: same screen as an earlier tap, no OCR and no network. */
    val screenHit: Boolean = false,
)

@Serializable
data class DeckCard(
    val name: String,
    val level: Int?,
    val iconUrl: String?,
    /** Card key ("hog-rider", "knight-hero", "tesla-ev1"); null for old cache entries. */
    val key: String? = null,
)

/**
 * One of the opponent's recently used decks (unique by card set, ignoring levels and
 * evolution/hero variants). Built from the newest battles and, on the website, topped up from the
 * player's "Decks" tab (last 7 days).
 */
@Serializable
data class RecentDeck(
    val cards: List<DeckCard>,
    /** Mode and time (epoch s) of the newest battle that used this deck; null if only known from the Decks tab. */
    val lastMode: String? = null,
    val lastTime: Long? = null,
    /** Used in [battleUses] of the [battlesSeen] newest battles. */
    val battleUses: Int = 0,
    val battlesSeen: Int = 0,
    /** Decks tab: played in [weekGames] of [weekTotal] games in the last 7 days. */
    val weekGames: Int? = null,
    val weekTotal: Int? = null,
)

@Serializable
data class LastBattle(
    /** "Ladder", "Path of Legends", "CRL 20-Win Challenge", ... */
    val mode: String,
    /** Epoch seconds, if known. */
    val time: Long?,
    val cards: List<DeckCard>,
    val source: String,
)

// ---- Official Clash Royale API DTOs (only the fields we use) ----

@Serializable
data class ApiClanList(val items: List<ApiClan> = emptyList())

@Serializable
data class ApiClan(val tag: String, val name: String, val members: Int? = null, val clanScore: Int? = null)

@Serializable
data class ApiMemberList(val items: List<ApiMember> = emptyList())

@Serializable
data class ApiMember(val tag: String, val name: String, val role: String? = null, val expLevel: Int? = null, val trophies: Int? = null)

@Serializable
data class ApiPlayer(
    val tag: String,
    val name: String,
    val expLevel: Int? = null,
    val trophies: Int? = null,
    val bestTrophies: Int? = null,
    val wins: Int? = null,
    val losses: Int? = null,
    val battleCount: Int? = null,
    val threeCrownWins: Int? = null,
    val role: String? = null,
    val clan: ApiPlayerClan? = null,
    val arena: ApiArena? = null,
    val currentDeck: List<ApiCard> = emptyList(),
)

@Serializable
data class ApiPlayerClan(val tag: String, val name: String)

@Serializable
data class ApiArena(val name: String? = null)

@Serializable
data class ApiCard(
    val name: String,
    val level: Int? = null,
    val maxLevel: Int? = null,
    val evolutionLevel: Int? = null,
    val iconUrls: ApiIconUrls? = null,
)

@Serializable
data class ApiIconUrls(val medium: String? = null, val evolutionMedium: String? = null)

@Serializable
data class ApiBattle(
    val type: String? = null,
    val battleTime: String? = null,
    val gameMode: ApiGameMode? = null,
    val team: List<ApiBattlePlayer> = emptyList(),
    val opponent: List<ApiBattlePlayer> = emptyList(),
)

@Serializable
data class ApiGameMode(val name: String? = null)

@Serializable
data class ApiBattlePlayer(val tag: String, val name: String? = null, val cards: List<ApiCard> = emptyList())

@Serializable
data class ApiError(val reason: String? = null, val message: String? = null)

internal fun tagNoHash(tag: String) = tag.trim().removePrefix("#").uppercase()
