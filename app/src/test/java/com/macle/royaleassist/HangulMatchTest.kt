package com.macle.royaleassist

import com.macle.royaleassist.cache.CachePolicy
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.StringSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sample 09: a long Hangul name read with three wrong syllables and an inserted space. */
class HangulMatchTest {
    private val real = "동덕여대여자화장실문지기황나☠"
    private val ocr = "동덕여태여자화장실 문지기황니호"

    @Test
    fun glyphsAndSpacesAreIgnored() {
        assertEquals(StringSim.compact("동덕여대여자화장실문지기황나"), StringSim.compact(real))
        assertEquals(StringSim.compact("Royale King"), StringSim.compact("★ Royale  King ★"))
        assertEquals(StringSim.compact("MasterX"), StringSim.compact("MasterX™<"))
        assertEquals(StringSim.compact("Eternal Night"), StringSim.compact("Eternal Night ⭐"))
        assertEquals(1.0, StringSim.similarity("동덕여대 여자화장실", "동덕여대여자화장실☠"), 1e-9)
    }

    @Test
    fun hangulIsComparedByJamo() {
        // 대/태 share the vowel; 나/니 share the initial: one jamo each, not a whole syllable.
        assertTrue(StringSim.similarity("대", "태") > StringSim.similarity("대", "호"))
        assertTrue(StringSim.similarity("나", "니") > 0.4)
        val s = StringSim.similarity(ocr, real)
        assertTrue("similarity $s", s >= 0.85)
        // Other players sharing the prefix score clearly lower.
        assertTrue(StringSim.similarity(ocr, "동덕여대총학생회장") < 0.75)
        assertTrue(StringSim.similarity(ocr, "동덕여대103kg앙증걸진영현") < 0.75)
    }

    @Test
    fun jamoClanReadAsCjkHomoglyphs() {
        // The Japanese model read the clan "ㅅㅁㅂ" as "人口日".
        assertEquals(1.0, StringSim.similarity("人口日", "ㅅㅁㅂ"), 1e-9)
        assertEquals(1.0, StringSim.similarity("ㅅ ㅁ ㅂ", "ㅅㅁㅂ"), 1e-9)
    }

    @Test
    fun misreadNameAndClanStillMakeAStrongMatch() {
        val rows = listOf(
            PlayerHit("동덕여대총학생회장", "LVJGGLU90", "보르지창고", "G22QYQJ0"),
            PlayerHit("동덕여대103kg앙증걸진영현", "Q280PLRJG", "강북중", "R2GVLRQP"),
            PlayerHit(real, "V8CY2LUQQ", "ㅅㅁㅂ", "8QRY0CVC"),
            PlayerHit("동덕여대개패기", "Q20U2JY2R", null, null),
        )
        val best = LookupChain.best(rows, ocr, "人口日")!!
        assertEquals("V8CY2LUQQ", best.hit.tag)
        assertTrue(LookupChain.isStrong(best, "人口日"))
    }

    @Test
    fun progressivePrefixes() {
        assertEquals(listOf("동덕여태여자화장", "동덕여태여", "동덕여"), LookupChain.progressivePrefixes(ocr))
        assertEquals(listOf("Thisisaver", "Thisisa", "Thisi"), LookupChain.progressivePrefixes("This is a very long name"))
        assertTrue(LookupChain.progressivePrefixes("Macle").isEmpty())
        assertTrue(LookupChain.progressivePrefixes("4858 1234 5678").isEmpty())
    }

    @Test
    fun cacheKeyIgnoresSpacingOfHangul() {
        assertEquals(
            CachePolicy.queryKey("동덕여태여자화장실 문지기황니호", "人口日").replace(" ", ""),
            CachePolicy.queryKey("동덕여태여자화장실문지기황니호", "人口日").replace(" ", ""),
        )
    }
}

class StrayTokenTest {
    @Test
    fun trimsBadgeEdgeTokens() {
        assertEquals("동덕여대여자화장실문지키황나", com.macle.royaleassist.pipeline.Analyzer.trimStrayTokens("1 동덕여대여자화장실문지키황나"))
        assertEquals("동덕여대 여자", com.macle.royaleassist.pipeline.Analyzer.trimStrayTokens("| 동덕여대 여자 ।"))
        assertEquals("Macle 2", com.macle.royaleassist.pipeline.Analyzer.trimStrayTokens("Macle 2"))
        assertEquals("Royale King", com.macle.royaleassist.pipeline.Analyzer.trimStrayTokens("Royale King"))
    }
}
