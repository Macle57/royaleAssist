package com.macle.royaleassist

import com.macle.royaleassist.lookup.VerifyCheck
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.NormalizedBoxes
import com.macle.royaleassist.ui.DeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure logic behind the iOS shell: Vision box geometry, the verifier's checks, deep links. */
class IosHooksTest {
    @Test
    fun visionBoxIsFlippedToTopLeftPixels() {
        // Bottom-left origin: a box at y 0.75..0.875 of a 400 px high image is 50..100 px from the top.
        assertEquals(Box(200, 50, 600, 100), NormalizedBoxes.toBox(0.25, 0.75, 0.5, 0.125, 800, 400))
        // Full image.
        assertEquals(Box(0, 0, 640, 480), NormalizedBoxes.toBox(0.0, 0.0, 1.0, 1.0, 640, 480))
    }

    @Test
    fun visionBoxIsDividedByThePreprocessingScale() {
        // Recognised on a 3x upscale of a 300x100 crop: boxes come back in crop pixels.
        val b = NormalizedBoxes.toBox(0.1, 0.5, 0.4, 0.3, 900, 300, scale = 3f)
        assertEquals(Box(30, 20, 150, 50), b)
    }

    @Test
    fun visionBoxIsClampedToTheImage() {
        assertEquals(Box(0, 0, 100, 15), NormalizedBoxes.toBox(-0.05, 0.85, 1.2, 0.3, 100, 100))
    }

    @Test
    fun verifyPassesOnlyOnARealRoyaleApiPage() {
        assertFalse(VerifyCheck.passed("Just a moment...", "https://royaleapi.com/player/search/results?q=Macle"))
        assertFalse(VerifyCheck.passed("", "https://royaleapi.com/"))
        assertFalse(VerifyCheck.passed(null, null))
        assertFalse(VerifyCheck.passed("Some page", "https://challenges.cloudflare.com/x"))
        assertTrue(VerifyCheck.passed("Search results - RoyaleAPI", "https://royaleapi.com/player/search/results?q=Macle"))
    }

    @Test
    fun onlyRoyaleApiCookiesAreCopied() {
        assertEquals("royaleapi.com", VerifyCheck.HOST)
        assertTrue(VerifyCheck.isRoyaleApiCookie(".royaleapi.com"))
        assertTrue(VerifyCheck.isRoyaleApiCookie("royaleapi.com"))
        assertTrue(VerifyCheck.isRoyaleApiCookie("www.RoyaleAPI.com"))
        assertFalse(VerifyCheck.isRoyaleApiCookie("notroyaleapi.com"))
        assertFalse(VerifyCheck.isRoyaleApiCookie(".cloudflare.com"))
        assertFalse(VerifyCheck.isRoyaleApiCookie("royaleapi.com.evil.example"))
        data class C(val name: String, val domain: String)
        val kept = VerifyCheck.royaleApiCookies(
            listOf(C("cf_clearance", ".royaleapi.com"), C("__cf_bm", "royaleapi.com"), C("_ga", ".google.com")),
        ) { it.domain }
        assertEquals(listOf("cf_clearance", "__cf_bm"), kept.map { it.name })
    }

    @Test
    fun copiedCookiesLoseTheirStoragePartition() {
        // Properties of WKWebView's cf_clearance (CHIPS-partitioned) as WKHTTPCookieStore reports them.
        val webKit = mapOf(
            "Name" to "cf_clearance", "Value" to "abc", "Domain" to ".royaleapi.com", "Path" to "/",
            "Secure" to "TRUE", "HttpOnly" to "TRUE", "SameSite" to "none", "Expires" to "2027-10-31",
            "Created" to "815108656", "StoragePartition" to "https://royaleapi.com",
        )
        val copy = VerifyCheck.portableCookieProperties(webKit)
        assertFalse("StoragePartition" in copy)
        assertEquals(webKit - "StoragePartition", copy)
    }

    @Test
    fun deepLinks() {
        assertEquals(DeepLink.Lookup("Macle", null), DeepLink.parse("royaleassist://lookup?name=Macle"))
        assertEquals(DeepLink.Lookup("Royale King", "GLI ARGONAUTI"), DeepLink.parse("royaleassist://lookup?name=Royale%20King&clan=GLI+ARGONAUTI"))
        assertEquals(DeepLink.Lookup("동덕여대", null), DeepLink.parse("royaleassist://lookup?name=%EB%8F%99%EB%8D%95%EC%97%AC%EB%8C%80&clan="))
        assertNull(DeepLink.parse("royaleassist://lookup"))
        assertEquals(DeepLink.Scan(), DeepLink.parse("royaleassist://scan"))
        assertEquals(DeepLink.Scan(latestPhoto = true), DeepLink.parse("royaleassist://scan?photo=latest"))
        assertEquals(DeepLink.Scan(path = "/Users/me/a b.jpg"), DeepLink.parse("royaleassist://scan?path=/Users/me/a%20b.jpg"))
        assertEquals(DeepLink.History, DeepLink.parse("ROYALEASSIST://history/"))
        assertEquals(DeepLink.Setup, DeepLink.parse("royaleassist://"))
        assertEquals(DeepLink.Verify("Macle"), DeepLink.parse("royaleassist://verify?q=Macle"))
        assertNull(DeepLink.parse("https://royaleapi.com/"))
        assertNull(DeepLink.parse("royaleassist://nope"))
        assertEquals("100%", DeepLink.percentDecode("100%"))
    }
}
