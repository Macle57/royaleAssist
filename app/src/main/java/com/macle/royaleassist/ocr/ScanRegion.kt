package com.macle.royaleassist.ocr

/**
 * The part of the screen that is OCR'd, as fractions of the screen width/height (0..1).
 *
 * In a Clash Royale battle the opponent's name and clan sit in the top-left corner. The default
 * frames that block on 1080x2340..2400 portrait screens: x 0..60 %, y 2.5..16 % (y 58..374 px on
 * 2340), leaving room above the name so a slightly tall font or a cutout offset is not clipped. Users can move and resize it in the overlay
 * ("Set scan region").
 */
data class ScanRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    fun toBox(width: Int, height: Int): Box {
        val l = (left * width).toInt().coerceIn(0, width - 1)
        val t = (top * height).toInt().coerceIn(0, height - 1)
        val r = (right * width).toInt().coerceIn(l + 1, width)
        val b = (bottom * height).toInt().coerceIn(t + 1, height)
        return Box(l, t, r, b)
    }

    /** Clamped to the screen and at least [MIN_SIZE] in each direction. */
    fun normalized(): ScanRegion {
        val l = left.coerceIn(0f, 1f - MIN_SIZE)
        val t = top.coerceIn(0f, 1f - MIN_SIZE)
        val r = right.coerceIn(l + MIN_SIZE, 1f)
        val b = bottom.coerceIn(t + MIN_SIZE, 1f)
        return ScanRegion(l, t, r, b)
    }

    companion object {
        const val MIN_SIZE = 0.03f
        val DEFAULT = ScanRegion(0f, 0.025f, 0.60f, 0.16f)

        fun fromBox(b: Box, width: Int, height: Int) = ScanRegion(
            b.left.toFloat() / width, b.top.toFloat() / height,
            b.right.toFloat() / width, b.bottom.toFloat() / height,
        ).normalized()
    }
}
