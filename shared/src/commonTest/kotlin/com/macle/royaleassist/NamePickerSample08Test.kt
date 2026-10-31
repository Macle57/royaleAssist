package com.macle.royaleassist

import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrLine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Picker fixtures recorded from the emulator harness on samples/08-daniel-fullscreen.jpg
 * (923x2000 copy of a 1080x2340 phone screenshot) and from the phone logs of failed taps.
 * Boxes are in screen pixels, warmth is the measured saturated-warm pixel fraction.
 */
class NamePickerSample08Test {
    private fun l(text: String, l: Int, t: Int, r: Int, b: Int, c: Float, w: Double, src: String = "latin") =
        OcrLine(text, Box(l, t, r, b), c, src, warmth = w)

    /** New default region (x 0-60 %, y 2.5-16 %). */
    private val defaultRegion = listOf(
        l("I6/ S361", 361, 70, 509, 136, 0.37f, 0.38),
        l("Daniel", 86, 96, 234, 142, 0.56f, 0.47),
        l("GLI ARGONAUTI", 100, 151, 302, 181, 0.81f, 0.00),
        l("MLeague 5", 24, 189, 209, 230, 0.72f, 0.26),
        l("16", 286, 283, 313, 312, 0.50f, 0.34),
    )

    /** The phone's region (x 0-68.5 %, y 4.87-18 %) after growing it upward. */
    private val phoneRegionGrown = listOf(
        l("SI6/ S361", 349, 73, 514, 138, 0.31f, 0.38),
        l("Daniel", 104, 90, 237, 157, 0.48f, 0.30),
        l("GLI ARGONAUTI", 82, 153, 302, 181, 0.80f, 0.00),
        l("|League 5", -4, 185, 210, 230, 0.67f, 0.25),
        l("16-", 285, 283, 315, 310, 0.52f, 0.33),
    )

    @Test
    fun picksDanielWithDefaultRegion() {
        val p = NamePicker.pick(defaultRegion)
        assertEquals("Daniel", p.name?.text)
        assertEquals("GLI ARGONAUTI", p.clan?.text)
    }

    @Test
    fun picksDanielWithPhoneRegion() {
        val p = NamePicker.pick(phoneRegionGrown)
        assertEquals("Daniel", p.name?.text)
        assertEquals("GLI ARGONAUTI", p.clan?.text)
    }

    /** Phone tap 1: the name was cut off, leaving "League 5" and a tower HP misread. */
    @Test
    fun cutOffNameGivesNoNameInsteadOfLeague() {
        val p = NamePicker.pick(
            listOf(
                l("League 5", 40, 190, 210, 230, 0.70f, 0.20),
                l("E4858", 140, 245, 260, 275, 0.60f, 0.05),
                l("पा", 30, 120, 70, 150, 0.45f, 0.10, "devanagari"),
            )
        )
        assertNull(p.name)
    }

    /** "baisanooo" tap: only a tower HP reading ("的4858") survived; it must not become the name. */
    @Test
    fun towerHpIsNeverAName() {
        assertNull(NamePicker.pick(listOf(l("的4858", 140, 245, 260, 275, 0.62f, 0.05, "chinese"))).name)
        assertNull(NamePicker.pick(listOf(l("4858", 140, 245, 260, 275, 0.90f, 0.30), l("16", 100, 245, 130, 275, 0.9f, 0.4))).name)
        // With the real name present the numbers are ignored and the warm name wins.
        val p = NamePicker.pick(
            listOf(
                l("4715", 600, 300, 700, 340, 0.9f, 0.40),
                l("baisaNo00i", 90, 100, 300, 160, 0.55f, 0.35),
                l("Black Chile", 92, 165, 260, 192, 0.8f, 0.0),
                l("E4858", 140, 245, 260, 275, 0.60f, 0.05),
            )
        )
        assertEquals("baisaNo00i", p.name?.text)
        assertEquals("Black Chile", p.clan?.text)
    }

    @Test
    fun numericFilters() {
        listOf("4858", "E4858", "的4858-", "1:44", "16", "I6/ S361", "5361").forEach {
            assertTrue(NamePicker.isMostlyDigits(it), it)
        }
        listOf("Daniel", "baisanooo", "MasterX", "BigWaNCeL6", "Player123").forEach {
            assertFalse(NamePicker.isMostlyDigits(it), it)
        }
        listOf("League 5", "League5", "MLeague 5", "|League 5", "Time left:", "Next:", "Max: 10").forEach {
            assertTrue(NamePicker.isGameLabel(it), it)
        }
        assertFalse(NamePicker.isGameLabel("Leaguers"))
    }

    @Test
    fun lookupRejectsNumericQueries() {
        listOf("4858", "E4858", "的4858", "16", "x").forEach { assertFalse(LookupChain.isPlausibleText(it), it) }
        assertTrue(LookupChain.isPlausibleText("Daniel"))
        assertTrue(LookupChain.ocrVariants("的4858").none { it.all { c -> c.isDigit() } })
    }
}
