package com.macle.royaleassist

import com.macle.royaleassist.cache.CachePolicy
import com.macle.royaleassist.cache.CacheTtl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class CachePolicyTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Test
    fun freshWithinTtlStaleAfter() {
        assertTrue(CachePolicy.isFresh(now - 23 * hour, now, CacheTtl.H24))
        assertFalse(CachePolicy.isFresh(now - 24 * hour, now, CacheTtl.H24))
        assertTrue(CachePolicy.isFresh(now - 59 * 60_000L, now, CacheTtl.H1))
        assertFalse(CachePolicy.isFresh(now - 61 * 60_000L, now, CacheTtl.H1))
        assertTrue(CachePolicy.isFresh(now - 5 * hour, now, CacheTtl.H6))
        assertFalse(CachePolicy.isFresh(now - 7 * hour, now, CacheTtl.H6))
        assertTrue(CachePolicy.isFresh(now - 6 * 24 * hour, now, CacheTtl.D7))
        assertFalse(CachePolicy.isFresh(now - 8 * 24 * hour, now, CacheTtl.D7))
    }

    @Test
    fun foreverNeverExpiresAndFutureCountsAsFresh() {
        assertTrue(CachePolicy.isFresh(0L, now, CacheTtl.FOREVER))
        assertTrue(CachePolicy.isFresh(now + hour, now, CacheTtl.H1))
    }

    @Test
    fun ttlFromSettingNameDefaultsTo24h() {
        assertEquals(CacheTtl.D7, CacheTtl.fromName("D7"))
        assertEquals(CacheTtl.H24, CacheTtl.fromName(null))
        assertEquals(CacheTtl.H24, CacheTtl.fromName("garbage"))
    }

    @Test
    fun queryKeyIgnoresOcrNoise() {
        val a = CachePolicy.queryKey("ROYale KiNg", "Eternal Night")
        assertEquals(a, CachePolicy.queryKey("Royale King", "Eternal Night ★"))
        assertEquals(a, CachePolicy.queryKey("  royale  king ", "ETERNAL NIGHT"))
        assertNotEquals(a, CachePolicy.queryKey("Royale King", "Other Clan"))
        assertNotEquals(CachePolicy.queryKey("Macle", null), CachePolicy.queryKey("Macle", "Kings & Queens"))
    }

    @Test
    fun ageLabels() {
        assertEquals("just now", CachePolicy.age(now - 10_000, now))
        assertEquals("3 min ago", CachePolicy.age(now - 3 * 60_000, now))
        assertEquals("2 h ago", CachePolicy.age(now - 2 * hour, now))
        assertEquals("4 d ago", CachePolicy.age(now - 4 * 24 * hour, now))
    }
}
