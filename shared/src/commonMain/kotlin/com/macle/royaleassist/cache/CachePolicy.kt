package com.macle.royaleassist.cache

import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.StringSim

/** How long cached lookups are trusted (setting "Cache for"). */
enum class CacheTtl(val label: String, val millis: Long?) {
    H1("1 h", 3_600_000L),
    H6("6 h", 6 * 3_600_000L),
    H24("24 h", 24 * 3_600_000L),
    D7("7 d", 7 * 24 * 3_600_000L),
    FOREVER("forever", null);

    companion object {
        val DEFAULT = H24
        fun fromName(s: String?) = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}

object CachePolicy {
    /** Fresh if younger than the TTL. Future timestamps (clock changes) count as fresh. */
    fun isFresh(fetchedAt: Long, now: Long, ttl: CacheTtl): Boolean {
        val max = ttl.millis ?: return true
        return now - fetchedAt < max
    }

    /**
     * Key for an OCR'd (name, clan) pair: cleaned, case/decoration-insensitive. Readings that
     * differ only by OCR noise such as case, a trailing star or "™" map to the same key.
     */
    fun queryKey(name: String, clan: String?, noClan: Boolean = false): String {
        val n = StringSim.normalize(LookupChain.cleanOcrName(name)).ifEmpty { name.trim() }
        val c = when {
            clan != null -> StringSim.normalize(LookupChain.cleanOcrName(clan))
            noClan -> NO_CLAN_KEY
            else -> ""
        }
        return "$n|$c"
    }

    /** Clan part of the query key when the screen showed "No Clan" (cannot collide with a real clan). */
    const val NO_CLAN_KEY = "<no clan>"

    /**
     * Version of the matching rules that produced a cached name -> player decision. Entries from
     * older rules are ignored (v2: clan tri-state, case-insensitive candidates, league tie-break).
     */
    const val MATCHER_VERSION = 2

    /** "just now", "3 min ago", "2 h ago", "4 d ago". */
    fun age(fetchedAt: Long, now: Long): String {
        val s = ((now - fetchedAt) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }
}
