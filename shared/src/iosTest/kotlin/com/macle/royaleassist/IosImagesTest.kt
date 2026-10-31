package com.macle.royaleassist

import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.image.toArgbImage
import com.macle.royaleassist.image.toCGImage
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class IosImagesTest {
    @Test
    fun argbSurvivesCgImageRoundTrip() {
        // Odd width so the CoreGraphics row stride differs from width * 4.
        val w = 37
        val h = 5
        val px = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 7 and 0xFF) shl 16) or ((i * 13 and 0xFF) shl 8) or (i * 29 and 0xFF) }
        px[0] = 0xFFFF0000.toInt() // red
        px[1] = 0xFF00FF00.toInt() // green
        px[2] = 0xFF0000FF.toInt() // blue
        val cg = ArgbImage(w, h, px).toCGImage()
        try {
            assertEquals(w.toULong(), CGImageGetWidth(cg))
            assertEquals(h.toULong(), CGImageGetHeight(cg))
            val back = cg.toArgbImage()
            assertEquals(w, back.width)
            assertEquals(h, back.height)
            assertContentEquals(px, back.pixels)
        } finally {
            CGImageRelease(cg)
        }
    }
}
