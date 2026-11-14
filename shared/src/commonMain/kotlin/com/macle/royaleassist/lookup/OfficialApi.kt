package com.macle.royaleassist.lookup

import com.macle.royaleassist.util.formUrlEncode
import kotlinx.serialization.json.Json

/**
 * Official Clash Royale API through the RoyaleAPI proxy (https://docs.royaleapi.com/proxy.html).
 * The token must be created at developer.clashroyale.com with IP 45.79.218.79 allow-listed.
 */
class OfficialApi(private val http: HttpClients) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun searchClans(name: String, token: String): List<ApiClan> = searchClans(name, token, limit = 20, maxPages = 1)

    /**
     * Clans whose name contains [name], [limit] per page, following the paging cursor for up to
     * [maxPages] pages (a common name like "iran" has hundreds).
     */
    suspend fun searchClans(name: String, token: String, limit: Int, maxPages: Int): List<ApiClan> {
        val q = formUrlEncode(name).replace("+", "%20")
        val out = mutableListOf<ApiClan>()
        var after: String? = null
        for (page in 0 until maxPages) {
            val url = "$BASE/clans?name=$q&limit=$limit" + (after?.let { "&after=" + formUrlEncode(it) } ?: "")
            val list = json.decodeFromString<ApiClanList>(http.getApiJson(url, token))
            out += list.items
            after = list.paging?.cursors?.after
            if (after == null || list.items.size < limit) break
        }
        return out
    }

    suspend fun clanMembers(clanTag: String, token: String): List<ApiMember> =
        json.decodeFromString<ApiMemberList>(
            http.getApiJson("$BASE/clans/%23${tagNoHash(clanTag)}/members", token)
        ).items

    suspend fun battleLog(tag: String, token: String): List<ApiBattle> =
        json.decodeFromString<List<ApiBattle>>(http.getApiJson("$BASE/players/%23${tagNoHash(tag)}/battlelog", token))

    suspend fun player(tag: String, token: String): ApiPlayer =
        json.decodeFromString<ApiPlayer>(http.getApiJson("$BASE/players/%23${tagNoHash(tag)}", token))

    companion object {
        const val BASE = "https://proxy.royaleapi.dev/v1"
        const val PROXY_IP = "45.79.218.79"

        fun ApiPlayer.toStats() = PlayerStats(
            name = name,
            tag = tagNoHash(tag),
            clanName = clan?.name,
            clanTag = clan?.tag?.let(::tagNoHash),
            clanRole = role,
            expLevel = expLevel,
            trophies = trophies,
            bestTrophies = bestTrophies,
            wins = wins,
            losses = losses,
            threeCrownWins = threeCrownWins,
            battleCount = battleCount,
            arena = arena?.name,
            deck = currentDeck.map { it.name },
            source = "Clash Royale API",
        )
    }
}
