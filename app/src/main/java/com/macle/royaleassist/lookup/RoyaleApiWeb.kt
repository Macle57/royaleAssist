package com.macle.royaleassist.lookup

import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Scrapes royaleapi.com. Structure verified on 2026-10-27 (fixtures in app/src/test/resources/royaleapi):
 *
 * Search results: `div.player_search_results__result_container` rows, each with
 *   `a.header[href=/player/TAG]` (name), `div.player_tag` (#TAG) and optionally
 *   `a.meta[href=/clan/CLANTAG]` whose text is "Clan Name&nbsp;&nbsp;#CLANTAG".
 *
 * Player page: `h1.ui.header` (name), `h2.ui.sub.header` (#TAG), `.league_info_container .item`
 *   ("14000 / 14000 PB", arena), clan in `.player_aux_info a[href^=/clan/]`, and many
 *   two-column `<tr><td>Label</td><td>Value</td></tr>` stat rows.
 *
 * Clan search (`/clans/search?name=`): `div.card.clanresult[data-clantag]` cards with `a.header`.
 * Clan page (`/clan/TAG`): member rows `tr.tr_member[data-tag]` with `a.member_link` (name + tag).
 */
object RoyaleApiWeb {
    const val BASE = "https://royaleapi.com"

    fun searchUrl(name: String, exact: Boolean): String {
        val q = URLEncoder.encode(name, "UTF-8")
        return "$BASE/player/search/results?q=$q" + if (exact) "&exact_match=on" else ""
    }

    fun playerUrl(tag: String) = "$BASE/player/${tagNoHash(tag)}"

    fun clanSearchUrl(name: String) = "$BASE/clans/search?name=${URLEncoder.encode(name, "UTF-8")}"

    fun clanUrl(tag: String) = "$BASE/clan/${tagNoHash(tag)}"

    /** (clanTag, clanName) pairs from the clan search page, in page order. */
    fun parseClanSearch(html: String): List<Pair<String, String>> =
        Jsoup.parse(html, BASE).select("div.card.clanresult[data-clantag]").mapNotNull { card ->
            val tag = card.attr("data-clantag").trim().uppercase()
            val name = card.selectFirst("a.header[href^=/clan/]")?.text()?.trim()
            if (tag.isEmpty() || name.isNullOrEmpty()) null else tag to name
        }

    /** Members of a clan page as hits (clan name taken from the page title header). */
    fun parseClanMembers(html: String, clanTag: String, clanName: String?): List<PlayerHit> =
        Jsoup.parse(html, BASE).select("tr.tr_member[data-tag]").mapNotNull { tr ->
            val tag = tr.attr("data-tag").trim().uppercase()
            val a = tr.selectFirst("a.member_link") ?: return@mapNotNull null
            val name = a.ownText().trim()
            if (tag.isEmpty() || name.isEmpty()) null else PlayerHit(name, tag, clanName, tagNoHash(clanTag))
        }

    fun parseSearch(html: String): List<PlayerHit> {
        val doc = Jsoup.parse(html, BASE)
        return doc.select("div.player_search_results__result_container").mapNotNull { row ->
            val a = row.selectFirst("a.header[href^=/player/]") ?: return@mapNotNull null
            val tag = a.attr("href").substringAfter("/player/").substringBefore('/').substringBefore('?')
            val name = a.text().trim()
            val clanA = row.selectFirst("a.meta[href^=/clan/]")
            val clanTag = clanA?.attr("href")?.substringAfter("/clan/")?.substringBefore('/')
            val clanName = clanA?.text()?.replace(' ', ' ')?.let { t ->
                if (clanTag != null) t.substringBeforeLast("#$clanTag") else t
            }?.trim()?.ifEmpty { null }
            if (tag.isBlank()) null else PlayerHit(name, tag.uppercase(), clanName, clanTag?.uppercase())
        }
    }

    fun parseProfile(html: String, fallbackTag: String): PlayerStats? {
        val doc = Jsoup.parse(html, BASE)
        val name = doc.selectFirst("h1.ui.header")?.text()?.trim() ?: return null
        val tag = doc.selectFirst("h2.ui.sub.header")?.text()?.trim()?.removePrefix("#") ?: fallbackTag

        val league = doc.select(".league_info_container .item").map { it.text().trim() }
        val nums = league.firstOrNull()?.let { t ->
            // "14000 / 14000 PB"
            Regex("[0-9][0-9,]*").findAll(t).map { it.value.replace(",", "").toIntOrNull() }.toList()
        }.orEmpty()
        val arena = league.getOrNull(1)

        val clanLink = doc.select(".player_aux_info a[href^=/clan/]").firstOrNull { it.text().isNotBlank() }
        val clanName = clanLink?.text()?.trim()
        val clanTag = clanLink?.attr("href")?.substringAfter("/clan/")?.substringBefore('/')
        val role = doc.selectFirst(".player_aux_info .ui.horizontal.list")?.children()
            ?.lastOrNull { it.hasClass("item") && !it.hasClass("clan_badge") && !it.hasClass("header") }
            ?.text()?.trim()?.ifEmpty { null }

        val rows = linkedMapOf<String, String>()
        for (tr in doc.select("tr")) {
            val tds = tr.select("> td")
            if (tds.size != 2) continue
            val label = tds[0].text().trim()
            val value = tds[1].text().replace(Regex("\\s+"), " ").trim()
            if (label.isEmpty() || value.isEmpty() || label.length > 40) continue
            if (label !in rows) rows[label] = value
        }
        fun intOf(label: String) =
            rows[label]?.let { Regex("[0-9][0-9,]*").find(it)?.value?.replace(",", "")?.toIntOrNull() }

        val interesting = listOf(
            "King Level", "Total games", "Total donations", "Cards found",
            "Account Age", "Games per day", "Max wins", "Star points",
        )
        return PlayerStats(
            name = name,
            tag = tag,
            clanName = clanName,
            clanTag = clanTag,
            clanRole = role,
            expLevel = intOf("Experience"),
            trophies = nums.getOrNull(0),
            bestTrophies = nums.getOrNull(1),
            battleCount = intOf("Total games"),
            arena = arena,
            extra = interesting.mapNotNull { k -> rows[k]?.let { k to it } },
            source = "royaleapi.com profile",
        )
    }
}
