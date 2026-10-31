package com.macle.royaleassist.ocr

import kotlin.math.roundToInt

/**
 * Normalised text boxes (Apple Vision: fractions 0..1 of the image, origin at the bottom-left) in
 * the conventions ML Kit's boxes have on Android: integer pixels in the recognised image, origin at
 * the top-left, then divided by the preprocessing scale so they are in the coordinates of the image
 * before upscaling (see [TextRecognizerBackend]).
 */
object NormalizedBoxes {
    /**
     * Box of a normalised rect ([x], [y] = bottom-left corner, [w] x [h]) in an [imageWidth] x
     * [imageHeight] image that was upscaled by [scale]. Clamped to the image.
     */
    fun toBox(x: Double, y: Double, w: Double, h: Double, imageWidth: Int, imageHeight: Int, scale: Float = 1f): Box {
        fun px(v: Double, size: Int) = (v * size).roundToInt().coerceIn(0, size)
        val left = px(x, imageWidth)
        val right = px(x + w, imageWidth)
        val top = px(1.0 - (y + h), imageHeight)
        val bottom = px(1.0 - y, imageHeight)
        val box = Box(left, top, right, bottom)
        return if (scale == 1f) box else box.scaled(1f / scale)
    }
}
