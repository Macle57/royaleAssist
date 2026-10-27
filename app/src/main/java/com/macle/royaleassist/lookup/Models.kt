package com.macle.royaleassist.lookup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row from RoyaleAPI's player search page, or one clan member from the official API. */
data class PlayerHit(
    val name: String,
    val tag: String,          // without '#'
    val clanName: String? = null,
    val clanTag: String? = null,
)

/** Basic stats shown in the panel. Every field is optional because the sources differ. */
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
    val deck: List<String> = emptyList(),
    /** Extra label/value pairs scraped from the RoyaleAPI profile page. */
    val extra: List<Pair<String, String>> = emptyList(),
    val source: String,
)

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
    val log: List<String> = emptyList(),
    val error: String? = null,
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
data class ApiCard(val name: String, val level: Int? = null)

@Serializable
data class ApiError(val reason: String? = null, val message: String? = null)

internal fun tagNoHash(tag: String) = tag.trim().removePrefix("#").uppercase()
