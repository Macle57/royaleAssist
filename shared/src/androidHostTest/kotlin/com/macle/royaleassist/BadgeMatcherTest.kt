package com.macle.royaleassist

import com.macle.royaleassist.badge.BadgeMatcher
import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.ocr.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clan badge next to the opponent's name, read off phone frames (top-left 540x360 of the
 * 1080x2340 capture, resources/badges) at the name box the app's OCR found. The right badges were
 * confirmed with the official API (clan #GQR2QPLY "iran" = Flame_03, #R8YUCYPL "Тцк" = flag_e_01).
 */
class BadgeMatcherTest {
    private fun read(path: String): ArgbImage {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)!!
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", java.io.InputStream::class.java).invoke(null, stream)!!
        val w = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val h = img.javaClass.getMethod("getHeight").invoke(img) as Int
        val px = IntArray(w * h)
        img.javaClass.getMethod(
            "getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(img, 0, 0, w, h, px, 0, w)
        return ArgbImage(w, h, px)
    }

    private fun check(file: String, name: Box, badge: String, maxMatches: Int) {
        val r = BadgeMatcher.read(read("badges/$file"), name)
        assertNotNull(r)
        r!!
        println("$file: ${r.describe()}")
        assertTrue("$file confident: ${r.describe()}", r.confident)
        val id = BadgeMatcher.idForName(badge)!!
        assertEquals("$file best badge", badge, BadgeMatcher.nameOf(r.matchingIds.first()))
        assertTrue("$file: $badge fits", id in r.matchingIds)
        assertTrue("$file: at most $maxMatches fit, got ${r.matchingIds.size}", r.matchingIds.size <= maxMatches)
    }

    @Test
    fun aceInIranWearsFlame03() = check("ace_iran.jpg", Box(95, 117, 245, 174), "Flame_03", maxMatches = 1)

    @Test
    fun keepCalmInTckWearsTheUkrainianFlag() = check("keepcalm_tck.jpg", Box(93, 111, 300, 171), "flag_e_01", maxMatches = 1)

    /** The French flag has look-alikes (Monaco-style red/white, Italy): a few fit, France first. */
    @Test
    fun gauloisWearsTheFrenchFlag() = check("af_nairolf_gaulois.jpg", Box(95, 115, 420, 173), "flag_k_01", maxMatches = 5)

    /** No clan, no badge: the reading must not claim one (corpus AuTT5Geq1xE_65). */
    @Test
    fun noClanIsNotConfident() {
        val r = BadgeMatcher.read(read("badges/no_clan.jpg"), Box(95, 93, 375, 158))
        if (r != null) {
            println("no_clan: ${r.describe()}")
            assertFalse(r.confident)
            assertTrue(r.matchingIds.isEmpty())
        }
    }

    @Test
    fun nameAtTheLeftEdgeHasNoBadge() {
        assertEquals(null, BadgeMatcher.read(read("badges/ace_iran.jpg"), Box(10, 117, 160, 174)))
    }

    @Test
    fun readingIsFast() {
        val img = read("badges/ace_iran.jpg")
        BadgeMatcher.read(img, Box(95, 117, 245, 174))
        val t0 = System.nanoTime()
        repeat(5) { BadgeMatcher.read(img, Box(95, 117, 245, 174)) }
        val ms = (System.nanoTime() - t0) / 5 / 1_000_000
        println("badge read: $ms ms")
        assertTrue("badge read took $ms ms", ms < 500)
    }
}
