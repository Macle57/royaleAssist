package com.macle.royaleassist

import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.LineMerger
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrLogicTest {
    private fun line(t: String, l: Int, top: Int, r: Int, b: Int, src: String = "latin", c: Float = 0.9f) =
        OcrLine(t, Box(l, top, r, b), c, src)

    @Test
    fun picksTallestLineAndClanBelow() {
        val lines = listOf(
            line("2:59", 800, 40, 1000, 140),            // timer: tall but no letters
            line("Royale King", 120, 200, 520, 260),
            line("Eternal Night", 122, 268, 400, 305),
            line("King Tower", 600, 270, 900, 300),      // not left-aligned
        )
        val p = NamePicker.pick(lines)
        assertEquals("Royale King", p.name?.text)
        assertEquals("Eternal Night", p.clan?.text)
    }

    @Test
    fun excludesBubbleRegion() {
        val lines = listOf(
            line("BIG", 0, 0, 100, 200),
            line("Royale King", 120, 200, 520, 260),
        )
        val p = NamePicker.pick(lines, exclude = Box(0, 0, 150, 150))
        assertEquals("Royale King", p.name?.text)
        assertNull(p.clan)
    }

    @Test
    fun mergePrefersChineseForHan() {
        val merged = LineMerger.merge(
            listOf(
                line("Bt", 100, 100, 160, 130, "latin", 0.4f),
                line("时光", 98, 101, 162, 131, "chinese", 0.8f),
                line("Trainer fart", 100, 40, 300, 80, "latin", 0.95f),
                line("Trainer fart", 101, 41, 299, 79, "chinese", 0.9f),
            )
        )
        assertEquals(2, merged.size)
        assertEquals("latin", merged.first { it.text.startsWith("Trainer") }.source)
        assertEquals("时光", merged[1].text)
    }
}
